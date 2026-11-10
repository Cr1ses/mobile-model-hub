import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 复现并定位 WorkBuddy 的 `code:11128 Illegal API invocation from an unapproved channel`。
 *
 * <p>这个错误的意思是「渠道校验没过」，但上游从不说明**缺哪一项**。
 * 所以这里做变量隔离：从一个最小可用集出发，逐个增删请求头，看哪个能让它变 200。
 */
public final class WbProbe {
    static String token;
    static String domain;
    static String host;

    public static void main(String[] args) throws Exception {
        token = System.getenv("WB_TOKEN");
        domain = System.getenv("WB_DOMAIN");
        host = System.getenv("WB_HOST");
        String mode = System.getenv().getOrDefault("WB_MODE", "all");
        if (token == null || token.isEmpty()) {
            System.out.println("缺 WB_TOKEN");
            return;
        }
        System.out.println("domain = " + domain);
        System.out.println("host   = " + host);
        System.out.println();

        if ("all".equals(mode)) {
            // ① 完全不带自定义头（基线）
            tryIt("最小：仅 Authorization", 0, 0, 0, 0, 0);
            // ② 只加三元组
            tryIt("三元组", 1, 0, 0, 0, 0);
            // ③ 三元组 + IDE 头
            tryIt("三元组+IDE", 1, 1, 0, 0, 0);
            // ④ 三元组 + IDE + 产品头
            tryIt("三元组+IDE+产品", 1, 1, 1, 0, 0);
            // ⑤ 全量（含 X-Domain / UA / trace）
            tryIt("全量", 1, 1, 1, 1, 1);
            // ⑥ 全量 + 额外疑似头
            tryIt("全量+额外", 1, 1, 1, 1, 1, 2);
        } else {
            tryIt("全量+额外", 1, 1, 1, 1, 1, 2);
        }
    }

    /**
     * @param conv   是否带 X-Conversation-*
     * @param ide    是否带 X-IDE-*
     * @param prod   是否带 X-Product / X-Product-Code / X-Domain
     * @param ua     是否带完整 User-Agent
     * @param extra  是否带其余细节头
     */
    static void tryIt(String label, int conv, int ide, int prod, int ua, int extra) throws Exception {
        tryIt(label, conv, ide, prod, ua, extra, 0);
    }

    static void tryIt(String label, int conv, int ide, int prod, int ua, int extra, int more)
            throws Exception {
        String base = host + "/chat/completions";
        HttpURLConnection conn = (HttpURLConnection) new URL(base).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        if (ua > 0) {
            conn.setRequestProperty("User-Agent",
                    "WorkBuddy/5.5.2 WorkBuddy AI/5.5.2 CLI/5.5.2");
        }
        String traceId = UUID.randomUUID().toString();
        if (conv > 0) {
            conn.setRequestProperty("X-Conversation-ID", traceId);
            conn.setRequestProperty("X-Conversation-Message-ID", traceId);
            conn.setRequestProperty("X-Conversation-Request-ID", traceId);
        }
        if (ide > 0) {
            conn.setRequestProperty("X-Agent-Purpose", "conversation");
            conn.setRequestProperty("X-IDE-Name", "WorkBuddy");
            conn.setRequestProperty("X-IDE-Type", "WorkBuddy");
            conn.setRequestProperty("X-IDE-Version", "5.5.2");
        }
        if (prod > 0) {
            conn.setRequestProperty("X-Domain", domain);
            conn.setRequestProperty("X-Product", "workbuddy");
            conn.setRequestProperty("X-Product-Code", "workbuddy");
        }
        if (extra > 0) {
            conn.setRequestProperty("X-Request-ID", traceId);
            conn.setRequestProperty("X-Trace-ID", traceId);
        }
        if (more > 0) {
            conn.setRequestProperty("X-Client-Version", "5.5.2");
            conn.setRequestProperty("X-Platform", "workbuddy");
            conn.setRequestProperty("X-Channel", "official");
        }

        String body = "{\"model\":\"hy4-preview\",\"messages\":[{\"role\":\"user\","
                + "\"content\":\"hi\"}],\"stream\":true,\"max_tokens\":8}";
        OutputStream os = conn.getOutputStream();
        os.write(body.getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        InputStream in = code == 200 ? conn.getInputStream() : conn.getErrorStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (in != null) {
            byte[] tmp = new byte[4096];
            int n;
            int total = 0;
            while (total < 1500 && (n = in.read(tmp)) > 0) {
                buf.write(tmp, 0, n);
                total += n;
            }
            in.close();
        }
        conn.disconnect();
        String resp = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        String shortResp = resp.replaceAll("\\s+", " ");
        if (shortResp.length() > 150) shortResp = shortResp.substring(0, 150) + "…";
        String verdict;
        if (code == 200 && resp.contains("choices")) verdict = "✅ 通过";
        else if (resp.contains("11128")) verdict = "❌ 11128 渠道校验";
        else verdict = "⚠ HTTP " + code;
        System.out.printf("%-22s %-16s %s%n", label, verdict, shortResp);
    }
}
