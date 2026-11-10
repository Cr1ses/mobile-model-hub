import com.wbgateway.ZcodeBridge;

import org.json.JSONObject;

/**
 * ZCode 协议转换的离线验证。
 *
 * <p>为什么这个渠道必须单独验：它的上游是 **Anthropic Messages 协议**，
 * 网关对外是 OpenAI 兼容 —— 中间那层转换错一处，症状都是
 * 「能连上但返回空 / 400」，看不出是哪一步。三类错误最容易发生：
 * <ol>
 *   <li>`system` 没提到顶层 → 上游把它当成一条普通消息，语气与约束全丢；</li>
 *   <li>`content` 没包成块数组 → 上游 400；</li>
 *   <li>流式没转换事件类型 → 客户端收到一堆它不认识的 `content_block_delta`，表现为「一直转圈」。</li>
 * </ol>
 */
public class VerifyZcode {

    static int pass = 0;
    static int fail = 0;

    static void check(String label, boolean ok, String detail) {
        if (ok) {
            pass += 1;
            System.out.println("  PASS  " + label + (detail == null || detail.isEmpty() ? "" : " -- " + detail));
        } else {
            fail += 1;
            System.out.println("  FAIL  " + label + (detail == null || detail.isEmpty() ? "" : " -- " + detail));
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 1. 请求：OpenAI → Anthropic ===");
        String openai = "{\"model\":\"GLM-5.3\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"你是助手\"},"
                + "{\"role\":\"user\",\"content\":\"你好\"},"
                + "{\"role\":\"assistant\",\"content\":\"在\"},"
                + "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"继续\"}]}],"
                + "\"max_tokens\":1024,\"stream\":true,\"temperature\":0.7}";
        JSONObject anth = new JSONObject(ZcodeBridge.toAnthropicRequest(new JSONObject(openai)));

        check("system 提到顶层", "你是助手".equals(anth.optString("system", "")),
                anth.optString("system", ""));
        org.json.JSONArray msgs = anth.optJSONArray("messages");
        check("messages 里不再有 system 角色", msgs != null && msgs.length() == 3,
                msgs == null ? "null" : String.valueOf(msgs.length()));
        JSONObject first = msgs == null ? null : msgs.optJSONObject(0);
        check("第一条是 user", first != null && "user".equals(first.optString("role", "")),
                first == null ? "" : first.optString("role", ""));
        org.json.JSONArray blocks = first == null ? null : first.optJSONArray("content");
        check("content 是块数组（不是纯字符串）", blocks != null && blocks.length() == 1, "");
        JSONObject block = blocks == null ? null : blocks.optJSONObject(0);
        check("块类型是 text 且带 text 字段",
                block != null && "text".equals(block.optString("type", ""))
                        && "你好".equals(block.optString("text", "")),
                block == null ? "" : block.toString());
        check("max_tokens 透传", anth.optInt("max_tokens", 0) == 1024, "");
        check("stream 透传", anth.optBoolean("stream", false), "");
        check("temperature 透传", Math.abs(anth.optDouble("temperature", 0) - 0.7) < 1e-9, "");

        // 没给 max_tokens 时必须补默认值（上游必填，缺了直接 400）
        JSONObject noMax = new JSONObject(ZcodeBridge.toAnthropicRequest(new JSONObject(
                "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")));
        check("缺 max_tokens 时补默认值", noMax.optInt("max_tokens", 0) > 0,
                String.valueOf(noMax.optInt("max_tokens", 0)));

        // 未知角色（tool）要降级成 user，否则上游 400
        JSONObject toolRole = new JSONObject(ZcodeBridge.toAnthropicRequest(new JSONObject(
                "{\"model\":\"m\",\"messages\":[{\"role\":\"tool\",\"content\":\"结果\"}]}")));
        JSONObject tm = toolRole.optJSONArray("messages").optJSONObject(0);
        check("未知角色降级为 user", "user".equals(tm.optString("role", "")), tm.optString("role", ""));

        System.out.println();
        System.out.println("=== 2. 响应：Anthropic → OpenAI（非流式）===");
        String anthResp = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"GLM-5.3\",\"content\":[{\"type\":\"text\",\"text\":\"你好！\"}],"
                + "\"stop_reason\":\"end_turn\","
                + "\"usage\":{\"input_tokens\":12,\"output_tokens\":5}}";
        JSONObject oa = ZcodeBridge.anthropicToOpenAi(new JSONObject(anthResp), "GLM-5.3");
        check("object 是 chat.completion", "chat.completion".equals(oa.optString("object", "")),
                oa.optString("object", ""));
        JSONObject choice = oa.optJSONArray("choices").optJSONObject(0);
        check("正文被拼出来", "你好！".equals(choice.optJSONObject("message").optString("content", "")),
                choice.optJSONObject("message").optString("content", ""));
        check("finish_reason 映射成 stop", "stop".equals(choice.optString("finish_reason", "")),
                choice.optString("finish_reason", ""));
        JSONObject usage = oa.optJSONObject("usage");
        check("usage 字段名转成 OpenAI 口径",
                usage.optInt("prompt_tokens", 0) == 12 && usage.optInt("completion_tokens", 0) == 5
                        && usage.optInt("total_tokens", 0) == 17,
                usage.toString());

        JSONObject len = ZcodeBridge.anthropicToOpenAi(new JSONObject(
                "{\"content\":[{\"type\":\"text\",\"text\":\"x\"}],\"stop_reason\":\"max_tokens\"}"), "m");
        check("max_tokens → length",
                "length".equals(len.optJSONArray("choices").optJSONObject(0).optString("finish_reason", "")), "");

        System.out.println();
        System.out.println("=== 3. 响应：Anthropic SSE → OpenAI SSE（流式）===");
        String sse =
                "event: message_start\n"
                        + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_2\",\"model\":\"GLM-5.3\","
                        + "\"usage\":{\"input_tokens\":10}}}\n\n"
                        + "event: content_block_start\n"
                        + "data: {\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                        + "event: content_block_delta\n"
                        + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"你\"}}\n\n"
                        + "event: content_block_delta\n"
                        + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"好\"}}\n\n"
                        + "event: message_delta\n"
                        + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                        + "\"usage\":{\"output_tokens\":2}}\n\n"
                        + "event: message_stop\n"
                        + "data: {\"type\":\"message_stop\"}\n\n";
        String converted = ZcodeBridge.anthropicStreamToOpenAi(sse, "GLM-5.3");
        check("出现 chat.completion.chunk", converted.contains("chat.completion.chunk"), "");
        check("正文分两帧传出", converted.contains("\"content\":\"你\"")
                && converted.contains("\"content\":\"好\""), "");
        check("首帧带 role", converted.contains("\"role\":\"assistant\""), "");
        check("有 finish_reason=stop", converted.contains("\"finish_reason\":\"stop\""), "");
        check("以 [DONE] 结尾", converted.trim().endsWith("data: [DONE]"), "");
        check("不再出现 Anthropic 事件类型",
                !converted.contains("content_block_delta") && !converted.contains("message_stop"),
                "");
        check("usage 带出来", converted.contains("\"total_tokens\":12"),
                converted.contains("\"total_tokens\"") ? "有" : "无");
        // 每帧都是合法的 data: 行
        int dataLines = 0;
        for (String line : converted.split("\n")) {
            if (line.startsWith("data: {")) dataLines += 1;
        }
        check("每帧都可被客户端解析", dataLines >= 4, dataLines + " 帧");

        System.out.println();
        System.out.println("=== 4. 错误说明 ===");
        check("401 说成人话",
                ZcodeBridge.describeError(401, "").contains("请重新登录"),
                ZcodeBridge.describeError(401, ""));
        check("空响应体不崩", !ZcodeBridge.describeError(500, null).isEmpty(), "");

        System.out.println();
        System.out.println("=== 结果：PASS " + pass + " / FAIL " + fail + " ===");
        if (fail > 0) System.exit(1);
    }
}
