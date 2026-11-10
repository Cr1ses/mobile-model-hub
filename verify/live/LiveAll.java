import com.wbgateway.Channel;
import com.wbgateway.Gateway;
import com.wbgateway.LoginProviders;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 端到端：把 Qoder CN 渠道装进真实 Gateway，逐个模型用**非流式**打一次。
 *
 * 这条路径覆盖「模型无法调用」的根因：网关必须按客户端要的形状回
 * （stream=false → 一个 JSON），不能把上游的 SSE 无条件透传。
 */
public final class LiveAll {
    static final int PORT = 18998;

    public static void main(String[] args) throws Exception {
        String uid = System.getenv("QODER_UID");
        String token = System.getenv("QODER_TOKEN");
        String machineId = System.getenv("QODER_MID");
        String name = System.getenv().getOrDefault("QODER_NAME", "user");
        String first = System.getenv().getOrDefault("QODER_FIRST", "qmodel");
        String[] models = System.getenv().getOrDefault("QODER_MODELS", "qmodel").split(",");

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
        c.cachedModels.add(first);
        config.channels.add(c);

        Gateway gw = new Gateway(config, new Gateway.Listener() {
            @Override public void onLog(String line) { }
            @Override public void onStateChanged(boolean running, int port) { }
        });
        gw.start();
        Thread.sleep(600);

        System.out.println("模型\t\t\t结果\t\t内容/原因");
        System.out.println("--------------------------------------------------------------------------");
        try {
            for (String raw : models) {
                String m = raw.trim();
                if (m.isEmpty()) continue;
                String reqBody = "{\"model\":\"qodercn:" + m
                        + "\",\"messages\":[{\"role\":\"user\",\"content\":\"say OK\"}],"
                        + "\"stream\":false,\"max_tokens\":24}";
                String resp;
                try {
                    resp = post(reqBody);
                } catch (Exception e) {
                    System.out.println(m + "\t\t异常\t\t" + e.getMessage());
                    continue;
                }
                System.out.println(m + "\t\t" + verdict(resp) + "\t\t" + detail(resp));
            }
        } finally {
            gw.stop();
        }
    }

    /** 从响应里判「能不能用」。不依赖 org.json 的 getJSONObject（stub 没有）。 */
    static String verdict(String resp) {
        if (resp.contains("\"error\"")) return "❌ 失败";
        if (resp.contains("\"choices\"") && resp.contains("\"content\"")) return "✅ 可用";
        return "❌ 异常";
    }

    static String detail(String resp) {
        String s = resp.replaceAll("\\s+", " ");
        if (s.contains("\"error\"")) {
            int i = s.indexOf("\"message\"");
            if (i > 0) s = s.substring(i);
        }
        return s.length() <= 110 ? s : s.substring(0, 110) + "…";
    }

    static String post(String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL("http://127.0.0.1:" + PORT + "/v1/chat/completions").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
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

    /** 最小 Store：只喂内存里的渠道，不碰 Android 配置。 */
    static final class Store implements Gateway.Store {
        final java.util.List<Channel> channels = new java.util.ArrayList<Channel>();

        @Override public String apiKey() { return "sk-live-test"; }
        @Override public int port() { return PORT; }
        @Override public boolean lan() { return false; }
        @Override public java.util.List<Channel> channels() { return channels; }

        @Override public Channel findChannel(String id) {
            for (Channel ch : channels) if (ch.id.equals(id)) return ch;
            return null;
        }

        @Override public void upsertChannel(Channel channel) { }
    }
}
