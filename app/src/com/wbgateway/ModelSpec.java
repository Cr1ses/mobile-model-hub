package com.wbgateway;

import org.json.JSONObject;

/**
 * 一个模型的规格（**来自上游真实下发**，不是本地猜测）。
 *
 * <h3>为什么要有这个类</h3>
 *
 * 之前界面上的规格全靠 {@link ModelMeta} 那张静态表 —— 只要上游上了新模型，
 * 就显示成「未知」；而且那张表是人工维护的，容易和上游不一致。
 *
 * 实际抓包发现上游在 `data.models[]` 里**就带了完整规格**
 * （WorkBuddy 34 条、Qoder 17 条），字段包括：
 *
 * <pre>
 * WorkBuddy: {id, name, maxInputTokens, maxOutputTokens, supportsImages,
 *             reasoning:{defaultEffort, supportedEfforts, canDisableThinking}, vendor, tags}
 * Qoder:     {key, id, name, isVL, isReasoning, effortLevels,
 *             maxInputTokens, defaultContextWindow, contextOptions, priceFactor}
 * </pre>
 *
 * 所以改为**优先用上游值**，静态表只在缺字段时兜底。
 *
 * <p>与 {@link ModelMeta.Info} 的区别：那个是「本地已知」，这个是「上游声明」。
 * 两者都可能有洞（上游某些条目不报 maxInputTokens），所以取长补短。
 */
public final class ModelSpec {

    /** 展示名（上游给的人类可读名，如 `Qwen3.8-Max`）。 */
    public String name = "";
    /** 上下文窗口（token），0 表示上游没报。 */
    public int contextWindow = 0;
    /** 最大输出（token），0 表示上游没报。 */
    public int maxOutput = 0;
    /** 是否支持图片输入。 */
    public boolean supportsImages = false;
    /** 推理档位（`low` / `high` / `max` …）；空表示非推理模型。 */
    public java.util.List<String> efforts = new java.util.ArrayList<>();
    /** 默认推理档位；空表示未指定。 */
    public String defaultEffort = "";
    /** 价格倍率（Qoder 有）；0 表示未上报。 */
    public double priceFactor = 0;
    /** 是否免费（Qoder 的 `isFree`）。 */
    public boolean free = false;
    /**
     * 积分倍率（归一化后的形式，如 `x0.69`）。
     *
     * <p>各渠道来源不同、形态也不同，统一归一化成一件事：
     * <ul>
     *   <li>WorkBuddy：`models[].credits`，值是 `x0.69` 或 `x1.33 credits`；</li>
     *   <li>CodeArts：`builtinModels[].credit[0].ratio_display`，值是 `0.7x`；</li>
     *   <li>Qoder：`priceFactor`，是数字 `0.5`。</li>
     * </ul>
     * 空串表示上游没给（代理渠道、ZCode 的 token 额度、Raccoon 的 points 都没有）。
     */
    public String creditRate = "";
    /** 促销 / 试用后的倍率（如 `x0`）。空串表示没有生效中的促销。 */
    public String discountedCreditRate = "";
    /** 倍率的补充说明（例如 CodeArts 的分档计费「<32K 与以上两档」）。 */
    public String creditNote = "";
    /**
     * 该模型此刻**正在排队**（上游繁忙），不代表额度或套餐问题。
     *
     * <p>为什么要有这个：Qoder 的加密推理端点在模型繁忙时回
     * `10605 / RESOURCE_LIMIT / serviceAvailable:false`。**这是排队码，
     * 不是「要订阅」** —— 实测同一账号连打 5 轮全部正常出正文，
     * 当时额度还剩 2079/2500。
     *
     * <p>界面标「繁忙」而不是「需订阅」：前者告诉用户「等一会儿再来」，
     * 后者会把用户引去付费升级。标注形态是模型名后的「繁忙」，
     * 与积分倍率并排显示。
     */    public boolean queued = false;
    /** 需要订阅时的补充说明（如「当前账号无套餐」）；空表示不显示细节。 */
    public String queuedNote = "";

    /**
     * 从 WorkBuddy 的 `data.models[]` 的一条解析。
     *
     * <p>字段名按实测：`name` / `maxInputTokens` / `maxOutputTokens` /
     * `supportsImages` / `reasoning`（对象或布尔）。
     */
    public static ModelSpec fromWorkbuddy(JSONObject m) {
        ModelSpec s = new ModelSpec();
        if (m == null) return s;
        s.name = m.optString("name", "");
        s.contextWindow = m.optInt("maxInputTokens", 0);
        s.maxOutput = m.optInt("maxOutputTokens", 0);
        s.supportsImages = m.optBoolean("supportsImages", false);
        // 积分倍率：`credits: "x0.69"`（也可能带单位 `x1.33 credits`）
        s.creditRate = normalizeRate(m.optString("credits", ""));

        // reasoning 可能三种形态：对象（带档位）、布尔 true、缺失。
        //
        // ⚠ 这里必须用 optJSONObject 而不是 `opt(...) instanceof JSONObject`：
        // Android 的 org.json 会把嵌套对象解析成 JSONObject，但桌面验证用的替身
        // 解析成 LinkedHashMap —— 用 instanceof 判断在桌面上永远失败，
        // 「推理档位」会全部丢掉（这个坑踩过）。optJSONObject 两边行为一致。
        JSONObject reason = m.optJSONObject("reasoning");
        if (reason != null) {
            s.defaultEffort = reason.optString("defaultEffort", "");
            org.json.JSONArray arr = reason.optJSONArray("supportedEfforts");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i += 1) {
                    String e = arr.optString(i, "").trim();
                    if (!e.isEmpty()) s.efforts.add(e);
                }
            }
            // 只有 defaultEffort 没有列表时，至少让界面知道它是推理模型
            if (s.efforts.isEmpty() && !s.defaultEffort.isEmpty()) {
                s.efforts.add(s.defaultEffort);
            }
        } else if (m.optBoolean("reasoning", false) || m.optBoolean("supportsReasoning", false)) {
            // 布尔 true：是推理模型，但没说档位
            if (s.efforts.isEmpty()) s.efforts.add("default");
        }
        return s;
    }

    /**
     * 从 Raccoon 的模型目录条目解析。
     *
     * <p>实测结构（`GET /api/web/llm/v2/model_catalog`）：
     * <pre>
     * {code:0, data:{categories:[{type:"chat", models:[{
     *    name:"sn-deepseek-v4-1-flash",      // 调用用的 id
     *    description:"DeepSeek-V4.1-Flash",  // 人类可读名
     *    visible:true,
     *    billing_multiplier:0.5,             // 原价
     *    billing_effective_multiplier:0.25,  // 折后（促销）
     *    billing_status:"discount",          // normal / discount / limited_free
     *    billing_status_note:"…",
     *    params:{context_window:1000000, max_tokens:100000}
     * }]}]}}
     * </pre>
     *
     * <p>⚠ 两个坑：
     * <ul>
     *   <li>`name` 是 id、`description` 才是展示名（和 Qoder 一样是反的）；</li>
     *   <li>倍率**必须显示 1 倍**。用户明确报障过「为什么 Kimi-K3 没有倍率，
     *       x1 也要显示」—— 所以这里不做「等于 1 就省略」的优化。</li>
     * </ul>
     */
    public static ModelSpec fromRaccoon(JSONObject m) {
        ModelSpec s = new ModelSpec();
        if (m == null) return s;
        String description = m.optString("description", "").trim();
        if (description.isEmpty()) description = m.optString("name", "").trim();
        s.name = description;
        JSONObject params = m.optJSONObject("params");
        int ctx = m.optInt("context_window", 0);
        if (ctx <= 0 && params != null) ctx = params.optInt("context_window", 0);
        s.contextWindow = ctx;
        int max = params == null ? 0 : params.optInt("max_tokens", 0);
        if (max <= 0) max = m.optInt("max_tokens", 0);
        s.maxOutput = max;
        // 倍率：`billing_effective_multiplier` 是实收，`billing_multiplier` 是原价
        double effective = optDoubleOrNaN(m, "billing_effective_multiplier");
        double base = optDoubleOrNaN(m, "billing_multiplier");
        if (!Double.isNaN(effective)) {
            s.creditRate = formatRate(Math.max(0, effective));
            if (!Double.isNaN(base) && base > effective) {
                s.discountedCreditRate = s.creditRate;
                s.creditRate = formatRate(Math.max(0, base));
            }
        } else if (!Double.isNaN(base)) {
            s.creditRate = formatRate(Math.max(0, base));
        }
        String status = m.optString("billing_status", "");
        String note = m.optString("billing_status_note", "").trim();
        if (!note.isEmpty()) s.creditNote = note;
        else if ("limited_free".equals(status)) s.creditNote = "限时免费";
        else if ("discount".equals(status)) s.creditNote = "限时折扣";
        return s;
    }

    /** `optDouble` 在「字段不存在」时也会给默认值，这里要区分「没给」与「给了 0」。 */
    private static double optDoubleOrNaN(JSONObject m, String key) {
        Object raw = m.opt(key);
        if (raw instanceof Number) {
            double v = ((Number) raw).doubleValue();
            return Double.isFinite(v) ? v : Double.NaN;
        }
        if (raw instanceof String) {
            try {
                return Double.parseDouble(((String) raw).trim());
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    /**
     * 从 Qoder 目录条目解析（字段来自 `$DSH_HOME/.qoder-catalog.qoder.json`）。
     *
     * <p>Qoder 用 `key` 作模型 id、`id` 作展示名（注意是**反的**）；
     * `isVL` 表示多模态；`effortLevels` 是档位数组。
     */
    public static ModelSpec fromQoder(JSONObject e) {
        ModelSpec s = new ModelSpec();
        if (e == null) return s;
        // Qoder: id 是展示名（如 "Qwen3.8-Max"），key 才是调用用的 id
        s.name = e.optString("id", "");
        if (s.name.isEmpty()) s.name = e.optString("name", "");
        s.contextWindow = e.optInt("maxInputTokens", 0);
        if (s.contextWindow <= 0) s.contextWindow = e.optInt("defaultContextWindow", 0);
        s.supportsImages = e.optBoolean("isVL", false);
        s.priceFactor = e.optDouble("priceFactor", 0);
        s.free = e.optBoolean("isFree", false);
        // Qoder 用价格倍率表达同一件事，统一成 `xN` 形态
        if (s.priceFactor > 0) s.creditRate = formatRate(s.priceFactor);
        else if (s.free) s.creditRate = "x0";
        // isReasoning 为真但 effortLevels 为空 → 是推理模型但不带档位（如实记一个 default）
        boolean reasoning = e.optBoolean("isReasoning", false);
        org.json.JSONArray arr = e.optJSONArray("effortLevels");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i += 1) {
                String lv = arr.optString(i, "").trim();
                if (!lv.isEmpty()) s.efforts.add(lv);
            }
        }
        if (s.efforts.isEmpty() && reasoning) s.efforts.add("default");
        return s;
    }

    /** 该规格是否含任何有用信息。 */
    /**
     * 浅拷贝（`efforts` 另建一份，避免共享列表被改）。
     *
     * <p>为什么需要：内置规格表是**共享模板**，按区域过滤时要逐条打标记
     * （如「需要订阅」），直接改模板会污染另一个区域的判断。
     */
    public ModelSpec copy() {
        ModelSpec s = new ModelSpec();
        s.name = name;
        s.contextWindow = contextWindow;
        s.maxOutput = maxOutput;
        s.supportsImages = supportsImages;
        s.efforts = new java.util.ArrayList<>(efforts);
        s.defaultEffort = defaultEffort;
        s.priceFactor = priceFactor;
        s.free = free;
        s.creditRate = creditRate;
        s.discountedCreditRate = discountedCreditRate;
        s.creditNote = creditNote;
        s.queued = queued;
        s.queuedNote = queuedNote;
        return s;
    }

    public boolean isEmpty() {
        return name.isEmpty() && contextWindow <= 0 && maxOutput <= 0
                && !supportsImages && efforts.isEmpty() && creditRate.isEmpty();
    }

    /**
     * 归一化积分倍率：`x0.69` / `0.7x` / `x1.33 credits` / `0.5` → `x0.69` 这种形态。
     *
     * <p>为什么要归一：三个上游给的写法各不相同（WorkBuddy 前缀式、CodeArts 后缀式、
     * Qoder 纯数字），界面若各自处理，迟早会出现「一个渠道显示 0.7x、另一个显示 x0.7」
     * 的不一致。统一在这里收口。
     *
     * @return `xN` 形态；无法识别时返回空串（宁可留空，也不瞎猜）
     */
    public static String normalizeRate(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.isEmpty()) return "";
        // 只取第一个 token：`x1.33 credits` 后面那截是同义说明
        String head = text.split("\\s+")[0];
        java.util.regex.Matcher pre =
                java.util.regex.Pattern.compile("^[xX](\\d+(?:\\.\\d+)?)").matcher(head);
        if (pre.find()) return "x" + trimZeros(pre.group(1));
        java.util.regex.Matcher suf =
                java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)?)[xX]$").matcher(head);
        if (suf.find()) return "x" + trimZeros(suf.group(1));
        // 纯数字（Qoder 的 priceFactor 经字符串传进来时的形态）
        java.util.regex.Matcher plain =
                java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)?)$").matcher(head);
        if (plain.find()) return "x" + trimZeros(plain.group(1));
        return "";
    }

    /** 数字 → `xN`（Qoder 的 priceFactor 是 double）。 */
    static String formatRate(double value) {
        if (value <= 0) return "";
        return "x" + trimZeros(String.valueOf(value));
    }

    /** `0.70` → `0.7`，`1.00` → `1`（去掉无意义的尾零）。 */
    static String trimZeros(String numeric) {
        if (numeric == null || numeric.isEmpty()) return numeric;
        String s = numeric;
        if (s.indexOf('.') >= 0) {
            while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * 界面用的倍率文案：`积分 x0.69`；有促销时 `积分 x0.69 → x0`。
     *
     * @return 空串表示该模型没有倍率信息（代理渠道 / token 额度制渠道）
     */
    public String creditRateLabel() {
        if (creditRate.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("积分 ");
        sb.append(creditRate);
        if (!discountedCreditRate.isEmpty() && !discountedCreditRate.equals(creditRate)) {
            sb.append(" → ").append(discountedCreditRate);
        }
        if (!creditNote.isEmpty()) sb.append("（").append(creditNote).append("）");
        return sb.toString();
    }

    /**
     * 模型名后面直接拼的短倍率：`x0.11` / `x0.2→x0.1`。
     *
     * <p>为什么短：用户的诉求就是「名字后面加倍率」，写成
     * `DeepSeek-V4.1-Flash x0.11` 一眼能扫完；带「积分」二字会把名字挤掉。
     * 促销用箭头（`x0.2→x0.1`）比文字更省空间，也和上游的写法一致。
     *
     * @return 空串表示该模型没有倍率
     */
    public String creditRateBadge() {
        if (creditRate.isEmpty()) return "";
        if (discountedCreditRate.isEmpty() || discountedCreditRate.equals(creditRate)) {
            return creditRate;
        }
        return creditRate + "→" + discountedCreditRate;
    }

    /**
     * 排队时模型名后的标注：`繁忙`；有补充说明时 `繁忙（正在排队）`。
     *
     * @return 空串表示该模型不需要订阅（或与订阅无关）
     */
    public String queueBadge() {
        if (!queued) return "";
        if (queuedNote.isEmpty()) return "繁忙";
        return "繁忙（" + queuedNote + "）";
    }

    /** 一句话规格（界面用）：`1M 上下文 · 输出 64K · 推理 high/max · 图片`。 */
    public String specLine() {
        StringBuilder sb = new StringBuilder();
        if (contextWindow > 0) sb.append(ModelMeta.shortTokens(contextWindow)).append(" 上下文");
        if (maxOutput > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("输出 ").append(ModelMeta.shortTokens(maxOutput));
        }
        if (!efforts.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("推理 ");
            for (int i = 0; i < efforts.size(); i += 1) {
                if (i > 0) sb.append('/');
                sb.append(efforts.get(i));
            }
        }
        if (supportsImages) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("图片");
        }
        if (free) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("免费");
        }
        return sb.toString();
    }

    /** 规格行 + 倍率（同一行，界面放得下；倍率没给就不加）。 */
    public String specLineWithRate() {
        String base = specLine();
        String rate = creditRateLabel();
        if (rate.isEmpty()) return base;
        return base.isEmpty() ? rate : base + " · " + rate;
    }

    /** 序列化（存进 Channel 的 JSON）。 */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            if (!name.isEmpty()) o.put("name", name);
            if (contextWindow > 0) o.put("ctx", contextWindow);
            if (maxOutput > 0) o.put("out", maxOutput);
            if (supportsImages) o.put("img", true);
            if (!efforts.isEmpty()) o.put("efforts", new org.json.JSONArray(efforts));
            if (!defaultEffort.isEmpty()) o.put("defEffort", defaultEffort);
            if (priceFactor > 0) o.put("price", priceFactor);
            if (free) o.put("free", true);
            // 倍率是可选信息，空就不写（省体积，也便于判断「上游到底给没给」）
            if (!creditRate.isEmpty()) o.put("rate", creditRate);
            if (!discountedCreditRate.isEmpty()) o.put("discRate", discountedCreditRate);
            if (!creditNote.isEmpty()) o.put("rateNote", creditNote);
            if (queued) o.put("queued", true);
            if (!queuedNote.isEmpty()) o.put("queueNote", queuedNote);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 反序列化。 */
    public static ModelSpec fromJson(JSONObject o) {
        ModelSpec s = new ModelSpec();
        if (o == null) return s;
        s.name = o.optString("name", "");
        s.contextWindow = o.optInt("ctx", 0);
        s.maxOutput = o.optInt("out", 0);
        s.supportsImages = o.optBoolean("img", false);
        s.defaultEffort = o.optString("defEffort", "");
        s.priceFactor = o.optDouble("price", 0);
        s.free = o.optBoolean("free", false);
        s.creditRate = o.optString("rate", "");
        s.discountedCreditRate = o.optString("discRate", "");
        s.creditNote = o.optString("rateNote", "");
        s.queued = o.optBoolean("queued", false);
        s.queuedNote = o.optString("queueNote", "");
        org.json.JSONArray arr = o.optJSONArray("efforts");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i += 1) {
                String e = arr.optString(i, "").trim();
                if (!e.isEmpty()) s.efforts.add(e);
            }
        }
        return s;
    }
}
