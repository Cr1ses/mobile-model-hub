package com.wbgateway;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 桌面验证用的桥接层。
 *
 * <p>为什么需要它：{@link ChannelLogin} 里的 `sha256Base64Url` 用 `android.util.Base64`，
 * 桌面 JVM 没有那个类。这里按同一份规范重写算法供验证用 —— 两边必须逐字符一致，
 * 否则手机上 PKCE 对不上（表现为「浏览器登录完了，轮询永远拿不到 token」）。
 *
 * <p>验证侧同时断言：长度 43、base64url 字符集、同一 verifier 输出稳定。
 */
public final class ChannelLoginProbe {

    private ChannelLoginProbe() {
    }

    /** 与 {@link ChannelLogin#extractCode(String)} 同一实现（桌面可跑）。 */
    public static String extractCode(String text) {
        return ChannelLogin.extractCode(text);
    }

    /** 与 {@link ChannelLogin#inferBaseUrl(String)} 同一实现。 */
    public static String inferBaseUrl(String loginType) {
        return ChannelLogin.inferBaseUrl(loginType);
    }

    /** 与 {@link ChannelLogin#randomPkceVerifier()} 同一实现。 */
    public static String randomPkceVerifier() {
        return ChannelLogin.randomPkceVerifier();
    }

    /** 与 {@link ChannelLogin#readTimestamp(Object)} 同一实现。 */
    public static long readTimestamp(Object raw) {
        return ChannelLogin.readTimestamp(raw);
    }

    /**
     * 把某个渠道的 `openApiBase` 临时指到假上游。
     *
     * <p>`Spec.openApiBase` 刻意**不是 final**：验证需要把登录端点指到本地假上游，
     * 真机上也可能要换环境。所以这里直接赋值，不用反射。
     *
     * @param loginType 渠道类型
     * @param baseUrl   新的基址
     */
    public static void overrideOpenApi(String loginType, String baseUrl) {
        LoginProviders.Spec spec = LoginProviders.specOf(loginType);
        if (spec != null) spec.openApiBase = baseUrl;
    }

    /**
     * 与 Android 侧等价地算 `base64url(sha256(text))`（去掉 padding）。
     *
     * <p>现在两侧都走 {@link ChannelLogin#sha256Base64Url(String)} 的纯 Java 实现，
     * 这里保留独立算法做**交叉校验**：两种写法必须逐字符相同，
     * 否则说明手写 base64url 有边界 bug（会直接导致 PKCE 校验失败）。
     */
    public static String sha256Base64Url(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            return "";
        }
    }

    /** 走 App 里那份纯 Java 实现（用于与上面的参考算法对账）。 */
    public static String sha256Base64UrlApp(String text) {
        return ChannelLogin.sha256Base64Url(text);
    }

    /** 见 {@link ChannelLogin#fetchModels(Channel)}。 */
    public static java.util.List<String> fetchModels(Channel channel) throws java.io.IOException {
        return ChannelLogin.fetchModels(channel);
    }
}
