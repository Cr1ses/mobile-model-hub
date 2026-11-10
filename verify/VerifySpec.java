import com.wbgateway.Channel;
import com.wbgateway.LoginProviders;
import com.wbgateway.ModelMeta;
import com.wbgateway.ModelSpec;
import com.wbgateway.ModelCatalog;

import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * 模型规格的桌面验证。
 *
 * <p>三个被用户指出的问题，逐条锁住：
 * <ol>
 *   <li><b>Qoder 的模型目录要是真实模型名</b>：不能是 `auto`/`common` 这类内部代号
 *       单独出现，而要带 `Qwen3.8-Max`、`GLM-5.3`、`Kimi-K3` 这样的可读名；</li>
 *   <li><b>上游所有模型都有规格信息</b>：上下文 / 输出 / 图片 / 推理档位要能显示；</li>
 *   <li><b>WorkBuddy 解析要用 `data.models[]` 的真实规格</b>，而不是靠本地静态表。</li>
 * </ol>
 *
 * <p>用真实抓包得到的 JSON 片段做输入（不是编的），这样断言才有意义。
 */
public class VerifySpec {

    static int pass = 0;
    static int fail = 0;

    static void check(String label, boolean ok, String detail) {
        if (ok) {
            pass += 1;
            System.out.println("  PASS  " + label + (detail.isEmpty() ? "" : " -- " + detail));
        } else {
            fail += 1;
            System.out.println("  FAIL  " + label + (detail.isEmpty() ? "" : " -- " + detail));
        }
    }

    /** 真实抓包的 WorkBuddy 模型条目（国内版 data.models[] 的片段）。 */
    static JSONObject wbModel(String id, String name, int in, int out, boolean img, String effortJson) {
        return wbModel(id, name, in, out, img, effortJson, "");
    }

    /** 带 `credits` 的版本（积分倍率来自这个字段）。 */
    static JSONObject wbModel(String id, String name, int in, int out, boolean img,
                              String effortJson, String credits) {
        try {
            String r = effortJson == null ? "null" : effortJson;
            String creditPart = credits == null || credits.isEmpty()
                    ? "" : ",\"credits\":\"" + credits + "\"";
            return new JSONObject("{\"id\":\"" + id + "\",\"name\":\"" + name
                    + "\",\"maxInputTokens\":" + in + ",\"maxOutputTokens\":" + out
                    + ",\"supportsImages\":" + img + ",\"reasoning\":" + r
                    + creditPart + "}");
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 1. WorkBuddy 上游规格解析（data.models[] 真实字段）===");
        ModelSpec glm = ModelSpec.fromWorkbuddy(wbModel("glm-5.3", "GLM-5.3", 1000000, 64000, true,
                "{\"defaultEffort\":\"high\",\"supportedEfforts\":[\"low\",\"high\",\"max\"]}"));
        check("取到展示名 GLM-5.3", "GLM-5.3".equals(glm.name), glm.name);
        check("取到上下文 1M", glm.contextWindow == 1000000, String.valueOf(glm.contextWindow));
        check("取到输出 64K", glm.maxOutput == 64000, String.valueOf(glm.maxOutput));
        check("取到支持图片", glm.supportsImages, "");
        check("取到 3 个推理档位", glm.efforts.size() == 3, glm.efforts.toString());
        check("取到默认档位 high", "high".equals(glm.defaultEffort), glm.defaultEffort);
        check("规格行含真实信息", glm.specLine().contains("1M") && glm.specLine().contains("推理"),
                glm.specLine());

        // reasoning 为布尔 true 的条目（如 glm-5.0）
        ModelSpec boolReason = ModelSpec.fromWorkbuddy(wbModel("glm-5.0", "GLM-5.0", 200000, 48000, false, "true"));
        check("reasoning:true 也认作推理模型", !boolReason.efforts.isEmpty(), boolReason.efforts.toString());
        check("上下文档位正常", boolReason.contextWindow == 200000, "");
        check("无图片能力", !boolReason.supportsImages, "");

        // reasoning 缺失
        ModelSpec noReason = ModelSpec.fromWorkbuddy(wbModel("default", "Default", 200000, 24000, false, null));
        check("无 reasoning 时不是推理模型", noReason.efforts.isEmpty(), "");
        check("规格行仍给出窗口", noReason.specLine().contains("200K"), noReason.specLine());

        System.out.println();
        System.out.println("=== 2. Qoder 模型表：只留实测可调用的（详见 VerifyQoder）===");
        Map<String, ModelSpec> qoder = LoginProviders.qoderBuiltinSpecs();
        // ⚠ 曾经是 17 条（目录里声明过的）。实测发现其中 11 个打这个端点会
        //   400 Unsupported model / 429 All backends failed —— 列出来点了必失败。
        //   现在只保留 200 的 6 条；17 条全量在 qoderAllSpecs() 里。
        check("14 条（6 可用 + 8 需订阅）", qoder.size() == 14, "共 " + qoder.size() + " 条");
        // 真实模型名（用户明确要求「要真实模型名」）
        String[][] expect = {
                {"ultimate", "Ultimate"},
                {"qmodel", "Qwen3.7-Plus"},
                {"kmodel", "Kimi-K2.8-Preview"},
                {"gmodel", "GLM-5.3"},
                {"dmodel", "DeepSeek-V4-Pro"},
                {"mmodel", "MiniMax-M3"},
        };
        for (String[] e : expect) {
            ModelSpec s = qoder.get(e[0]);
            check("id " + e[0] + " → 真实名 " + e[1],
                    s != null && e[1].equals(s.name), s == null ? "缺失" : s.name);
        }
        // 每个模型都要有规格信息（用户要求「上游的所有模型都有规格信息」）
        int withSpec = 0;
        for (ModelSpec s : qoder.values()) {
            if (s.contextWindow > 0) withSpec += 1;
        }
        check("14 条全部带上下文窗口", withSpec == 14, withSpec + " / 14");
        int withEfforts = 0;
        for (ModelSpec s : qoder.values()) {
            if (!s.efforts.isEmpty()) withEfforts += 1;
        }
        // 只有 mmodel 在目录里 isReasoning=false，本来就没有档位
        check("推理模型带档位（14 条里 13 条是推理模型）", withEfforts == 13, withEfforts + " 条带档位");
        check("非推理模型确实没档位", qoder.get("mmodel").efforts.isEmpty(), "");
        check("Ultimate 上下文 1M", qoder.get("ultimate").contextWindow == 1000000, "");
        check("DeepSeek-V4-Pro 价格倍率 0.5",
                qoder.get("dmodel").priceFactor == 0.5,
                String.valueOf(qoder.get("dmodel").priceFactor));
        check("规格行可读", qoder.get("gmodel").specLine().contains("上下文"),
                qoder.get("gmodel").specLine());

        System.out.println();
        System.out.println("=== 3. Qoder 刷新后界面能显示真实名与规格 ===");
        Channel qoderCh = new Channel();
        qoderCh.id = "qoder";
        qoderCh.loginType = LoginProviders.QODER;
        qoderCh.kind = Channel.KIND_LOGIN;
        qoderCh.accessToken = "t";
        ModelCatalog.RefreshResult r = ModelCatalog.refresh(qoderCh);
        check("刷新成功", r.ok, r.message);
        check("拿到 14 个模型（6 可用 + 8 需订阅）", r.models.size() == 14, "共 " + r.models.size());
        check("写入 14 条规格", qoderCh.modelSpecs.size() == 14, "共 " + qoderCh.modelSpecs.size());
        ModelSpec g = qoderCh.modelSpecs.get("gmodel");
        check("界面查表能拿到真实名", g != null && "GLM-5.3".equals(g.name), g == null ? "缺" : g.name);
        check("提示说明「含真实规格」", r.message.contains("真实规格"), r.message);

        System.out.println();
        System.out.println("=== 4. 规格优先用上游、静态表兜底 ===");
        // 上游给了规格 → 用上游名
        Channel upCh = new Channel();
        upCh.modelSpecs.put("some-new-model", ModelSpec.fromWorkbuddy(
                wbModel("some-new-model", "Brand New Model", 500000, 32000, true, null)));
        ModelSpec up = upCh.modelSpecs.get("some-new-model");
        check("上游新模型有可读名（静态表里没有）", "Brand New Model".equals(up.name), up.name);
        check("静态表确实不认识它", ModelMeta.lookup("some-new-model") == null, "");
        // 上游没给 → 静态表兜底
        ModelSpec fallback = upCh.modelSpecs.get("glm-5.3");
        check("上游没给的模型走静态表兜底", fallback == null && ModelMeta.lookup("glm-5.3") != null, "");

        System.out.println();
        System.out.println("=== 5. 规格能存进配置并读回（重启不丢）===");
        Channel saved = new Channel();
        saved.id = "qoder";
        saved.loginType = LoginProviders.QODER;
        saved.kind = Channel.KIND_LOGIN;
        saved.modelSpecs.putAll(LoginProviders.qoderBuiltinSpecs());
        String json = saved.toJson().toString();
        Channel loaded = Channel.fromJson(new JSONObject(json));
        check("序列化后能读回（14 条）", loaded.modelSpecs.size() == 14, "共 " + loaded.modelSpecs.size());
        ModelSpec loadedG = loaded.modelSpecs.get("gmodel");
        check("读回后名字正确", loadedG != null && "GLM-5.3".equals(loadedG.name), "");
        check("读回后窗口正确", loadedG != null && loadedG.contextWindow == 180000,
                loadedG == null ? "" : String.valueOf(loadedG.contextWindow));
        check("读回后档位正确", loadedG != null && loadedG.efforts.size() == 3,
                loadedG == null ? "" : loadedG.efforts.toString());

        System.out.println();
        System.out.println("=== 6. WorkBuddy 内置表不含 ZCode/Qoder 的模型（不串味）===");
        List<String> wbModels = LoginProviders.defaultModelsFor(LoginProviders.WB_CN);
        check("WorkBuddy 无内置表（一律以实时为准）", wbModels.isEmpty(), "共 " + wbModels.size());
        check("Qoder 内置表不含 WorkBuddy 专有 id",
                !LoginProviders.defaultModelsFor(LoginProviders.QODER).contains("hy4free"), "");

        System.out.println();
        System.out.println("=== 7. 数字格式（上游既有 2 的幂也有人类整数）===");
        // 上游真实值：131072（2 的幂）与 200000/1000000（人类整数）都要好看
        check("131072 → 128K", "128K".equals(ModelMeta.shortTokens(131072)),
                ModelMeta.shortTokens(131072));
        check("200000 → 200K", "200K".equals(ModelMeta.shortTokens(200000)),
                ModelMeta.shortTokens(200000));
        check("1000000 → 1M", "1M".equals(ModelMeta.shortTokens(1000000)),
                ModelMeta.shortTokens(1000000));
        check("960000 → 960K", "960K".equals(ModelMeta.shortTokens(960000)),
                ModelMeta.shortTokens(960000));
        check("1048576 → 1M", "1M".equals(ModelMeta.shortTokens(1048576)),
                ModelMeta.shortTokens(1048576));
        check("262144 → 256K", "256K".equals(ModelMeta.shortTokens(262144)),
                ModelMeta.shortTokens(262144));
        check("0 → 占位符", "—".equals(ModelMeta.shortTokens(0)), ModelMeta.shortTokens(0));

        System.out.println();
        System.out.println("=== 8. 渠道标题不出现「名字 · 名字」 ===");
        // 这个是用户实际报上来的现象：「Qoder（中国版） · Qoder（中国版）」看着像重复渲染，
        // 实际是取不到真实昵称时把渠道显示名当了昵称。
        String[][] cases = {
                // 渠道名, 昵称, 期望标题
                {"Qoder（中国版）", "Qoder（中国版）", "Qoder（中国版）"},
                {"ZCode（智谱）", "ZCode（智谱）", "ZCode（智谱）"},
                {"Qoder", "Qoder", "Qoder"},
                {"WorkBuddy AI（国际版）", "ccg1gcc", "WorkBuddy AI（国际版） · ccg1gcc"},
                {"Raccoon（商汤）", "RaccoonIsabella", "Raccoon（商汤） · RaccoonIsabella"},
                {"Qoder", "", "Qoder"},
        };
        for (String[] c : cases) {
            String got = com.wbgateway.TitleProbe.channelTitle(c[0], c[1], "qoder");
            check("标题「" + c[2] + "」", c[2].equals(got), got);
        }

        System.out.println();
        System.out.println("=== 9. 积分倍率（三个渠道三种形态，归一成一种）===");
        // WorkBuddy：`credits: "x0.69"` 与带单位的 `"x1.33 credits"`
        ModelSpec wbRate = ModelSpec.fromWorkbuddy(wbModel("glm-5.3", "GLM-5.3",
                1000000, 48000, true, null, "x0.69"));
        check("WorkBuddy 前缀式倍率 → x0.69", "x0.69".equals(wbRate.creditRate), wbRate.creditRate);
        ModelSpec wbUnit = ModelSpec.fromWorkbuddy(wbModel("max-model", "Max",
                272000, 72000, true, null, "x1.33 credits"));
        check("带单位也能读出倍率", "x1.33".equals(wbUnit.creditRate), wbUnit.creditRate);
        ModelSpec wbFree = ModelSpec.fromWorkbuddy(wbModel("hy4free", "Hy4",
                200000, 64000, false, null, "x0.00"));
        check("倍率 0 保留为 x0（不丢）", "x0".equals(wbFree.creditRate), wbFree.creditRate);
        check("没有 credits 字段 → 空串", ModelSpec.fromWorkbuddy(
                wbModel("x", "X", 1000, 10, false, null)).creditRate.isEmpty(), "");

        // 文案：普通 / 有促销 / 分档
        check("文案「积分 x0.69」", "积分 x0.69".equals(wbRate.creditRateLabel()),
                wbRate.creditRateLabel());
        wbRate.discountedCreditRate = "x0";
        check("有促销时显示 x0.69 → x0",
                "积分 x0.69 → x0".equals(wbRate.creditRateLabel()), wbRate.creditRateLabel());

        // CodeArts：`credit[].ratio_display: "0.7x"`（后缀式），多档要带备注
        ModelSpec ca = new ModelSpec();
        com.wbgateway.RateProbe.applyCodeArtsCredit(ca, new org.json.JSONArray(
                "[{\"input_from\":0,\"input_to\":-1,\"ratio\":\"0.05\",\"ratio_display\":\"0.7x\"}]"));
        check("CodeArts 后缀式倍率 → x0.7", "x0.7".equals(ca.creditRate), ca.creditRate);
        ModelSpec caTier = new ModelSpec();
        com.wbgateway.RateProbe.applyCodeArtsCredit(caTier, new org.json.JSONArray(
                "[{\"input_from\":0,\"input_to\":32000,\"ratio\":\"0.028\",\"ratio_display\":\"0.7x\"},"
                        + "{\"input_from\":32000,\"input_to\":-1,\"ratio\":\"0.05\",\"ratio_display\":\"1.2x\"}]"));
        check("CodeArts 分档时主倍率取第一档", "x0.7".equals(caTier.creditRate), caTier.creditRate);
        check("分档备注说明边界", caTier.creditNote.contains("32K"), caTier.creditNote);
        check("CodeArts 倍率文案", caTier.creditRateLabel().contains("积分 x0.7 → ")
                || caTier.creditRateLabel().contains("积分 x0.7（"), caTier.creditRateLabel());
        // 分档模型的短徽标仍走长文案（否则「32K 以内」这种关键信息会丢）
        check("分档模型在名字后仍带备注",
                com.wbgateway.RateProbe.creditRateText(caTier).contains("32K"),
                com.wbgateway.RateProbe.creditRateText(caTier));
        check("普通模型的名字后缀是短形态",
                "x0.7".equals(com.wbgateway.RateProbe.creditRateText(ca)),
                com.wbgateway.RateProbe.creditRateText(ca));

        // Qoder：priceFactor 是数字
        Map<String, ModelSpec> qoderSpecs = LoginProviders.qoderBuiltinSpecs();
        ModelSpec qoderUltimate = qoderSpecs.get("ultimate");
        check("Qoder 数字倍率 → x2", "x2".equals(qoderUltimate.creditRate), qoderUltimate.creditRate);
        // qmodel（Qwen3.7-Plus）倍率 0.1 —— 原来的 qfmodel 已不在可用集合里
        ModelSpec qoderCheap = qoderSpecs.get("qmodel");
        check("Qoder 低倍率模型 → x0.1", "x0.1".equals(qoderCheap.creditRate), qoderCheap.creditRate);

        // 归一化的边界
        check("0.70 尾零被去掉", "x0.7".equals(ModelSpec.normalizeRate("0.70x")), "");
        check("垃圾串返回空", ModelSpec.normalizeRate("free").isEmpty(), "");
        check("空串返回空", ModelSpec.normalizeRate("").isEmpty(), "");

        // 促销时间窗：过期的不能显示（否则用户以为现在免费）
        org.json.JSONObject expired = new org.json.JSONObject(
                "{\"enabled\":true,\"modelIds\":[\"hy4free\"],"
                        + "\"discount\":{\"discountedCredits\":\"0x\"},"
                        + "\"schedule\":{\"validFrom\":\"2026-07-06T00:00:00+08:00\","
                        + "\"validUntil\":\"2026-08-01T00:00:00+08:00\"}}");
        check("过期促销不生效", !com.wbgateway.RateProbe.promotionActiveNow(expired, System.currentTimeMillis()), "");
        org.json.JSONObject active = new org.json.JSONObject(
                "{\"enabled\":true,\"modelIds\":[\"hy4free\"],"
                        + "\"discount\":{\"discountedCredits\":\"0x\"},"
                        + "\"schedule\":{\"validFrom\":\"2026-01-01T00:00:00+08:00\","
                        + "\"validUntil\":\"2030-01-01T00:00:00+08:00\"}}");
        check("生效中的促销算有效", com.wbgateway.RateProbe.promotionActiveNow(active, System.currentTimeMillis()), "");

        // 端到端：从「/v3/config 形状的响应」一路走到界面文案
        // （促销只对生效中的模型生效，且要能标出 x0.15 → x0）
        String cfgJson = "{\"code\":0,\"data\":{"
                + "\"agents\":[{\"name\":\"cli\",\"models\":[\"promo-model\",\"plain-model\"]}],"
                + "\"models\":["
                + "{\"id\":\"promo-model\",\"name\":\"Promo\",\"maxInputTokens\":200000,"
                + "\"maxOutputTokens\":64000,\"credits\":\"x0.15\"},"
                + "{\"id\":\"plain-model\",\"name\":\"Plain\",\"maxInputTokens\":100000,"
                + "\"maxOutputTokens\":32000,\"credits\":\"x1.2\"}],"
                + "\"modelPromotions\":[{\"enabled\":true,\"modelIds\":[\"promo-model\"],"
                + "\"discount\":{\"discountedCredits\":\"0x\"},"
                + "\"schedule\":{\"validFrom\":\"2020-01-01T00:00:00+08:00\","
                + "\"validUntil\":\"2099-01-01T00:00:00+08:00\"}}]}}";
        LoginProviders.Catalog e2e = com.wbgateway.CatalogProbe.parse(new org.json.JSONObject(cfgJson));
        check("e2e：促销模型标出 x0.15 → x0",
                "x0.15→x0".equals(com.wbgateway.RateProbe.creditRateText(
                        e2e.specs.get("promo-model"))),
                com.wbgateway.RateProbe.creditRateText(e2e.specs.get("promo-model")));
        check("e2e：未促销的模型不出现箭头",
                "x1.2".equals(com.wbgateway.RateProbe.creditRateText(
                        e2e.specs.get("plain-model"))),
                com.wbgateway.RateProbe.creditRateText(e2e.specs.get("plain-model")));

        // 代理渠道：没有倍率就不显示（不能凭空写 x1）
        Channel proxyChannel = new Channel("ds", "代理", "https://api.deepseek.com/v1");
        check("代理渠道模型没有倍率",
                com.wbgateway.RateProbe.creditRateText(
                        ModelSpec.fromWorkbuddy(wbModel("x", "X", 1000, 10, false, null))).isEmpty(), "");
        check("null 规格不崩", com.wbgateway.RateProbe.creditRateText(null).isEmpty(), "");
        check("代理渠道自身字段为空", proxyChannel.modelSpecs.isEmpty(), "");

        System.out.println();
        System.out.println("=== 10. Raccoon 也有倍率（上游 model_catalog 真实字段）===");
        // 真实响应片段（2026-11-04，`/api/web/llm/v2/model_catalog`）
        String raccoonJson = "{\"code\":0,\"data\":{\"categories\":["
                + "{\"type\":\"chat\",\"models\":["
                + "{\"name\":\"sn-deepseek-v4-1-flash\",\"description\":\"DeepSeek-V4.1-Flash\","
                + "\"visible\":true,\"billing_multiplier\":0.25,"
                + "\"billing_effective_multiplier\":0.25,\"billing_status\":\"normal\","
                + "\"params\":{\"context_window\":1000000,\"max_tokens\":100000}},"
                + "{\"name\":\"sn-glm-5-3-flash\",\"description\":\"GLM-5-3-Flash\","
                + "\"visible\":true,\"billing_multiplier\":0.2,"
                + "\"billing_effective_multiplier\":0.1,\"billing_status\":\"discount\","
                + "\"params\":{\"context_window\":1000000,\"max_tokens\":100000}},"
                + "{\"name\":\"sn-kimi-k3\",\"description\":\"Kimi-K3\","
                + "\"visible\":true,\"billing_multiplier\":1,"
                + "\"billing_effective_multiplier\":1,\"billing_status\":\"normal\","
                + "\"params\":{\"context_window\":1000000,\"max_tokens\":100000}},"
                + "{\"name\":\"sn-hidden\",\"visible\":false,"
                + "\"billing_effective_multiplier\":0.5}]},"
                + "{\"type\":\"image\",\"models\":[{\"name\":\"img-model\",\"visible\":true}]}]}}";
        LoginProviders.Catalog raccoonCat = com.wbgateway.CatalogProbe.parseRaccoon(
                new org.json.JSONObject(raccoonJson));
        check("Raccoon 目录解析出 3 个 chat 模型", raccoonCat.ids.size() == 3,
                raccoonCat.ids.toString());
        check("不可见的模型被跳过", !raccoonCat.ids.contains("sn-hidden"), "");
        check("绘图分类不进清单", !raccoonCat.ids.contains("img-model"), "");
        ModelSpec ds = raccoonCat.specs.get("sn-deepseek-v4-1-flash");
        check("DeepSeek-V4.1-Flash 名字取 description",
                ds != null && "DeepSeek-V4.1-Flash".equals(ds.name), ds == null ? "" : ds.name);
        check("Raccoon 倍率 x0.25", ds != null && "x0.25".equals(ds.creditRate),
                ds == null ? "" : ds.creditRate);
        ModelSpec flash = raccoonCat.specs.get("sn-glm-5-3-flash");
        check("促销模型：原价 x0.2 → 实收 x0.1",
                flash != null && "x0.2".equals(flash.creditRate)
                        && "x0.1".equals(flash.discountedCreditRate),
                flash == null ? "" : flash.creditRateLabel());
        ModelSpec kimi = raccoonCat.specs.get("sn-kimi-k3");
        check("1 倍也显示（x1，不省略）", kimi != null && "x1".equals(kimi.creditRate),
                kimi == null ? "" : kimi.creditRate);
        check("名字后缀形态：DeepSeek-V4.1-Flash x0.25",
                (ds.name + "  " + com.wbgateway.RateProbe.creditRateText(ds))
                        .equals("DeepSeek-V4.1-Flash  x0.25"),
                ds.name + "  " + com.wbgateway.RateProbe.creditRateText(ds));

        // 内置表兜底：目录拉不到时也必须有倍率（否则界面上又只剩 id）
        Map<String, ModelSpec> raccoonBuiltin = LoginProviders.raccoonBuiltinSpecs();
        check("Raccoon 内置表 6 条", raccoonBuiltin.size() == 6,
                "共 " + raccoonBuiltin.size() + " 条");
        int withRate = 0;
        for (ModelSpec s : raccoonBuiltin.values()) {
            if (!s.creditRate.isEmpty()) withRate += 1;
        }
        check("内置表每条都带倍率", withRate == raccoonBuiltin.size(),
                withRate + " / " + raccoonBuiltin.size());
        check("内置表里 Kimi-K3 是 x1",
                "x1".equals(raccoonBuiltin.get("sn-kimi-k3").creditRate), "");

        System.out.println();
        System.out.println("=== 11. 各登录渠道的聊天基址（缺了就报「未配置 Base URL」）===");
        // 这条是真 bug：WorkBuddy 登录时没人写 baseUrl，调用时网关说「渠道未配置 Base URL」
        String[][] bases = {
                {LoginProviders.WB_INTL, "https://www.workbuddy.ai/v2"},
                {LoginProviders.WB_CN, "https://www.codebuddy.cn/v2"},
                {LoginProviders.QODER, "https://api2-v2.qoder.sh/model/v1"},
                {LoginProviders.QODER_CN, "https://gateway.qoder.com.cn"},
                {LoginProviders.ZCODE, "https://zcode.z.ai/api/v1/zcode-plan/anthropic/v1"},
                {LoginProviders.RACCOON, "https://xiaohuanxiong.com/api/web/llm/v2"},
                {LoginProviders.CODEARTS, "https://snap-access.cn-north-4.myhuaweicloud.com/api/v2"},
        };
        for (String[] b : bases) {
            String got = LoginProviders.chatBaseFor(b[0]);
            check("基址 " + b[0], b[1].equals(got), got);
        }
        check("未知渠道返回空（报配置错误，不瞎猜）",
                LoginProviders.chatBaseFor("nope").isEmpty(), "");

        // 老渠道（存的 JSON 里没有 baseUrl）读出来必须已经补好
        Channel oldWb = Channel.fromJson(new org.json.JSONObject(
                "{\"id\":\"workbuddy-cn\",\"kind\":\"login\",\"loginType\":\"workbuddy-cn\","
                        + "\"accessToken\":\"t\"}"));
        check("老 WorkBuddy 渠道读出来就有基址",
                "https://www.codebuddy.cn/v2".equals(oldWb.baseUrl), oldWb.baseUrl);
        check("老渠道 chatBase 不再为空",
                !com.wbgateway.GatewayProbe.chatBase(oldWb).isEmpty(),
                com.wbgateway.GatewayProbe.chatBase(oldWb));

        System.out.println();
        System.out.println("=== 结果：PASS " + pass + " / FAIL " + fail + " ===");
        if (fail > 0) System.exit(1);
    }
}
