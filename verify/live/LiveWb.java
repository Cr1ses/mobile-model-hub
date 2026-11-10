import com.wbgateway.Channel;
import com.wbgateway.Gateway;
import com.wbgateway.LoginProviders;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 端到端：WorkBuddy 渠道装进真实 Gateway，验证 system 补齐是否让 11128 消失。
 *
 * <p>三种客户端形态都要覆盖：
 * <ol>
 *   <li>不带 system —— 曾经必回 11128；</li>
 *   <li>自带 system —— 不该被重复插入；</li>
 *   <li>stream=false —— 顺带回归非流式路径。</li>
 * </ol>
 */
public final class LiveWb {
    static final int PORT = 18997;

    public static void main(String[] args) throws Exception {
        String token = System.getenv("WB_TOKEN");
        String host = System.getenv("WB_HOST");
        String domain = System.getenv("WB_DOMAIN");
        String loginType = System.getenv().getOrDefault("WB_LOGIN", LoginProviders.WB_INTL);
        String model = System.getenv().getOrDefault("WB_MODEL", "hy4-preview");
        String baseUrl = System.getenv().getOrDefault("WB_BASE", host);

        Store config = new Store();
        Channel c = new Channel();
        c.id = "wb";
        c.name = "WorkBuddy";
        c.kind = Channel.KIND_LOGIN;
        c.loginType = loginType;
        c.accessToken = token;
        c.domain = domain;
        c.baseUrl = baseUrl;
        c.enabled = true;
        c.cachedModels.add(model);
        config.channels.add(c);

        Gateway gw = new Gateway(config, new Gateway.Listener() {
            @Override public void onLog(String line) { }
            @Override public void onStateChanged(boolean running, int port) { }
        });
        gw.start();
        Thread.sleep(600);

        try {
            System.out.println("=== ① 不带 system（曾必回 11128）===");
            run(model, "{\"model\":\"wb:" + model
                    + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                    + "\"stream\":true,\"max_tokens\":8}");

            System.out.println();
            System.out.println("=== ② 自带 system（不该被重复插入）===");
            run(model, "{\"model\":\"wb:" + model
                    + "\",\"messages\":[{\"role\":\"system\",\"content\":\"Be brief.\"},"
                    + "{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true,\"max_tokens\":8}");

            System.out.println();
            System.out.println("=== ③ stream=false ===");
            run(model, "{\"model\":\"wb:" + model
                    + "\",\"messages\":[{\"role\":\"user\",\"content\":\"say OK\"}],"
                    + "\"stream\":false,\"max_tokens\":16}");
        } finally {
            gw.stop();
        }
    }

    static void run(String model, String body) throws Exception {
        String resp;
        try {
            resp = post(body);
        } catch (Exception e) {
            System.out.println("  异常: " + e.getMessage());
            return;
        }
        String flat = resp.replaceAll("\\s+", " ");
        if (flat.contains("11128")) {
            String msg = "";
            int i = flat.indexOf("\"msg\"");
            if (i > 0) msg = flat.substring(i, Math.min(i + 90, flat.length()));
            System.out.println("  ❌ 仍被拦: " + msg);
        } else if (flat.contains("choices")) {
            System.out.println("  ✅ 通过，有正文");
            System.out.println("  " + flat.substring(0, Math.min(200, flat.length())));
        } else if (flat.contains("\"error\"")) {
            System.out.println("  ⚠ 网关错误: " + flat.substring(0, Math.min(200, flat.length())));
        } else {
            System.out.println("  ? 未知: " + flat.substring(0, Math.min(200, flat.length())));
        }
    }

    static String post(String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL("http://127.0.0.1:" + PORT + "/v1/chat/completions").openConnection();
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
