package com.wbgateway;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raccoon 登录态解析的**纯逻辑**（零 Android 依赖）。
 *
 * <h3>为什么单独一个类</h3>
 *
 * 这些逻辑原本在 {@link RaccoonLoginActivity} 里，但那个类是 `Activity` 子类 ——
 * 桌面验证一加载它就 `NoClassDefFoundError: android/app/Activity`，
 * 于是**最容易出错的那段判断反而没法离线测**。抽出来之后：
 * Activity 只负责拿 cookie，挑 token 的规则在这里，可以单独跑用例。
 *
 * <h3>挑 token 的规则从哪来</h3>
 *
 * 站点自己的代码（bundle 里）是：
 * <pre>
 * let a = /^[A-Za-z0-9_-]*xhx_token$/.test(cookieName) ? cookieName.slice(0, -9) : null
 * access  = `${a}raccoon_token`
 * refresh = `${a}raccoon_refresh_token`
 * </pre>
 * `cookieName` 来自运行时配置（不是固定字符串），所以这里按**规则**识别，
 * 不硬编码键名 —— 站点换前缀后硬编码会静默失效（表现为「登录成功了但一用就 401」）。
 */
public final class RaccoonToken {

    /** 站点 cookieName 的后缀长度（"xhx_token" 共 9 个字符）。 */
    private static final String COOKIE_SUFFIX = "xhx_token";

    /** 访问令牌名后缀。 */
    private static final String ACCESS_SUFFIX = "raccoon_token";

    private RaccoonToken() {
    }

    /**
     * 从已解析的 cookie 表里挑出访问令牌。
     *
     * <p>两条路，先精确后兜底：
     * <ol>
     *   <li>找形如 `*xhx_token` 的项，按站点规则推出前缀，再取 `前缀 + raccoon_token`；</li>
     *   <li>找不到就扫所有以 `raccoon_token` 结尾、且名字里不含 `refresh` 的项。</li>
     * </ol>
     *
     * <p>⚠ **不能**把「任意非空 cookie」当 token：未登录时页面上本来就有
     * 设备指纹类 cookie（如 `_l_KPLiPs`），误取会让网关拿着垃圾去请求，
     * 用户看到的是「登录成功但一直 401」。
     *
     * @param jar cookie 名 → 值
     * @return 访问令牌；找不到返回空串
     */
    public static String pickAccessToken(Map<String, String> jar) {
        if (jar == null || jar.isEmpty()) return "";

        // ① 按站点规则推导
        for (Map.Entry<String, String> entry : jar.entrySet()) {
            String name = entry.getKey();
            if (name == null || !name.matches("[A-Za-z0-9_-]*" + COOKIE_SUFFIX)) continue;
            String prefix = name.substring(0, name.length() - COOKIE_SUFFIX.length());
            String token = jar.get(prefix + ACCESS_SUFFIX);
            if (isUsable(token)) return token;
        }

        // ② 兜底：任何 *raccoon_token（排除 refresh）
        for (Map.Entry<String, String> entry : jar.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null) continue;
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (!lower.endsWith(ACCESS_SUFFIX)) continue;
            if (lower.contains("refresh")) continue;
            if (isUsable(value)) return value;
        }
        return "";
    }

    /**
     * 列出**所有可疑的**访问令牌候选，供调用方逐个实际校验。
     *
     * <h3>为什么需要这一步</h3>
     *
     * 上面 {@link #pickAccessToken} 依赖「命名规则」，而据实观察：
     * <ul>
     *   <li>站点真实的 `cookieName` 由**运行时配置**下发，代码里没有字面量，
     *       离线无法确证它一定是 `*xhx_token` 形态；</li>
     *   <li>实测该站点确实会用 **HttpOnly** cookie（`INGRESSCOOKIE` 就是），
     *       说明「哪些 cookie 可见」本身就会变。</li>
     * </ul>
     * 与其猜名字，不如**把候选都列出来，用一次真实请求判断哪个有效** ——
     * 有效的那个必然能调通 `user_info`。这比任何命名推断都可靠，也不怕站点改版。
     *
     * <p>排序：按规则推导的优先，其余按名字出现顺序。调用方逐个试，
     * 命中即停。已排除 `refresh` 类与明显非令牌项。
     *
     * @param jar cookie 名 → 值
     * @return 去重后的候选令牌（可能为空）
     */
    public static java.util.List<String> candidateTokens(Map<String, String> jar) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (jar == null || jar.isEmpty()) return out;

        // 优先：按规则推导出来的
        String derived = pickAccessToken(jar);
        if (isUsable(derived)) out.add(derived.trim());

        // 其次：名字里带 token 的（排除 refresh / 明显非令牌）
        for (Map.Entry<String, String> entry : jar.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || !isUsable(value)) continue;
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("refresh")) continue;
            if (!lower.contains("token") && !lower.endsWith("_tk")) continue;
            String v = value.trim();
            // 太短的不像 JWT / 不透明令牌
            if (v.length() < 16) continue;
            if (!out.contains(v)) out.add(v);
        }
        return out;
    }

    private static boolean isUsable(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 解析 `a=1; b=2` 形态的 cookie 串。
     *
     * <p>值做一次 URL 解码（与站点的 `readCookie` 一致）。
     */
    public static Map<String, String> parseCookieHeader(String header) {
        Map<String, String> out = new LinkedHashMap<>();
        if (header == null || header.isEmpty()) return out;
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim();
            if (k.isEmpty()) continue;
            try {
                v = java.net.URLDecoder.decode(v, "UTF-8");
            } catch (Exception ignored) {
            }
            out.put(k, v);
        }
        return out;
    }

    /**
     * 把 `WebView.evaluateJavascript` 回传的 JSON 字面量还原成原值。
     *
     * <p>它的返回值是「JSON 编码后的字符串」：正常是 `"abc"`，空白是 `null`，
     * JS 抛错时可能是 `null` 或空。这里都归一到空串，避免把 `"null"` 当 token。
     */
    public static String unquoteJson(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        if (v.isEmpty() || "null".equals(v) || "undefined".equals(v)) return "";
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i += 1) {
            char ch = v.charAt(i);
            if (ch == '\\' && i + 1 < v.length()) {
                char nx = v.charAt(i + 1);
                if (nx == 'n') { sb.append('\n'); i += 1; continue; }
                if (nx == 'r') { sb.append('\r'); i += 1; continue; }
                if (nx == 't') { sb.append('\t'); i += 1; continue; }
                if (nx == '"' || nx == '\\' || nx == '/') { sb.append(nx); i += 1; continue; }
                if (nx == 'u' && i + 5 < v.length()) {
                    try {
                        sb.append((char) Integer.parseInt(v.substring(i + 2, i + 6), 16));
                        i += 5;
                        continue;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            sb.append(ch);
        }
        return sb.toString().trim();
    }
}
