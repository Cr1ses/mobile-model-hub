package com.wbgateway;

/**
 * CodeArts 回调解析的桌面验证桥接（见 {@link OAuthCallback#codeArtsCode}）。
 *
 * <p>这段判断错了的后果是「用户登录成功但 App 一直卡在登录页」——
 * 因为认不出回调 URL 就拿不到 code，而界面上看不出任何错误。
 */
public final class CodeArtsCallbackProbe {

    private CodeArtsCallbackProbe() {
    }

    /** 见 {@link OAuthCallback#codeArtsCode(String)}。 */
    public static String extractCode(String url) {
        return OAuthCallback.codeArtsCode(url);
    }
}
