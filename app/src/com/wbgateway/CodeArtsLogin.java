package com.wbgateway;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * 华为 CodeArts 的登录与续期。
 *
 * <h3>协议（从 CodeArts 客户端逆向确认，2026-11）</h3>
 *
 * <pre>
 * 授权页  https://auth.huaweicloud.com/authui/login.html?service=&lt;doer/redirect…&gt;
 * 中转    https://devcloud.cn-north-4.huaweicloud.com/doer/redirect
 *         ?IdeaType=jetbrains&amp;auth_callback_url=&lt;本机回调&gt;&amp;plugin-name=snap_jetbrains
 *         &amp;plugin-version=26.3.3&amp;ticket_id=&lt;64hex&gt;
 * 换令牌  POST https://sts.cn-north-4.myhuaweicloud.com/v1/oauth2/tokens
 *         头：DPoP: &lt;ES256 JWS&gt;、Content-Type: application/x-www-form-urlencoded
 *         body：client_id=codearts-agent &amp; code &amp; code_verifier
 *               &amp; grant_type=authorization_code &amp; redirect_uri=&lt;本机回调&gt;
 *         → {credentials:{access_key_id, secret_access_key, security_token, expiration},
 *            refresh_token}
 * 续期    POST 同一端点，grant_type=refresh_token &amp; refresh_token &amp; code_verifier
 * 聊天    POST https://snap-access.cn-north-4.myhuaweicloud.com/api/v2/chat/completions
 *         （用 AK/SK/security_token 做 SDK-HMAC-SHA256 签名，见 {@link HuaweiSign}）
 * 模型    GET  https://snap-access.cn-north-4.myhuaweicloud.com/v1/model/builtin
 * </pre>
 *
 * <h3>为什么手机上必须自己实现 DPoP</h3>
 *
 * 桌面版起一个 `http://127.0.0.1:PORT/authentication` 回调服务器接收授权码。
 * 手机上同样可行 —— 但这个回调只在**登录那一次**需要；之后续期只需要
 * 私钥 JWK（登录时自己生成并保存），不再需要回调服务器。
 *
 * <h3>关键约束：临时凭据约 2 小时</h3>
 *
 * `security_token` 是华为云临时凭据，过期后所有签名请求都会返回
 * `APIG.0301 Unauthorized`。所以必须实现 refresh_token 续期，
 * 否则用户每天都要重新登录一次。续期需要登录时保存的
 * `code_verifier` 与 `dpop_private_key_jwk`。
 */
public final class CodeArtsLogin {

    /** 华为云 STS 换令牌端点。 */
    public static final String STS_ENDPOINT = "https://sts.cn-north-4.myhuaweicloud.com/v1/oauth2/tokens";
    /** OAuth 客户端 id（CodeArts 自己的）。 */
    public static final String CLIENT_ID = "codearts-agent";
    /** 回调路径（手机上也用这个，绑到本机端口）。 */
    public static final String REDIRECT_PATH = "/oauth/callback";
    /** 授权页。 */
    public static final String AUTH_BASE = "https://auth.huaweicloud.com/authui/login.html";
    /** 中转页（旧 ticket 流程用；仅保留常量，当前不走它）。 */
    public static final String DOER_REDIRECT = "https://devcloud.cn-north-4.huaweicloud.com/doer/redirect";
    /**
     * 当前 CodeArts 客户端用的授权入口（**portal**，不是 auth.huaweicloud.com）。
     *
     * <p>两种流程都真实存在过：
     * <ul>
     *   <li><b>旧 ticket 流程</b>：`auth.huaweicloud.com?service=doer/redirect…`
     *       → 回调带 `?secret=` → 再轮询 `/snap-manager/v1/login/ticket`；</li>
     *   <li><b>新 OAuth 流程</b>（桌面版现在用的）：`codearts.huaweicloud.com/portal/authorize`
     *       → 回调带 `?code=` → STS 换临时凭据。</li>
     * </ul>
     *
     * <p>App 最早实现成了旧流程，但回调却按新流程解析 `code` —— 结果是
     * 「登录完什么也不发生」。现在两条回调都认（见 {@link #fetchTicketCredentials}）。
     */
    public static final String PORTAL_AUTHORIZE = "https://codearts.huaweicloud.com/portal/authorize";

    /** 新流程的插件标识（**与旧流程不同**：snap_AIIDE / 5.2.0）。 */
    public static final String PLUGIN_NAME = "snap_AIIDE";
    /** 新流程的插件版本。 */
    public static final String PLUGIN_VERSION = "5.2.0";

    /** 本机回调端口（WebView 里不真监听，只用于拼回调地址）。 */
    public static final int CALLBACK_PORT = 18080;

    /** 旧流程的凭据轮询端点（回调只给 secret 时用）。 */
    public static final String TICKET_ENDPOINT =
            "https://snap-access.cn-north-4.myhuaweicloud.com/snap-manager/v1/login/ticket";

    /** OAuth 主题 / 语言（与桌面客户端一致，仅影响登录页外观）。 */
    private static final String OAUTH_THEME = "2";
    private static final String OAUTH_LOCALE = "zh-cn";

    /**
     * 聊天端点的基址（渠道的 `baseUrl` 用它，网关再拼 `/chat/completions`）。
     *
     * <p>为什么要有这个常量：早期登录流程没有写 `baseUrl`，存下来的渠道调用时
     * 会把 URL 拼成裸的 `/chat/completions` —— 报错是
     * 「无法连接上游 ：no protocol: /chat/completions」，看起来像网络问题，
     * 其实是渠道缺了基址。读配置时用它兜底（见 {@link Channel#fromJson}）。
     */
    public static final String API_BASE = "https://snap-access.cn-north-4.myhuaweicloud.com/api/v2";

    private CodeArtsLogin() {
    }

    /**
     * 拼授权 URL（**新 portal OAuth 流程**）。
     *
     * <p>参数与桌面客户端逐字一致（`buildOAuthLoginUrl` 实测）：
     * <pre>
     * https://codearts.huaweicloud.com/portal/authorize
     *   ?theme=2&locale=zh-cn
     *   &uri_scheme=codearts-agent&client_id=codearts-agent
     *   &port=18080&code_challenge=…&code_challenge_method=SHA-256
     *   &ticket_id=…&plugin-name=snap_AIIDE&plugin-version=5.2.0
     * </pre>
     *
     * <p>⚠ `code_challenge_method` 是带连字符的 `SHA-256`（不是 OAuth 惯用的 `S256`），
     * 写成 `S256` 时门户会忽略 challenge，换令牌就会因 verifier 不匹配失败。
     *
     * @param port        本机回调端口（门户会往 `http://127.0.0.1:{port}/oauth/callback` 跳）
     * @param codeChallenge PKCE challenge（{@link #pkce()} 的第 2 个值）
     * @param ticketId    32 字节随机 hex
     */
    public static String buildAuthUrl(int port, String codeChallenge, String ticketId) {
        return PORTAL_AUTHORIZE
                + "?theme=" + OAUTH_THEME
                + "&locale=" + OAUTH_LOCALE
                + "&uri_scheme=" + enc(CLIENT_ID)
                + "&client_id=" + enc(CLIENT_ID)
                + "&port=" + port
                + "&code_challenge=" + enc(codeChallenge)
                + "&code_challenge_method=SHA-256"
                + "&ticket_id=" + enc(ticketId)
                + "&plugin-name=" + enc(PLUGIN_NAME)
                + "&plugin-version=" + enc(PLUGIN_VERSION);
    }

    /** 本机回调地址（必须与换令牌时的 `redirect_uri` 完全一致）。 */
    public static String callbackUrl(int port) {
        return "http://127.0.0.1:" + port + REDIRECT_PATH;
    }

    /**
     * 生成 PKCE 对（S256）。
     *
     * @return `[0]=code_verifier, [1]=code_challenge`
     */
    public static String[] pkce() {
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        String verifier = b64Url(raw);
        String challenge;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            challenge = b64Url(md.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            challenge = verifier;
        }
        return new String[]{verifier, challenge};
    }

    /** DPoP 私钥持有者（登录时生成，续期时复用）。 */
    public static final class DpopKey {
        public KeyPair pair;
        /** 公钥 JWK（放进 DPoP 头）。 */
        public JSONObject publicJwk = new JSONObject();
        /** 私钥 JWK（存进渠道配置，用于续期）。 */
        public JSONObject privateJwk = new JSONObject();
    }

    /** 生成 ES256（P-256）密钥对并导出 JWK。 */
    public static DpopKey generateDpopKey() {
        DpopKey out = new DpopKey();
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            out.pair = g.generateKeyPair();

            ECPublicKey pub = (ECPublicKey) out.pair.getPublic();
            ECPrivateKey priv = (ECPrivateKey) out.pair.getPrivate();
            String x = b64Url(unsigned(pub.getW().getAffineX().toByteArray(), 32));
            String y = b64Url(unsigned(pub.getW().getAffineY().toByteArray(), 32));
            String d = b64Url(unsigned(priv.getS().toByteArray(), 32));

            out.publicJwk.put("kty", "EC");
            out.publicJwk.put("crv", "P-256");
            out.publicJwk.put("x", x);
            out.publicJwk.put("y", y);

            out.privateJwk.put("kty", "EC");
            out.privateJwk.put("crv", "P-256");
            out.privateJwk.put("x", x);
            out.privateJwk.put("y", y);
            out.privateJwk.put("d", d);
        } catch (Exception e) {
            out.pair = null;
        }
        return out;
    }

    /**
     * 生成 DPoP JWS（ES256，`typ=dpop+jwt`）。
     *
     * <p>载荷含 `htm`（HTTP 方法）、`htu`（目标 URL）、`iat`、`jti`，
     * 头部嵌公钥 JWK —— 华为用它校验「这个请求确实是持有私钥的人发的」。
     */
    public static String dpopJws(DpopKey key, String htm, String htu) throws Exception {
        if (key == null || key.pair == null) throw new Exception("DPoP 密钥不可用");
        JSONObject head = new JSONObject();
        head.put("alg", "ES256");
        head.put("typ", "dpop+jwt");
        head.put("jwk", key.publicJwk);

        JSONObject payload = new JSONObject();
        payload.put("htm", htm);
        payload.put("htu", htu);
        payload.put("iat", System.currentTimeMillis() / 1000);
        byte[] jti = new byte[32];
        new java.security.SecureRandom().nextBytes(jti);
        payload.put("jti", hex(jti));

        String signingInput = b64Url(head.toString().getBytes(StandardCharsets.UTF_8))
                + "." + b64Url(payload.toString().getBytes(StandardCharsets.UTF_8));

        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(key.pair.getPrivate());
        sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        byte[] der = sig.sign();
        // JWS 要的是 r||s 各 32 字节，不是 DER
        byte[] raw = derToRaw(der, 32);
        return signingInput + "." + b64Url(raw);
    }

    /** 用授权码换临时凭据。 */
    public static JSONObject exchangeCode(String code, String codeVerifier,
                                          String redirectUri, DpopKey key, int timeoutMs)
            throws Exception {
        String body = "client_id=" + enc(CLIENT_ID)
                + "&code=" + enc(code)
                + "&code_verifier=" + enc(codeVerifier)
                + "&grant_type=authorization_code"
                + "&redirect_uri=" + enc(redirectUri);
        return postSts(body, key, timeoutMs);
    }

    /**
     * 用 refresh_token 续期（**避免用户每天重新登录**）。
     *
     * @return 新的凭据响应；失败抛异常（`invalid_grant` 表示 refresh 也过期了）
     */
    public static JSONObject refresh(String refreshToken, String codeVerifier,
                                     DpopKey key, int timeoutMs) throws Exception {
        String body = "client_id=" + enc(CLIENT_ID)
                + "&code_verifier=" + enc(codeVerifier)
                + "&grant_type=refresh_token"
                + "&refresh_token=" + enc(refreshToken);
        return postSts(body, key, timeoutMs);
    }

    // -----------------------------------------------------------------------
    // 旧 ticket 流程的兜底：回调只给 secret 时轮询凭据
    // -----------------------------------------------------------------------

    /**
     * 用回调里的 `secret` 去取临时凭据（**旧流程**，最长轮询 `timeoutMs`）。
     *
     * <p>为什么两条路都要留着：门户改版前后，回调可能带 `code`（新流程）
     * 或 `secret`（旧流程）。只认一种的表现是「登录完停在原地」——
     * 用户完全看不出是哪一步没接上。
     *
     * @return 与 STS 响应同形状的 `{credentials:{…}}`
     */
    public static JSONObject fetchTicketCredentials(String ticketId, String secret, int timeoutMs)
            throws Exception {
        String url = TICKET_ENDPOINT + "?ticket_id=" + enc(ticketId) + "&secret=" + enc(secret);
        long deadline = System.currentTimeMillis() + Math.max(5_000, timeoutMs);
        String lastProblem = "";
        while (System.currentTimeMillis() < deadline) {
            try {
                String text = getTicket(url, timeoutMs);
                JSONObject root = new JSONObject(text);
                JSONObject data = unwrapSnap(root);
                JSONObject token = parseTicketCredential(data);
                if (token != null) return token;
                lastProblem = "上游还没有下发凭据";
            } catch (Exception e) {
                lastProblem = String.valueOf(e.getMessage());
            }
            Thread.sleep(1_000);
        }
        throw new java.io.IOException("凭据轮询超时"
                + (lastProblem.isEmpty() ? "" : "：" + lastProblem));
    }

    /**
     * 解析 ticket 响应里的凭据（纯函数，便于离线验证）。
     *
     * <p>上游给过两种形状（都实测过）：
     * <pre>
     * {result:{accessKeyId, secretAccessKey, securityToken, expiration}}
     * {credential:{access, secret, securitytoken, expires_at}}
     * </pre>
     *
     * @return `{credentials:{access_key_id, secret_access_key, security_token, expiration}}`；
     *         认不出返回 null
     */
    public static JSONObject parseTicketCredential(JSONObject data) {
        if (data == null) return null;
        JSONObject result = data.optJSONObject("result");
        if (result != null) {
            String ak = result.optString("accessKeyId", "");
            String st = result.optString("securityToken", "");
            if (!ak.isEmpty() && !st.isEmpty()) {
                String exp = result.optString("expiration", "");
                if (exp.isEmpty()) exp = result.optString("expiresAt", "");
                return credentials(ak, result.optString("secretAccessKey", ""), st, exp);
            }
        }
        JSONObject cred = data.optJSONObject("credential");
        if (cred != null) {
            String ak = cred.optString("access", "");
            String st = cred.optString("securitytoken", "");
            if (st.isEmpty()) st = cred.optString("securityToken", "");
            if (!ak.isEmpty() && !st.isEmpty()) {
                String exp = cred.optString("expires_at", "");
                if (exp.isEmpty()) exp = cred.optString("expiresAt", "");
                return credentials(ak, cred.optString("secret", ""), st, exp);
            }
        }
        return null;
    }

    private static JSONObject credentials(String ak, String sk, String st, String expiration) {
        JSONObject cred = new JSONObject();
        JSONObject token = new JSONObject();
        try {
            cred.put("access_key_id", ak);
            cred.put("secret_access_key", sk);
            cred.put("security_token", st);
            cred.put("expiration", expiration == null ? "" : expiration);
            token.put("credentials", cred);
        } catch (Exception ignored) {
        }
        return token;
    }

    /** 拆 `{code,data}` 信封；没有 code 时原样返回。 */
    private static JSONObject unwrapSnap(JSONObject root) throws java.io.IOException {
        if (root == null || !root.has("code")) return root;
        int code = root.optInt("code", -1);
        if (code != 0) {
            String msg = root.optString("message", "");
            if (msg.isEmpty()) msg = root.optString("msg", "");
            throw new java.io.IOException(msg.isEmpty() ? "业务码 " + code : msg);
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) throw new java.io.IOException("响应缺少 data");
        return data;
    }

    private static String getTicket(String url, int timeoutMs) throws java.io.IOException {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            conn.setRequestProperty("plugin-name", PLUGIN_NAME);
            conn.setRequestProperty("plugin-version", PLUGIN_VERSION);
            int status = conn.getResponseCode();
            java.io.InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(in);
            if (status < 200 || status >= 300) {
                String snippet = text == null ? "" : text.trim();
                if (snippet.length() > 120) snippet = snippet.substring(0, 120) + "…";
                throw new java.io.IOException("HTTP " + status + "：" + snippet);
            }
            return text;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static JSONObject postSts(String formBody, DpopKey key, int timeoutMs) throws Exception {
        String dpop = dpopJws(key, "POST", STS_ENDPOINT);

        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(STS_ENDPOINT).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("DPoP", dpop);
            byte[] bytes = formBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            java.io.OutputStream os = conn.getOutputStream();
            os.write(bytes);
            os.close();

            int status = conn.getResponseCode();
            java.io.InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(in);
            if (status < 200 || status >= 300) {
                String snippet = text == null ? "" : text.trim();
                if (snippet.length() > 160) snippet = snippet.substring(0, 160) + "…";
                throw new java.io.IOException("换令牌失败（HTTP " + status + "）：" + snippet);
            }
            return new JSONObject(text);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // -----------------------------------------------------------------------
    // 工具
    // -----------------------------------------------------------------------

    /** 从 STS 响应里取凭据并写进渠道。 */
    public static boolean applyCredentials(Channel c, JSONObject token) {
        JSONObject cred = token.optJSONObject("credentials");
        if (cred == null) return false;
        String ak = cred.optString("access_key_id", "");
        String sk = cred.optString("secret_access_key", "");
        String st = cred.optString("security_token", "");
        if (ak.isEmpty() || sk.isEmpty() || st.isEmpty()) return false;
        c.accessKeyId = ak;
        c.secretAccessKey = sk;
        c.securityToken = st;
        c.expiresAt = parseExpiry(cred.optString("expiration", ""));
        String refresh = token.optString("refresh_token", "");
        if (!refresh.isEmpty()) c.refreshToken = refresh;
        return true;
    }

    /** 解析 `2026-10-22T07:07:47.609Z` 这类时间。 */
    public static long parseExpiry(String raw) {
        if (raw == null || raw.isEmpty()) return 0;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date d = f.parse(raw);
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            try {
                java.text.SimpleDateFormat f2 = new java.text.SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
                f2.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                java.util.Date d = f2.parse(raw);
                return d == null ? 0 : d.getTime();
            } catch (Exception e2) {
                return 0;
            }
        }
    }

    /** 把 DPoP 私钥 JWK 存成字符串（渠道配置里）。 */
    public static String jwkToStore(DpopKey key) {
        return key == null || key.privateJwk == null ? "" : key.privateJwk.toString();
    }

    /**
     * 续期成功后的回写回调（由界面注入；桌面验证不注入）。
     *
     * <p>为什么需要：签到 / 积分都可能在凭据过期后才被点开。不先续期的话，
     * 它们会直接拿到 401 并报「凭据过期」—— 而用户什么都没做错。
     */
    public interface RenewListener {
        void onRenewed(Channel channel);
    }

    /** 续期回写回调（可为 null）。 */
    public static RenewListener renewListener = null;

    /**
     * 凭据保鲜：**只**在已过期且续期材料齐全时去续。
     *
     * <p>为什么做成静默返回 boolean：网关与界面（签到 / 积分）都需要「先续期再发请求」，
     * 但两边对失败的处理不同 —— 网关记日志继续发，界面要提示「请重新登录」。
     * 所以这里只负责「续没续成」，把 channel 的字段就地更新，落盘交给回调。
     *
     * <p>续期三件套缺一不可：`refreshToken`、`codeVerifier`、DPoP 私钥。
     * 缺任何一样都会返回 false（对应「必须重新登录」）。
     *
     * @param c         渠道（会被就地更新）
     * @param timeoutMs 超时
     * @return true 表示已续期成功且 c 的凭据已更新
     */
    public static boolean refreshIfExpired(Channel c, int timeoutMs) {
        if (c == null || !LoginProviders.CODEARTS.equals(c.loginType)) return false;
        if (!c.isExpired()) return false;
        if (c.refreshToken == null || c.refreshToken.isEmpty()) return false;
        if (c.codeVerifier == null || c.codeVerifier.isEmpty()) return false;
        DpopKey key = jwkFromStore(c.dpopJwk);
        if (key == null) return false;
        try {
            JSONObject token = refresh(c.refreshToken, c.codeVerifier, key, timeoutMs);
            if (!applyCredentials(c, token)) return false;
            if (renewListener != null) renewListener.onRenewed(c);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 从配置字符串恢复 DPoP 私钥（续期用）。 */
    public static DpopKey jwkFromStore(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject jwk = new JSONObject(json);
            String d = jwk.optString("d", "");
            String x = jwk.optString("x", "");
            String y = jwk.optString("y", "");
            if (d.isEmpty() || x.isEmpty() || y.isEmpty()) return null;

            // 用 BC/标准方式从 JWK 还原 EC 私钥比较麻烦，这里改用
            // 「重新生成 + 不匹配也没关系」不可行 —— DPoP 必须用同一把密钥，
            // 因为 refresh 时华为会校验 jwk 指纹与首次一致。
            // 所以走 Java 自带的 EC 参数构造。
            java.security.AlgorithmParameters params = java.security.AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            java.security.spec.ECParameterSpec ecSpec =
                    params.getParameterSpec(java.security.spec.ECParameterSpec.class);

            java.math.BigInteger s = new java.math.BigInteger(1, b64UrlDecode(d));
            java.security.spec.ECPrivateKeySpec privSpec =
                    new java.security.spec.ECPrivateKeySpec(s, ecSpec);
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("EC");
            java.security.PrivateKey priv = kf.generatePrivate(privSpec);

            java.security.spec.ECPoint point = new java.security.spec.ECPoint(
                    new java.math.BigInteger(1, b64UrlDecode(x)),
                    new java.math.BigInteger(1, b64UrlDecode(y)));
            java.security.spec.ECPublicKeySpec pubSpec =
                    new java.security.spec.ECPublicKeySpec(point, ecSpec);
            java.security.PublicKey pub = kf.generatePublic(pubSpec);

            DpopKey out = new DpopKey();
            out.pair = new KeyPair(pub, priv);
            out.privateJwk = jwk;
            JSONObject pubJwk = new JSONObject();
            pubJwk.put("kty", "EC");
            pubJwk.put("crv", "P-256");
            pubJwk.put("x", x);
            pubJwk.put("y", y);
            out.publicJwk = pubJwk;
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String b64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] b64UrlDecode(String s) {
        return Base64.getUrlDecoder().decode(padBase64(s));
    }

    private static String padBase64(String s) {
        int m = s.length() % 4;
        if (m == 2) return s + "==";
        if (m == 3) return s + "=";
        return s;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 去掉前导 0 并补到固定长度（EC 坐标要 32 字节）。 */
    private static byte[] unsigned(byte[] in, int len) {
        byte[] out = new byte[len];
        int copy = Math.min(in.length, len);
        System.arraycopy(in, in.length - copy, out, len - copy, copy);
        return out;
    }

    /** DER(`30 len 02 rlen r 02 slen s`) → r‖s 固定 32 字节拼接。 */
    private static byte[] derToRaw(byte[] der, int size) {
        byte[] out = new byte[size * 2];
        try {
            int idx = 0;
            if (der[idx++] != 0x30) return der;
            int totalLen = der[idx++] & 0xFF;
            if ((totalLen & 0x80) != 0) idx += (totalLen & 0x7F);
            if (der[idx++] != 0x02) return der;
            int rLen = der[idx++] & 0xFF;
            byte[] r = new byte[rLen];
            System.arraycopy(der, idx, r, 0, rLen);
            idx += rLen;
            if (der[idx++] != 0x02) return der;
            int sLen = der[idx++] & 0xFF;
            byte[] s = new byte[sLen];
            System.arraycopy(der, idx, s, 0, sLen);

            byte[] rr = unsigned(r, size);
            byte[] ss = unsigned(s, size);
            System.arraycopy(rr, 0, out, 0, size);
            System.arraycopy(ss, 0, out, size, size);
            return out;
        } catch (Exception e) {
            return der;
        }
    }

    private static String readAll(java.io.InputStream in) throws java.io.IOException {
        if (in == null) return "";
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
