package com.wbgateway;

/**
 * ZCode 令牌选择的验证桥接。
 *
 * <p>这条链路的 bug 极其隐蔽：代码不报错、登录也「成功」，只是**用错了字段**，
 * 于是额度/签到永远 401。用户看到的是「重新登录也没用」——
 * 因为问题根本不在登录，而在拿 bigmodel 令牌去调 zcode-plan 接口。
 *
 * <p>实测判据（2026-11-04）：
 * <pre>
 * zcode_jwt → 200 {"code":0}
 * bigmodel  → 401
 * </pre>
 */
public final class ZcodeTokenProbe {

    private ZcodeTokenProbe() {
    }

    /** 构造一个「像 ZCode zcode-plan JWT」的假令牌（载荷含 user_id + token_version）。 */
    public static String fakeZcodeJwt() {
        return jwt("{\"user_id\":\"23391788591088955\",\"token_version\":0,"
                + "\"sub\":\"23391788591088955\",\"iat\":1791631058}");
    }

    /** 构造一个「像 bigmodel 业务令牌」的假令牌（payload 无上面两个字段）。 */
    public static String fakeBigmodelToken() {
        return jwt("{\"iss\":\"bigmodel\",\"exp\":1821332741,\"sub\":\"acc-1\"}");
    }

    private static String jwt(String payloadJson) {
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String body = b64(payloadJson);
        return header + "." + body + ".sig";
    }

    private static String b64(String s) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 见 {@link Credits#pickZcodeJwtFromAccess(String)}。 */
    public static String pickJwtFromAccess(String accessToken) {
        return Credits.pickZcodeJwtFromAccess(accessToken);
    }

    /** 见 {@link Checkin#zcodeAuth(Channel)}：返回实际会用来发请求的令牌。 */
    public static String authToken(Channel channel) {
        return Checkin.zcodeAuth(channel).accessToken;
    }

    /** 造一个渠道。 */
    public static Channel channel(String loginType, String accessToken, String zcodeJwt) {
        Channel c = new Channel();
        c.id = loginType;
        c.loginType = loginType;
        c.kind = Channel.KIND_LOGIN;
        c.accessToken = accessToken == null ? "" : accessToken;
        c.zcodeJwt = zcodeJwt == null ? "" : zcodeJwt;
        return c;
    }
}
