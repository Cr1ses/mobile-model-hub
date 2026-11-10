package com.wbgateway;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Qoder 模型可用性的**运行时探测**。
 *
 * <p>为什么必须探测而不能写死：同一账号的模型可用性会随额度来回变 ——
 * 2026-11-05 实测 `qfmodel` 被套餐挡住、`kmodel_latest` 能用；
 * 2026-11-10 反过来（`qfmodel` 能用、`kmodel_latest` 回 RESOURCE_LIMIT）。
 * 静态清单每次都要跟着改，改慢了界面就在骗人。
 *
 * <p>所以这里发**真实请求**（每一次都会消耗少量上游额度），把结果分成四类：
 * <ul>
 *   <li>{@link Status#OK} 能出正文 —— 直接可用；</li>
 *   <li>{@link Status#QUEUED} 回 10605 / RESOURCE_LIMIT —— 标「繁忙」（排队，不是缺额度）；</li>
 *   <li>{@link Status#NOT_AVAILABLE} 模型不存在 / 后端故障 —— 不标订阅，如实说不可用；</li>
 *   <li>{@link Status#UNKNOWN} 网络错、超时、凭据问题 —— 不下结论。</li>
 * </ul>
 *
 * <p>⚠ 探测是**串行**的：并发打同一账号容易触发上游限流，反而测不准。
 * 调用方应在后台线程里跑，别阻塞界面。
 */
final class QoderProbe {

    // ⚠ 状态码用 int 常量而不是 enum：d8 处理嵌套 enum 会内部崩溃
    // （`Cannot invoke String.length() because parameter1 is null`），
    // 这个项目的构建链不能打包任何嵌套类型。
    /** 能出正文。 */
    static final int OK = 0;
    /** 回 RESOURCE_LIMIT（业务码 10605 / serviceAvailable:false）。 */
    static final int QUEUED = 1;
    /** 模型不存在（invalid_model_error）。 */
    static final int MODEL_GONE = 2;
    /** 上游后端故障（provider_error / 429）。 */
    static final int BACKEND_DOWN = 3;
    /** 无法判定（网络、超时、凭据）。 */
    static final int UNKNOWN = 4;

    private QoderProbe() {}

    /**
     * 逐个探测该区域的模型。
     *
     * @param channel  渠道（要带齐 token / uid / machineId）
     * @param models   要探测的模型 id
     * @return id → 结果（保序，与入参一致）
     */
    static Map<String, QoderProbeResult> probeAll(Channel channel, List<String> models) {
        Map<String, QoderProbeResult> out = new LinkedHashMap<>();
        if (channel == null) return out;
        for (String id : models) {
            if (id == null || id.isEmpty()) continue;
            out.put(id, probeOne(channel, id));
        }
        return out;
    }

    /**
     * 探测单个模型。
     *
     * <p>请求体故意做到最小（`max_tokens=1`、一句 `hi`）：探测只关心
     * 「上游放不放行」，不关心回答质量，越省额度越好。
     */
    static QoderProbeResult probeOne(Channel channel, String model) {
        String base = LoginProviders.qoderAlgoBase(channel.loginType);
        if (base.isEmpty()) return new QoderProbeResult(model, UNKNOWN, "未知的 Qoder 区域");

        String uid = channel.uid == null ? "" : channel.uid;
        String token = channel.accessToken == null ? "" : channel.accessToken;
        if (uid.isEmpty() || token.isEmpty()) {
            return new QoderProbeResult(model, UNKNOWN, "凭据不完整（缺 uid 或 token）");
        }
        String machineId = channel.machineId == null || channel.machineId.isEmpty()
                ? uid : channel.machineId;

        String url = base + LoginProviders.QODER_ALGO_PATH;
        HttpURLConnection conn = null;
        try {
            // 探测也要带真实规格：否则上游按缺省值选后端，探到的可用性
            // 可能和真实请求不一致（小上下文模型会被当成大模型路由）。
            String inferBody = Gateway.buildQoderInferBody(model,
                    "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\","
                            + "\"content\":\"hi\"}],\"max_tokens\":1}",
                    channel.modelSpecs.get(model));
            String encoded = QoderCosy.cosyEncode(inferBody.getBytes("UTF-8"));

            QoderCosy.Session session = QoderCosy.newSession(
                    uid, channel.nickname, token, machineId, machineId, "5");
            String payloadB64 = QoderCosy.buildPayloadB64(session.info);
            String date = QoderCosy.rfc1123();
            String sig = QoderCosy.signRequest(payloadB64, session.cosyKey, date,
                    encoded, QoderCosy.pathSigFrom(url));

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(45_000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Authorization", QoderCosy.composeBearer(payloadB64, sig));
            conn.setRequestProperty("cosy-key", session.cosyKey);
            conn.setRequestProperty("cosy-user", uid);
            conn.setRequestProperty("cosy-machineid", machineId);
            conn.setRequestProperty("cosy-machinetoken", machineId);
            conn.setRequestProperty("cosy-machinetype", "5");
            conn.setRequestProperty("cosy-clienttype", "5");
            conn.setRequestProperty("cosy-business-product", "cli");
            conn.setRequestProperty("cosy-business-type", "agent");
            conn.setRequestProperty("cosy-scene", "assistant");
            conn.setRequestProperty("cosy-version", QoderCosy.COSY_VERSION);
            conn.setRequestProperty("cosy-date", date);
            conn.setRequestProperty("X-Model-Key", model);
            conn.setRequestProperty("X-Model-Source", "system");

            OutputStream os = conn.getOutputStream();
            os.write(encoded.getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            // 上游把业务错误包在 200 的 SSE 里，所以非 200 才是网络层问题
            if (code != 200) {
                return new QoderProbeResult(model, UNKNOWN, "上游 HTTP " + code);
            }
            String text = readSome(conn.getInputStream(), 4096);
            return classify(model, text);
        } catch (java.net.SocketTimeoutException e) {
            return new QoderProbeResult(model, UNKNOWN, "探测超时");
        } catch (Exception e) {
            return new QoderProbeResult(model, UNKNOWN, "探测失败：" + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 按上游响应分类。
     *
     * <p>判定顺序很重要：先看**成功**再看错误 —— 有的模型会先吐一两个
     * 正常帧、后面才跟 error 事件，那种应当算可用（用户至少拿到了正文）。
     */
    static QoderProbeResult classify(String model, String text) {
        String t = text == null ? "" : text;

        boolean hasChoices = t.contains("choices");
        boolean hasError = t.contains("\"error\"") || t.contains("event:error")
                || t.contains("event: error");

        if (hasChoices && !hasError) {
            return new QoderProbeResult(model, OK, "");
        }
        if (t.contains("invalid_model")) {
            return new QoderProbeResult(model, MODEL_GONE,
                    "上游没有这个模型（invalid_model_error）");
        }
        if (UpstreamError.isQueued(t)) {
            return new QoderProbeResult(model, QUEUED, "模型繁忙，稍后重试");
        }
        if (t.contains("provider_error")) {
            return new QoderProbeResult(model, BACKEND_DOWN, "上游后端暂不可用（provider_error）");
        }
        if (hasError) {
            return new QoderProbeResult(model, BACKEND_DOWN,
                    UpstreamError.describeSseError(t));
        }
        if (hasChoices) {
            return new QoderProbeResult(model, OK, "");
        }
        return new QoderProbeResult(model, UNKNOWN, "无法识别上游响应");
    }

    /** 只读前 n 字节：探测不需要整条流。 */
    private static String readSome(InputStream in, int limit) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[2048];
            int n;
            int total = 0;
            while (total < limit && (n = in.read(tmp)) > 0) {
                buf.write(tmp, 0, n);
                total += n;
                // 读到足够判断就停：往下读会白等整条流
                String s = new String(buf.toByteArray(), "UTF-8");
                if (s.contains("invalid_model") || s.contains("RESOURCE_LIMIT")
                        || s.contains("provider_error")) break;
            }
            return new String(buf.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }
}
