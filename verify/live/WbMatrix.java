import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 穷举 WorkBuddy 的 `11128` 家族：同一个错误码下有多个 msg，
 * 每个都要能用「缺什么」解释清楚。
 *
 * <p>已观察到的三种：
 * <ol>
 *   <li>{@code request illegal} —— 缺会话三元组；</li>
 *   <li>{@code first message is not system prompt} —— 缺 system；</li>
 *   <li>{@code Illegal API invocation from an unapproved channel}
 *       —— 未知，本轮要查。</li>
 * </ol>
 */
public final class WbMatrix {
    static String token;
    static String host;
    static String domain;
    static String model;

    public static void main(String[] args) throws Exception {
        token = System.getenv("WB_TOKEN");
        host = System.getenv("WB_HOST");
        domain = System.getenv("WB_DOMAIN");
        model = System.getenv().getOrDefault("WB_MODEL", "hy4free");

        // 组合：会话三元组 / IDE 头 / 产品头 / system / 工具字段
        run("① 全头+system", true, true, true, true, false);
        run("② 全头+system+tools", true, true, true, true, true);
        run("③ 无X-Domain", false, true, true, true, false);
        run("④ 无IDE头", true, false, true, true, false);
        run("⑤ 无产品头", true, true, false, true, false);
        run("⑥ 无system", true, true, true, false, false);
    }

    static void run(String label, boolean conv, boolean ide, boolean prod,
                    boolean sys, boolean tools) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(host + "/chat/completions").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "text/event-stream");
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("User-Agent", "WorkBuddy/5.5.2 WorkBuddy AI/5.5.2 CLI/5.5.2");
        String t = UUID.randomUUID().toString();
        if (conv) {
            c.setRequestProperty("X-Conversation-ID", t);
            c.setRequestProperty("X-Conversation-Message-ID", t);
            c.setRequestProperty("X-Conversation-Request-ID", t);
        }
        if (ide) {
            c.setRequestProperty("X-Agent-Purpose", "conversation");
            c.setRequestProperty("X-IDE-Name", "WorkBuddy");
            c.setRequestProperty("X-IDE-Type", "WorkBuddy");
            c.setRequestProperty("X-IDE-Version", "5.5.2");
        }
        if (prod) {
            c.setRequestProperty("X-Domain", domain);
            c.setRequestProperty("X-Product", "workbuddy");
            c.setRequestProperty("X-Product-Code", "workbuddy");
        }
        String msgs = sys
                ? "[{\"role\":\"system\",\"content\":\"\"},{\"role\":\"user\",\"content\":\"hi\"}]"
                : "[{\"role\":\"user\",\"content\":\"hi\"}]";
        String toolsJson = tools ? "[{\"type\":\"function\",\"function\":{\"name\":\"f\","
                + "\"description\":\"d\",\"parameters\":{\"type\":\"object\",\"properties\":{}}}}]" : "[]";
        String body = "{\"model\":\"" + model + "\",\"messages\":" + msgs
                + ",\"stream\":true,\"max_tokens\":8,\"tools\":" + toolsJson + "}";
        OutputStream os = c.getOutputStream();
        os.write(body.getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();

        int code = c.getResponseCode();
        InputStream in = code == 200 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (in != null) {
            byte[] tmp = new byte[4096];
            int n;
            int tot = 0;
            while (tot < 1200 && (n = in.read(tmp)) > 0) {
                buf.write(tmp, 0, n);
                tot += n;
            }
            in.close();
        }
        c.disconnect();
        String r = new String(buf.toByteArray(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        String msg = "";
        int i = r.indexOf("\"msg\":\"");
        if (i >= 0) {
            int st = i + 7;
            int en = r.indexOf('"', st);
            if (en > st) msg = r.substring(st, en);
        }
        String verdict;
        if (code == 200 && (r.contains("choices") || r.contains("heartbeat"))) verdict = "✅ 通过";
        else if (!msg.isEmpty()) verdict = "❌ 11128 " + msg;
        else verdict = "⚠ HTTP " + code + " " + r.substring(0, Math.min(60, r.length()));
        System.out.printf("%-24s %s%n", label, verdict);
    }
}
