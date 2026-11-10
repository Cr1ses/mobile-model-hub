package com.wbgateway;

/**
 * 官方登录页地址的桌面验证桥接（见 {@link LoginPage}）。
 *
 * <p>这条链路值得验的点不是「有没有值」，而是**别把设备码模板当登录页用**：
 * `Spec.loginUrlTemplate` 里带 `{state}` 占位符，直接打开会得到一个 404/报错页；
 * 以及国内版别指向 `copilot.tencent.com`（那是 API 域名，打开是一段 JSON）。
 */
public final class LoginPageProbe {

    private LoginPageProbe() {
    }

    /** 见 {@link LoginPage#urlOf(String)}。 */
    public static String urlOf(String loginType) {
        return LoginPage.urlOf(loginType);
    }

    /** 见 {@link LoginPage#hostOf(String)}。 */
    public static String hostOf(String loginType) {
        return LoginPage.hostOf(loginType);
    }

    /** 见 {@link LoginPage#has(String)}。 */
    public static boolean has(String loginType) {
        return LoginPage.has(loginType);
    }
}
