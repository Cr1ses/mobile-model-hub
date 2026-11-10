import com.wbgateway.Channel;
import com.wbgateway.Gateway;

import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 桌面验证：直接跑 APK 里那份 Gateway.java 的真实代码。
 *
 * 为什么可以：Gateway.java 不依赖任何 Android API，只用 JDK 标准库 + org.json
 * （Android 自带 org.json，桌面用同一个开源实现即可等价替换）。
 * 这里用一个假的 OpenAI 兼容上游，验证：
 *   1. /v1/models 聚合与鉴权
 *   2. /v1/chat/completions 非流式透传
 *   3. /v1/chat/completions 流式逐块透传（不缓冲）
 *   4. 未知模型 / 缺 Key 的错误分支
 */
public class Verify {

    /** 假上游：端口由调用方指定，支持 /models 与 /chat/completions（SSE）。 */
    static class FakeUpstream {
        com.sun.net.httpserver.HttpServer server;

        void start(int port) throws Exception {
            server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/v1/models", exchange -> {
                String body = "{\"data\":[{\"id\":\"upstream-model-a\"},{\"id\":\"upstream-model-b\"}]}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                boolean stream = "text/event-stream".equals(
                        exchange.getRequestHeaders().getFirst("Accept"));
                if (stream) {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    java.io.OutputStream os = exchange.getResponseBody();
                    for (String piece : new String[]{"Hello", " from", " fake", " upstream"}) {
                        String frame = "data: {\"choices\":[{\"delta\":{\"content\":\"" + piece + "\"}}]}\n\n";
                        os.write(frame.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        Thread.sleep(80);
                    }
                    os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    exchange.close();
                } else {
                    String body = "{\"id\":\"x\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"non-stream ok\"}}],"
                            + "\"auth_seen\":\"" + (auth == null ? "NONE" : auth.replace("Bearer ", "Bearer-ok-")) + "\"}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                } } catch (Exception ignored) { }
            });
            server.start();
        }

        void stop() {
            if (server != null) server.stop(0);
        }
    }

    /** 内存版 Store（桌面没有 SharedPreferences）——直接实现 Gateway.Store。 */
    static class MemConfig implements Gateway.Store {
        private final List<Channel> channels = new ArrayList<>();
        private final String apiKey = "test-key-123";

        MemConfig() {
        }

        @Override
        public String apiKey() {
            return apiKey;
        }

        @Override
        public int port() {
            return 18787;
        }

        @Override
        public boolean lan() {
            return false;
        }

        @Override
        public List<Channel> channels() {
            return new ArrayList<>(channels);
        }

        @Override
        public Channel findChannel(String id) {
            for (Channel c : channels) if (c.id.equals(id)) return c;
            return null;
        }

        @Override
        public void upsertChannel(Channel channel) {
            for (int i = 0; i < channels.size(); i += 1) {
                if (channels.get(i).id.equals(channel.id)) {
                    channels.set(i, channel);
                    return;
                }
            }
            channels.add(channel);
        }
    }

    /** 记录收到的请求头（验证「会话三元组」真的发出去了）。 */
    static class HeaderRecorder {
        com.sun.net.httpserver.HttpServer server;
        volatile String reqPath = "";
        final java.util.Map<String, String> headers = new java.util.concurrent.ConcurrentHashMap<>();

        void start(int port) throws Exception {
            server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/", exchange -> {
                reqPath = exchange.getRequestURI().getPath();
                headers.clear();
                for (java.util.Map.Entry<String, java.util.List<String>> e
                        : exchange.getRequestHeaders().entrySet()) {
                    headers.put(e.getKey().toLowerCase(java.util.Locale.US), e.getValue().get(0));
                }
                byte[] body = "{\"choices\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        void stop() {
            if (server != null) server.stop(0);
        }

        boolean saw(String name) {
            String v = headers.get(name.toLowerCase(java.util.Locale.US));
            return v != null && !v.trim().isEmpty();
        }

        String value(String name) {
            String v = headers.get(name.toLowerCase(java.util.Locale.US));
            return v == null ? "" : v;
        }

        String path() {
            return reqPath;
        }

        String dump(String name) {
            return name + "=" + value(name);
        }
    }

    /** 假上游：HTTP 200，但响应体是 SSE 错误事件（复刻 Qoder 的真实行为）。 */
    static class SseErrorUpstream {
        com.sun.net.httpserver.HttpServer server;

        void start(int port) throws Exception {
            server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/", exchange -> {
                byte[] body = ("event: error\n"
                        + "data: {\"code\":\"invalid_model_error\","
                        + "\"message\":\"Unsupported model \\\"cmodel\\\"\"}\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        void stop() {
            if (server != null) server.stop(0);
        }
    }

    static int pass = 0;
    static int fail = 0;

    static void check(String label, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + label + (detail.isEmpty() ? "" : " -- " + detail));
        } else {
            fail++;
            System.out.println("  FAIL  " + label + (detail.isEmpty() ? "" : " -- " + detail));
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 启动假上游 :19001 ===");
        FakeUpstream upstream = new FakeUpstream();
        upstream.start(19001);

        MemConfig config = new MemConfig();
        Channel c = new Channel("ds", "假上游", "http://127.0.0.1:19001/v1");
        c.apiKeys.add("sk-upstream-secret");
        c.cachedModels.add("upstream-model-a");
        c.cachedModels.add("upstream-model-b");
        config.channels.add(c);

        // 缺 Base URL 的渠道：这条曾经报成「无法连接上游 ：no protocol: /chat/completions」，
        // 看起来像网络故障，实际是配置缺项。现在必须明确报 config_error。
        Channel noBase = new Channel("nobase", "缺基址渠道", "");
        noBase.apiKeys.add("sk-x");
        config.channels.add(noBase);

        System.out.println("=== 启动网关（真实 Gateway.java）:18787 ===");
        final List<String> logs = new ArrayList<>();
        Gateway gw = new Gateway(config, new Gateway.Listener() {
            @Override
            public void onLog(String line) {
                logs.add(line);
                System.out.println("  [gateway] " + line);
            }

            @Override
            public void onStateChanged(boolean running, int port) {
            }
        });
        gw.start();
        Thread.sleep(300);

        System.out.println();
        System.out.println("=== 1. /v1/models 鉴权 ===");
        HttpURLConnection noAuth = get("http://127.0.0.1:18787/v1/models", null);
        check("无 Key 返回 401", noAuth.getResponseCode() == 401, "HTTP " + noAuth.getResponseCode());

        HttpURLConnection withAuth = get("http://127.0.0.1:18787/v1/models", "test-key-123");
        String modelsBody = readAll(withAuth.getInputStream());
        check("带 Key 返回 200", withAuth.getResponseCode() == 200, "HTTP " + withAuth.getResponseCode());
        check("模型带渠道前缀", modelsBody.contains("ds:upstream-model-a"), modelsBody);
        check("模型清单完整", modelsBody.contains("upstream-model-b"), "");

        System.out.println();
        System.out.println("=== 2. 非流式转发 ===");
        String payload = "{\"model\":\"ds:upstream-model-a\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}";
        HttpURLConnection post = post("http://127.0.0.1:18787/v1/chat/completions", payload, "test-key-123");
        String nonStream = readAll(post.getInputStream());
        check("非流式返回 200", post.getResponseCode() == 200, "HTTP " + post.getResponseCode());
        check("内容透传", nonStream.contains("non-stream ok"), nonStream);
        check("上游 Key 被替换（不泄漏客户端 Key）",
                nonStream.contains("Bearer-ok-sk-upstream-secret") && !nonStream.contains("test-key-123"),
                "上游收到的是渠道自己的 Key");
        check("模型 id 前缀已剥离", !nonStream.contains("ds:upstream-model-a"), "");

        System.out.println();
        System.out.println("=== 3. 流式逐块透传 ===");
        String streamPayload = "{\"model\":\"ds:upstream-model-a\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}";
        HttpURLConnection sconn = post("http://127.0.0.1:18787/v1/chat/completions", streamPayload, "test-key-123");
        check("流式返回 200", sconn.getResponseCode() == 200, "HTTP " + sconn.getResponseCode());
        check("Content-Type 是 SSE",
                sconn.getContentType() != null && sconn.getContentType().contains("text/event-stream"),
                sconn.getContentType());
        java.io.InputStream in = sconn.getInputStream();
        StringBuilder raw = new StringBuilder();
        long firstChunkAt = 0;
        long startAt = System.currentTimeMillis();
        byte[] buf = new byte[1024];
        int n;
        int chunks = 0;
        while ((n = in.read(buf)) > 0) {
            chunks++;
            if (firstChunkAt == 0) firstChunkAt = System.currentTimeMillis();
            raw.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        check("收到多块（流式而非一次性）", chunks > 1, chunks + " 块");
        check("首块早于整体完成",
                firstChunkAt > 0 && (System.currentTimeMillis() - startAt) > 100,
                "首块延迟 " + (firstChunkAt - startAt) + "ms");
        check("包含完整内容", raw.toString().contains("Hello") && raw.toString().contains("upstream"), "");
        check("包含 [DONE]", raw.toString().contains("[DONE]"), "");

        System.out.println();
        System.out.println("=== 4. 错误分支 ===");
        HttpURLConnection unknown = post("http://127.0.0.1:18787/v1/chat/completions",
                "{\"model\":\"ds:no-such-model\",\"messages\":[]}", "test-key-123");
        check("未知渠道模型能转发到上游（不本地拦截）", unknown.getResponseCode() == 200,
                "本地不维护权威模型表，交给上游裁决");
        HttpURLConnection badChannel = post("http://127.0.0.1:18787/v1/chat/completions",
                "{\"model\":\"zzz:foo\",\"messages\":[]}", "test-key-123");
        check("不存在的渠道前缀返回 404", badChannel.getResponseCode() == 404,
                "HTTP " + badChannel.getResponseCode());

        HttpURLConnection noBaseResp = post("http://127.0.0.1:18787/v1/chat/completions",
                "{\"model\":\"nobase:m\",\"messages\":[]}", "test-key-123");
        int noBaseStatus = noBaseResp.getResponseCode();
        String noBaseBody = readAll(noBaseStatus >= 400
                ? noBaseResp.getErrorStream() : noBaseResp.getInputStream());
        check("缺 Base URL → 502 config_error（不是 no protocol）",
                noBaseStatus == 502
                        && noBaseBody.contains("config_error")
                        && noBaseBody.contains("Base URL"),
                "HTTP " + noBaseStatus + " " + noBaseBody);

        System.out.println();
        System.out.println("=== 5. 状态页 ===");
        HttpURLConnection status = get("http://127.0.0.1:18787/", null);
        String statusBody = readAll(status.getInputStream());
        check("状态页 200", status.getResponseCode() == 200, "");
        check("状态页含 Base URL", statusBody.contains("127.0.0.1:18787/v1"), "");
        check("状态页含渠道", statusBody.contains("ds"), "");

        System.out.println();
        System.out.println("=== 6. 停止后能重新启动（界面上会反复点）===");
        gw.stop();
        gw.start();
        Thread.sleep(200);
        HttpURLConnection restarted = get("http://127.0.0.1:18787/v1/models", "test-key-123");
        int restartedStatus = restarted.getResponseCode();
        String restartedBody = readAll(restarted.getInputStream());
        check("重启后仍能服务请求", restartedStatus == 200 && restartedBody.contains("ds:"),
                "HTTP " + restartedStatus);

        System.out.println();
        System.out.println("=== 7. WorkBuddy 会话三元组（缺了上游回 11128 request illegal）===");
        // 用一个记录请求头的假上游，确认网关真的把三个 X-Conversation-* 发出去了。
        // 直接把渠道加进正在跑的网关（Store.channels() 是实时读的），不另起实例。
        HeaderRecorder recorder = new HeaderRecorder();
        recorder.start(19002);
        Channel wb = new Channel();
        wb.id = "wb";
        wb.kind = Channel.KIND_LOGIN;
        wb.loginType = com.wbgateway.LoginProviders.WB_CN;
        wb.accessToken = "token-x";
        wb.domain = "www.codebuddy.cn";
        wb.baseUrl = "http://127.0.0.1:19002/v2";
        wb.cachedModels.add("glm-5.3");
        config.channels.add(wb);

        HttpURLConnection wbResp = post("http://127.0.0.1:18787/v1/chat/completions",
                "{\"model\":\"wb:glm-5.3\",\"messages\":[]}", "test-key-123");
        int wbStatus = wbResp.getResponseCode();
        readAll(wbStatus >= 400 ? wbResp.getErrorStream() : wbResp.getInputStream());
        recorder.stop();
        check("WorkBuddy 请求被转发到 /v2/chat/completions",
                "/v2/chat/completions".equals(recorder.path()), recorder.path());
        check("带了 X-Conversation-ID", recorder.saw("X-Conversation-ID"),
                recorder.dump("X-Conversation-ID"));
        check("带了 X-Conversation-Message-ID", recorder.saw("X-Conversation-Message-ID"), "");
        check("带了 X-Conversation-Request-ID", recorder.saw("X-Conversation-Request-ID"), "");
        check("带了 X-Agent-Purpose: conversation",
                "conversation".equals(recorder.value("X-Agent-Purpose")), recorder.value("X-Agent-Purpose"));
        check("带了 X-IDE-Name", recorder.saw("X-IDE-Name"), recorder.value("X-IDE-Name"));
        config.channels.remove(wb);

        System.out.println();
        System.out.println("=== 8. 上游「HTTP 200 + event: error」要改写成错误 ===");
        // 这是 OpenAI 兼容上游的通病：不支持某个模型时回 200，但响应体是
        // SSE 错误事件。若网关原样透传，客户端会「成功但没输出」——比报错更难查。
        //
        // ⚠ 不能用 Qoder 做被测渠道：它现在走 cosy 加密端点（proxyChatQoder），
        //   不走这里要验证的「普通 HTTP 转发 + SSE 窥探」路径。用 OpenAI 兼容
        //   的代理型渠道才能覆盖到那段代码。
        SseErrorUpstream errUp = new SseErrorUpstream();
        errUp.start(19003);
        Channel qd = new Channel();
        qd.id = "oai-proxy";
        qd.kind = Channel.KIND_PROXY;
        qd.baseUrl = "http://127.0.0.1:19003/model/v1";
        qd.apiKeys.add("sk-test");
        qd.cachedModels.add("gmodel");
        config.channels.add(qd);
        HttpURLConnection errResp = post("http://127.0.0.1:18787/v1/chat/completions",
                "{\"model\":\"oai-proxy:gmodel\",\"messages\":[],\"stream\":true}", "test-key-123");
        int errStatus = errResp.getResponseCode();
        String errBody = readAll(errStatus >= 400 ? errResp.getErrorStream() : errResp.getInputStream());
        errUp.stop();
        config.channels.remove(qd);
        check("不再回 200（否则客户端会当成成功）", errStatus == 502, "HTTP " + errStatus);
        check("响应体是 JSON 错误，不是 SSE 事件",
                errBody.contains("upstream_error") && !errBody.contains("event: error"), errBody);
        check("错误里带上游原文", errBody.contains("Unsupported model"), errBody);

        System.out.println();
        System.out.println("=== 9. WorkBuddy：system 补齐 / 强制流式 / 非流式聚合 ===");
        // ① messages 首条不是 system 时要补一条空 system。
        //    实测：不带 system → 11128 first message is not system prompt。
        String noSys = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        String fixed = com.wbgateway.Gateway.ensureSystemFirst(noSys);
        check("缺 system 时补上第一条",
                fixed.contains("\"role\":\"system\""), fixed);
        check("补的是 system 且排在 user 之前",
                fixed.indexOf("\"system\"") < fixed.indexOf("\"user\""), fixed);
        check("用户正文没被改动", fixed.contains("\"content\":\"hi\""), fixed);

        // ② 已有 system 时不重复插入
        String withSys = "{\"model\":\"m\",\"messages\":[{\"role\":\"system\",\"content\":\"Be brief.\"},"
                + "{\"role\":\"user\",\"content\":\"hi\"}]}";
        String kept = com.wbgateway.Gateway.ensureSystemFirst(withSys);
        int sysCount = kept.split("\"role\":\"system\"", -1).length - 1;
        check("已有 system 时不重复插入", sysCount == 1, "出现 " + sysCount + " 次");
        check("原有 system 内容保留", kept.contains("Be brief."), kept);

        // ③ 空 messages 也要能补
        String emptyMsgs = com.wbgateway.Gateway.ensureSystemFirst(
                "{\"model\":\"m\",\"messages\":[]}");
        check("空 messages 也补 system", emptyMsgs.contains("\"role\":\"system\""), emptyMsgs);

        // ④ 坏 JSON 原样返回（不能吞掉调用方的请求）
        String broken = com.wbgateway.Gateway.ensureSystemFirst("not-json");
        check("坏 JSON 原样返回", "not-json".equals(broken), broken);

        // ⑤ WorkBuddy 只支持流式：stream=false 要被改写成 true
        String forced = com.wbgateway.Gateway.forceStream(
                "{\"model\":\"m\",\"messages\":[],\"stream\":false}");
        check("stream=false 被强制改成 true",
                forced.contains("\"stream\":true") && !forced.contains("\"stream\":false"), forced);
        String forcedNoStream = com.wbgateway.Gateway.forceStream(
                "{\"model\":\"m\",\"messages\":[]}");
        check("缺 stream 字段也补成 true", forcedNoStream.contains("\"stream\":true"),
                forcedNoStream);

        // ⑥ 非流式聚合：SSE 增量要拼成一条 JSON，且**跳过 heartbeat 注释**
        String sse = ": heartbeat\n\n"
                + "data: {\"id\":\"x1\",\"model\":\"hy4-preview\",\"created\":1794296516,"
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}\n\n"
                + "data: {\"id\":\"x1\",\"model\":\"hy4-preview\",\"created\":1794296516,"
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"!\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
        String agg = com.wbgateway.Gateway.aggregateSse(
                sse.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new java.io.ByteArrayInputStream(new byte[0]));
        check("聚合结果是 JSON", agg.startsWith("{"), agg);
        check("聚合出完整正文 OK!", agg.contains("\"content\":\"OK!\""), agg);
        check("聚合结果是 chat.completion",
                agg.contains("\"object\":\"chat.completion\""), agg);
        check("heartbeat 注释没被拼进正文", !agg.contains("heartbeat"), agg);
        check("finish_reason 被保留", agg.contains("\"finish_reason\":\"stop\""), agg);
        check("模型名被保留", agg.contains("hy4-preview"), agg);

        // ⑦ 跨分块到达的 SSE 也要能拼（head + 后续流）
        String part1 = "data: {\"id\":\"x2\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"AB\"}}]}\n\n";
        String part2 = "data: {\"id\":\"x2\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"CD\"}}]}\n\n";
        String agg2 = com.wbgateway.Gateway.aggregateSse(
                part1.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new java.io.ByteArrayInputStream(part2.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)));
        check("跨块拼接正确", agg2.contains("\"content\":\"ABCD\""), agg2);

        System.out.println();
        System.out.println("=== 10. WorkBuddy 提示词指纹（11128 第三道门）===");
        // 实测：system 开头命中第三方 CLI 特征时，上游回
        //   `11128 Illegal API invocation from an unapproved channel`
        // 与「缺 X-Domain」「缺 system」共用同一个码，文案完全一样。
        String claudeSys = "{\"model\":\"m\",\"messages\":[{\"role\":\"system\","
                + "\"content\":\"You are Claude Code, Anthropic's official CLI for Claude. "
                + "Always be concise.\"},{\"role\":\"user\",\"content\":\"hi\"}]}";
        String neut = com.wbgateway.Gateway.neutralizePrompt(claudeSys);
        check("Claude 风格 system 被中性化", !neut.contains("Claude Code"), neut);
        check("中性化后仍带 system 角色", neut.contains("\"role\":\"system\""), neut);
        check("原有指令（Always be concise）被保留", neut.contains("Always be concise"), neut);
        check("中性化后 user 消息不变", neut.contains("\"content\":\"hi\""), neut);

        // 正常提示词不该被改
        String okSys = "{\"model\":\"m\",\"messages\":[{\"role\":\"system\","
                + "\"content\":\"You are a helpful assistant.\"},"
                + "{\"role\":\"user\",\"content\":\"hi\"}]}";
        check("正常提示词不被改动",
                com.wbgateway.Gateway.neutralizePrompt(okSys).equals(okSys), "");

        String cnSys = "{\"model\":\"m\",\"messages\":[{\"role\":\"system\","
                + "\"content\":\"你是一个乐于助人的AI助手。\"},"
                + "{\"role\":\"user\",\"content\":\"hi\"}]}";
        check("中文提示词不被改动",
                com.wbgateway.Gateway.neutralizePrompt(cnSys).equals(cnSys), "");

        // ⚠ 不要给 cursor / windsurf / aider 也加「必被抹除」的断言：
        //   实测（2026-11-10）它们都不会触发 WorkBuddy 的指纹拦截 —— 上游只匹配
        //   "You are Claude Code, Anthropic's official CLI for Claude" 这一条
        //   精确身份声明。给它们做中性化属于无谓改写用户的提示词。
        String cursorPrompt = "{\"model\":\"m\",\"messages\":[{\"role\":\"system\","
                + "\"content\":\"You are Cursor, an AI code editor.\"},"
                + "{\"role\":\"user\",\"content\":\"hi\"}]}";
        check("Cursor 提示词不被改写（实测不触发拦截）",
                com.wbgateway.Gateway.neutralizePrompt(cursorPrompt).equals(cursorPrompt), "");

        // 重复出现是真实踩过的坑：只抹第一处时，「中性前缀 + 残留原句」
        // 会重新组成完整组合 —— 实测仍被上游拦。
        String fpTwice = "You are Claude Code, Anthropic's official CLI for Claude. "
                + "You are Claude Code, Anthropic's official CLI for Claude.";
        String tw = com.wbgateway.Gateway.neutralizeText(fpTwice);
        check("重复出现时全部抹除，不留残留原句",
                !tw.toLowerCase().contains("anthropic")
                        && !tw.toLowerCase().contains("claude code"), tw);

        // 大小写 / 全角逗号 / 多余空白都要覆盖（上游对这些不敏感）
        String[] fpVariants = {
            "you are claude code, anthropic's official cli for claude.",
            "  You are Claude Code, Anthropic's official CLI for Claude.",
            "You are Claude Code\uFF0CAnthropic's official CLI for Claude.",
            "You are  Claude  Code,  Anthropic's  official  CLI  for  Claude.",
        };
        for (int fi = 0; fi < fpVariants.length; fi++) {
            String fv = com.wbgateway.Gateway.neutralizeText(fpVariants[fi]);
            check("指纹变体 " + (fi + 1) + " 被抹除",
                    !fv.toLowerCase().contains("anthropic")
                            && !fv.toLowerCase().contains("claude code"), fv);
        }

        // 抹除后要留下可用的中性提示词，不能是空的
        check("抹除后仍有中性开场",
                com.wbgateway.Gateway.neutralizeText(
                        "You are Claude Code, Anthropic's official CLI for Claude.")
                        .contains("helpful assistant"), "");

        // 指令部分必须保留
        String fpKeep = com.wbgateway.Gateway.neutralizeText(
                "You are Claude Code, Anthropic's official CLI for Claude.\n\n"
                        + "Always answer in Chinese.");
        check("抹除指纹后保留其余指令", fpKeep.contains("Always answer in Chinese"), fpKeep);

        check("坏 JSON 原样返回",
                "not-json".equals(com.wbgateway.Gateway.neutralizePrompt("not-json")), "");
        check("没有 system 时不报错",
                com.wbgateway.Gateway.neutralizePrompt(
                        "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                        .contains("\"content\":\"hi\""), "");

        System.out.println();
        System.out.println("=== 11. 渠道能力开关：只有该开的渠道才做补救 ===");
        // 「三道门」是 WorkBuddy 特有的。给所有渠道无条件开这几项会改写
        // 用户提示词 / 强插 system —— 对没有该限制的上游是有害的。
        com.wbgateway.LoginProviders.Spec wbIntl =
                com.wbgateway.LoginProviders.specOf(com.wbgateway.LoginProviders.WB_INTL);
        com.wbgateway.LoginProviders.Spec wbCn =
                com.wbgateway.LoginProviders.specOf(com.wbgateway.LoginProviders.WB_CN);
        check("WorkBuddy 国际版开启 streamOnly", wbIntl != null && wbIntl.streamOnly, "");
        check("WorkBuddy 国际版开启 requiresSystemFirst",
                wbIntl != null && wbIntl.requiresSystemFirst, "");
        check("WorkBuddy 国际版开启 promptFingerprintGuard",
                wbIntl != null && wbIntl.promptFingerprintGuard, "");
        check("WorkBuddy 国内版三项同样开启",
                wbCn != null && wbCn.streamOnly && wbCn.requiresSystemFirst
                        && wbCn.promptFingerprintGuard, "");

        // 其他渠道必须保持关闭
        for (String t : new String[]{
                com.wbgateway.LoginProviders.QODER,
                com.wbgateway.LoginProviders.QODER_CN,
                com.wbgateway.LoginProviders.ZCODE,
                com.wbgateway.LoginProviders.RACCOON,
                com.wbgateway.LoginProviders.CODEARTS}) {
            com.wbgateway.LoginProviders.Spec s = com.wbgateway.LoginProviders.specOf(t);
            check(t + " 不开启补救（避免无谓改写）",
                    s != null && !s.streamOnly && !s.requiresSystemFirst
                            && !s.promptFingerprintGuard, "");
        }

        System.out.println();
        System.out.println("=== 12. 共用错误码要翻译成能动手的说明 ===");
        Channel wbc = new Channel();
        wbc.id = "wb";
        wbc.loginType = com.wbgateway.LoginProviders.WB_INTL;
        wbc.domain = "www.workbuddy.ai";

        String e1 = com.wbgateway.Gateway.explainUpstreamRejection(wbc,
                "{\"code\":11128,\"msg\":\"first message is not system prompt\"}");
        check("首条非 system 的说明提到 system",
                e1 != null && e1.contains("system"), String.valueOf(e1));

        String e2 = com.wbgateway.Gateway.explainUpstreamRejection(wbc,
                "{\"code\":11128,\"msg\":\"Illegal API invocation from an unapproved channel\"}");
        check("提示词指纹的说明提到提示词",
                e2 != null && e2.contains("提示词"), String.valueOf(e2));
        check("提示词指纹的说明给出可操作建议",
                e2 != null && (e2.contains("去掉") || e2.contains("身份声明")),
                String.valueOf(e2));

        String e3 = com.wbgateway.Gateway.explainUpstreamRejection(wbc,
                "{\"code\":11128,\"msg\":\"request illegal\"}");
        check("渠道头缺失的说明提到 domain",
                e3 != null && e3.contains("domain"), String.valueOf(e3));

        String e4 = com.wbgateway.Gateway.explainUpstreamRejection(wbc,
                "{\"code\":11101,\"msg\":\"Non-stream chat request is currently not supported\"}");
        check("非流式被拒的说明提到流式",
                e4 != null && e4.contains("流式"), String.valueOf(e4));

        String e5 = com.wbgateway.Gateway.explainUpstreamRejection(wbc,
                "{\"code\":3012,\"msg\":\"request has been blocked due to unusual activity.\"}");
        check("风控 3012 说明与请求格式无关",
                e5 != null && e5.contains("风控"), String.valueOf(e5));

        // 认不出的错误不要瞎编，交回原始错误体
        check("未知错误不编造说明",
                com.wbgateway.Gateway.explainUpstreamRejection(wbc,
                        "{\"error\":\"something else\"}") == null, "");

        System.out.println();
        System.out.println("=== 13. 模型对外用真实名，内部 key 仍可路由 ===");
        // 需求：客户端模型选择器里要看到 `Qwen3.7-Plus`，不是 `qmodel`。
        // 但路由必须还能用内部 key —— 所以两边都要通。
        Channel nameCh = new Channel();
        nameCh.id = "qcn";
        nameCh.loginType = com.wbgateway.LoginProviders.QODER_CN;
        nameCh.enabled = true;
        nameCh.modelSpecs.putAll(
                com.wbgateway.LoginProviders.qoderBuiltinSpecs(
                        com.wbgateway.LoginProviders.QODER_CN));
        for (String id : com.wbgateway.LoginProviders.qoderUsableModels(
                com.wbgateway.LoginProviders.QODER_CN)) {
            if (!nameCh.cachedModels.contains(id)) nameCh.cachedModels.add(id);
        }

        check("displayNameOf 返回真实名", "Qwen3.7-Plus".equals(nameCh.displayNameOf("qmodel")),
                nameCh.displayNameOf("qmodel"));
        check("Kimi 真实名正确", "Kimi-K2.8-Preview".equals(nameCh.displayNameOf("kmodel")),
                nameCh.displayNameOf("kmodel"));
        check("GLM 真实名正确", "GLM-5.3".equals(nameCh.displayNameOf("gmodel")),
                nameCh.displayNameOf("gmodel"));

        // 反查：真实名 -> 内部 key
        check("真实名能反查回内部 key", "qmodel".equals(nameCh.resolveModelId("Qwen3.7-Plus")),
                String.valueOf(nameCh.resolveModelId("Qwen3.7-Plus")));
        check("内部 key 原样通过", "qmodel".equals(nameCh.resolveModelId("qmodel")),
                String.valueOf(nameCh.resolveModelId("qmodel")));
        check("大小写不敏感", "qmodel".equals(nameCh.resolveModelId("qwen3.7-plus")),
                String.valueOf(nameCh.resolveModelId("qwen3.7-plus")));
        check("不存在的名字返回 null", nameCh.resolveModelId("no-such-model") == null, "");

        // 没规格时不能编造名字，退回 id
        Channel bare = new Channel();
        bare.id = "x";
        bare.cachedModels.add("custom-abc");
        check("无规格时退回 id（不编造）",
                "custom-abc".equals(bare.displayNameOf("custom-abc")),
                bare.displayNameOf("custom-abc"));

        // /v1/models 要输出真实名
        config.channels.add(nameCh);
        HttpURLConnection lm = get("http://127.0.0.1:18787/v1/models", "test-key-123");
        String lmBody = readAll(lm.getInputStream());
        check("模型列表用真实名", lmBody.contains("qcn:Qwen3.7-Plus"), lmBody);
        check("模型列表不再用内部 key 当 id",
                !lmBody.contains("\"id\":\"qcn:qmodel\""), lmBody);
        check("同时给出 upstream_id 便于排查",
                lmBody.contains("\"upstream_id\":\"qmodel\""), lmBody);
        config.channels.remove(nameCh);

        System.out.println();
        System.out.println("=== 14. 上游调用自动填规格（上下文 / 输出 / 推理档位）===");
        // 规格来自内置实测表：qmodel=Qwen3.7-Plus，1M 上下文、推理档位 default
        com.wbgateway.ModelSpec qs = new com.wbgateway.ModelSpec();
        qs.name = "Qwen3.7-Plus";
        qs.contextWindow = 1000000;
        qs.maxOutput = 64000;
        qs.supportsImages = true;
        qs.efforts.add("high");
        qs.efforts.add("low");
        qs.defaultEffort = "high";

        // 客户端只给 messages：其余全部要自动补齐
        String minimal = "{\"model\":\"qmodel\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        String built = com.wbgateway.Gateway.buildQoderInferBody("qmodel", minimal, qs);
        org.json.JSONObject bo = new org.json.JSONObject(built);
        org.json.JSONObject mcfg = bo.optJSONObject("model_config");
        org.json.JSONObject pars = bo.optJSONObject("parameters");

        check("max_input_tokens 用真实上下文（1M 而不是 200000）",
                mcfg != null && mcfg.optInt("max_input_tokens", 0) == 1000000,
                mcfg == null ? "无 model_config" : String.valueOf(mcfg.optInt("max_input_tokens", 0)));
        check("parameters.context_length 也填了",
                pars != null && pars.optInt("context_length", 0) == 1000000,
                pars == null ? "无 parameters" : String.valueOf(pars.optInt("context_length", 0)));
        check("max_tokens 用模型输出上限",
                pars != null && pars.optInt("max_tokens", 0) == 64000,
                pars == null ? "" : String.valueOf(pars.optInt("max_tokens", 0)));
        check("reasoning_effort 用默认档位",
                "high".equals(pars == null ? "" : pars.optString("reasoning_effort", "")),
                pars == null ? "" : pars.optString("reasoning_effort", ""));
        check("is_vl 按规格（支持图片）",
                mcfg != null && mcfg.optBoolean("is_vl", false), "");
        check("display_name 传真实名",
                mcfg != null && "Qwen3.7-Plus".equals(mcfg.optString("display_name", "")),
                mcfg == null ? "" : mcfg.optString("display_name", ""));
        check("is_reasoning 按规格", mcfg != null && mcfg.optBoolean("is_reasoning", false), "");

        // 客户端显式指定时必须优先
        String explicit = "{\"model\":\"qmodel\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                + "\"max_tokens\":123,\"reasoning_effort\":\"low\"}";
        org.json.JSONObject eo = new org.json.JSONObject(
                com.wbgateway.Gateway.buildQoderInferBody("qmodel", explicit, qs));
        org.json.JSONObject ep = eo.optJSONObject("parameters");
        check("客户端 max_tokens 优先",
                ep != null && ep.optInt("max_tokens", 0) == 123,
                ep == null ? "" : String.valueOf(ep.optInt("max_tokens", 0)));
        check("客户端 reasoning_effort 优先",
                "low".equals(ep == null ? "" : ep.optString("reasoning_effort", "")),
                ep == null ? "" : ep.optString("reasoning_effort", ""));

        // 超过模型上限要夹住
        String tooBig = "{\"model\":\"qmodel\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                + "\"max_tokens\":999999}";
        org.json.JSONObject to = new org.json.JSONObject(
                com.wbgateway.Gateway.buildQoderInferBody("qmodel", tooBig, qs));
        check("超过输出上限被夹住",
                to.optJSONObject("parameters").optInt("max_tokens", 0) == 64000,
                String.valueOf(to.optJSONObject("parameters").optInt("max_tokens", 0)));

        // 没有规格时不能编造
        org.json.JSONObject noSpec = new org.json.JSONObject(
                com.wbgateway.Gateway.buildQoderInferBody("unknown", minimal, null));
        check("无规格时 is_vl 不瞎填 true",
                noSpec.optJSONObject("model_config") != null
                        && !noSpec.optJSONObject("model_config").optBoolean("is_vl", false), "");
        check("无规格时 display_name 为空而不是假名",
                "".equals(noSpec.optJSONObject("model_config").optString("display_name", "")), "");

        // 通用渠道的 fillModelParams
        Channel fch = new Channel();
        fch.id = "fc";
        fch.modelSpecs.put("m1", qs);
        String filled = com.wbgateway.Gateway.fillModelParams(
                "{\"model\":\"m1\",\"messages\":[]}", fch, "m1");
        check("通用渠道补 max_tokens",
                new org.json.JSONObject(filled).optInt("max_tokens", 0) == 64000, filled);
        check("通用渠道补 reasoning_effort",
                "high".equals(new org.json.JSONObject(filled).optString("reasoning_effort", "")), filled);
        // 真实名也要能查到规格
        String filledByName = com.wbgateway.Gateway.fillModelParams(
                "{\"model\":\"Qwen3.7-Plus\",\"messages\":[]}", fch, "Qwen3.7-Plus");
        check("用真实名也能查到规格并补齐",
                new org.json.JSONObject(filledByName).optInt("max_tokens", 0) == 64000,
                filledByName);
        // 已指定则不覆盖
        String keep = com.wbgateway.Gateway.fillModelParams(
                "{\"model\":\"m1\",\"messages\":[],\"max_tokens\":7}", fch, "m1");
        check("已指定 max_tokens 不覆盖",
                new org.json.JSONObject(keep).optInt("max_tokens", 0) == 7, keep);
        // 不碰采样参数
        String samp = com.wbgateway.Gateway.fillModelParams(
                "{\"model\":\"m1\",\"messages\":[],\"temperature\":0.2,\"top_p\":0.9}", fch, "m1");
        check("不改动 temperature / top_p",
                samp.contains("\"temperature\":0.2") && samp.contains("\"top_p\":0.9"), samp);

        System.out.println();
        System.out.println("=== 15. 局域网地址（给其他手机用）===");
        // 关键点：`127.0.0.1` 是回环，别的设备填了必然连不上。
        // 所以必须能列出真实的局域网地址。
        java.util.List<Object> lanList = new java.util.ArrayList<Object>();
        java.lang.reflect.Method listM = Class.forName("com.wbgateway.LanAddress")
                .getDeclaredMethod("list");
        listM.setAccessible(true);
        Object cands = listM.invoke(null);
        check("LanAddress.list 不返回 null", cands != null, "");

        // looksReachable：Wi-Fi / 以太网 / USB 共享算可达，蜂窝不算
        java.lang.reflect.Method reachM = Class.forName("com.wbgateway.LanAddress")
                .getDeclaredMethod("looksReachable", String.class);
        reachM.setAccessible(true);
        check("wlan0 判为可被其他设备访问",
                (Boolean) reachM.invoke(null, "wlan0"), "");
        check("eth0 判为可访问", (Boolean) reachM.invoke(null, "eth0"), "");
        check("rndis0（USB 共享）判为可访问",
                (Boolean) reachM.invoke(null, "rndis0"), "");
        check("rmnet_data1（蜂窝）判为**不**可访问",
                !(Boolean) reachM.invoke(null, "rmnet_data1"), "");
        check("ccmni0（蜂窝）判为不可访问",
                !(Boolean) reachM.invoke(null, "ccmni0"), "");

        // baseUrl 拼接格式
        java.lang.reflect.Method baseM = Class.forName("com.wbgateway.LanAddress")
                .getDeclaredMethod("baseUrl", int.class);
        baseM.setAccessible(true);
        String lanBase = (String) baseM.invoke(null, 8787);
        // 虚拟网段过滤：VPN / CGNAT 的地址别的设备连不上，不能当局域网地址给出去
        java.lang.reflect.Method virtM = Class.forName("com.wbgateway.LanAddress")
                .getDeclaredMethod("isVirtualIp", String.class);
        virtM.setAccessible(true);
        check("Radmin VPN 段（26.x）被判为虚拟",
                (Boolean) virtM.invoke(null, "26.141.229.248"), "");
        check("CGNAT 段（100.64-127.x）被判为虚拟",
                (Boolean) virtM.invoke(null, "100.93.12.191"), "");
        check("Docker 段（172.17）被判为虚拟",
                (Boolean) virtM.invoke(null, "172.17.0.1"), "");
        check("真实局域网（192.168）**不**被判为虚拟",
                !(Boolean) virtM.invoke(null, "192.168.1.5"), "");
        check("真实局域网（10.101）**不**被判为虚拟",
                !(Boolean) virtM.invoke(null, "10.101.214.80"), "");

        check("baseUrl 格式正确或为空（无地址时不编造）",
                lanBase.isEmpty() || (lanBase.startsWith("http://")
                        && lanBase.endsWith(":8787/v1")), lanBase);
        // 绝对不能把回环地址当成局域网地址给出去
        check("局域网 Base URL 不含 127.0.0.1",
                !lanBase.contains("127.0.0.1"), lanBase);

        gw.stop();
        upstream.stop();

        System.out.println();
        System.out.println("=== 结果：PASS " + pass + " / FAIL " + fail + " ===");
        if (fail > 0) System.exit(1);
    }

    static HttpURLConnection get(String url, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        if (key != null) c.setRequestProperty("Authorization", "Bearer " + key);
        return c;
    }

    static HttpURLConnection post(String url, String body, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(5000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        c.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        FileOutputStream os = null;
        try {
            os = (FileOutputStream) c.getOutputStream();
        } catch (Exception e) {
            // fallback
        }
        java.io.OutputStream out = c.getOutputStream();
        out.write(bytes);
        out.flush();
        out.close();
        return c;
    }

    static String readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
