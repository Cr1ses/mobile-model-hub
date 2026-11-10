package com.wbgateway;

/**
 * ZCode 回调解析的桌面验证桥接（见 {@link OAuthCallback}）。
 *
 * <p>逻辑已抽到 `OAuthCallback`（零 Android 依赖），这里只做转发 ——
 * 之所以不直接调 Activity 里的方法：那是 `Activity` 子类，桌面一加载就
 * `NoClassDefFoundError: android/app/Activity`，反而验不到。
 */
public final class ZcodeProbe {

    private ZcodeProbe() {
    }

    /** 见 {@link OAuthCallback#zcodeCode(String)}。 */
    public static String extractCode(String url) {
        return OAuthCallback.zcodeCode(url);
    }

    /** 见 {@link OAuthCallback#queryParam(String, String)}。 */
    public static String queryParam(String url, String key) {
        return OAuthCallback.queryParam(url, key);
    }
}
