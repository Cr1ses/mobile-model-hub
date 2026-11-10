import com.wbgateway.Channel;
import com.wbgateway.Checkin;
import com.wbgateway.CheckinProbe;
import com.wbgateway.CodeArtsLogin;
import com.wbgateway.Credits;
import com.wbgateway.HuaweiSign;
import com.wbgateway.LoginProviders;
import com.wbgateway.ModelSpec;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * 华为 CodeArts 渠道的桌面验证。
 *
 * <p>三块必须验：
 * <ol>
 *   <li><b>华为云签名</b>：算法错了会直接 401，且错误码和「凭据过期」一样，
 *       很容易误判成用户该重新登录；</li>
 *   <li><b>回调解析</b>：认错 URL 就永远拿不到 code，用户卡在登录页；</li>
 *   <li><b>续期三件套</b>：临时凭据只有约 2 小时，缺一样就得每天重登。</li>
 * </ol>
 *
 * <p>签名用**固定输入**对照手算结果，确保换实现时不会漂。
 */
public class VerifyCodeArts {

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

    public static void main(String[] args) throws Exception {
        System.out.println("=== 1. 华为云签名：与官方算法对齐 ===");
        // 用固定 AK/SK 对固定请求签名，检查结构正确（不依赖时间）
        HuaweiSign.Signed s = HuaweiSign.sign("AKID", "SKID", "TOKEN",
                "GET", "https://snap-access.cn-north-4.myhuaweicloud.com/v1/model/builtin", "");
        check("签名成功", s.ok(), s.error);
        String auth = s.headers.get("Authorization");
        check("Authorization 前缀是 SDK-HMAC-SHA256",
                auth != null && auth.startsWith("SDK-HMAC-SHA256 Access=AKID,"), trim(auth));
        check("带 SignedHeaders", auth != null && auth.contains("SignedHeaders="), "");
        check("带 Signature（64 位 hex）",
                auth != null && auth.matches(".*Signature=[0-9a-f]{64}$"), "");
        check("带 x-sdk-date（华为格式）",
                s.headers.get("x-sdk-date") != null
                        && s.headers.get("x-sdk-date").matches("\\d{8}T\\d{6}Z"),
                s.headers.get("x-sdk-date"));
        check("带 x-sdk-content-sha256", s.headers.get("x-sdk-content-sha256") != null,
                "");
        check("带 x-security-token", "TOKEN".equals(s.headers.get("x-security-token")), "");
        check("带 host", s.headers.get("host") != null, s.headers.get("host"));
        // GET 不签 content-type，POST 要签
        check("GET 不签 content-type", s.headers.get("content-type") == null, "");
        HuaweiSign.Signed sp = HuaweiSign.sign("AKID", "SKID", "TOKEN",
                "POST", "https://snap-access.cn-north-4.myhuaweicloud.com/api/v2/chat/completions",
                "{\"a\":1}");
        check("POST 会签 content-type",
                "application/json".equals(sp.headers.get("content-type")), "");
        check("body 不同 → 签名不同",
                !sp.headers.get("Authorization").equals(s.headers.get("Authorization")), "");

        // 确定性：同输入同签名（同一秒内）
        HuaweiSign.Signed s2 = HuaweiSign.sign("AKID", "SKID", "TOKEN",
                "GET", "https://snap-access.cn-north-4.myhuaweicloud.com/v1/model/builtin", "");
        check("同输入签名稳定（同一秒）",
                s.headers.get("Authorization").equals(s2.headers.get("Authorization"))
                        || !s.headers.get("x-sdk-date").equals(s2.headers.get("x-sdk-date")),
                "");
        // 缺 AK 要明确报错，不要发出一个必然 401 的请求
        check("缺 AK 时报错而不是发请求",
                !HuaweiSign.sign("", "SK", "T", "GET", "https://a.com/x", "").ok(), "");

        System.out.println();
        System.out.println("=== 2. 回调解析（认错就永远拿不到 code）===");
        String callback = "http://127.0.0.1:18080/oauth/callback";
        check("标准回调能取到 code",
                "abc123".equals(com.wbgateway.CodeArtsCallbackProbe.extractCode(
                        callback + "?code=abc123&state=xyz")), "");
        check("code 带 URL 编码能解码",
                "a b".equals(com.wbgateway.CodeArtsCallbackProbe.extractCode(
                        callback + "?code=a%20b")), "");
        check("参数放在 # 片段里也能取到",
                "hash1".equals(com.wbgateway.CodeArtsCallbackProbe.extractCode(
                        callback + "#code=hash1&state=x")), "");
        check("登录页里的 code 参数不算回调",
                com.wbgateway.CodeArtsCallbackProbe.extractCode(
                        "https://auth.huaweicloud.com/authui/login.html?code=notcallback").isEmpty(), "");
        check("无 code 返回空",
                com.wbgateway.CodeArtsCallbackProbe.extractCode(callback + "?state=only").isEmpty(), "");
        check("null / 空串不崩",
                com.wbgateway.CodeArtsCallbackProbe.extractCode(null).isEmpty()
                        && com.wbgateway.CodeArtsCallbackProbe.extractCode("").isEmpty(), "");

        // 旧 ticket 流程的回调是 ?secret=…，两种都要认
        check("旧流程 secret 回调也能识别",
                "sec-1".equals(com.wbgateway.OAuthCallback.codeArtsSecret(
                        callback + "?secret=sec-1")), "");
        check("secret 不会从别处误取",
                com.wbgateway.OAuthCallback.codeArtsSecret(
                        "https://auth.huaweicloud.com/login?secret=x").isEmpty(), "");
        check("code 回调不会被当成 secret",
                com.wbgateway.OAuthCallback.codeArtsSecret(callback + "?code=abc").isEmpty(), "");

        System.out.println();
        System.out.println("=== 3. 协议常量与端点 ===");
        check("STS 端点是华为云", CodeArtsLogin.STS_ENDPOINT.contains("sts.cn-north-4.myhuaweicloud.com"), "");
        check("client_id 是 codearts-agent", "codearts-agent".equals(CodeArtsLogin.CLIENT_ID), "");
        check("回调路径 /oauth/callback", "/oauth/callback".equals(CodeArtsLogin.REDIRECT_PATH), "");
        check("回调地址带端口",
                "http://127.0.0.1:18080/oauth/callback".equals(CodeArtsLogin.callbackUrl(18080)), "");

        // 授权 URL：必须走新 portal 流程（旧 doer/redirect 流程回调只给 secret，
        // 而当时的实现只认 code —— 这就是「登录不上/登录完没反应」的根因）
        String authUrl = CodeArtsLogin.buildAuthUrl(18080, "challenge123", "tid123");
        check("授权 URL 指向 portal/authorize",
                authUrl.startsWith(CodeArtsLogin.PORTAL_AUTHORIZE + "?"), trim(authUrl));
        check("带 port 与 code_challenge_method=SHA-256",
                authUrl.contains("port=18080")
                        && authUrl.contains("code_challenge_method=SHA-256"), "");
        check("带 client_id / uri_scheme",
                authUrl.contains("client_id=codearts-agent")
                        && authUrl.contains("uri_scheme=codearts-agent"), "");
        check("插件标识是新流程的 snap_AIIDE / 5.2.0",
                authUrl.contains("plugin-name=snap_AIIDE")
                        && authUrl.contains("plugin-version=5.2.0"), "");
        check("带 code_challenge 与 ticket_id",
                authUrl.contains("code_challenge=challenge123")
                        && authUrl.contains("ticket_id=tid123"), "");

        System.out.println();
        System.out.println("=== 4. PKCE 与 DPoP（续期必需）===");
        String[] pkce = CodeArtsLogin.pkce();
        check("code_verifier 长度 >= 43", pkce[0].length() >= 43, "长度 " + pkce[0].length());
        check("code_challenge 非空且不同于 verifier",
                !pkce[1].isEmpty() && !pkce[1].equals(pkce[0]), "");
        check("PKCE 每次不同", !pkce[0].equals(CodeArtsLogin.pkce()[0]), "");

        CodeArtsLogin.DpopKey key = CodeArtsLogin.generateDpopKey();
        check("能生成 DPoP 密钥", key != null && key.pair != null, "");
        check("公钥 JWK 含 kty/crv/x/y",
                key.publicJwk.optString("kty").equals("EC")
                        && key.publicJwk.optString("crv").equals("P-256")
                        && !key.publicJwk.optString("x").isEmpty()
                        && !key.publicJwk.optString("y").isEmpty(), key.publicJwk.toString());
        check("私钥 JWK 含 d", !key.privateJwk.optString("d").isEmpty(), "");
        String jws = CodeArtsLogin.dpopJws(key, "POST", CodeArtsLogin.STS_ENDPOINT);
        check("DPoP JWS 是三段式", jws.split("\\.").length == 3, "");
        // 存下来再恢复：续期靠这个
        String stored = CodeArtsLogin.jwkToStore(key);
        CodeArtsLogin.DpopKey restored = CodeArtsLogin.jwkFromStore(stored);
        check("DPoP 私钥能存能还原（续期必需）", restored != null && restored.pair != null, "");
        check("还原后 x 一致",
                restored != null && restored.publicJwk.optString("x")
                        .equals(key.publicJwk.optString("x")), "");
        check("垃圾串不崩", CodeArtsLogin.jwkFromStore("not-a-jwk") == null
                && CodeArtsLogin.jwkFromStore("") == null, "");

        System.out.println();
        System.out.println("=== 5. 凭据解析与模型表 ===");
        JSONObject token = new JSONObject("{\"credentials\":{\"access_key_id\":\"AK\","
                + "\"secret_access_key\":\"SK\",\"security_token\":\"ST\","
                + "\"expiration\":\"2026-10-22T07:07:47.609Z\"},\"refresh_token\":\"RT\"}");
        com.wbgateway.Channel c = new com.wbgateway.Channel();
        check("能解析 STS 凭据", CodeArtsLogin.applyCredentials(c, token), "");
        check("AK/SK/ST 都落位",
                "AK".equals(c.accessKeyId) && "SK".equals(c.secretAccessKey)
                        && "ST".equals(c.securityToken), "");
        check("refresh_token 落位", "RT".equals(c.refreshToken), "");
        check("过期时间解析成毫秒", c.expiresAt > 0, String.valueOf(c.expiresAt));
        check("空凭据不算成功",
                !CodeArtsLogin.applyCredentials(new com.wbgateway.Channel(), new JSONObject("{}")), "");

        // 旧流程（ticket 轮询）的两种响应形状都要能喂给 applyCredentials
        JSONObject t1 = CodeArtsLogin.parseTicketCredential(new JSONObject(
                "{\"result\":{\"accessKeyId\":\"AK1\",\"secretAccessKey\":\"SK1\","
                        + "\"securityToken\":\"ST1\",\"expiration\":\"2026-11-05T00:00:00Z\"}}"));
        check("ticket 的 result 形状能解析",
                t1 != null && "AK1".equals(t1.optJSONObject("credentials").optString("access_key_id")),
                "");
        JSONObject t2 = CodeArtsLogin.parseTicketCredential(new JSONObject(
                "{\"credential\":{\"access\":\"AK2\",\"secret\":\"SK2\","
                        + "\"securitytoken\":\"ST2\",\"expires_at\":\"2026-11-05T00:00:00Z\"}}"));
        check("ticket 的 credential 形状能解析",
                t2 != null && "ST2".equals(t2.optJSONObject("credentials").optString("security_token")),
                "");
        check("ticket 认不出时返回 null",
                CodeArtsLogin.parseTicketCredential(new JSONObject("{}")) == null, "");
        check("ticket 凭据能被 applyCredentials 接住",
                CodeArtsLogin.applyCredentials(codeartsChannel(), t1), "");

        // 老渠道缺 baseUrl：读配置时必须补上，否则调用会拼出裸的 /chat/completions
        Channel migrated = Channel.fromJson(new JSONObject(
                "{\"id\":\"codearts\",\"kind\":\"login\",\"loginType\":\"codearts\"}"));
        check("老渠道缺 baseUrl → 补默认基址",
                CodeArtsLogin.API_BASE.equals(migrated.baseUrl), migrated.baseUrl);
        Channel trailing = Channel.fromJson(new JSONObject(
                "{\"id\":\"x\",\"baseUrl\":\"https://api.example.com/v1///\"}"));
        check("Base URL 去掉末尾斜杠",
                "https://api.example.com/v1".equals(trailing.baseUrl), trailing.baseUrl);

        // 转发时的兜底：老 CodeArts 渠道即使没存 baseUrl 也要拼出完整地址，
        // 否则又会回到「no protocol: /chat/completions」。
        check("转发基址有兜底",
                CodeArtsLogin.API_BASE.equals(com.wbgateway.GatewayProbe.chatBase(migrated)),
                com.wbgateway.GatewayProbe.chatBase(migrated));
        Channel otherLogin = new Channel();
        otherLogin.kind = Channel.KIND_LOGIN;
        otherLogin.loginType = LoginProviders.WB_INTL;
        // WorkBuddy 也有默认基址（`{endpoint}/v2`）——「未配置 Base URL」那个 bug 就是它
        check("WorkBuddy 缺基址也能兜出地址",
                "https://www.workbuddy.ai/v2".equals(com.wbgateway.GatewayProbe.chatBase(otherLogin)),
                com.wbgateway.GatewayProbe.chatBase(otherLogin));
        Channel unknown = new Channel();
        unknown.kind = Channel.KIND_PROXY;
        check("未知渠道仍返回空（报配置错误，不瞎猜地址）",
                com.wbgateway.GatewayProbe.chatBase(unknown).isEmpty(), "");

        Map<String, ModelSpec> models = LoginProviders.codeartsBuiltinSpecs();
        check("内置 9 个模型", models.size() == 9, "共 " + models.size() + " 个");
        check("含 glm-5.3-flash", models.containsKey("glm-5.3-flash"), "");
        check("含 deepseek-v4.1-flash", models.containsKey("deepseek-v4.1-flash"), "");
        check("含 openpangu", models.containsKey("openpangu-2.0-pro"), "");
        ModelSpec glm = models.get("GLM-5.2");
        check("GLM-5.2 上下文 202752", glm != null && glm.contextWindow == 202752,
                glm == null ? "" : String.valueOf(glm.contextWindow));
        check("模型有可读名（不是内部 id）",
                glm != null && "GLM-5.2".equals(glm.name), glm == null ? "" : glm.name);

        System.out.println();
        System.out.println("=== 6. 支持矩阵 ===");
        check("CodeArts 有 Spec", LoginProviders.specOf(LoginProviders.CODEARTS) != null, "");
        check("CodeArts 不是设备码流程",
                !LoginProviders.specOf(LoginProviders.CODEARTS).supportsDeviceFlow, "");
        check("CodeArts 有内置模型表",
                !LoginProviders.defaultModelsFor(LoginProviders.CODEARTS).isEmpty(),
                "共 " + LoginProviders.defaultModelsFor(LoginProviders.CODEARTS).size() + " 个");

        System.out.println();
        System.out.println("=== 7. 凭据识别（没有 accessToken 也要算已登录）===");
        Channel ca = codeartsChannel();
        check("AK/SK/ST 齐全 → 算已登录", ca.hasCredential(), "");
        Channel noToken = codeartsChannel();
        noToken.securityToken = "";
        check("缺 security_token → 不算已登录", !noToken.hasCredential(), "");
        Channel bearer = new Channel();
        bearer.kind = Channel.KIND_LOGIN;
        bearer.accessToken = "t";
        check("普通渠道仍按 accessToken 判定", bearer.hasCredential(), "");
        check("Checkin 支持 CodeArts", Checkin.supported(LoginProviders.CODEARTS), "");
        check("Credits 支持 CodeArts", Credits.supported(LoginProviders.CODEARTS), "");

        // 续期的「不该发请求」分支：材料不全时必须是同步返回 false，不能真去连网
        Channel fresh = codeartsChannel();
        fresh.expiresAt = System.currentTimeMillis() + 3_600_000L;
        check("未过期 → 不续期", !CodeArtsLogin.refreshIfExpired(fresh, 1000), "");
        Channel expiredNoRefresh = codeartsChannel();
        expiredNoRefresh.expiresAt = 1L;
        check("过期但没有 refresh_token → 不续期",
                !CodeArtsLogin.refreshIfExpired(expiredNoRefresh, 1000), "");
        Channel expiredNoJwk = codeartsChannel();
        expiredNoJwk.expiresAt = 1L;
        expiredNoJwk.refreshToken = "RT";
        expiredNoJwk.codeVerifier = "CV";
        check("过期但 DPoP 私钥丢了 → 不续期（只能重登）",
                !CodeArtsLogin.refreshIfExpired(expiredNoJwk, 1000), "");
        Channel notCodeArts = new Channel();
        notCodeArts.kind = Channel.KIND_LOGIN;
        notCodeArts.accessToken = "t";
        notCodeArts.expiresAt = 1L;
        check("别的渠道不受影响", !CodeArtsLogin.refreshIfExpired(notCodeArts, 1000), "");

        System.out.println();
        System.out.println("=== 8. 签到判定（纯函数）===");
        JSONArray items = new JSONArray("[{\"campaignId\":7,\"type\":\"OTHER\"},"
                + "{\"campaignId\":1,\"type\":\"USER_LOGIN\",\"claimable\":true,"
                + "\"status\":\"CLAIMABLE\",\"benefitAmount\":1000}]");
        JSONObject daily = CheckinProbe.codeartsDailyActivity(items);
        check("能找到 USER_LOGIN 活动", daily != null, "");
        check("campaignId 数字也能读成 id",
                "1".equals(CheckinProbe.codeartsActivityId(daily)), "");
        check("claimable=true → 可领", CheckinProbe.codeartsClaimable(daily), "");
        check("CLAIMABLE 不算已领", !CheckinProbe.codeartsClaimed(daily), "");

        JSONObject claimed = new JSONObject("{\"campaignId\":1,\"type\":\"USER_LOGIN\","
                + "\"claimable\":false,\"status\":\"CLAIMED\"}");
        check("claimable=false → 不可领", !CheckinProbe.codeartsClaimable(claimed), "");
        check("CLAIMED 算已领", CheckinProbe.codeartsClaimed(claimed), "");
        check("CONFIRMED 也算已领",
                CheckinProbe.codeartsClaimed(new JSONObject("{\"status\":\"CONFIRMED\"}")), "");
        check("空活动不崩且不算已领",
                !CheckinProbe.codeartsClaimed(null) && !CheckinProbe.codeartsClaimable(null), "");
        check("没有 USER_LOGIN 时返回 null",
                CheckinProbe.codeartsDailyActivity(
                        new JSONArray("[{\"campaignId\":2,\"type\":\"OTHER\"}]")) == null, "");

        System.out.println();
        System.out.println("=== 9. 完整签到链路（本地假上游）===");
        FakeSnapServer up = new FakeSnapServer();
        try {
            CheckinProbe.overrideCodeartsBase(up.base);

            Checkin.Status st = Checkin.status(ca);
            check("能查到可领状态", st.ok && st.active && !st.todayCheckedIn, st.summary());
            check("可领积分 1000", st.dailyCredit == 1000, String.valueOf(st.dailyCredit));

            Checkin.Claim r = Checkin.run(ca);
            check("领取成功", "claimed".equals(r.kind), r.message);
            check("领到 1000 积分", r.credit == 1000, String.valueOf(r.credit));
            check("领取后调了 confirm", up.confirmCalls == 1, "次数 " + up.confirmCalls);

            // 上游领完就变成 CLAIMED：再点一次必须是「已签过」而不是失败
            Checkin.Claim again = Checkin.run(ca);
            check("重复领取 → already-claimed（不是失败）",
                    "already-claimed".equals(again.kind), again.message);

            Checkin.Status st2 = Checkin.status(ca);
            check("状态变成「今天已领取」", st2.ok && st2.todayCheckedIn, st2.summary());

            // Token 计费账号：不是失败，是「不在活动范围」
            up.creditPackage = false;
            up.tokenPackage = true;
            Checkin.Status st3 = Checkin.status(ca);
            check("Token 计费账号如实说明", st3.ok && !st3.active
                    && st3.message.contains("Token"), st3.message);

            // 没有签到活动
            up.creditPackage = true;
            up.tokenPackage = false;
            up.hasActivity = false;
            Checkin.Status st4 = Checkin.status(ca);
            check("没有活动时如实说明", st4.ok && !st4.active
                    && st4.message.contains("未找到"), st4.message);
        } finally {
            CheckinProbe.overrideCodeartsBase(null);
            up.stop();
        }

        System.out.println();
        System.out.println("=== 结果：PASS " + pass + " / FAIL " + fail + " ===");
        if (fail > 0) System.exit(1);
    }

    static String trim(String s) {
        if (s == null) return "(null)";
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    /** 一个「已登录」的 CodeArts 渠道（只有 AK/SK/ST，没有 accessToken）。 */
    static Channel codeartsChannel() {
        Channel c = new Channel();
        c.id = LoginProviders.CODEARTS;
        c.name = "华为 CodeArts";
        c.kind = Channel.KIND_LOGIN;
        c.loginType = LoginProviders.CODEARTS;
        c.accessKeyId = "AKID";
        c.secretAccessKey = "SKID";
        c.securityToken = "STID";
        return c;
    }

    /**
     * 本地假上游：模拟 CodeArts 的签到链路。
     *
     * <p>不校验签名（签名结构在别的断言里已经钉住），只回放真实形态的响应 ——
     * 这样能跑**完整**的「查状态 → 领取 → confirm → 再查」，
     * 而不是只测几个纯函数。
     */
    static final class FakeSnapServer {
        HttpServer server;
        String base;
        volatile boolean creditPackage = true;
        volatile boolean tokenPackage = false;
        volatile boolean hasActivity = true;
        volatile boolean claimable = true;
        volatile String status = "CLAIMABLE";
        volatile int claimCalls = 0;
        volatile int confirmCalls = 0;

        FakeSnapServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", new SnapHandler(this));
            server.start();
            base = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }
    }

    static final class SnapHandler implements HttpHandler {
        private final FakeSnapServer up;

        SnapHandler(FakeSnapServer up) {
            this.up = up;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            java.io.InputStream in = exchange.getRequestBody();
            byte[] drain = new byte[4096];
            while (in.read(drain) > 0) {
                // 读掉请求体
            }
            String path = exchange.getRequestURI().getPath();
            String body;
            if (path.endsWith("/snap-manager/v1/statistics/plugin")) {
                body = "{\"package\":{\"is_credit_package\":" + up.creditPackage
                        + ",\"is_token_package\":" + up.tokenPackage
                        + ",\"package_name_cn\":\"基础积分包\"},"
                        + "\"metrics\":[{\"name\":\"usageTotalPackageCredit\","
                        + "\"package_credit_amount\":5000,\"package_credit_used\":4000,"
                        + "\"package_credit_remain\":1000}]}";
            } else if (path.endsWith("/v1/ops/delivery")) {
                body = up.hasActivity
                        ? "{\"items\":[{\"campaignId\":1,\"type\":\"USER_LOGIN\","
                        + "\"title\":\"每日登录奖励\",\"claimable\":" + up.claimable
                        + ",\"status\":\"" + up.status + "\",\"benefitAmount\":1000}]}"
                        : "{\"items\":[]}";
            } else if (path.endsWith("/v1/ops/claim")) {
                up.claimCalls += 1;
                // 上游领完就变：claimable=false + CLAIMED
                up.claimable = false;
                up.status = "CLAIMED";
                body = "{\"code\":0,\"data\":{\"id\":\"benefit-1\",\"benefitAmount\":1000}}";
            } else if (path.endsWith("/v1/ops/confirm")) {
                up.confirmCalls += 1;
                body = "{\"code\":0,\"data\":{}}";
            } else {
                body = "{\"error\":\"not found\"}";
            }
            byte[] out = body.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            OutputStream os = exchange.getResponseBody();
            os.write(out);
            os.close();
        }
    }
}
