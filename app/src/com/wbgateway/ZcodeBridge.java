package com.wbgateway;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * OpenAI ⇄ Anthropic Messages 的协议转换（**纯逻辑，零 Android 依赖**）。
 *
 * <h3>为什么需要它</h3>
 *
 * ZCode 的上游只支持 Anthropic 协议：`POST /api/v1/zcode-plan/anthropic/v1/messages`
 * （其余路径实测 404）。而网关对外承诺的是 **OpenAI 兼容**。两边结构完全不同：
 *
 * <pre>
 * 请求 OpenAI                         请求 Anthropic
 * {model, messages:[{role,content}],  {model, max_tokens,
 *  max_tokens, stream}                 system: "…",
 *                                      messages:[{role,content:[{type:"text",text}]}],
 *                                      stream}
 *
 * 响应 choices[].delta.content       响应 content_block_delta → delta.text
 * 结束 data: [DONE]                  结束 message_stop
 * </pre>
 *
 * 只改 URL 不改结构，上游会直接 400/404 —— 这是最容易踩的坑，所以转换单独成类，
 * 并逐条断言（`verify/VerifyZcode.java`）。
 */
public final class ZcodeBridge {

    private ZcodeBridge() {
    }

    /** Anthropic 的默认 max_tokens（上游必填；客户端没给时用这个）。 */
    private static final int DEFAULT_MAX_TOKENS = 8192;

    // -----------------------------------------------------------------------
    // 请求：OpenAI → Anthropic
    // -----------------------------------------------------------------------

    /**
     * 把 OpenAI 请求体转成 Anthropic Messages 请求体。
     *
     * <p>三条必须处理的差异：
     * <ol>
     *   <li>`system` 是**顶层字段**（不是 messages 里的一条）；</li>
     *   <li>`content` 必须是**块数组**（`[{type:"text",text}]`），纯字符串会被拒；</li>
     *   <li>`max_tokens` 必填，客户端没给时要补默认值 —— 缺了上游直接 400。</li>
     * </ol>
     *
     * @param openai 客户端原始请求
     * @return Anthropic 请求体（`stream` 跟随入参）
     */
    public static String toAnthropicRequest(JSONObject openai) throws Exception {
        if (openai == null) throw new IllegalArgumentException("body is null");

        JSONObject out = new JSONObject();
        out.put("model", openai.optString("model", ""));

        int maxTokens = openai.optInt("max_tokens", 0);
        if (maxTokens <= 0) maxTokens = DEFAULT_MAX_TOKENS;
        out.put("max_tokens", maxTokens);

        JSONArray messages = new JSONArray();
        JSONArray systemParts = new JSONArray();

        JSONArray src = openai.optJSONArray("messages");
        if (src != null) {
            for (int i = 0; i < src.length(); i += 1) {
                JSONObject m = src.optJSONObject(i);
                if (m == null) continue;
                String role = m.optString("role", "user");
                String text = contentToText(m.opt("content"));
                if (text.isEmpty()) continue;

                if ("system".equals(role)) {
                    // Anthropic 的 system 是顶层；多条时按顺序拼接（保留语义）
                    systemParts.put(text);
                    continue;
                }
                // Anthropic 只认 user / assistant；别的角色（如 tool）降级为 user，
                // 否则上游会因未知 role 直接 400。
                String mapped = "assistant".equals(role) ? "assistant" : "user";
                JSONObject one = new JSONObject();
                one.put("role", mapped);
                JSONArray blocks = new JSONArray();
                JSONObject block = new JSONObject();
                block.put("type", "text");
                block.put("text", text);
                blocks.put(block);
                one.put("content", blocks);
                messages.put(one);
            }
        }
        if (messages.length() == 0) {
            // 上游要求至少一条 messages；给个空 user 比 400 好
            JSONObject one = new JSONObject();
            one.put("role", "user");
            JSONArray blocks = new JSONArray();
            JSONObject block = new JSONObject();
            block.put("type", "text");
            block.put("text", "");
            blocks.put(block);
            one.put("content", blocks);
            messages.put(one);
        }

        if (systemParts.length() > 0) out.put("system", joinTexts(systemParts, "\n\n"));
        out.put("messages", messages);
        if (openai.optBoolean("stream", false)) out.put("stream", true);

        // 透传常见可选参数（不回传 None，避免上游报「未知字段」）
        if (openai.has("temperature")) out.put("temperature", openai.optDouble("temperature", 1));
        if (openai.has("top_p")) out.put("top_p", openai.optDouble("top_p", 1));
        JSONArray stop = openai.optJSONArray("stop");
        if (stop != null && stop.length() > 0) {
            JSONArray seq = new JSONArray();
            for (int i = 0; i < stop.length(); i += 1) {
                String s = stop.optString(i, "");
                if (!s.isEmpty()) seq.put(s);
            }
            if (seq.length() > 0) out.put("stop_sequences", seq);
        }
        return out.toString();
    }

    /** 把 OpenAI 的 content（字符串或块数组）压成纯文本。 */
    private static String contentToText(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            JSONArray arr = (JSONArray) content;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i += 1) {
                JSONObject part = arr.optJSONObject(i);
                if (part == null) {
                    String s = arr.optString(i, "");
                    if (!s.isEmpty()) sb.append(s);
                    continue;
                }
                String type = part.optString("type", "text");
                if ("text".equals(type)) {
                    sb.append(part.optString("text", ""));
                } else if ("image_url".equals(type)) {
                    // 图片在这条链路暂不支持（ZCode 的 plan 端点按文本处理），
                    // 明确留一个标记而不是静默丢弃，便于排查
                    sb.append("[图片已省略]");
                }
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    private static String joinTexts(JSONArray arr, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i += 1) {
            if (i > 0) sb.append(sep);
            sb.append(arr.optString(i, ""));
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // 响应：Anthropic → OpenAI
    // -----------------------------------------------------------------------

    /**
     * 非流式响应转换。
     *
     * <p>Anthropic 的 `content` 是块数组，`usage` 是 `input_tokens` / `output_tokens`；
     * OpenAI 要 `choices[0].message.content` 与 `usage.prompt_tokens` / `completion_tokens`。
     */
    public static JSONObject anthropicToOpenAi(JSONObject anthropic, String model) {
        JSONObject out = new JSONObject();
        try {
            out.put("id", anthropic.optString("id", "chatcmpl-zcode"));
            out.put("object", "chat.completion");
            out.put("created", System.currentTimeMillis() / 1000);
            out.put("model", model == null || model.isEmpty()
                    ? anthropic.optString("model", "") : model);

            String text = extractText(anthropic.optJSONArray("content"));

            JSONObject message = new JSONObject();
            message.put("role", "assistant");
            message.put("content", text);

            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("finish_reason", mapFinishReason(anthropic.optString("stop_reason", "")));
            JSONArray choices = new JSONArray();
            choices.put(choice);
            out.put("choices", choices);

            JSONObject usage = anthropic.optJSONObject("usage");
            int in = usage == null ? 0 : usage.optInt("input_tokens", 0);
            int outTok = usage == null ? 0 : usage.optInt("output_tokens", 0);
            JSONObject u = new JSONObject();
            u.put("prompt_tokens", in);
            u.put("completion_tokens", outTok);
            u.put("total_tokens", in + outTok);
            out.put("usage", u);
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 把 Anthropic 的 content 块数组拼成文本。 */
    private static String extractText(JSONArray content) {
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i += 1) {
            JSONObject block = content.optJSONObject(i);
            if (block == null) continue;
            if ("text".equals(block.optString("type", ""))) {
                sb.append(block.optString("text", ""));
            }
        }
        return sb.toString();
    }

    /** Anthropic 的 stop_reason → OpenAI 的 finish_reason。 */
    static String mapFinishReason(String stopReason) {
        if ("end_turn".equals(stopReason) || "stop_sequence".equals(stopReason)) return "stop";
        if ("max_tokens".equals(stopReason)) return "length";
        if ("tool_use".equals(stopReason)) return "tool_calls";
        return "stop";
    }

    // -----------------------------------------------------------------------
    // 流式：Anthropic SSE → OpenAI SSE
    // -----------------------------------------------------------------------

    /**
     * 把 Anthropic 的 SSE 事件流整体转成 OpenAI 的 SSE 文本。
     *
     * <p>为什么整段转换而不是逐行流式：上游一次响应几十 KB，整段读进来的代价
     * 远小于「跨事件边界拼接」的复杂度；且转换本身是纯字符串处理，不引入延迟
     *  —— 真正的延迟在等上游，不在这一层。
     *
     * <p>事件映射：
     * <pre>
     * message_start          → 首个 chunk（带 role）
     * content_block_delta     → choices[].delta.content
     * message_delta           → 带 finish_reason 的 chunk
     * message_stop            → data: [DONE]
     * </pre>
     * `ping` / `content_block_start` / `content_block_stop` 直接丢弃（OpenAI 无对应）。
     */
    public static String anthropicStreamToOpenAi(String sse, String model) {
        StringBuilder out = new StringBuilder();
        String id = "chatcmpl-zcode";
        long created = System.currentTimeMillis() / 1000;
        String shownModel = model == null ? "" : model;
        boolean roleSent = false;
        int promptTokens = 0;
        int completionTokens = 0;
        String stopReason = "";
        boolean sawStop = false;

        for (String line : sse.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) continue;
            String payload = trimmed.substring("data:".length()).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) continue;

            JSONObject evt;
            try {
                evt = new JSONObject(payload);
            } catch (Exception e) {
                continue;
            }
            String type = evt.optString("type", "");
            JSONObject message = evt.optJSONObject("message");
            if ("message_start".equals(type) && message != null) {
                id = message.optString("id", id);
                if (shownModel.isEmpty()) shownModel = message.optString("model", "");
                JSONObject usage = message.optJSONObject("usage");
                if (usage != null) promptTokens = usage.optInt("input_tokens", 0);
                if (!roleSent) {
                    JSONObject delta = new JSONObject();
                    try {
                        delta.put("role", "assistant");
                        delta.put("content", "");
                    } catch (Exception ignored) {
                    }
                    appendChunk(out, id, created, shownModel, delta, null);
                    roleSent = true;
                }
                continue;
            }
            if ("content_block_delta".equals(type)) {
                JSONObject d = evt.optJSONObject("delta");
                String text = d == null ? "" : d.optString("text", "");
                if (text.isEmpty()) continue;
                JSONObject delta = new JSONObject();
                try {
                    delta.put("content", text);
                } catch (Exception ignored) {
                }
                appendChunk(out, id, created, shownModel, delta, null);
                continue;
            }
            if ("message_delta".equals(type)) {
                JSONObject d = evt.optJSONObject("delta");
                if (d != null) stopReason = d.optString("stop_reason", stopReason);
                JSONObject usage = evt.optJSONObject("usage");
                if (usage != null) completionTokens = usage.optInt("output_tokens", 0);
                JSONObject delta = new JSONObject();
                appendChunk(out, id, created, shownModel, delta, mapFinishReason(stopReason));
                continue;
            }
            if ("message_stop".equals(type)) {
                sawStop = true;
            }
        }
        if (completionTokens > 0 || promptTokens > 0) {
            // OpenAI 客户端会在最后一个 chunk 里读 usage（stream_options 打开时）。
            // ⚠ 必须排在 `[DONE]` **之前**：服务端一看到 [DONE] 就结束读取，
            // 后面的 usage 会被丢掉（客户端表现为「用量恒为 0」）。
            JSONObject usage = new JSONObject();
            try {
                usage.put("prompt_tokens", promptTokens);
                usage.put("completion_tokens", completionTokens);
                usage.put("total_tokens", promptTokens + completionTokens);
                JSONObject chunk = new JSONObject();
                chunk.put("id", id);
                chunk.put("object", "chat.completion.chunk");
                chunk.put("created", created);
                chunk.put("model", shownModel);
                chunk.put("choices", new JSONArray());
                chunk.put("usage", usage);
                out.append("data: ").append(chunk.toString()).append("\n\n");
            } catch (Exception ignored) {
            }
        }
        if (sawStop) out.append("data: [DONE]\n\n");
        return out.toString();
    }

    private static void appendChunk(StringBuilder out, String id, long created, String model,
                                    JSONObject delta, String finishReason) {
        try {
            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("delta", delta);
            // finish_reason 只在最后一帧给值；中间帧**不写该字段**。
            // （不用 JSONObject.NULL：桌面验证用的 org.json 替身没有这个常量，
            //   而快照行为应与 Android 一致 —— 省略字段是两边都对的写法。）
            if (finishReason != null) {
                choice.put("finish_reason", finishReason);
            }
            JSONArray choices = new JSONArray();
            choices.put(choice);
            JSONObject chunk = new JSONObject();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", created);
            chunk.put("model", model);
            chunk.put("choices", choices);
            out.append("data: ").append(chunk.toString()).append("\n\n");
        } catch (Exception ignored) {
        }
    }

    /** 把上游错误说得更像人话。 */
    public static String describeError(int status, String body) {
        String text = body == null ? "" : body.trim();
        if (status == 401 || status == 403) {
            return "ZCode 凭据无效或已过期（HTTP " + status + "），请重新登录";
        }
        if (text.isEmpty()) return "ZCode 上游返回 HTTP " + status;
        return "ZCode 上游返回 HTTP " + status + "：" + text;
    }
}
