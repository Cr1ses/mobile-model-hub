import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 横向验证：把「WorkBuddy 踩过的三个坑」在其他渠道上逐一试一遍。
 *
 * <p>三个坑（每个都会让调用**必然失败**，但错误文案各不相同、都容易被误判）：
 * <ol>
 *   <li>非流式被拒 —— 上游只支持 SSE；</li>
 *   <li>首条消息必须是 system；</li>
 *   <li>system 开头命中第三方客户端指纹。</li>
 * </ol>
 *
 * <p>这里只关心「渠道会不会因此失败」，不关心回答质量，所以 max_tokens 都很小。
 */
public final class ChannelMatrix {
    static final String PROMPT_CLAUDE =
            "You are Claude Code, Anthropic's official CLI for Claude. Be concise.";
    static final String PROMPT_PLAIN = "You are a helpful assistant.";
    static final String PROMPT_CN = "你是一个乐于助人的AI助手。";

    public static void main(String[] args) throws Exception {
        String which = System.getenv().getOrDefault("CH", "all");
        if ("zcode".equals(which) || "all".equals(which)) zcode();
        if ("codearts".equals(which) || "all".equals(which)) codearts();
        if ("proxy".equals(which) || "all".equals(which)) proxy();
    }

    // ---------- ZCode（Anthropic 协议） ----------
    static void zcode() {
        String token = System.getenv("ZCODE_TOKEN");
        String host = System.getenv().getOrDefault("ZCODE_HOST",
                "https://zcode.z.ai/api/v1/zcode-plan/anthropic/v1");
        String ver = System.getenv().getOrDefault("ZCODE_VER", "1.0.0");
        System.out.println("=== ZCode（Anthropic Messages）===");
        if (token == null || token.isEmpty()) {
            System.out.println("  缺 ZCODE_TOKEN，跳过");
            return;
        }
        // Anthropic 的 system 是**顶层字段**，不是 messages[0]
        run("无 system", host + "/messages", token, ver, null, "hi");
        run("system=Claude 指纹", host + "/messages", token, ver, PROMPT_CLAUDE, "hi");
        run("system=普通", host + "/messages", token, ver, PROMPT_PLAIN, "hi");
        run("system=中文", host + "/messages", token, ver, PROMPT_CN, "hi");
    }

    static void run(String label, String url, String token, String ver,
                    String sys, String userText) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "text/event-stream");
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("anthropic-version", "2023-06-01");
            c.setRequestProperty("User-Agent", "ZCode/" + ver);
            c.setRequestProperty("X-ZCode-App-Version", ver);

            String body = "{\"model\":\"GLM-5.3\",\"max_tokens\":16,\"stream\":true"
                    + (sys == null ? "" : ",\"system\":\"" + esc(sys) + "\"")
                    + ",\"messages\":[{\"role\":\"user\",\"content\":\"" + esc(userText) + "\"}]}";
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            int code = c.getResponseCode();
            String r = read(code == 200 ? c.getInputStream() : c.getErrorStream());
            c.disconnect();
            System.out.printf("  %-22s %s%n", label, verdict(code, r));
        } catch (Exception e) {
            System.out.printf("  %-22s 异常 %s%n", label, e.getMessage());
        }
    }

    // ---------- 华为 CodeArts ----------
    static void codearts() {
        System.out.println("=== 华为 CodeArts ===");
        String ak = System.getenv("CA_AK");
        if (ak == null || ak.isEmpty()) {
            System.out.println("  缺 CA_AK，跳过（需要 STS 三件套 + DPoP 签名，离线难复现）");
            return;
        }
        System.out.println("  已有凭据，但签名链在 Gateway 内，直接跑网关更准");
    }

    // ---------- 通用代理渠道 ----------
    static void proxy() {
        System.out.println("=== 通用代理渠道（DeepSeek 官方）===");
        String key = System.getenv("DEEPSEEK_KEY");
        if (key == null || key.isEmpty()) {
            System.out.println("  缺 DEEPSEEK_KEY，跳过");
            return;
        }
        String host = "https://api.deepseek.com/v1";
        openAi("无 system", host, key, "deepseek-chat", null, false);
        openAi("system=Claude 指纹", host, key, "deepseek-chat", PROMPT_CLAUDE, false);
        openAi("非流式", host, key, "deepseek-chat", null, true);
    }

    static void openAi(String label, String host, String key, String model,
                       String sys, boolean noStream) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(host + "/chat/completions").openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + key);
            String msgs = (sys == null ? "" : "{\"role\":\"system\",\"content\":\"" + esc(sys) + "\"},")
                    + "{\"role\":\"user\",\"content\":\"hi\"}";
            String body = "{\"model\":\"" + model + "\",\"messages\":[" + msgs + "],"
                    + "\"stream\":" + (noStream ? "false" : "true") + ",\"max_tokens\":16}";
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            int code = c.getResponseCode();
            String r = read(code == 200 ? c.getInputStream() : c.getErrorStream());
            c.disconnect();
            System.out.printf("  %-22s %s%n", label, verdict(code, r));
        } catch (Exception e) {
            System.out.printf("  %-22s 异常 %s%n", label, e.getMessage());
        }
    }

    static String verdict(int code, String r) {
        String flat = r.replaceAll("\\s+", " ");
        if (code == 200 && (flat.contains("choices") || flat.contains("content")
                || flat.contains("delta") || flat.contains("message_start"))) {
            return "✅ 通过";
        }
        if (code == 200) return "⚠ 200 但无正文";
        String snip = flat.length() > 100 ? flat.substring(0, 100) : flat;
        return "❌ HTTP " + code + " " + snip;
    }

    static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        int tot = 0;
        while (tot < 2000 && (n = in.read(tmp)) > 0) {
            buf.write(tmp, 0, n);
            tot += n;
        }
        in.close();
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
