package com.wbgateway;

/**
 * 验证桥接：把 {@link Gateway#chatBase} 暴露出来。
 *
 * <p>它是包内可见的（不对外 API），但「缺 Base URL 时兜底到哪」是这次修 bug
 * 的核心判定，值得单独断言 —— 错了会重新退回「no protocol」那种难排查的报错。
 */
public final class GatewayProbe {

    private GatewayProbe() {
    }

    /** 见 {@link Gateway#chatBase(Channel)}。 */
    public static String chatBase(Channel channel) {
        return Gateway.chatBase(channel);
    }
}