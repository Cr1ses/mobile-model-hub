package com.wbgateway;

import java.util.Map;

/**
 * Raccoon 登录态识别的桌面验证桥接。
 *
 * <p>`RaccoonLoginActivity` 是 Android Activity，桌面跑不起来；但真正容易出错的
 * 不是界面，而是**从一堆 cookie 里挑出访问令牌**那段判断 —— 挑错的表现是
 * 「登录成功了，但网关一用就 401」。那段逻辑已抽到 {@link RaccoonToken}（无 Android 依赖），
 * 这里只做转发。
 */
public final class RaccoonLoginProbe {

    private RaccoonLoginProbe() {
    }

    /** 见 {@link RaccoonToken#pickAccessToken(Map)}。 */
    public static String pickToken(Map<String, String> jar) {
        return RaccoonToken.pickAccessToken(jar);
    }

    /** 见 {@link RaccoonToken#candidateTokens(Map)}。 */
    public static java.util.List<String> candidates(Map<String, String> jar) {
        return RaccoonToken.candidateTokens(jar);
    }

    /** 见 {@link RaccoonToken#parseCookieHeader(String)}。 */
    public static Map<String, String> parseCookie(String header) {
        return RaccoonToken.parseCookieHeader(header);
    }

    /** 见 {@link RaccoonToken#unquoteJson(String)}。 */
    public static String unquote(String raw) {
        return RaccoonToken.unquoteJson(raw);
    }
}
