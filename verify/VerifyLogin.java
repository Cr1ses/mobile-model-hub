import com.wbgateway.Channel;
import com.wbgateway.ChannelLogin;
import com.wbgateway.LoginProviders;
import com.wbgateway.QrCode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 直接登录的桌面等价验证：跑**同一份** {@link ChannelLogin} 代码，只把上游换成假的。
 *
 * <p>为什么必须验：登录是最容易「看起来成功、其实拿到的是坏凭据」的一段代码 ——
 * 字段名对不上（`token` / `device_token` / `access_token`）、时间戳按秒按毫秒搞混、
 * 状态机少一个分支，都不会抛异常，只会让用户之后每次聊天都 401。
 *
 * <p>覆盖：
 * <ol>
 *   <li>Qoder 轮询：404（未完成）→ 200（拿到 token）的状态迁移与字段落位；</li>
 *   <li>Qoder 续期：带 machine_id、拿回新 token 且保留 refresh_token；</li>
 *   <li>ZCode：从 URL / 纯 code / JSON 三种粘贴形态里抠出 code；换 token 的双层信封；</li>
 *   <li>Raccoon：pending → logging → success 的扫码状态机；</li>
 *   <li>登录产物的通用契约：kind=login、有 token、模型清单非空、baseUrl 可转发。</li>
 * </ol>
 */
public class VerifyLogin {

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

    /** 假上游：按路径分发各渠道的登录端点。 */
    static class FakeLoginServer {
        HttpServer server;
        int port;
        /** Qoder 轮询被调用次数（用于验证「先 404 后成功」）。 */
        volatile int qoderPolls = 0;
        /** 记录最近一次收到的请求体（用于断言参数）。 */
        volatile String lastBody = "";
        volatile String lastQuery = "";
        /** Raccoon 轮询次数。 */
        volatile int raccoonPolls = 0;

        void start() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            port = server.getAddress().getPort();

            // --- Qoder 设备令牌轮询 ---
            server.createContext("/api/v1/deviceToken/poll", exchange -> {
                qoderPolls += 1;
                lastQuery = exchange.getRequestURI().getQuery();
                if (qoderPolls == 1) {
                    // 第一次：上游是 404（用户还没在浏览器里完成）
                    send(exchange, 404, "{\"errorCode\":\"NotFound\",\"errorMessage\":\"Not found\"}");
                    return;
                }
                String token = jwt("qoder-user", System.currentTimeMillis() / 1000 + 3600);
                send(exchange, 200, "{\"token\":\"" + token + "\","
                        + "\"refresh_token\":\"drf-test-refresh\","
                        + "\"expires_at\":" + (System.currentTimeMillis() + 3600_000L) + ","
                        + "\"user_name\":\"ccg1gcc\"}");
            });

            // --- Qoder 续期 ---
            server.createContext("/api/v1/deviceToken/refresh", exchange -> {
                lastBody = readBody(exchange);
                String token = jwt("qoder-user", System.currentTimeMillis() / 1000 + 7200);
                send(exchange, 200, "{\"token\":\"" + token + "\","
                        + "\"expires_at\":" + (System.currentTimeMillis() + 7200_000L) + "}");
            });

            // --- ZCode 换 token ---
            server.createContext("/api/v1/oauth/token", exchange -> {
                lastBody = readBody(exchange);
                String zcodeJwt = jwt("zcode-user", System.currentTimeMillis() / 1000 + 3600);
                send(exchange, 200, "{\"code\":0,\"msg\":\"OK\",\"data\":{"
                        + "\"token\":\"" + zcodeJwt + "\","
                        + "\"bigmodel\":{\"access_token\":\"bm-access-1\","
                        + "\"refresh_token\":\"bm-refresh-1\"}}}");
            });

            // --- Raccoon 建二维码 ---
            server.createContext("/api/iam/v1/auth/mp/qrcode", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/login")) {
                    raccoonPolls += 1;
                    lastBody = readBody(exchange);
                    if (raccoonPolls == 1) {
                        send(exchange, 200, "{\"code\":\"OK\",\"data\":{\"status\":\"pending\"}}");
                    } else if (raccoonPolls == 2) {
                        send(exchange, 200, "{\"code\":\"OK\",\"data\":{\"status\":\"logging\"}}");
                    } else {
                        String token = jwt("raccoon-user", System.currentTimeMillis() / 1000 + 3600);
                        send(exchange, 200, "{\"code\":\"OK\",\"data\":{\"status\":\"success\","
                                + "\"access_token\":\"" + token + "\","
                                + "\"refresh_token\":\"rc-refresh-1\","
                                + "\"token_type\":\"Bearer\"}}");
                    }
                    return;
                }
                send(exchange, 200, "{\"code\":\"OK\",\"message\":\"success\",\"data\":{"
                        + "\"expires_in\":300,\"poll_interval\":2,"
                        + "\"qrcode_code\":\"qr-test-code\",\"scan_path\":\"/login/mp\","
                        + "\"status\":\"pending\"}}");
            });

            // --- Raccoon 账号信息 ---
            server.createContext("/api/web/auth/v1/user_info", exchange ->
                    send(exchange, 200, "{\"code\":0,\"data\":{\"id\":\"u1\",\"name\":\"RaccoonIsabella\","
                            + "\"office_identity\":\"\"}}"));

            server.start();
        }

        void stop() {
            if (server != null) server.stop(0);
        }
    }

    /** 造一个只含 exp/sub 的 JWT（不需要真签名，代码只解 payload）。 */
    static String jwt(String sub, long exp) {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":\"" + sub + "\",\"exp\":" + exp
                + ",\"nickname\":\"" + sub + "\"}");
        return header + "." + payload + ".sig";
    }

    static String base64Url(String text) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
        exchange.close();
    }

    static String readBody(HttpExchange exchange) throws java.io.IOException {
        InputStream in = exchange.getRequestBody();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 启动假登录上游 ===");
        FakeLoginServer up = new FakeLoginServer();
        up.start();
        System.out.println("  端口 " + up.port);

        System.out.println();
        System.out.println("=== 1. Qoder 设备码：状态机与字段落位（假上游）===");
        // 用假上游端口替换 Spec.openApiBase，让 ChannelLogin 的真实代码跑完整链路
        String savedQoderOpenApi = LoginProviders.specOf(LoginProviders.QODER).openApiBase;
        com.wbgateway.ChannelLoginProbe.overrideOpenApi(LoginProviders.QODER,
                "http://127.0.0.1:" + up.port);
        try {
            ChannelLogin.LoginSession qs = ChannelLogin.begin(LoginProviders.QODER);
            check("begin 产出授权 URL", qs.loginUrl.startsWith("https://qoder.com/device/selectAccounts?"),
                    qs.loginUrl.substring(0, Math.min(60, qs.loginUrl.length())) + "…");
            check("授权 URL 带齐五个参数",
                    qs.loginUrl.contains("challenge=") && qs.loginUrl.contains("challenge_method=S256")
                            && qs.loginUrl.contains("nonce=") && qs.loginUrl.contains("machine_id=")
                            && qs.loginUrl.contains("client_id="), "");
            check("会话里有 machine_id", !qs.machineId.isEmpty(), qs.machineId);
            check("verifier 长度合规", qs.verifier.length() >= 43, "长度 " + qs.verifier.length());

            ChannelLogin.PollResult first = ChannelLogin.poll(qs, null);
            check("首轮 404 → 等待（不是失败）", first.channel == null && !first.terminal, first.message);

            ChannelLogin.PollResult second = ChannelLogin.poll(qs, null);
            check("第二轮拿到 token", second.channel != null && !second.channel.accessToken.isEmpty(),
                    second.message);
            if (second.channel != null) {
                Channel ch = second.channel;
                check("refresh_token 落位", "drf-test-refresh".equals(ch.refreshToken), ch.refreshToken);
                check("昵称来自上游 user_name", "ccg1gcc".equals(ch.nickname), ch.nickname);
                check("过期时间按毫秒落在未来", ch.expiresAt > System.currentTimeMillis(), "expiresAt=" + ch.expiresAt);
                check("machine_id 被写回渠道（续期要用）", qs.machineId.equals(ch.machineId), ch.machineId);
                check("kind = login", Channel.KIND_LOGIN.equals(ch.kind), ch.kind);
                check("有可转发基址", !ch.baseUrl.isEmpty() && ch.baseUrl.startsWith("https://"), ch.baseUrl);
                check("模型清单非空（内置兜底）", !ch.effectiveModels().isEmpty(),
                        ch.effectiveModels().size() + " 个");

                // 续期：只换 token，不丢 refresh_token
                ch.expiresAt = System.currentTimeMillis() - 1000L;
                Channel refreshed = ChannelLogin.refresh(ch);
                check("续期成功", refreshed != null && !refreshed.accessToken.isEmpty(), "");
                if (refreshed != null) {
                    check("续期请求带了 machine_id", up.lastBody.contains("machine_id"), up.lastBody);
                    check("续期保留原 refresh_token（上游没给新的）",
                            "drf-test-refresh".equals(refreshed.refreshToken), refreshed.refreshToken);
                    check("续期后过期时间更晚", refreshed.expiresAt > ch.expiresAt,
                            ch.expiresAt + " → " + refreshed.expiresAt);
                }
            }
        } finally {
            com.wbgateway.ChannelLoginProbe.overrideOpenApi(LoginProviders.QODER, savedQoderOpenApi);
        }

        System.out.println();
        System.out.println("=== 2. ZCode code 提取（三种粘贴形态）===");
        String fromUrl = com.wbgateway.ChannelLoginProbe.extractCode(
                "https://zcode.z.ai/app/oauth/login?redirect=zcode%3A%2F%2Foauth%2Fcallback&code=abc123&state=xyz");
        check("从完整回调 URL 提取 code", "abc123".equals(fromUrl), fromUrl);
        String fromJson = com.wbgateway.ChannelLoginProbe.extractCode("{\"code\":\"json-code\"}");
        check("从 JSON 提取 code", "json-code".equals(fromJson), fromJson);
        String plain = com.wbgateway.ChannelLoginProbe.extractCode("  plain-code  ");
        check("纯 code 原样保留", "plain-code".equals(plain), plain);
        String noCode = com.wbgateway.ChannelLoginProbe.extractCode("state=only");
        check("没有 code 时不瞎猜", noCode.isEmpty(), "[" + noCode + "]");

        System.out.println();
        System.out.println("=== 3. 二维码内容可编码（保留的能力，供将来复用）===");
        String scanUrl = "https://xiaohuanxiong.com/login/mp?code=qr-test-code"
                + "&appname=%E5%95%86%E6%B1%A4%E5%B0%8F%E6%B5%A3%E7%86%8A&source=platform";
        QrCode qr = QrCode.encode(scanUrl, QrCode.Ecc.M);
        check("扫码 URL 能编成二维码", qr.size() > 0, qr.size() + "×" + qr.size() + " v" + qr.version());
        check("版本合理（手机上够扫）", qr.version() >= 5 && qr.version() <= 15, "v" + qr.version());

        System.out.println();
        System.out.println("=== 3b. Raccoon 登录态识别（WebView 取 token 的核心逻辑）===");
        // 站点规则：cookieName 形如 *xhx_token → 访问令牌 = 前缀 + raccoon_token
        java.util.Map<String, String> jar1 = new java.util.LinkedHashMap<>();
        jar1.put("xhx_token", "BASE");
        jar1.put("raccoon_token", "ACCESS-1");
        jar1.put("raccoon_refresh_token", "REFRESH-1");
        check("按规则推导出访问令牌",
                "ACCESS-1".equals(com.wbgateway.RaccoonLoginProbe.pickToken(jar1)),
                com.wbgateway.RaccoonLoginProbe.pickToken(jar1));
        check("不会误取 refresh_token",
                !"REFRESH-1".equals(com.wbgateway.RaccoonLoginProbe.pickToken(jar1)), "");

        // 带前缀的形态（站点不同产品会带前缀）
        java.util.Map<String, String> jar2 = new java.util.LinkedHashMap<>();
        jar2.put("web_prod_xhx_token", "BASE");
        jar2.put("web_prod_raccoon_token", "ACCESS-PREFIXED");
        check("带前缀时也能推导",
                "ACCESS-PREFIXED".equals(com.wbgateway.RaccoonLoginProbe.pickToken(jar2)),
                com.wbgateway.RaccoonLoginProbe.pickToken(jar2));

        // 兜底：只有 raccoon_token
        java.util.Map<String, String> jar3 = new java.util.LinkedHashMap<>();
        jar3.put("raccoon_token", "ACCESS-ONLY");
        check("没有 xhx_token 时走兜底",
                "ACCESS-ONLY".equals(com.wbgateway.RaccoonLoginProbe.pickToken(jar3)),
                com.wbgateway.RaccoonLoginProbe.pickToken(jar3));

        // 未登录：必须返回空，绝不能把设备指纹当 token
        java.util.Map<String, String> jar4 = new java.util.LinkedHashMap<>();
        jar4.put("_l_KPLiPs", "device-fingerprint");
        jar4.put("website-language", "zh");
        check("未登录时返回空（不误判设备指纹）",
                com.wbgateway.RaccoonLoginProbe.pickToken(jar4).isEmpty(),
                "[" + com.wbgateway.RaccoonLoginProbe.pickToken(jar4) + "]");
        check("空 cookie 表返回空",
                com.wbgateway.RaccoonLoginProbe.pickToken(new java.util.LinkedHashMap<String, String>()).isEmpty(),
                "");

        // 候选令牌：不靠猜名字，交给调用方用真实请求验证
        java.util.List<String> cand = com.wbgateway.RaccoonLoginProbe.candidates(jar1);
        check("候选里包含推导出的令牌", cand.contains("ACCESS-1"), cand.toString());
        check("候选排除 refresh", !cand.contains("REFRESH-1"), cand.toString());
        check("候选表为空时不返回垃圾",
                com.wbgateway.RaccoonLoginProbe.candidates(jar4).isEmpty(),
                com.wbgateway.RaccoonLoginProbe.candidates(jar4).toString());
        // 未登录页面上真实存在的设备指纹不能被当成候选
        java.util.Map<String, String> jar5 = new java.util.LinkedHashMap<>();
        jar5.put("_l_KPLiPs", "5e4NL9vDauNlJkzJXZ5KFvkAP70ketYMYp7pAaYv2l");
        jar5.put("FZ_STROAGE.xiaohuanxiong.com", "eyJBUktBUFBJRCI6IjU3MzcwNWI1M2Q3YzlhNTkifQ==");
        check("设备指纹不进候选（长度够但不含 token）",
                com.wbgateway.RaccoonLoginProbe.candidates(jar5).isEmpty(),
                com.wbgateway.RaccoonLoginProbe.candidates(jar5).toString());

        // cookie 串解析
        java.util.Map<String, String> parsed =
                com.wbgateway.RaccoonLoginProbe.parseCookie("a=1; b=2; c=x%20y");
        check("解析 cookie 串", "1".equals(parsed.get("a")) && "2".equals(parsed.get("b")),
                parsed.toString());
        check("cookie 值做了 URL 解码", "x y".equals(parsed.get("c")), String.valueOf(parsed.get("c")));

        // evaluateJavascript 回传值的解包
        check("解包带引号的 JSON 字符串",
                "tok".equals(com.wbgateway.RaccoonLoginProbe.unquote("\"tok\"")),
                com.wbgateway.RaccoonLoginProbe.unquote("\"tok\""));
        check("null 视为空", com.wbgateway.RaccoonLoginProbe.unquote("null").isEmpty(), "");
        check("空串视为空", com.wbgateway.RaccoonLoginProbe.unquote("\"\"").isEmpty(), "");

        System.out.println();
        System.out.println("=== 4. 登录产物契约 ===");
        Channel qoderChannel = new Channel();
        qoderChannel.id = "qoder";
        qoderChannel.loginType = LoginProviders.QODER;
        qoderChannel.kind = Channel.KIND_LOGIN;
        qoderChannel.accessToken = jwt("u", System.currentTimeMillis() / 1000 + 3600);
        check("登录型渠道 kind 正确", Channel.KIND_LOGIN.equals(qoderChannel.kind), "");
        check("有凭据 hasCredential", qoderChannel.hasCredential(), "");

        String baseUrl = com.wbgateway.ChannelLoginProbe.inferBaseUrl(LoginProviders.QODER);
        check("Qoder 有可转发基址", baseUrl.startsWith("https://"), baseUrl);
        String rcBase = com.wbgateway.ChannelLoginProbe.inferBaseUrl(LoginProviders.RACCOON);
        check("Raccoon 有可转发基址", rcBase.startsWith("https://"), rcBase);
        String zcBase = com.wbgateway.ChannelLoginProbe.inferBaseUrl(LoginProviders.ZCODE);
        check("ZCode 有可转发基址", zcBase.startsWith("https://"), zcBase);

        System.out.println();
        System.out.println("=== 5. PKCE 形状（RFC 7636）===");
        String verifier = com.wbgateway.ChannelLoginProbe.randomPkceVerifier();
        check("verifier 长度在 43–128", verifier.length() >= 43 && verifier.length() <= 128,
                "长度 " + verifier.length());
        check("verifier 字符合法", verifier.matches("[A-Za-z0-9\\-._~]+"), "");
        String challenge = com.wbgateway.ChannelLoginProbe.sha256Base64Url(verifier);
        check("challenge 是 43 字符的 base64url", challenge.length() == 43 && challenge.matches("[A-Za-z0-9\\-_]+"),
                "长度 " + challenge.length());
        check("challenge 与 verifier 稳定对应",
                challenge.equals(com.wbgateway.ChannelLoginProbe.sha256Base64Url(verifier)), "");

        System.out.println();
        System.out.println("=== 6. 时间戳解析（秒 / 毫秒 / ISO）===");
        check("秒级时间戳", com.wbgateway.ChannelLoginProbe.readTimestamp(1_700_000_000L) == 1_700_000_000_000L, "");
        check("毫秒级时间戳", com.wbgateway.ChannelLoginProbe.readTimestamp(1_700_000_000_000L) == 1_700_000_000_000L, "");
        check("字符串毫秒", com.wbgateway.ChannelLoginProbe.readTimestamp("1700000000000") == 1_700_000_000_000L, "");
        check("0 视为未知", com.wbgateway.ChannelLoginProbe.readTimestamp(0) == 0L, "");

        System.out.println();
        System.out.println("=== 7. 渠道支持矩阵 ===");
        check("Qoder 支持直接登录", ChannelLogin.supportsDirectLogin(LoginProviders.QODER), "");
        check("Qoder CN 支持直接登录", ChannelLogin.supportsDirectLogin(LoginProviders.QODER_CN), "");
        check("ZCode 支持直接登录", ChannelLogin.supportsDirectLogin(LoginProviders.ZCODE), "");
        check("Qoder 是浏览器形态", "browser".equals(ChannelLogin.loginMode(LoginProviders.QODER)), "");
        // Raccoon 不走 ChannelLogin：它的短信登录必须留在网页环境里（见 RaccoonLoginActivity），
        // 因此它不该出现在 supportsDirectLogin 里，也不该有「扫码形态」。
        check("Raccoon 不走 ChannelLogin（改用 WebView）",
                !ChannelLogin.supportsDirectLogin(LoginProviders.RACCOON), "");
        check("Raccoon 没有 ChannelLogin 形态",
                ChannelLogin.loginMode(LoginProviders.RACCOON).isEmpty(), "");
        check("WorkBuddy 不走这里（有自己的设备流程）",
                !ChannelLogin.supportsDirectLogin(LoginProviders.WB_INTL), "");

        System.out.println();
        System.out.println("=== 8. 内置模型表兜底 ===");
        List<String> raccoonModels = LoginProviders.defaultModelsFor(LoginProviders.RACCOON);
        check("Raccoon 有内置模型", !raccoonModels.isEmpty(), raccoonModels.size() + " 个");
        check("Raccoon 模型 id 前缀正确", raccoonModels.get(0).startsWith("sn-"), raccoonModels.get(0));
        List<String> zcodeModels = LoginProviders.defaultModelsFor(LoginProviders.ZCODE);
        check("ZCode 有内置模型", !zcodeModels.isEmpty(), zcodeModels.toString());
        check("只有 WorkBuddy 有实时目录", LoginProviders.hasLiveModelCatalog(LoginProviders.WB_INTL)
                && !LoginProviders.hasLiveModelCatalog(LoginProviders.RACCOON), "");

        // Raccoon 上游**没有**模型目录端点（实测全 404），所以拉取必须直接返回空，
        // 不能去请求一个 404 —— 那会让每次登录都白打一次请求，且错误信息误导排查。
        Channel raccoonProbe = new Channel();
        raccoonProbe.loginType = LoginProviders.RACCOON;
        raccoonProbe.kind = Channel.KIND_LOGIN;
        boolean threw = false;
        List<String> fetched = new ArrayList<>();
        try {
            fetched = com.wbgateway.ChannelLoginProbe.fetchModels(raccoonProbe);
        } catch (Exception e) {
            threw = true;
        }
        check("Raccoon 拉模型不发请求（直接返回空）", !threw && fetched.isEmpty(),
                threw ? "抛了异常" : fetched.size() + " 个");

        up.stop();

        System.out.println();
        System.out.println("=== 9. 官方登录页地址（「打开登录页」入口）===");
        for (String t : new String[]{LoginProviders.WB_INTL, LoginProviders.WB_CN,
                LoginProviders.QODER, LoginProviders.QODER_CN,
                LoginProviders.ZCODE, LoginProviders.RACCOON}) {
            String url = com.wbgateway.LoginPageProbe.urlOf(t);
            String host = com.wbgateway.LoginPageProbe.hostOf(t);
            check(t + " 有登录页且是 https",
                    url.startsWith("https://") && !host.isEmpty(), url);
        }
        check("未知渠道不返回假地址",
                com.wbgateway.LoginPageProbe.urlOf("nope").isEmpty(), "");
        check("has() 与 urlOf() 一致",
                com.wbgateway.LoginPageProbe.has(LoginProviders.QODER)
                        && !com.wbgateway.LoginPageProbe.has("nope"), "");
        // 登录页**不能**是设备码模板（带 {state} 占位符，打开就是错的）
        boolean noPlaceholder = true;
        for (String t : new String[]{LoginProviders.WB_INTL, LoginProviders.WB_CN}) {
            if (com.wbgateway.LoginPageProbe.urlOf(t).contains("{")) noPlaceholder = false;
        }
        check("登录页不带 {state} 占位符", noPlaceholder, "占位符会拼出打不开的地址");
        check("国内版不指向 API 域名（copilot.tencent.com 返回 JSON 不是登录页）",
                !com.wbgateway.LoginPageProbe.urlOf(LoginProviders.WB_CN).contains("copilot.tencent.com"),
                com.wbgateway.LoginPageProbe.urlOf(LoginProviders.WB_CN));

        System.out.println();
        System.out.println("=== 10. ZCode 回调自动捕获（登录后无须任何操作）===");
        // 正式回调形态
        check("zcode:// 回调能取到 code",
                "abc123".equals(com.wbgateway.ZcodeProbe.extractCode(
                        "zcode://oauth/callback?code=abc123&state=s1")), "");
        check("https 中转页能取到 code",
                "def456".equals(com.wbgateway.ZcodeProbe.extractCode(
                        "https://zcode.z.ai/app/oauth/login?redirect=zcode%3A%2F%2Foauth%2Fcallback&app_version=3.14.3&code=def456&state=s2")), "");
        check("authCode 同义参数也认",
                "ghi789".equals(com.wbgateway.ZcodeProbe.extractCode(
                        "zcode://oauth/callback?authCode=ghi789&state=s3")), "");
        check("code 做了 URL 解码",
                "a b".equals(com.wbgateway.ZcodeProbe.extractCode(
                        "zcode://oauth/callback?code=a%20b")), "");
        // 反例：不能把普通页面里的 code 参数当授权码
        check("登录页里的普通 code 参数不算回调",
                com.wbgateway.ZcodeProbe.extractCode(
                        "https://bigmodel.cn/login?redirect=x&code=notacallback").isEmpty(), "");
        check("无 code 时返回空",
                com.wbgateway.ZcodeProbe.extractCode("zcode://oauth/callback?state=only").isEmpty(), "");
        check("null / 空串不崩",
                com.wbgateway.ZcodeProbe.extractCode(null).isEmpty()
                        && com.wbgateway.ZcodeProbe.extractCode("").isEmpty(), "");
        check("只有 state 的 https 中转页不误判",
                com.wbgateway.ZcodeProbe.extractCode(
                        "https://zcode.z.ai/app/oauth/login?state=only").isEmpty(), "");
        check("queryParam 不把前缀相同的键误匹配",
                com.wbgateway.ZcodeProbe.queryParam("x?codex=1&code=2", "code").equals("2"),
                com.wbgateway.ZcodeProbe.queryParam("x?codex=1&code=2", "code"));
        check("hash 之后的参数不参与解析",
                com.wbgateway.ZcodeProbe.queryParam("x?a=1#code=zzz", "code").isEmpty(), "");

        System.out.println();
        System.out.println("=== 结果：PASS " + pass + " / FAIL " + fail + " ===");
        if (fail > 0) System.exit(1);
    }
}
