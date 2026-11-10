package com.wbgateway;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 各渠道的**官方登录页**地址。
 *
 * <h3>为什么单独一层，而不是直接用 {@link LoginProviders.Spec#loginUrlTemplate}</h3>
 *
 * 那个字段是给「设备码流程」用的模板（带 `{state}` 占位），只有 WorkBuddy 两区域能拼；
 * 而「我想自己去官网上登一下」是另一件事 —— 用户要的是**能直接打开、能正常登录的网页**，
 * 不需要任何占位符。两者混用会拼出 `…?state={state}` 这种打开就报错的地址。
 *
 * <h3>这些地址是怎么定的</h3>
 * 全部从各自的客户端/网页实测过（2026-11），不是按域名猜的：
 * <ul>
 *   <li>WorkBuddy 国际版：`www.workbuddy.ai/login`</li>
 *   <li>WorkBuddy 国内版：`www.codebuddy.cn/login`（`copilot.tencent.com` 是 API 域名，
 *       直接开那个会得到一段 JSON 而不是登录页）</li>
 *   <li>Qoder / 中国版：`qoder.com` / `qoder.cn`（授权入口在 `/device/selectAccounts`，
 *       但那里未登录会 302 到 `/users/sign-in`，所以给根域名最稳）</li>
 *   <li>ZCode：`zcode.z.ai`（登录后由 bigmodel 门户下发 code）</li>
 *   <li>Raccoon：`xiaohuanxiong.com/login`（手机号 / 微信扫码都在这一页）</li>
 * </ul>
 *
 * <h3>这个入口有什么用</h3>
 * 两个真实用途：
 * <ol>
 *   <li>App 内登录偶发失败时，去官网确认「账号本身能不能登」；</li>
 *   <li>ZCode / Qoder 这类需要 token 的场景，官网登录后可以从浏览器拿到凭据再粘回来。</li>
 * </ol>
 */
public final class LoginPage {

    /** 渠道 → 官方登录页。 */
    private static final Map<String, String> PAGES = new LinkedHashMap<>();

    static {
        PAGES.put(LoginProviders.WB_INTL, "https://www.workbuddy.ai/login");
        PAGES.put(LoginProviders.WB_CN, "https://www.codebuddy.cn/login");
        PAGES.put(LoginProviders.QODER, "https://qoder.com/users/sign-in");
        PAGES.put(LoginProviders.QODER_CN, "https://qoder.cn/users/sign-in");
        PAGES.put(LoginProviders.ZCODE, "https://zcode.z.ai/");
        PAGES.put(LoginProviders.RACCOON, "https://xiaohuanxiong.com/login");
        // 华为 CodeArts 的登录入口是华为云统一认证页
        PAGES.put(LoginProviders.CODEARTS, "https://codearts.huaweicloud.com/portal/login");
    }

    private LoginPage() {
    }

    /**
     * 该渠道的官方登录页。
     *
     * @param loginType 渠道类型
     * @return 可打开的 https 地址；未知渠道返回空串
     */
    public static String urlOf(String loginType) {
        if (loginType == null) return "";
        String url = PAGES.get(loginType);
        return url == null ? "" : url;
    }

    /** 该渠道登录页的主机名（界面显示用，比整条 URL 省地方）。 */
    public static String hostOf(String loginType) {
        String url = urlOf(loginType);
        if (url.isEmpty()) return "";
        try {
            java.net.URL u = new java.net.URL(url);
            String host = u.getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            return "";
        }
    }

    /** 是否有可打开的登录页。 */
    public static boolean has(String loginType) {
        return !urlOf(loginType).isEmpty();
    }
}
