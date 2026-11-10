package com.wbgateway;

/**
 * 上游「伪成功」响应的识别（**纯逻辑，零 Android 依赖**）。
 *
 * <h3>为什么需要</h3>
 *
 * 有的上游把错误放在 **SSE 事件**里，HTTP 状态仍是 200。实测（Qoder）：
 *
 * <pre>
 * POST /model/v1/chat/completions   {"model":"cmodel", …}
 * ← HTTP/1.1 200 OK
 *   event: error
 *   data: {"code":"invalid_model_error","message":"Unsupported model \"cmodel\""}
 * </pre>
 *
 * 如果网关原样透传，客户端会收到一个它不认识的 `event: error` —— 多数 OpenAI
 * 客户端会**静默忽略**，表现为「请求成功但没有任何输出」，比明确的报错更难查。
 *
 * <p>所以这里识别「首块就是 error 事件」的形态，由网关改写成正常的错误响应。
 * 只认**明确的** `event: error` 或 `data: {"error":…}`，不靠关键字猜 ——
 * 正常正文里完全可能出现 "error" 这个词（比如模型在解释报错），误判会把
 * 一次正常回答吞掉。
 */
public final class UpstreamError {

    private UpstreamError() {
    }

    /**
     * 首块是不是「SSE 错误事件」。
     *
     * @param head 响应体开头若干字节（UTF-8 文本）
     */
    public static boolean isSseErrorEvent(String head) {
        if (head == null || head.isEmpty()) return false;
        String text = head.trim();
        if (text.isEmpty()) return false;
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            // `:` 开头是 SSE 注释 / 心跳（WorkBuddy 实测回 `: heartbeat`），跳过继续看
            if (l.startsWith(":")) continue;
            if ("event: error".equals(l) || "event:error".equals(l)) return true;
            // 明确是别的事件类型 → 这是正常流，不必再往下看
            if (l.startsWith("event:")) return false;
            if (l.startsWith("data:")) return dataIsError(l.substring("data:".length()).trim());
        }
        return false;
    }

    /**
     * `data:` 这一行是不是错误载荷。
     *
     * <p>只认两种：`{"error": …}` 顶层字段，或 `{"code": "<非数字错误码>"}`
     * 且**没有 choices**。有 choices 的一律当正常响应。
     */
    static boolean dataIsError(String json) {
        if (json == null || json.isEmpty()) return false;
        if (!json.startsWith("{")) return false;
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);
            if (obj.has("choices")) return false;         // 正常分片优先
            if (obj.has("error")) return true;
            Object code = obj.opt("code");
            // Qoder 的错误码是字符串（"invalid_model_error"）；数字 code 是别的语义
            return code instanceof String && !((String) code).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从错误事件里抽出给用户看的一句话。
     *
     * @return 形如 `上游拒绝该请求：Unsupported model "cmodel"（invalid_model_error）`
     */
    public static String describeSseError(String head) {
        String text = head == null ? "" : head;
        String code = "";
        String message = "";

        // 两种输入都要能吃：带 `data:` 前缀的 SSE 事件流，以及**裸 JSON**。
        // Qoder 的信封里 `body` 字段本身就是一段 JSON 文本（没有 `data:` 前缀），
        // 旧实现只认前者，于是所有 Qoder 业务错误都被压成「没给出原因」——
        // 用户看不到到底是没有权限还是模型不存在。
        java.util.List<String> candidates = new java.util.ArrayList<String>();
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("data:")) l = l.substring("data:".length()).trim();
            if (l.startsWith("{")) candidates.add(l);
        }
        // 整体也当成一个候选（多行 JSON 或单行裸 JSON）
        String whole = text.trim();
        if (whole.startsWith("{") && !candidates.contains(whole)) candidates.add(whole);

        for (String json : candidates) {
            if (!json.startsWith("{")) continue;
            try {
                org.json.JSONObject obj = new org.json.JSONObject(json);
                // Qoder 的信封：真正的错误在 `body` 里，而 body 又是一段
                // **转义过的 JSON 字符串**。只看外层会一个字段都取不到，
                // 于是退化成「没给出原因」—— 用户看不到到底为什么失败。
                String inner = obj.optString("body", "");
                if (!inner.isEmpty() && inner.trim().startsWith("{")) {
                    obj = new org.json.JSONObject(inner.trim());
                }
                String c = obj.optString("code", "");
                String m = obj.optString("message", "");
                String sub = obj.optString("subCode", "");
                if (m.isEmpty()) {
                    org.json.JSONObject err = obj.optJSONObject("error");
                    if (err != null) m = err.optString("message", "");
                }
                if (!c.isEmpty()) code = c;
                if (!m.isEmpty()) message = m;
                // message 自身也可能是嵌套 JSON，从中再挖一层可读原因
                if (m.trim().startsWith("{")) {
                    try {
                        org.json.JSONObject deep = new org.json.JSONObject(m.trim());
                        String dm = deep.optString("message", "");
                        String ds = deep.optString("subCode", sub);
                        if (!ds.isEmpty()) sub = ds;
                        if (!dm.isEmpty() && dm.trim().startsWith("{")) {
                            org.json.JSONObject deeper = new org.json.JSONObject(dm.trim());
                            String sa = deeper.optString("subCode", "");
                            if (!sa.isEmpty()) sub = sa;
                            dm = "";
                        }
                        if (!dm.isEmpty()) message = dm;
                    } catch (Exception ignored2) {
                    }
                }
                if (code.isEmpty() && !sub.isEmpty()) code = sub;
                if (!code.isEmpty() || !message.isEmpty()) break;
            } catch (Exception ignored) {
            }
        }
        if (message.isEmpty() && code.isEmpty()) {
            // 完全没有结构化信息时，至少把原文抖出来一点，别让用户面对空白
            String snip = text.replaceAll("\\s+", " ").trim();
            if (snip.isEmpty()) return "上游返回了错误事件，但没给出原因";
            if (snip.length() > 200) snip = snip.substring(0, 200) + "…";
            return "上游返回了错误事件：" + snip;
        }
        // 排队要和「模型不存在」「后端不可用」分开说，否则用户会去查错方向。
        //
        // ⚠ 这里曾经判成「需要订阅套餐」，是错的：10605 是**排队码**
        //   （参考实现里就叫 QUEUE_BUSINESS_CODE）。实测（2026-11-10）
        //   kmodel_latest 连打 5 轮全部出正文 —— 它只是那一刻在排队，
        //   过一会儿就通，跟套餐无关。把它说成「要付费」会把用户引去升级套餐。
        if (isQueued(text)) {
            String wait = extractRetryAfter(text);
            return "上游正在排队（模型繁忙，不是额度/套餐问题）"
                    + (wait.isEmpty() ? "" : "，建议 " + wait + " 后重试")
                    + "；也可以换一个模型。上游业务码 10605 / RESOURCE_LIMIT";
        }
        StringBuilder sb = new StringBuilder("上游拒绝该请求");
        if (!message.isEmpty()) sb.append("：").append(message);
        if (!code.isEmpty()) sb.append("（").append(code).append("）");
        return sb.toString();
    }

    /**
     * 判断错误是否属于**排队**（模型繁忙）。
     *
     * <p>Qoder 的加密推理端点在排队时回：
     * <pre>{"code":"10605","subCode":"RESOURCE_LIMIT",
     *  "message":"{…\"isQueued\":false,\"serviceAvailable\":false,
     *             \"retryAfterSeconds\":30}"}</pre>
     *
     * <p>`10605` 就是参考实现里的 `QUEUE_BUSINESS_CODE`。**它表示排队，
     * 不表示「需要订阅套餐」**：实测同一账号连打 5 轮全部正常出正文，
     * 说明只是那一刻模型队列满了。
     *
     * <p>识别要点：
     * <ul>
     *   <li>有 `10605` / `RESOURCE_LIMIT` 就算排队；</li>
     *   <li>`isQueued:true` 是明确排队；`isQueued:false` + `serviceAvailable:false`
     *       是「该模型此刻没排队但不可用」—— 同样属于瞬时状态，可重试；</li>
     *   <li>**不能**据此说用户要付费：额度够用时也会出现。</li>
     * </ul>
     */
    static boolean isQueued(String text) {
        if (text == null) return false;
        String t = text;
        if (t.contains("10605")) return true;
        if (t.contains("RESOURCE_LIMIT")) return true;
        if (t.contains("isQueued\":true") || t.contains("isQueued\\\":true")) return true;
        return false;
    }

    /** 从错误体里抠出 `retryAfterSeconds`，返回给人看的「30 秒」；没有则空串。 */
    static String extractRetryAfter(String text) {
        if (text == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("retryAfterSeconds[\\\\\\\"]*\\s*:\\s*(\\d+)").matcher(text);
        if (!m.find()) return "";
        int sec = 0;
        try {
            sec = Integer.parseInt(m.group(1));
        } catch (Exception e) {
            return "";
        }
        if (sec <= 0) return "";
        if (sec < 60) return sec + " 秒";
        return (sec / 60) + " 分钟";
    }
}