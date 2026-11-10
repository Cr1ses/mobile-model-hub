import com.wbgateway.Channel;
import com.wbgateway.LoginProviders;
import com.wbgateway.ModelCatalog;

import java.util.List;

/**
 * 「刷新模型」的桌面验证。
 *
 * <p>这条链路的 bug 形态很典型：点了没反应，界面说「上游没返回模型清单」，
 * 用户以为是网络问题 —— 实际是**把 WorkBuddy 的私有目录接口拼到了别家域名上**。
 * 实测：qoder/qodercn → 404、zcode → 307、raccoon → 200 但返 HTML。
 *
 * <p>所以这里锁三件事：
 * <ol>
 *   <li>没有实时目录的渠道**绝不**去发那个请求；</li>
 *   <li>它们刷新后应拿到**内置表**，且明确标注来源；</li>
 *   <li>WorkBuddy 的「拉不到就报错」语义不变（不拿内置表冒充实时清单）。</li>
 * </ol>
 */
public class VerifyCatalog {

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

    static Channel loginChannel(String id, String loginType) {
        Channel c = new Channel();
        c.id = id;
        c.loginType = loginType;
        c.kind = Channel.KIND_LOGIN;
        c.accessToken = "test-token";
        return c;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 1. 哪些渠道真有模型目录接口 ===");
        check("WorkBuddy 国际版有", LoginProviders.hasLiveModelCatalog(LoginProviders.WB_INTL), "");
        check("WorkBuddy 国内版有", LoginProviders.hasLiveModelCatalog(LoginProviders.WB_CN), "");
        check("Qoder 没有", !LoginProviders.hasLiveModelCatalog(LoginProviders.QODER), "");
        check("Qoder 中国版没有", !LoginProviders.hasLiveModelCatalog(LoginProviders.QODER_CN), "");
        check("ZCode 没有", !LoginProviders.hasLiveModelCatalog(LoginProviders.ZCODE), "");
        check("Raccoon 没有", !LoginProviders.hasLiveModelCatalog(LoginProviders.RACCOON), "");

        System.out.println();
        System.out.println("=== 2. 没有目录接口的渠道：不再发那个注定失败的请求 ===");
        // 关键断言：fetchModels 必须**直接拒绝**，而不是真的去请求
        // （真请求的后果是 404 / 307 / HTML，然后界面显示「上游没返回模型清单」）
        for (String t : new String[]{LoginProviders.QODER, LoginProviders.QODER_CN,
                LoginProviders.ZCODE, LoginProviders.RACCOON}) {
            Channel c = loginChannel(t, t);
            boolean threw = false;
            String msg = "";
            try {
                LoginProviders.fetchModels(c);
            } catch (Exception e) {
                threw = true;
                msg = String.valueOf(e.getMessage());
            }
            check(t + " 调 fetchModels 直接拒绝", threw && msg.contains("没有模型目录接口"), msg);
        }

        System.out.println();
        System.out.println("=== 3. 刷新走内置表（并说明来源）===");
        Channel qoder = loginChannel("qoder", LoginProviders.QODER);
        ModelCatalog.RefreshResult r1 = ModelCatalog.refresh(qoder);
        check("Qoder 刷新成功", r1.ok, r1.message);
        check("Qoder 来源是 builtin", "builtin".equals(r1.source), r1.source);
        check("Qoder 拿到内置模型", !r1.models.isEmpty(), "共 " + r1.models.size() + " 个");
        check("Qoder 写进了 manualModels（不是 cachedModels）",
                !qoder.manualModels.isEmpty() && qoder.cachedModels.isEmpty(),
                "manual=" + qoder.manualModels.size() + " cached=" + qoder.cachedModels.size());
        check("提示里说明「未与账号权限核对」", r1.message.contains("未与账号权限核对"), r1.message);

        Channel zcode = loginChannel("zcode", LoginProviders.ZCODE);
        ModelCatalog.RefreshResult r2 = ModelCatalog.refresh(zcode);
        check("ZCode 刷新成功", r2.ok, r2.message);
        check("ZCode 拿到内置模型", !r2.models.isEmpty(), "共 " + r2.models.size() + " 个");

        Channel raccoon = loginChannel("raccoon", LoginProviders.RACCOON);
        ModelCatalog.RefreshResult r3 = ModelCatalog.refresh(raccoon);
        check("Raccoon 刷新成功", r3.ok, r3.message);
        check("Raccoon 拿到内置模型", !r3.models.isEmpty(), "共 " + r3.models.size() + " 个");

        System.out.println();
        System.out.println("=== 4. 刷新后能出现在模型清单里（界面看得到）===");
        check("Qoder 刷完后 effectiveModels 非空", !qoder.effectiveModels().isEmpty(),
                "共 " + qoder.effectiveModels().size() + " 个");
        check("ZCode 刷完后 effectiveModels 非空", !zcode.effectiveModels().isEmpty(),
                "共 " + zcode.effectiveModels().size() + " 个");
        check("刷新时间戳被更新", qoder.modelsUpdatedAt > 0, String.valueOf(qoder.modelsUpdatedAt));

        System.out.println();
        System.out.println("=== 5. 重复刷新幂等（不会越刷越多）===");
        int before = qoder.manualModels.size();
        ModelCatalog.refresh(qoder);
        ModelCatalog.refresh(qoder);
        check("多次刷新数量不变", qoder.manualModels.size() == before,
                before + " → " + qoder.manualModels.size());

        System.out.println();
        System.out.println("=== 6. WorkBuddy：拉不到就报错，不拿内置表冒充实时 ===");
        Channel wb = loginChannel("cn", LoginProviders.WB_CN);
        // 这里不真的联网：用一个不可解析的域名制造失败
        wb.domain = "invalid.invalid";
        ModelCatalog.RefreshResult r4 = ModelCatalog.refresh(wb);
        check("WorkBuddy 失败时 ok=false", !r4.ok, r4.message);
        check("WorkBuddy 失败时不写内置表（不冒充实时清单）", wb.manualModels.isEmpty(), "");

        System.out.println();
        System.out.println("=== 7. 代理渠道不受影响 ===");
        Channel proxy = new Channel("ds", "代理", "https://api.deepseek.com/v1");
        proxy.apiKeys.add("sk-x");
        ModelCatalog.RefreshResult r5 = ModelCatalog.refresh(proxy);
        check("代理渠道没有内置表 → 如实报错", !r5.ok, r5.message);

        System.out.println();
        System.out.println("=== 8. /v3/config 解析（国际版 scoped 端点 500 时的退路）===");
        // 真实响应片段（2026-11-04 国际版实测结构，已裁短）
        String cfg = "{\"code\":0,\"data\":{"
                + "\"agents\":[{\"name\":\"cli\",\"models\":"
                + "[\"default-model\",\"max-model\",\"auto\",\"hy3\",\"glm-5.3\","
                + "\"deepseek-v4.1-flash-sg\",\"nes-foo\",\"completion-bar\"]}],"
                + "\"models\":["
                + "{\"id\":\"default-model\",\"name\":\"Auto\",\"maxInputTokens\":176000,"
                + "\"maxOutputTokens\":24000,\"supportsImages\":true},"
                + "{\"id\":\"glm-5.3\",\"name\":\"GLM-5.3\",\"maxInputTokens\":200000,"
                + "\"maxOutputTokens\":64000,\"supportsImages\":true},"
                + "{\"id\":\"completion-bar\",\"name\":\"Completion\",\"maxOutputTokens\":128}]}}";
        LoginProviders.Catalog cfgCat = com.wbgateway.CatalogProbe.parse(new org.json.JSONObject(cfg));
        check("能从 agents[].models 取到清单", cfgCat.ids.size() >= 4, cfgCat.ids.toString());
        check("包含 default-model 与 glm-5.3",
                cfgCat.ids.contains("default-model") && cfgCat.ids.contains("glm-5.3"), "");
        check("过滤掉 auto 别名", !cfgCat.ids.contains("auto"), "");
        check("过滤掉 nes- / completion- 前缀", !cfgCat.ids.contains("nes-foo")
                && !cfgCat.ids.contains("completion-bar"), "");
        check("规格被解析出来", cfgCat.specs.get("glm-5.3") != null
                && cfgCat.specs.get("glm-5.3").maxOutput == 64000,
                cfgCat.specs.get("glm-5.3") == null ? "" : String.valueOf(cfgCat.specs.get("glm-5.3").maxOutput));

        System.out.println();
        System.out.println("=== 结果：PASS " + pass + " / FAIL " + fail + " ===");
        if (fail > 0) System.exit(1);
    }
}
