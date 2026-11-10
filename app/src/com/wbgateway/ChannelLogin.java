package com.wbgateway;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 三个非 WorkBuddy 渠道的**直接登录**实现（Qoder / Qoder 中国版 / ZCode / Raccoon）。
 *
 * <p>这四个渠道以前只能「粘贴 token」，因为它们的官方登录都不是一次 HTTP 调用。
 * 现在各自补齐了可行链路，共同点是：**登录过程不依赖粘贴，也不需要桌面端**。
 *
 * <h3>Qoder / Qoder 中国版 —— 设备码式 PKCE</h3>
 * <pre>
 * 1. 本地生成 PKCE（verifier 43–128 位，challenge = base64url(sha256(verifier))）
 *    与 nonce、machine_id（都是随机 UUID）
 * 2. 打开 {authBase}/device/selectAccounts?challenge=…&amp;challenge_method=S256
 *      &amp;nonce=…&amp;machine_id=…&amp;client_id=…
 *    用户在浏览器里登录并选账号（实测：未登录时 302 到 /users/sign-in）
 * 3. 轮询 {openApiBase}/api/v1/deviceToken/poll?nonce=…&amp;verifier=…&amp;challenge_method=S256
 *    未完成时返回 404；完成时返回 token / refresh_token / …
 * </pre>
 * 这条链路**不需要 WASM 签名器** —— 签名器只在「聊天」那一跳用，登录不需要。
 *
 * <h3>ZCode —— 浏览器 OAuth + 自定义 scheme 回调</h3>
 * <pre>
 * 1. 生成 state
 * 2. 打开 https://bigmodel.cn/login?redirect=&lt;回跳地址&gt;&amp;appId=zcode&amp;state=…
 *    回跳地址 = https://zcode.z.ai/app/oauth/login?redirect=zcode://oauth/callback&amp;app_version=…
 * 3. 用户在浏览器登录后，ZCode 门户把 code 带回自定义 scheme；
 *    本机拿不到那个 scheme，所以改成**解析回跳 URL 上的 code**
 * 4. POST https://zcode.z.ai/api/v1/oauth/token
 *    {provider:"bigmodel", code, redirect_uri, state} → {data:{token, bigmodel:{access_token,…}}}
 * </pre>
 *
 * <h3>Raccoon —— 真二维码扫码</h3>
 * <pre>
 * 1. POST {base}/api/iam/v1/auth/mp/qrcode  {} → {qrcode_code, scan_path, expires_in, poll_interval}
 * 2. 把「scan_path?code=…&amp;appname=…&amp;source=platform」画成二维码给手机扫
 * 3. 轮询 POST {base}/api/iam/v1/auth/mp/qrcode/login {qrcode_code}
 *    → status: pending / logging / canceled / success（success 时带 access_token）
 * </pre>
 * 二维码由本机用 {@link QrCode} 编码并绘制，不依赖服务端出图。
 *
 * <p>⚠ 所有上游响应里可能出现 token，本类**不打印**任何凭据，错误消息只保留
 * 状态码与业务提示。
 */
public final class ChannelLogin {

    /** 单次请求超时。 */
    private static final int TIMEOUT_MS = 25_000;

    private ChannelLogin() {
    }

    // -----------------------------------------------------------------------
    // 通用结果
    // -----------------------------------------------------------------------

    /** 一次登录会话（不同渠道字段含义不同，未用的留空）。 */
    public static class LoginSession {
        /** 渠道类型（qoder / qodercn / zcode / raccoon）。 */
        public String loginType = "";
        /** 要打开 / 要编码成二维码的内容。 */
        public String loginUrl = "";
        /** Qoder 用：nonce。 */
        public String nonce = "";
        /** Qoder 用：PKCE verifier。 */
        public String verifier = "";
        /** Qoder 用：machine_id（续期时要原样带回）。 */
        public String machineId = "";
        /** ZCode 用：state。 */
        public String state = "";
        /** ZCode 用：回跳地址（换 token 时要原样带回）。 */
        public String redirectUri = "";
        /** ZCode 用：provider（bigmodel / zai）。 */
        public String provider = "bigmodel";
        /** Raccoon 用：二维码 code。 */
        public String qrCode = "";
        /** Raccoon 用：轮询间隔（毫秒）。 */
        public long pollIntervalMs = 2000L;
        /** 会话过期时间（毫秒时间戳；0 表示未知）。 */
        public long expiresAt = 0L;
    }

    /** 轮询结果：`channel` 非 null 表示登录成功。 */
    public static class PollResult {
        /** 已登录渠道（成功时非 null）。 */
        public Channel channel;
        /** 状态文案（给界面显示）。 */
        public String message = "";
        /** 是否已经无法继续（失败 / 过期 / 取消）。 */
        public boolean terminal = false;

        static PollResult waiting(String message) {
            PollResult r = new PollResult();
            r.message = message;
            return r;
        }

        static PollResult failed(String message) {
            PollResult r = new PollResult();
            r.message = message;
            r.terminal = true;
            return r;
        }

        static PollResult ok(Channel channel, String message) {
            PollResult r = new PollResult();
            r.channel = channel;
            r.message = message;
            r.terminal = true;
            return r;
        }
    }

    /** 该渠道是否已支持「App 内直接登录」。 */
    public static boolean supportsDirectLogin(String loginType) {
        return LoginProviders.QODER.equals(loginType)
                || LoginProviders.QODER_CN.equals(loginType)
                || LoginProviders.ZCODE.equals(loginType);
    }

    /**
     * 该渠道的登录形态。
     *
     * @return `browser`（浏览器完成后回 App）/ 空串（不走这里）
     */
    public static String loginMode(String loginType) {
        if (LoginProviders.QODER.equals(loginType) || LoginProviders.QODER_CN.equals(loginType)) return "browser";
        if (LoginProviders.ZCODE.equals(loginType)) return "browser";
        return "";
    }

    // -----------------------------------------------------------------------
    // 第一步：发起登录
    // -----------------------------------------------------------------------

    /** 发起一次登录，返回会话（内含要打开的 URL）。 */
    public static LoginSession begin(String loginType) throws IOException {
        if (LoginProviders.QODER.equals(loginType)) return beginQoder(loginType);
        if (LoginProviders.QODER_CN.equals(loginType)) return beginQoder(loginType);
        if (LoginProviders.ZCODE.equals(loginType)) return beginZcode();
        throw new IOException("该渠道不走这条登录链路：" + loginType);
    }

    // ---- Qoder / Qoder 中国版 ----

    private static LoginSession beginQoder(String loginType) throws IOException {
        LoginProviders.Spec spec = LoginProviders.specOf(loginType);
        if (spec == null) throw new IOException("未知登录类型：" + loginType);

        LoginSession s = new LoginSession();
        s.loginType = loginType;
        s.verifier = randomPkceVerifier();
        String challenge = sha256Base64Url(s.verifier);
        s.nonce = UUID.randomUUID().toString();
        s.machineId = UUID.randomUUID().toString();

        // client_id 来自 Qoder 客户端的 authClientIds.prod（国际版与国内版不同）
        String clientId = loginType.equals(LoginProviders.QODER_CN)
                ? "732aef47-9cf2-46a2-95fe-4cebb5d0d1fa"
                : "e883ade2-e6e3-4d6d-adf7-f92ceff5fdcb";

        String query = "challenge=" + urlEncode(challenge)
                + "&challenge_method=S256"
                + "&nonce=" + urlEncode(s.nonce)
                + "&machine_id=" + urlEncode(s.machineId)
                + "&client_id=" + urlEncode(clientId);
        s.loginUrl = spec.endpoint + "/device/selectAccounts?" + query;
        return s;
    }

    // ---- ZCode ----

    private static LoginSession beginZcode() throws IOException {
        LoginSession s = new LoginSession();
        s.loginType = LoginProviders.ZCODE;
        s.state = UUID.randomUUID().toString().replace("-", "");
        // 门户侧要求的回跳地址：先落到 zcode 的 oauth 页面，再由它转自定义 scheme。
        // 手机上拿不到 zcode:// 回调，所以用户完成登录后**复制地址栏的 code**
        // 回到 App 粘贴 —— 这是一条不需要电脑的路径。
        s.redirectUri = "https://zcode.z.ai/app/oauth/login?redirect="
                + urlEncode("zcode://oauth/callback")
                + "&app_version=" + urlEncode(ZCODE_APP_VERSION);
        s.loginUrl = "https://bigmodel.cn/login?redirect=" + urlEncode(s.redirectUri)
                + "&appId=zcode&state=" + urlEncode(s.state);
        return s;
    }

    /** ZCode 客户端版本号（上游会校验，与桌面版一致）。 */
    private static final String ZCODE_APP_VERSION = "3.14.3";

    // ---- Raccoon ----

    private static LoginSession beginRaccoon() throws IOException {
        LoginProviders.Spec spec = LoginProviders.specOf(LoginProviders.RACCOON);
        if (spec == null) throw new IOException("未知登录类型");

        String url = spec.endpoint + "/api/iam/v1/auth/mp/qrcode";
        JSONObject body = requestJson("POST", url, spec, null, "{}", TIMEOUT_MS);
        JSONObject data = body.optJSONObject("data");
        if (data == null) throw new IOException("创建登录二维码失败：响应缺少 data");

        LoginSession s = new LoginSession();
        s.loginType = LoginProviders.RACCOON;
        s.qrCode = data.optString("qrcode_code", "");
        String scanPath = data.optString("scan_path", "");
        if (s.qrCode.isEmpty()) throw new IOException("创建登录二维码失败：缺少 qrcode_code");
        if (scanPath.isEmpty()) scanPath = "/login/mp";

        long expiresIn = data.optLong("expires_in", 300L);
        long pollInterval = data.optLong("poll_interval", 2L);
        s.pollIntervalMs = Math.max(1000L, Math.min(60_000L, pollInterval * 1000L));
        s.expiresAt = System.currentTimeMillis() + expiresIn * 1000L;

        // 二维码内容 = 扫码页 + code + appname + source（与网页端拼法一致）
        s.loginUrl = spec.endpoint + scanPath
                + "?code=" + urlEncode(s.qrCode)
                + "&appname=" + urlEncode("商汤小浣熊")
                + "&source=platform";
        return s;
    }

    // -----------------------------------------------------------------------
    // 第二步：轮询 / 完成
    // -----------------------------------------------------------------------

    /**
     * 轮询一次登录状态。
     *
     * @param session 会话
     * @param code    ZCode 需要用户粘贴回来的 code；其它渠道传 null
     */
    public static PollResult poll(LoginSession session, String code) throws IOException {
        if (LoginProviders.QODER.equals(session.loginType)
                || LoginProviders.QODER_CN.equals(session.loginType)) {
            return pollQoder(session);
        }
        if (LoginProviders.ZCODE.equals(session.loginType)) {
            return exchangeZcode(session, code);
        }
        if (LoginProviders.RACCOON.equals(session.loginType)) {
            return pollRaccoon(session);
        }
        return PollResult.failed("未知登录会话：" + session.loginType);
    }

    // ---- Qoder ----

    private static PollResult pollQoder(LoginSession s) throws IOException {
        LoginProviders.Spec spec = LoginProviders.specOf(s.loginType);
        if (spec == null) return PollResult.failed("未知登录类型");

        String query = "nonce=" + urlEncode(s.nonce)
                + "&verifier=" + urlEncode(s.verifier)
                + "&challenge_method=S256";
        String url = spec.openApiBase + "/api/v1/deviceToken/poll?" + query;

        HttpResult result = requestRaw("GET", url, spec, null, null, TIMEOUT_MS);
        // 未完成时上游返回 404（实测），不是错误
        if (result.status == 404) return PollResult.waiting("等待在浏览器里完成登录…");
        if (result.status < 200 || result.status >= 300) {
            return PollResult.waiting("登录服务返回 HTTP " + result.status + "，稍后重试");
        }
        JSONObject payload;
        try {
            payload = new JSONObject(result.body);
        } catch (Exception e) {
            return PollResult.waiting("响应解析中…");
        }
        String access = firstNonEmpty(payload, "token", "device_token", "access_token");
        if (access.isEmpty()) return PollResult.waiting("等待在浏览器里完成登录…");

        Channel c = newChannel(s.loginType);
        c.accessToken = access;
        c.refreshToken = firstNonEmpty(payload, "refresh_token", "refreshToken");
        c.expiresAt = readTimestamp(payload.opt("expires_at") != null
                ? payload.opt("expires_at") : payload.opt("expiresAt"));
        long refreshExp = readTimestamp(payload.opt("refresh_token_expires_at") != null
                ? payload.opt("refresh_token_expires_at") : payload.opt("refreshTokenExpiresAt"));
        // 过期时间未知时按 access token 的 JWT 兜底
        if (c.expiresAt <= 0) c.expiresAt = LoginProviders.jwtExpiresAt(access);
        c.domain = spec.apiDomain;
        // machine_id 必须与发起登录时用的一致，否则续期会被上游拒绝
        c.machineId = s.machineId;
        c.refreshExpiresAt = refreshExp;
        c.nickname = firstNonEmpty(payload, "user_name", "userName", "nickname");
        if (c.nickname.isEmpty()) c.nickname = LoginProviders.jwtNickname(access);
        // 取不到真实昵称时**留空**，不要把渠道名当昵称存进去 —— 那会让界面显示成「名字 · 名字」

        finishChannel(c);
        return PollResult.ok(c, "登录成功");
    }

    // ---- ZCode ----

    /**
     * 用浏览器带回来的 code 换 token。
     *
     * <p>为什么必须让用户把 code 带回来：ZCode 的回调是自定义 scheme
     * （`zcode://oauth/callback`），浏览器能识别但**只有装了 ZCode 桌面端才有人接**。
     * 手机上没有任何应用响应它，页面会停在那个地址上 —— 用户从地址栏复制
     * `?code=…` 回 App 是最短的可走通路径（全程不碰电脑）。
     */
    private static PollResult exchangeZcode(LoginSession s, String code) throws IOException {
        if (code == null || code.trim().isEmpty()) {
            return PollResult.waiting("登录完成后，把地址栏里的 code 复制粘贴到这里");
        }
        String cleaned = extractCode(code.trim());
        if (cleaned.isEmpty()) return PollResult.waiting("没读到有效的 code，请重新复制");

        LoginProviders.Spec spec = LoginProviders.specOf(LoginProviders.ZCODE);
        if (spec == null) return PollResult.failed("未知登录类型");

        JSONObject req = new JSONObject();
        try {
            req.put("provider", s.provider);
            req.put("code", cleaned);
            req.put("redirect_uri", s.redirectUri);
            req.put("state", s.state);
        } catch (Exception ignored) {
        }
        String url = "https://zcode.z.ai/api/v1/oauth/token";
        JSONObject body = requestJson("POST", url, spec, null, req.toString(), TIMEOUT_MS);

        int bizCode = body.optInt("code", 0);
        if (body.has("code") && bizCode != 0) {
            return PollResult.failed("登录失败（" + bizCode + "）：" + body.optString("msg", "上游拒绝"));
        }
        JSONObject data = body.optJSONObject("data");
        if (data == null) return PollResult.failed("登录失败：响应缺少 data");

        // BigModel 形态：data.token 是 zcode JWT，data.bigmodel.access_token 是业务令牌
        String zcodeJwt = data.optString("token", "");
        JSONObject bigmodel = data.optJSONObject("bigmodel");
        String business = "";
        String refresh = "";
        if (bigmodel != null) {
            business = firstNonEmpty(bigmodel, "access_token", "accessToken");
            refresh = firstNonEmpty(bigmodel, "refresh_token", "refreshToken");
            if (business.isEmpty()) business = zcodeJwt;
        }
        // Z.ai（zai）形态：data.access_token 直接可用
        if (business.isEmpty()) business = firstNonEmpty(data, "access_token", "accessToken");
        if (business.isEmpty()) business = zcodeJwt;
        if (business.isEmpty()) return PollResult.failed("登录失败：响应里没有可用令牌");

        Channel c = newChannel(LoginProviders.ZCODE);
        c.accessToken = business;
        c.zcodeJwt = zcodeJwt;
        c.refreshToken = refresh;
        c.domain = spec.apiDomain;
        c.nickname = firstNonEmpty(data, "nickname", "user_name", "userName");
        if (c.nickname.isEmpty()) c.nickname = LoginProviders.jwtNickname(zcodeJwt);
        // 取不到真实昵称时**留空**，不要把渠道名当昵称存进去 —— 那会让界面显示成「名字 · 名字」
        c.expiresAt = LoginProviders.jwtExpiresAt(zcodeJwt);

        finishChannel(c);
        return PollResult.ok(c, "登录成功");
    }

    /**
     * 从「粘贴的整段内容」里抠出 code：支持 URL、`code=…`、JSON、纯 code。
     *
     * <p>⚠ 有一条容易被忽略的分支：**看起来像参数串但不是 code 的内容**
     * （比如用户把整个回调地址里的 `state=…` 段粘进来）。此时不能把原文当
     * 纯 code 送上游 —— 那会用一串无效字符去换 token，得到的是一句含糊的
     * 「登录失败」，而真正的原因是「你粘错了东西」。所以带 `=` 但不含
     * `code=` 的文本一律视为无效。
     */
    static String extractCode(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return "";

        int idx = t.indexOf("code=");
        if (idx >= 0) {
            String rest = t.substring(idx + 5);
            int amp = rest.indexOf('&');
            if (amp >= 0) rest = rest.substring(0, amp);
            return urlDecode(rest).trim();
        }

        if (t.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(t);
                return firstNonEmpty(o, "code", "authCode");
            } catch (Exception ignored) {
                return "";
            }
        }

        // 带 `key=value` 形态但不是 code：粘错了东西，别当成 code 用
        if (t.contains("=") && !t.startsWith("code")) return "";

        // 纯 code：去掉可能残留的空白与引号
        return t.replaceAll("[\"']", "").trim();
    }

    // ---- Raccoon ----

    private static PollResult pollRaccoon(LoginSession s) throws IOException {
        LoginProviders.Spec spec = LoginProviders.specOf(LoginProviders.RACCOON);
        if (spec == null) return PollResult.failed("未知登录类型");
        if (s.expiresAt > 0 && System.currentTimeMillis() > s.expiresAt) {
            return PollResult.failed("二维码已过期，请重新发起登录");
        }

        String url = spec.endpoint + "/api/iam/v1/auth/mp/qrcode/login";
        JSONObject req = new JSONObject();
        try {
            req.put("qrcode_code", s.qrCode);
        } catch (Exception ignored) {
        }
        JSONObject body = requestJson("POST", url, spec, null, req.toString(), TIMEOUT_MS);
        JSONObject data = body.optJSONObject("data");
        if (data == null) return PollResult.waiting("等待扫码…");

        String status = data.optString("status", "");
        if ("pending".equals(status)) return PollResult.waiting("等待扫码…");
        if ("logging".equals(status)) return PollResult.waiting("已扫码，请在手机上确认");
        if ("canceled".equals(status)) return PollResult.failed("登录已取消，请重新发起");
        if (!"success".equals(status)) return PollResult.waiting("等待扫码…");

        String access = data.optString("access_token", "");
        if (access.isEmpty()) return PollResult.waiting("登录确认中…");

        Channel c = newChannel(LoginProviders.RACCOON);
        c.accessToken = access;
        c.refreshToken = data.optString("refresh_token", "");
        c.domain = spec.apiDomain;
        c.officeIdentity = data.optString("office_identity", "");
        c.expiresAt = LoginProviders.jwtExpiresAt(access);
        String nick = firstNonEmpty(data, "name", "nickname", "user_name");
        if (!nick.isEmpty()) c.nickname = nick;
        if (c.nickname.isEmpty()) c.nickname = LoginProviders.jwtNickname(access);
        // 取不到真实昵称时**留空**，不要把渠道名当昵称存进去 —— 那会让界面显示成「名字 · 名字」

        // 拉一次账号信息，把昵称补全（失败不影响登录）
        try {
            JSONObject me = fetchRaccoonUserInfo(c, spec);
            if (me != null) {
                String name = firstNonEmpty(me, "name", "nickname");
                if (!name.isEmpty()) c.nickname = name;
                String office = me.optString("office_identity", "");
                if (!office.isEmpty()) c.officeIdentity = office;
            }
        } catch (IOException ignored) {
        }

        finishChannel(c);
        return PollResult.ok(c, "登录成功");
    }

    /** Raccoon 账号信息（昵称 / 组织标识）。 */
    private static JSONObject fetchRaccoonUserInfo(Channel channel, LoginProviders.Spec spec) throws IOException {
        String url = spec.endpoint + "/api/web/auth/v1/user_info";
        try {
            JSONObject body = requestJson("GET", url, spec, channel, null, 12_000);
            return body.optJSONObject("data");
        } catch (IOException e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // 续期
    // -----------------------------------------------------------------------

    /**
     * 各渠道的 token 续期。
     *
     * @return 续期后的渠道；无法续期返回 null（调用方保持原样并如实报错）
     */
    public static Channel refresh(Channel channel) throws IOException {
        if (LoginProviders.QODER.equals(channel.loginType)
                || LoginProviders.QODER_CN.equals(channel.loginType)) {
            return refreshQoder(channel);
        }
        if (LoginProviders.ZCODE.equals(channel.loginType)) return refreshZcode(channel);
        if (LoginProviders.RACCOON.equals(channel.loginType)) return refreshRaccoon(channel);
        return null;
    }

    private static Channel refreshQoder(Channel channel) throws IOException {
        if (channel.refreshToken.isEmpty()) return null;
        LoginProviders.Spec spec = LoginProviders.specOf(channel.loginType);
        if (spec == null) return null;

        JSONObject req = new JSONObject();
        try {
            req.put("refresh_token", channel.refreshToken);
            req.put("machine_id", channel.machineId == null ? "" : channel.machineId);
        } catch (Exception ignored) {
        }
        String url = spec.openApiBase + "/api/v1/deviceToken/refresh";
        HttpResult r = requestRaw("POST", url, spec, channel, req.toString(), TIMEOUT_MS);
        if (r.status < 200 || r.status >= 300) return null;
        JSONObject body;
        try {
            body = new JSONObject(r.body);
        } catch (Exception e) {
            return null;
        }
        String access = firstNonEmpty(body, "token", "device_token", "access_token");
        if (access.isEmpty()) return null;

        Channel c = copyOf(channel);
        c.accessToken = access;
        String nextRefresh = firstNonEmpty(body, "refresh_token", "refreshToken");
        if (!nextRefresh.isEmpty()) c.refreshToken = nextRefresh;
        c.expiresAt = readTimestamp(body.opt("expires_at") != null
                ? body.opt("expires_at") : body.opt("expiresAt"));
        if (c.expiresAt <= 0) c.expiresAt = LoginProviders.jwtExpiresAt(access);
        return c;
    }

    private static Channel refreshZcode(Channel channel) throws IOException {
        // ZCode 的业务令牌由 ChatGPT 侧 OAuth 发行，没有公开 refresh 端点；
        // 拿 zcode JWT 自行续期属于未验证行为，这里如实返回 null（界面提示重新登录）。
        return null;
    }

    private static Channel refreshRaccoon(Channel channel) throws IOException {
        if (channel.refreshToken.isEmpty()) return null;
        LoginProviders.Spec spec = LoginProviders.specOf(LoginProviders.RACCOON);
        if (spec == null) return null;

        JSONObject req = new JSONObject();
        try {
            req.put("refresh_token", channel.refreshToken);
        } catch (Exception ignored) {
        }
        String url = spec.endpoint + "/api/web/auth/v1/refresh";
        HttpResult r = requestRaw("POST", url, spec, null, req.toString(), TIMEOUT_MS);
        if (r.status < 200 || r.status >= 300) return null;
        JSONObject body;
        try {
            body = new JSONObject(r.body);
        } catch (Exception e) {
            return null;
        }
        JSONObject data = body.optJSONObject("data");
        if (data == null) return null;
        String access = data.optString("access_token", "");
        if (access.isEmpty()) return null;

        Channel c = copyOf(channel);
        c.accessToken = access;
        String nextRefresh = data.optString("refresh_token", "");
        if (!nextRefresh.isEmpty()) c.refreshToken = nextRefresh;
        c.expiresAt = LoginProviders.jwtExpiresAt(access);
        return c;
    }

    // -----------------------------------------------------------------------
    // 登录后的收尾：拉模型
    // -----------------------------------------------------------------------

    /**
     * 登录成功后统一收尾：建渠道对象、拉一次模型清单。
     *
     * <p>只有 Raccoon 有可信的模型目录接口（`/api/web/llm/v2/models` 之类未必存在，
     * 因此这里**优先真实接口，失败才退内置表**）；Qoder / ZCode 上游没有目录接口，
     * 用内置表并在界面标注来源（`hasLiveModelCatalog` 决定标注）。
     */
    private static void finishChannel(Channel channel) {
        channel.enabled = true;
        channel.modelsUpdatedAt = System.currentTimeMillis();
        try {
            java.util.List<String> models = fetchModels(channel);
            if (!models.isEmpty()) {
                channel.cachedModels = models;
                return;
            }
        } catch (IOException ignored) {
            // 拉不到就走内置表
        }
        if (channel.manualModels.isEmpty() && !LoginProviders.hasLiveModelCatalog(channel.loginType)) {
            channel.manualModels = LoginProviders.defaultModelsFor(channel.loginType);
            // 内置表里有规格/倍率的渠道，把规格一起写进去 ——
            // 否则界面上只剩模型 id，看不出「哪个贵」。
            if (LoginProviders.QODER.equals(channel.loginType)
                    || LoginProviders.QODER_CN.equals(channel.loginType)) {
                channel.modelSpecs.putAll(LoginProviders.qoderBuiltinSpecs());
            } else if (LoginProviders.RACCOON.equals(channel.loginType)) {
                channel.modelSpecs.putAll(LoginProviders.raccoonBuiltinSpecs());
            }
        }
    }

    /**
     * 拉该渠道的模型清单。
     *
     * <p>只有 Raccoon 有目录端点：`GET /api/web/llm/v2/model_catalog`
     * （实测无 token 时 401 = 存在且要鉴权；`/llm/v2/models` 才是 404）。
     * 目录返回空（没权限 / 没开通）时返回空列表，由调用方退回内置表 ——
     * 内置表同样带积分倍率，界面不会因此少信息。
     *
     * <p>Qoder / ZCode 上游没有目录端点（Qoder 的 `/sash/api/v1/me/models`
     * 等路径全 404，同一 token 调其它接口都 200），所以这里返回空。
     */
    static java.util.List<String> fetchModels(Channel channel) throws IOException {
        if (LoginProviders.RACCOON.equals(channel.loginType)) {
            // 端点存在（`/api/web/llm/v2/model_catalog`，无 token 时 401），
            // 但没权限 / 没开通时会返回空 —— 交给调用方退回内置表。
            LoginProviders.Catalog cat = LoginProviders.fetchRaccoonModels(channel);
            if (!cat.ids.isEmpty()) {
                channel.modelSpecs.clear();
                channel.modelSpecs.putAll(cat.specs);
            }
            return new java.util.ArrayList<>(cat.ids);
        }
        return new java.util.ArrayList<>();
    }

    // -----------------------------------------------------------------------
    // 工具
    // -----------------------------------------------------------------------

    /** 裸 HTTP 结果。 */
    static class HttpResult {
        int status;
        String body = "";
    }

    private static Channel newChannel(String loginType) {
        Channel c = new Channel();
        c.id = loginType;
        c.loginType = loginType;
        c.kind = Channel.KIND_LOGIN;
        LoginProviders.Spec spec = LoginProviders.specOf(loginType);
        c.name = spec == null ? loginType : spec.displayName;
        c.baseUrl = inferBaseUrl(loginType);
        return c;
    }

    /** 聊天转发的基址（各渠道的 chat/completions 前缀不同；统一走一张表）。 */
    static String inferBaseUrl(String loginType) {
        return LoginProviders.chatBaseFor(loginType);
    }

    /** 深拷贝渠道（避免就地改 UI 持有的对象）。 */
    static Channel copyOf(Channel src) {
        return Channel.fromJson(src.toJson());
    }

    /** PKCE verifier：43–128 位，字符集严格按 RFC 7636。 */
    static String randomPkceVerifier() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        java.security.SecureRandom random = new java.security.SecureRandom();
        int length = 64;
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i += 1) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /**
     * `base64url(sha256(text))`，去掉 padding。
     *
     * <p>为什么不用 `android.util.Base64`：这个类只存在于 Android，
     * 桌面验证（`verify/VerifyLogin.java`）跑同一份代码时会 `NoClassDefFoundError`。
     * 手写 base64url 只有十几行，换来「同一份实现两端都能跑」——
     * 而 PKCE 算错的症状是「浏览器里登录完了，轮询永远拿不到 token」，
     * 那种问题必须在离线的验证里就能发现。
     */
    static String sha256Base64Url(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return base64UrlNoPadding(digest);
        } catch (Exception e) {
            return text;
        }
    }

    /** 标准 base64 的 URL 安全变体，去掉尾部 `=`（RFC 4648 §5）。 */
    static String base64UrlNoPadding(byte[] data) {
        final char[] alphabet =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
        StringBuilder sb = new StringBuilder((data.length * 4 + 2) / 3);
        int i = 0;
        while (i + 3 <= data.length) {
            int chunk = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(alphabet[(chunk >>> 18) & 0x3F]);
            sb.append(alphabet[(chunk >>> 12) & 0x3F]);
            sb.append(alphabet[(chunk >>> 6) & 0x3F]);
            sb.append(alphabet[chunk & 0x3F]);
            i += 3;
        }
        int rest = data.length - i;
        if (rest == 1) {
            int chunk = (data[i] & 0xFF) << 16;
            sb.append(alphabet[(chunk >>> 18) & 0x3F]);
            sb.append(alphabet[(chunk >>> 12) & 0x3F]);
        } else if (rest == 2) {
            int chunk = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(alphabet[(chunk >>> 18) & 0x3F]);
            sb.append(alphabet[(chunk >>> 12) & 0x3F]);
            sb.append(alphabet[(chunk >>> 6) & 0x3F]);
        }
        return sb.toString();
    }

    /** 读时间戳：支持秒 / 毫秒 / ISO 字符串。 */
    static long readTimestamp(Object raw) {
        if (raw == null) return 0L;
        if (raw instanceof Number) {
            double v = ((Number) raw).doubleValue();
            if (v <= 0) return 0L;
            return v < 1e12 ? (long) (v * 1000) : (long) v;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) return 0L;
        try {
            long v = Long.parseLong(text);
            return v < 1e12 ? v * 1000 : v;
        } catch (NumberFormatException ignored) {
        }
        return LoginProviders.parseDate(text);
    }

    static String firstNonEmpty(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ---- HTTP ----

    private static JSONObject requestJson(String method, String url, LoginProviders.Spec spec,
                                          Channel credential, String body, int timeoutMs) throws IOException {
        HttpResult r = requestRaw(method, url, spec, credential, body, timeoutMs);
        if (r.status < 200 || r.status >= 300) {
            throw new IOException("HTTP " + r.status + (r.body.isEmpty() ? "" : "：" + shrink(r.body)));
        }
        try {
            return new JSONObject(r.body);
        } catch (Exception e) {
            throw new IOException("响应不是 JSON（HTTP " + r.status + "）：" + shrink(r.body));
        }
    }

    /** 给同包内其它类用的包内版本（{@link RaccoonLoginActivity} 取账号信息用）。 */
    static JSONObject requestJsonPublic(String method, String url, LoginProviders.Spec spec,
                                        Channel credential, String body, int timeoutMs) throws IOException {
        return requestJson(method, url, spec, credential, body, timeoutMs);
    }

    static HttpResult requestRaw(String method, String url, LoginProviders.Spec spec,
                                 Channel credential, String body, int timeoutMs) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", spec == null
                    ? "MobileModelHub/1.0" : spec.userAgent);
            if (credential != null && !credential.accessToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + credential.accessToken);
                if (!credential.officeIdentity.isEmpty()) {
                    conn.setRequestProperty("X-Org-Code", credential.officeIdentity);
                }
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();
            }
            HttpResult r = new HttpResult();
            r.status = conn.getResponseCode();
            InputStream is = r.status >= 200 && r.status < 300
                    ? conn.getInputStream() : conn.getErrorStream();
            r.body = is == null ? "" : new String(readAll(is), StandardCharsets.UTF_8);
            return r;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String shrink(String text) {
        String t = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
