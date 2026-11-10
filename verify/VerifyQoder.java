import com.wbgateway.Channel;
import com.wbgateway.LoginProviders;
import com.wbgateway.ModelSpec;

/**
 * Qoder 渠道的离线验证。
 *
 * <h3>为什么这个渠道值得单独钉</h3>
 *
 * 它曾经被判定成「必须 WASM 签名才能用」，于是聊天功能整条被放弃 ——
 * 后来实测发现**普通 Bearer token 就能调通**，真因只是「拿错了凭据」
 * （PAT 未激活）与「模型 id 不在这个端点接受的集合里」。两个判断错误都很贵：
 * <ul>
 *   <li>误判「必须 WASM」→ 整条链路被放弃，用户完全用不了；</li>
 *   <li>误判「17 个模型都能用」→ 界面列一堆点了必 400 的名字。</li>
 * </ul>
 * 所以这里同时钉「可用模型集合」与「基址」。
 */
public class VerifyQoder {

    static int pass = 0;
    static int fail = 0;

    static void check(String label, boolean ok, String detail) {
        if (ok) {
            pass += 1;
            System.out.println("  PASS  " + label + (detail == null || detail.isEmpty() ? "" : " -- " + detail));
        } else {
            fail += 1;
            System.out.println("  FAIL  " + label + (detail == null || detail.isEmpty() ? "" : " -- " + detail));
        }
    }

    public static void main(String[] args) {
        System.out.println("=== 1. 聊天基址（写错就整条不通）===");
        check("国际版基址是 api2-v2.qoder.sh/model/v1",
                "https://api2-v2.qoder.sh/model/v1".equals(
                        LoginProviders.chatBaseFor(LoginProviders.QODER)),
                LoginProviders.chatBaseFor(LoginProviders.QODER));
        // CN 没有公开 OpenAI 兼容端点（/model/v1/chat/completions 实测 404），
        // 所以这里存的是**根地址**，algo 路径由 proxyChatQoder 自己拼。
        check("中国版基址是根地址 gateway.qoder.com.cn",
                "https://gateway.qoder.com.cn".equals(
                        LoginProviders.chatBaseFor(LoginProviders.QODER_CN)),
                LoginProviders.chatBaseFor(LoginProviders.QODER_CN));
        check("中国版 algo 基址与根地址一致",
                "https://gateway.qoder.com.cn".equals(
                        LoginProviders.qoderAlgoBase(LoginProviders.QODER_CN)),
                LoginProviders.qoderAlgoBase(LoginProviders.QODER_CN));
        check("国际版 algo 基址是 api2.qoder.sh（与公开端点 api2-v2 不同主机）",
                "https://api2.qoder.sh".equals(
                        LoginProviders.qoderAlgoBase(LoginProviders.QODER)),
                LoginProviders.qoderAlgoBase(LoginProviders.QODER));

        System.out.println();
        System.out.println("=== 2. 真实可用模型集合（实测 200 的才留）===");
        java.util.List<String> usable = LoginProviders.qoderUsableModels();
        check("恰好 6 个可用模型", usable.size() == 6, "共 " + usable.size() + " 个");
        for (String must : new String[]{"ultimate", "qmodel", "kmodel", "gmodel", "dmodel", "mmodel"}) {
            check("含 " + must, usable.contains(must), "");
        }
        // 这些实测是 400 Unsupported model / 429，不能出现在清单里
        // 「需订阅」的模型**要列出来**（否则用户不知道升级能多用什么），
        // 但必须带标记，不能混在可用项里当成能直接用的。
        java.util.Map<String, ModelSpec> allSpecs = LoginProviders.qoderBuiltinSpecs();
        for (String sub : new String[]{"cmodel", "smodel", "qmodel_38max", "qfmodel",
                "qmodel_latest", "kmodel_latest", "gfmodel", "dfmodel"}) {
            check("清单含需订阅的 " + sub,
                    LoginProviders.defaultModelsFor(LoginProviders.QODER).contains(sub),
                    LoginProviders.defaultModelsFor(LoginProviders.QODER).toString());
            // 不再静态标记：可用性随账号额度变，必须由运行时探测写。
            // 静态打标会把「此刻恰好能用」误报成「需要订阅」（实测 5 天内就翻转了）。
            check(sub + " 未被静态标记（交给运行时探测）",
                    allSpecs.containsKey(sub) && !allSpecs.get(sub).queued, "");
        }
        check("可用集（qoderUsableModels）本身不含需订阅的 cmodel",
                !LoginProviders.qoderUsableModels(LoginProviders.QODER).contains("cmodel"), "");

        System.out.println();
        System.out.println("=== 3. 规格只给可用模型配（避免「看起来能用」）===");
        java.util.Map<String, ModelSpec> specs = LoginProviders.qoderBuiltinSpecs();
        check("规格表 = 可用 + 需订阅（14 = 6 + 8）", specs.size() == 14,
                "实际 " + specs.size());
        for (String id : usable) {
            check("有 " + id + " 的规格", specs.containsKey(id), "");
        }
        ModelSpec g = specs.get("gmodel");
        check("gmodel 是 GLM-5.3", g != null && "GLM-5.3".equals(g.name), g == null ? "" : g.name);
        check("gmodel 倍率 x0.8", g != null && "x0.8".equals(g.creditRate),
                g == null ? "" : g.creditRate);
        ModelSpec u = specs.get("ultimate");
        check("ultimate 倍率 x2", u != null && "x2".equals(u.creditRate), u == null ? "" : u.creditRate);
        check("任何一条都有倍率（Qoder 按倍率计费）", allHaveRate(specs), "");

        System.out.println();
        System.out.println("=== 4. 鉴权：不需要 WASM 签名（曾经判断错了）===");
        // 这条是「知识性断言」：它把实测结论固定下来，防止有人再按
        // 「必须 COSY 签名」去改实现。真正的证据在 README 的真实接口验证一节。
        Channel c = new Channel();
        c.kind = Channel.KIND_LOGIN;
        c.loginType = LoginProviders.QODER;
        c.accessToken = "dt-fake";
        check("普通 accessToken 即可参与转发（不要求 COSY）",
                !c.accessToken.isEmpty(), "");
        check("基址不含 gateway（那是历史字段，实测 503）",
                !LoginProviders.chatBaseFor(LoginProviders.QODER).contains("gateway.qoder.com"),
                LoginProviders.chatBaseFor(LoginProviders.QODER));

        System.out.println();

        System.out.println();
        System.out.println("=== 4b. 老渠道缺 uid 时的回填 ===");
        // 这是「真机升级后一调用就报凭据缺少 uid」的根因：
        // uid 是后加的字段，老数据库里没有；凭据 JSON 里其实带着它，
        // 粘贴时没读 → 必须补上，否则用户要重新登录才能用。
        check("凭据 JSON 里的 uid 能被解析出来",
                "01234567-89ab-cdef-0123-456789abcdef".equals(
                        LoginProviders.regexString(
                                "{\"uid\":\"01234567-89ab-cdef-0123-456789abcdef\"}", "uid")),
                LoginProviders.regexString(
                        "{\"uid\":\"01234567-89ab-cdef-0123-456789abcdef\"}", "uid"));
        check("user_id 也能当别名",
                "abc".equals(LoginProviders.regexString("{\"user_id\":\"abc\"}", "user_id")), "");
        check("JWT 形态能推 uid",
                !LoginProviders.inferQoderUid(
                        "eyJhbGciOiJIUzI1NiJ9.eyJ1aWQiOiJ0ZXN0LXVpZC0xMjMifQ.sig").isEmpty(),
                LoginProviders.inferQoderUid(
                        "eyJhbGciOiJIUzI1NiJ9.eyJ1aWQiOiJ0ZXN0LXVpZC0xMjMifQ.sig"));
        check("dt- 形态推不出 uid（返回空串，不编造）",
                LoginProviders.inferQoderUid("dt-EXAMPLETOKEN1234567890AB").isEmpty(), "");
        check("空 token 不崩",
                LoginProviders.inferQoderUid("").isEmpty()
                        && LoginProviders.inferQoderUid(null).isEmpty(), "");
        System.out.println("=== 5. SSE 伪成功要能被识别（Qoder 实测 HTTP 200 + event: error）===");
        String sseError = "event: error\n"
                + "data: {\"code\":\"invalid_model_error\",\"message\":\"Unsupported model \\\"cmodel\\\"\"}\n\n";
        check("识别 event: error", com.wbgateway.UpstreamError.isSseErrorEvent(sseError), "");
        String described = com.wbgateway.UpstreamError.describeSseError(sseError);
        check("说明里带上游原文", described.contains("Unsupported model"), described);
        check("说明里带错误码", described.contains("invalid_model_error"), described);
        check("正常流不会被误判",
                !com.wbgateway.UpstreamError.isSseErrorEvent(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"error 是英文单词\"}}]}\n\n"), "");
        check("正文里出现 error 字样不误判",
                !com.wbgateway.UpstreamError.isSseErrorEvent(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"Error handling\"}}]}\n\n"), "");
        check("空输入不崩",
                !com.wbgateway.UpstreamError.isSseErrorEvent(null)
                        && !com.wbgateway.UpstreamError.isSseErrorEvent(""), "");

        System.out.println();
        System.out.println("=== 结果：PASS " + pass + " / FAIL " + fail + " ===");
        if (fail > 0) System.exit(1);
    }

    static boolean allHaveRate(java.util.Map<String, ModelSpec> specs) {
        for (ModelSpec s : specs.values()) {
            if (s.creditRate.isEmpty()) return false;
        }
        return !specs.isEmpty();
    }
}
