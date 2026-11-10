package com.wbgateway;

/**
 * OAuth 回调 URL 的解析（**纯逻辑，零 Android 依赖**）。
 *
 * <h3>为什么单独抽出来</h3>
 *
 * 这段判断决定「登录完能不能自动拿到 token」。放在 Activity 里就没法离线验证 ——
 * 桌面一加载 `Activity` 子类就 `NoClassDefFoundError`，而它的失败形态很隐蔽：
 * <ul>
 *   <li>解析**太松**：把登录页里普通 `code` 参数当授权码，拿去换 token 得到一句
 *       莫名其妙的错误，用户以为账号有问题；</li>
 *   <li>解析**太严**：回调明明来了却认不出，用户卡在「已完成登录」的页面上干等。</li>
 * </ul>
 * 抽成纯函数后可以逐条断言（见 `verify/VerifyLogin.java` 第 10 组）。
 */
public final class OAuthCallback {

    /** ZCode 的回调 scheme。 */
    private static final String ZCODE_SCHEME = "zcode://oauth/callback";

    /** ZCode 的中转页（code 有时直接挂在这个 https 地址上）。 */
    private static final String ZCODE_RELAY = "https://zcode.z.ai/app/oauth/login";

    private OAuthCallback() {
    }

    /**
     * 从 ZCode 回调 URL 里取授权码。
     *
     * <p>只认两种来源：自定义 scheme 回调，或 zcode 的 https 中转页。
     * 其它页面（比如登录页）即使带 `code` 参数也**不认** —— 那不是授权码。
     *
     * @param url 回调 URL
     * @return 授权码；不是回调或没有 code 时返回空串
     */
    public static String zcodeCode(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.isEmpty()) return "";
        boolean isCallback = u.startsWith(ZCODE_SCHEME)
                || u.startsWith(ZCODE_RELAY)
                || u.contains("/app/oauth/login?");
        if (!isCallback) return "";
        String code = queryParam(u, "code");
        if (code.isEmpty()) code = queryParam(u, "authCode");
        return code;
    }

    /**
     * 从 CodeArts 的 OAuth 回调里取授权码。
     *
     * <p>回调形如 `http://127.0.0.1:PORT/oauth/callback?code=…&state=…`。
     * 只认带 `/oauth/callback` 路径的 URL —— 华为登录页本身也可能有 `code`
     * 查询参数，把那个当授权码会让换令牌直接失败。
     *
     * @param url 回调 URL
     * @return 授权码；不是回调或没有 code 时返回空串
     */
    public static String codeArtsCode(String url) {
        if (url == null) return "";
        if (!url.contains(CODEARTS_CALLBACK_PATH)) return "";
        String code = queryParam(url, "code");
        // 少数 SPA 会把参数放在 # 后面；查询串没有时再查一次片段
        if (code.isEmpty()) code = hashParam(url, "code");
        return code;
    }

    /**
     * 从 CodeArts 回调里取 `secret`（**旧 ticket 流程**的回调形态）。
     *
     * <p>门户改版前回调是 `?secret=…`，之后要拿它去轮询凭据；改版后是 `?code=…`。
     * 两种都认，否则用户会卡在「登录完成但没回 App」。
     *
     * @param url 回调 URL
     * @return secret；不是回调或没有 secret 时返回空串
     */
    public static String codeArtsSecret(String url) {
        if (url == null) return "";
        if (!url.contains(CODEARTS_CALLBACK_PATH)) return "";
        String secret = queryParam(url, "secret");
        if (secret.isEmpty()) secret = hashParam(url, "secret");
        return secret;
    }

    /** 从 `#` 片段里取参数（`#code=…&state=…` 这种形态）。 */
    private static String hashParam(String url, String key) {
        int h = url.indexOf('#');
        if (h < 0 || h + 1 >= url.length()) return "";
        return queryParam("?" + url.substring(h + 1), key);
    }

    /** CodeArts 的回调路径。 */
    private static final String CODEARTS_CALLBACK_PATH = "/oauth/callback";

    /**
     * 取查询参数（自己解析，不依赖 `Uri` 的行为差异）。
     *
     * <p>两个细节：按键名**精确**匹配（`codex=1` 不能被当成 `code`）；
     * `#` 之后的片段不参与解析（有些站点把参数塞在 hash 里）。
     */
    public static String queryParam(String url, String key) {
        if (url == null || key == null) return "";
        int q = url.indexOf('?');
        if (q < 0 || q + 1 >= url.length()) return "";
        String query = url.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) query = query.substring(0, hash);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if (!key.equals(pair.substring(0, eq))) continue;
            String v = pair.substring(eq + 1);
            try {
                return java.net.URLDecoder.decode(v, "UTF-8").trim();
            } catch (Exception e) {
                return v.trim();
            }
        }
        return "";
    }
}
