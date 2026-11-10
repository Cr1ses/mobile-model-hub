import com.wbgateway.QoderCosy;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 端到端实测：用项目里的 QoderCosy 真实打 Qoder CN 网关。
 *
 * 这不是单元测试（会消耗少量上游额度），而是「签名算法真的被服务端接受」的
 * 唯一可信证据。验证脚本只跑纯函数；这条命令由人工触发。
 */
public final class LiveQoder {
    static final String BASE = "https://gateway.qoder.com.cn";
    static final String PATH =
        "/algo/api/v2/service/pro/sse/agent_chat_generation"
      + "?FetchKeys=llm_model_result&AgentId=agent_common&Encode=1";

    public static void main(String[] args) throws Exception {
        String uid = System.getenv("QODER_UID");
        String token = System.getenv("QODER_TOKEN");
        String machineId = System.getenv("QODER_MID");
        String name = System.getenv().getOrDefault("QODER_NAME", "user");
        String[] models = System.getenv().getOrDefault("QODER_MODELS", "qmodel").split(",");

        Path bodyFile = Files.createTempFile("qbody", ".txt");
        Path outFile = Files.createTempFile("qresp", ".txt");

        int ok = 0;
        int bad = 0;
        for (String raw : models) {
            String model = raw.trim();
            if (model.isEmpty()) continue;

            QoderCosy.Session s = QoderCosy.newSession(uid, name, token, machineId, machineId, "5");
            // 与 Gateway.buildQoderInferBody 同构的最小载荷
            String body = inferBody(model);
            String encoded = QoderCosy.cosyEncode(body.getBytes(StandardCharsets.UTF_8));
            String payloadB64 = QoderCosy.buildPayloadB64(s.info);
            String date = QoderCosy.rfc1123();
            String sig = QoderCosy.signRequest(payloadB64, s.cosyKey, date, encoded,
                    QoderCosy.pathSigFrom(BASE + PATH));
            String auth = QoderCosy.composeBearer(payloadB64, sig);

            Files.write(bodyFile, encoded.getBytes(StandardCharsets.UTF_8));

            HttpURLConnection conn = (HttpURLConnection) new URL(BASE + PATH).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Authorization", auth);
            conn.setRequestProperty("cosy-key", s.cosyKey);
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
            os.write(encoded.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            String resp;
            try {
                int code = conn.getResponseCode();
                java.io.InputStream in = code == 200 ? conn.getInputStream() : conn.getErrorStream();
                StringBuilder sb = new StringBuilder();
                if (in != null) {
                    byte[] buf = new byte[8192];
                    int n;
                    int total = 0;
                    while ((n = in.read(buf)) > 0 && total < 6000) {
                        sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        total += n;
                    }
                    in.close();
                }
                resp = sb.toString();
            } finally {
                conn.disconnect();
            }

            String tag;
            if (resp.contains("\"choices\"")) { tag = "OK 有正文"; ok++; }
            else if (resp.contains("Signature")) { tag = "签名被拒"; bad++; }
            else if (resp.contains("invalid_model")) { tag = "模型不存在"; bad++; }
            else if (resp.contains("RESOURCE_LIMIT")) { tag = "额度受限"; bad++; }
            else if (resp.contains("provider_error")) { tag = "后端不可用"; bad++; }
            else { tag = "其它"; bad++; }

            String snip = resp.replaceAll("\\s+", " ");
            System.out.println(String.format("%-16s %-14s %s", model, tag,
                    snip.substring(0, Math.min(80, snip.length()))));
        }
        System.out.println();
        System.out.println("可用 " + ok + " 个，不可用 " + bad + " 个");
    }

    static String inferBody(String model) {
        String rid = java.util.UUID.randomUUID().toString();
        return "{\"request_id\":\"" + rid + "\",\"request_set_id\":\"" + rid + "\","
            + "\"chat_record_id\":\"" + rid + "\",\"session_id\":\""
            + java.util.UUID.randomUUID() + "\",\"stream\":true,"
            + "\"chat_task\":\"FREE_INPUT\",\"chat_context\":{\"text\":\"say OK\","
            + "\"features\":[],\"extra\":{\"context\":[],\"modelConfig\":{\"key\":\""
            + model + "\",\"is_reasoning\":false},\"originalContent\":\"say OK\"},"
            + "\"chatPrompt\":\"\",\"imageUrls\":null},\"is_reply\":true,"
            + "\"is_retry\":false,\"source\":1,\"version\":\"3\","
            + "\"agent_id\":\"agent_common\",\"task_id\":\"common\","
            + "\"session_type\":\"qodercli\",\"aliyun_user_type\":\"\","
            + "\"model_config\":{\"key\":\"" + model + "\",\"display_name\":\"\","
            + "\"model\":\"\",\"format\":\"openai\",\"is_vl\":true,"
            + "\"is_reasoning\":false,\"api_key\":\"\",\"url\":\"\","
            + "\"source\":\"system\",\"max_input_tokens\":200000},"
            + "\"custom_model\":null,\"system\":[],"
            + "\"messages\":[{\"role\":\"user\",\"content\":\"say OK\"}],"
            + "\"tools\":[],\"parameters\":{\"max_tokens\":16},"
            + "\"business\":{\"type\":\"agent\"}}";
    }
}
