package com.wbgateway;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型目录刷新（「刷新模型」按钮的统一入口）。
 *
 * <h3>为什么需要这一层</h3>
 *
 * 「刷新模型」原来直接调 {@link LoginProviders#fetchModels}，而那个函数**只认
 * WorkBuddy 的私有目录接口**（`/console/enterprises/personal/models`）。
 * 对别的渠道等于把请求拼到错误地址上：
 *
 * <pre>
 * qoder.com/console/…        → 404
 * qoder.cn/console/…         → 404
 * zcode.z.ai/console/…       → 307
 * xiaohuanxiong.com/console/… → 200，但返 HTML
 * </pre>
 *
 * 结果就是「点了刷新模型没反应」—— 用户看到「上游没返回模型清单」，
 * 以为是网络问题，其实这个渠道根本没有这个接口。
 *
 * <h3>现在的策略</h3>
 *
 * <ul>
 *   <li><b>有实时目录</b>（只有 WorkBuddy 两区域）：真去拉，拉不到就如实报错；</li>
 *   <li><b>没有实时目录</b>（Qoder / ZCode / Raccoon）：**不发请求**，
 *       直接把内置表写进 `manualModels`，并说明「这是内置清单，未与账号权限核对」。</li>
 * </ul>
 *
 * 这样点「刷新」永远有明确反馈，不会出现「转一圈什么都没变」。
 */
public final class ModelCatalog {

    private ModelCatalog() {
    }

    /** 一次刷新的结果。 */
    public static final class RefreshResult {
        /** 是否拿到了模型清单。 */
        public boolean ok;
        /** 本次得到的模型 id（ok=true 时有效）。 */
        public List<String> models = new ArrayList<>();
        /** 带规格的模型条数（可能小于 models.size()，上游不一定每条都给规格）。 */
        public int specCount = 0;
        /** 清单来源：`live`（上游实时）/ `builtin`（内置表）。 */
        public String source = "";
        /** 给用户看的一句话。 */
        public String message = "";

        static RefreshResult fail(String message) {
            RefreshResult r = new RefreshResult();
            r.ok = false;
            r.message = message;
            return r;
        }
    }

    /**
     * 刷新一个渠道的模型清单（**阻塞**，须在后台线程调用）。
     *
     * <p>只在成功时改 `channel.cachedModels` / `manualModels`；
     * 失败一律保留原状并把原因写进结果。
     *
     * @param channel 目标渠道
     * @return 刷新结果（不抛异常）
     */
    public static RefreshResult refresh(Channel channel) {
        if (channel == null) return RefreshResult.fail("渠道不存在");
        try {
            // CodeArts：有真实清单端点（签名 GET），拉不到就退内置表。
            // 它的临时凭据约 2 小时，过期时拉不到属于常态，
            // 所以这里**不报错**而是退回内置表 —— 这与「不能假装成功」不冲突：
            // 提示里会写明来源是内置表。
            if (LoginProviders.CODEARTS.equals(channel.loginType)) {
                return refreshCodeArts(channel);
            }
            if (LoginProviders.hasLiveModelCatalog(channel.loginType)) {
                return refreshLive(channel);
            }
            // Raccoon 也有真实目录（`/api/web/llm/v2/model_catalog`，带积分倍率）；
            // 拉不到才退回内置表 —— 它的目录可能因为账号权限为空。
            if (LoginProviders.RACCOON.equals(channel.loginType)) {
                return refreshRaccoon(channel);
            }
            return refreshBuiltin(channel);
        } catch (Exception e) {
            // 有目录接口但拉失败：给出比「刷新失败」更有用的说明，
            // 并让用户知道可以先用内置表顶上。
            //
            // ⚠ 国际版（www.workbuddy.ai）的 scoped 端点**稳定 500**，但
            // `/v3/config` 是好的 —— fetchModelsDetailed 已经在内部兜到那条路，
            // 所以走到这里说明两条都失败了（通常是 token 过期），要如实说。
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("401") || msg.contains("403")) {
                return RefreshResult.fail("凭据已失效或过期，请重新登录该渠道");
            }
            if (msg.contains("500")) {
                return RefreshResult.fail("上游模型目录接口返回 500（服务端故障，加任何请求头都一样）"
                        + " —— 这不是账号问题；可以先用内置清单");
            }
            return RefreshResult.fail("刷新失败：" + msg);
        }
    }

    /**
     * Raccoon 的模型刷新：先拉真实目录（带积分倍率），拉不到退内置表。
     *
     * <p>为什么不报错：Raccoon 的目录可能因为账号权限 / 未开通而返回空，
     * 此时内置表（同样带倍率，来自实测）仍然可用。报错反而挡住用户，
     * 但提示里会写清来源，不假装是实时结果。
     */
    private static RefreshResult refreshRaccoon(Channel channel) {
        LoginProviders.Catalog cat = LoginProviders.fetchRaccoonModels(channel);
        if (!cat.ids.isEmpty()) {
            channel.cachedModels = cat.ids;
            channel.modelSpecs.clear();
            channel.modelSpecs.putAll(cat.specs);
            channel.manualModels = new ArrayList<>();
            channel.modelsUpdatedAt = System.currentTimeMillis();
            RefreshResult r = new RefreshResult();
            r.ok = true;
            r.models = cat.ids;
            r.specCount = cat.specs.size();
            r.source = "live";
            r.message = "已从 Raccoon 刷新 " + cat.ids.size() + " 个模型"
                    + (cat.specs.isEmpty() ? "" : "（含 " + cat.specs.size() + " 条规格）");
            return r;
        }
        RefreshResult r = refreshBuiltin(channel);
        if (r.ok) {
            r.message = "没有取到 Raccoon 目录（账号权限或网络问题），已用内置清单（"
                    + r.models.size() + " 个，含积分倍率）";
        }
        return r;
    }

    /**
     * CodeArts 的模型刷新：先签名拉真实清单，拉不到退内置表。
     *
     * <p>为什么不跟 WorkBuddy 一样「拉不到就报错」：CodeArts 的临时凭据
     * 只有约 2 小时，过期后清单必然拉不到；此时内置表仍然可用
     * （模型 id 是平台上固定的），报错反而挡住用户。所以退回内置表，
     * 但**提示里写清来源**，不假装是实时结果。
     */
    private static RefreshResult refreshCodeArts(Channel channel) {
        LoginProviders.Catalog cat = LoginProviders.fetchCodeArtsModels(channel);
        if (!cat.ids.isEmpty()) {
            channel.cachedModels = cat.ids;
            channel.modelSpecs.clear();
            channel.modelSpecs.putAll(cat.specs);
            channel.manualModels = new ArrayList<>();
            channel.modelsUpdatedAt = System.currentTimeMillis();
            RefreshResult r = new RefreshResult();
            r.ok = true;
            r.models = cat.ids;
            r.specCount = cat.specs.size();
            r.source = "live";
            r.message = "已从华为云刷新 " + cat.ids.size() + " 个模型";
            return r;
        }
        // 退回内置表（并说明原因，不假装成功）
        RefreshResult r = refreshBuiltin(channel);
        if (r.ok) {
            r.message = "没有取到华为云清单（临时凭据可能已过期），已用内置清单（"
                    + r.models.size() + " 个）";
        }
        return r;
    }

    /** 有实时目录的渠道：真去拉（含上游下发的规格）。 */
    private static RefreshResult refreshLive(Channel channel) throws java.io.IOException {
        LoginProviders.Catalog cat = LoginProviders.fetchModelsDetailed(channel);
        List<String> models = cat.ids;
        if (models.isEmpty()) {
            // 不拿内置表顶替：WorkBuddy 能拉到却为空，说明账号确实没有可用模型
            return RefreshResult.fail("上游没有返回任何模型（该账号可能确实没有可用模型）");
        }
        channel.cachedModels = models;
        // 规格也一起存：界面上要显示上游声明的真实名与真实窗口
        channel.modelSpecs.clear();
        channel.modelSpecs.putAll(cat.specs);
        channel.modelsUpdatedAt = System.currentTimeMillis();
        RefreshResult r = new RefreshResult();
        r.ok = true;
        r.models = models;
        r.specCount = cat.specs.size();
        r.source = "live";
        r.message = "已从上游刷新 " + models.size() + " 个模型"
                + (cat.specs.isEmpty() ? "" : "（含 " + cat.specs.size() + " 条规格）");
        return r;
    }

    /** 没有实时目录的渠道：用内置表（含规格），并且说明清楚。 */
    private static RefreshResult refreshBuiltin(Channel channel) {
        List<String> builtin = LoginProviders.defaultModelsFor(channel.loginType);
        if (builtin.isEmpty()) {
            return RefreshResult.fail("该渠道没有可用的模型清单");
        }
        // 内置表写进 manualModels：cachedModels 语义是「从上游缓存的」，
        // 混进去会让界面误以为是实时清单
        channel.manualModels = new ArrayList<>(builtin);
        // Qoder 的内置表带完整规格，一并写入，界面就能显示真实名与窗口
        channel.modelSpecs.clear();
        int probed = 0;
        int needSub = 0;
        if (LoginProviders.isQoderFamily(channel.loginType)) {
            channel.modelSpecs.putAll(LoginProviders.qoderBuiltinSpecs(channel.loginType));
            // 逐模型**实测**：可用性会随账号额度变，静态清单靠不住。
            // 拿到结果就写回 spec，界面据此显示「繁忙」或真实错误。
            java.util.Map<String, QoderProbeResult> results =
                    QoderProbe.probeAll(channel, new ArrayList<>(channel.modelSpecs.keySet()));
            probed = results.size();
            for (java.util.Map.Entry<String, QoderProbeResult> e : results.entrySet()) {
                ModelSpec spec = channel.modelSpecs.get(e.getKey());
                if (spec == null) continue;
                QoderProbeResult res = e.getValue();
                if (res.queued()) {
                    spec.queued = true;
                    spec.queuedNote = "正在排队";
                    needSub += 1;
                } else if (!res.usable() && res.status != QoderProbe.UNKNOWN) {
                    // 模型不存在 / 后端故障：不标订阅，但把真实原因写进备注，
                    // 免得用户点了失败还以为是网关的问题
                    spec.creditNote = spec.creditNote.isEmpty()
                            ? res.reason : spec.creditNote + "；" + res.reason;
                }
            }
        } else if (LoginProviders.RACCOON.equals(channel.loginType)) {
            // Raccoon 的内置表也带倍率（x0 / x0.25 / x1 …），
            // 不写进去界面上就只剩模型 id，看不出哪个贵
            channel.modelSpecs.putAll(LoginProviders.raccoonBuiltinSpecs());
        } else if (LoginProviders.CODEARTS.equals(channel.loginType)) {
            channel.modelSpecs.putAll(LoginProviders.codeartsBuiltinSpecs());
        }
        channel.modelsUpdatedAt = System.currentTimeMillis();
        RefreshResult r = new RefreshResult();
        r.ok = true;
        r.models = builtin;
        r.specCount = channel.modelSpecs.size();
        r.source = "builtin";
        // Qoder 的内置表是**实测过的**（每个 id 都真打过上游），措辞要区别于
        // 「目录里抄来的、没核对过」那种内置表 —— 否则用户会以为它不可靠。
        String why;
        if (LoginProviders.isQoderFamily(channel.loginType)) {
            why = "该渠道没有模型目录接口，已用内置清单（" + builtin.size() + " 个）"
                    + "并逐个实测：" + probed + " 个已探测"
                    + (needSub > 0 ? "，其中 " + needSub + " 个正在排队（繁忙）" : "");
        } else {
            why = "该渠道没有模型目录接口，已用内置清单（" + builtin.size() + " 个";
        }
        r.message = why
                + (channel.modelSpecs.isEmpty() ? "" : "，含真实规格")
                + "，未与账号权限核对）";
        return r;
    }
}
