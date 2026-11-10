package com.wbgateway;

/**
 * 一个 Qoder 模型的探测结果。
 *
 * <p>⚠ 独立顶层类，不是 QoderProbe 的内部类：d8 处理嵌套类型会内部崩溃
 * （`Cannot invoke String.length() because parameter1 is null`），
 * 这个项目的构建链不能打包任何嵌套 / 合成类型。
 */
final class QoderProbeResult {
    final String model;
    /** 取值见 QoderProbe 的 OK / QUEUED / … 常量。 */
    final int status;
    /** 给用户看的一句话；可用时为空。 */
    final String reason;

    QoderProbeResult(String model, int status, String reason) {
        this.model = model;
        this.status = status;
        this.reason = reason;
    }

    /** 这个模型此刻是否能调用。 */
    boolean usable() {
        return status == QoderProbe.OK;
    }

    /** 是否该在界面上标「繁忙」。 */
    boolean queued() {
        return status == QoderProbe.QUEUED;
    }
}