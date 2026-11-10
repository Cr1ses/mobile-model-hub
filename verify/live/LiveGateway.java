import com.wbgateway.Channel;
import com.wbgateway.Config;
import com.wbgateway.Gateway;
import com.wbgateway.LoginProviders;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 端到端：把 Qoder CN 渠道装进真实 Gateway，用流式 / 非流式各打一次。
 *
 * 这是「模型无法调用」的复现与修复验证：网关必须按客户端要的形状回，
 * 不能把上游的 SSE 无条件透传。
 */
public final class LiveGateway {
    public static void main(String[] args) throws Exception {
        String uid = System.getenv("QODER_UID");
        String token = System.getenv("QODER_TOKEN");
        String machineId = System.getenv("QODER_MID");
        String name = System.getenv().getOrDefault("QODER_NAME", "user");
        String model = System.getenv().getOrDefault("QODER_MODEL", "qmodel");

        Store config = new Store();

        Channel c = new Channel();
        c.id = "qodercn";
        c.name = "Qoder CN";
        c.kind = Channel.KIND_LOGIN;
        c.loginType = LoginProviders.QODER_CN;
        c.accessToken = token;
        c.uid = uid;
        c.machineId = machineId;
        c.nickname = name;
        c.baseUrl = LoginProviders.chatBaseFor(LoginProviders.QODER_CN);
        c.enabled = true;
        c.cachedModels.add(model);
        config.channels.add(c);

        Gateway gw = new Gateway(config, new Gateway.Listener() {
            @Override public void onLog(String line) {
                System.out.println("  [gw] " + line);
            }
            @Override public void onStateChanged(boolean running, int port) { }
        });
        gw.start();
        Thread.sleep(600);

        try {
            // ---- 非流式 ----
            System.out.println("===== stream=false =====");
            String reqBody = "{\"model\":\"qodercn:" + model
                    + "\",\"messages\":[{\"role\":\"user\",\"content\":\"say OK\"}],"
                    + "\"stream\":false,\"max_tokens\":16}";
            String resp = post("http://127.0.0.1:18999/v1/chat/completions", reqBody);
            System.out.println(resp.substring(0, Math.min(500, resp.length())));
            boolean isJson = resp.trim().startsWith("{");
            boolean hasContent = resp.contains("\"content\"");
            System.out.println(">>> 是 JSON: " + isJson + "  含 content: " + hasContent);

            System.out.println();
            System.out.println("===== stream=true =====");
            String reqBody2 = "{\"model\":\"qodercn:" + model
                    + "\",\"messages\":[{\"role\":\"user\",\"content\":\"say OK\"}],"
                    + "\"stream\":true,\"max_tokens\":16}";
            String resp2 = post("http://127.0.0.1:18999/v1/chat/completions", reqBody2);
            System.out.println(resp2.substring(0, Math.min(400, resp2.length())));
            boolean isSse = resp2.contains("data: ");
            System.out.println(">>> 是 SSE: " + isSse);
        } finally {
            gw.stop();
        }
    }

    /** 最小 Store：只喂内存里的渠道，不碰 Android 配置。 */
    static final class Store implements Gateway.Store {
        final java.util.List<Channel> channels = new java.util.ArrayList<Channel>();

        @Override public String apiKey() { return "sk-live-test"; }
        @Override public int port() { return 18999; }
        @Override public boolean lan() { return false; }
        @Override public java.util.List<Channel> channels() { return channels; }

        @Override public Channel findChannel(String id) {
            for (Channel ch : channels) if (ch.id.equals(id)) return ch;
            return null;
        }

        @Override public void upsertChannel(Channel channel) { }
    }

    static String post(String url, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(90000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer sk-live-test");
        OutputStream os = conn.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.flush();
        os.close();
        int code = conn.getResponseCode();
        InputStream in = code == 200 ? conn.getInputStream() : conn.getErrorStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (in != null) {
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
            in.close();
        }
        conn.disconnect();
        return new String(buf.toByteArray(), "UTF-8");
    }
}
