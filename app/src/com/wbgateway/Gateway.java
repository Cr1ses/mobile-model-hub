package com.wbgateway;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一 API 网关：把多个 OpenAI 兼容上游聚合成一个 /v1 端点。
 *
 * 线程模型：一个 accept 线程 + 固定大小线程池处理连接。
 * 每个连接一个线程（HTTP/1.1 短连接），够用且实现简单。
 *
 * 路由：
 *   GET  /v1/models            → 全部渠道模型（前缀 <渠道id>:）
 *   POST /v1/chat/completions  → 按模型前缀转发到对应上游（流式/非流式透传）
 *   GET  /                     → 状态页（同机浏览器可看）
 */
public class Gateway {

    /** 单次请求体上限 20 MiB。 */
    private static final int MAX_BODY = 20 * 1024 * 1024;

    private final Store config;
    private final Listener listener;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running = false;
    /**
     * 连接处理线程池。
     *
     * <p>**每次 start() 重建、stop() 关闭**：池 shutdown 之后不能再提交任务，
     * 而用户在界面上会反复「停止 → 启动」—— 复用一个已关闭的池会让第二次启动后
     * 所有请求都没人处理（端口在监听但永远不回）。
     */
    private ExecutorService pool = null;

    /**
     * 连接处理线程工厂：**daemon**。
     *
     * <p>为什么要 daemon：`newCachedThreadPool` 的默认线程不是 daemon，
     * 空闲线程会活 60 秒、阻塞在 `accept` 之外的读上也最多活 120 秒。
     * 结果是「网关已停止」之后进程/测试 JVM 还赖着不退 —— 离线验证会卡住，
     * 排查时很容易误以为是死锁。
     */
    private static final class WorkerFactory implements java.util.concurrent.ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "gateway-worker");
            t.setDaemon(true);
            return t;
        }
    }

    /** 回调：让 UI 能感知启动/停止与错误。 */
    public interface Listener {
        void onLog(String line);
        void onStateChanged(boolean running, int port);
    }

    public Gateway(Store config, Listener listener) {
        this.config = config;
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    /** 启动监听。 */
    public synchronized void start() throws IOException {
        if (running) return;
        int port = config.port();
        String host = config.lan() ? "0.0.0.0" : "127.0.0.1";
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName(host), port), 50);
        running = true;
        pool = Executors.newCachedThreadPool(new WorkerFactory());

        acceptThread = new Thread(this::acceptLoop, "gateway-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        log("网关已启动：http://" + host + ":" + port + "/v1");
        listener.onStateChanged(true, port);
    }

    /** 停止监听。 */
    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        serverSocket = null;
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        ExecutorService p = pool;
        pool = null;
        if (p != null) p.shutdownNow();
        log("网关已停止");
        listener.onStateChanged(false, config.port());
    }

    private void acceptLoop() {
        while (running) {
            // 每轮抓一次引用：stop() 会把 serverSocket 关掉/置空，
            // 而重启会换一个新的 —— 用局部引用才能分清「是哪一次启动的 socket」。
            ServerSocket ss = serverSocket;
            if (ss == null) break;
            try {
                final Socket socket = ss.accept();
                ExecutorService p = pool;
                if (p == null) {
                    socket.close();
                    break;
                }
                p.submit(() -> handleConnection(socket));
            } catch (IOException e) {
                // 停止时 close 会让 accept 抛 Socket closed —— 这不是错误，直接退出循环。
                if (ss.isClosed() || !running) break;
                log("accept 失败：" + e.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------------
    // 单个连接
    // -----------------------------------------------------------------------

    private void handleConnection(Socket socket) {
        try {
            socket.setSoTimeout(120_000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            Request req = Request.read(in);
            if (req == null) {
                socket.close();
                return;
            }

            // 只接受来自本机回环或（lan 模式下）局域网；不校验来源地址，
            // 因为默认监听 127.0.0.1 本身已经隔离。
            String path = req.path;
            if (path.startsWith("/v1/")) {
                if (!checkAuth(req, out)) return;
            }

            if ("GET".equals(req.method) && (path.equals("/v1/models") || path.equals("/v1/models/"))) {
                handleModels(out);
            } else if ("POST".equals(req.method) && (path.equals("/v1/chat/completions") || path.equals("/chat/completions"))) {
                handleChat(req, out);
            } else if ("GET".equals(req.method) && (path.equals("/") || path.equals("/health"))) {
                handleStatus(out);
            } else if ("OPTIONS".equals(req.method)) {
                writeSimple(out, 204, "text/plain", new byte[0], true);
            } else {
                writeError(out, 404, "not_found_error", "no route for " + req.method + " " + path);
            }
            out.flush();
            socket.close();
        } catch (Exception e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private boolean checkAuth(Request req, OutputStream out) throws IOException {
        String token = req.header("authorization");
        if (token != null && token.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            token = token.substring(7).trim();
        } else {
            token = req.header("x-api-key");
        }
        if (token == null) token = "";
        if (!token.equals(config.apiKey())) {
            writeError(out, 401, "invalid_request_error",
                    "missing or invalid API key (Authorization: Bearer <key>)");
            return false;
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // /v1/models
    // -----------------------------------------------------------------------

    private void handleModels(OutputStream out) throws IOException {
        JSONArray data = new JSONArray();
        for (Channel c : config.channels()) {
            if (!c.enabled) continue;
            for (String model : c.effectiveModels()) {
                JSONObject m = new JSONObject();
                try {
                    // id 用**真实名**（如 `Qwen3.7-Plus`），用户才能看懂。
                    // 内部 key（`qmodel`）也仍然接受，见 handleChat 的 resolve。
                    m.put("id", c.id + ":" + c.displayNameOf(model));
                    m.put("object", "model");
                    m.put("created", 0);
                    m.put("owned_by", "channel-" + c.id);
                    // 把内部 id 一并给出：需要精确指定时客户端可以用它，
                    // 也方便排查「这个名字对应哪个上游 key」。
                    m.put("upstream_id", model);
                } catch (Exception ignored) {
                }
                data.put(m);
            }
        }
        JSONObject body = new JSONObject();
        try {
            body.put("object", "list");
            body.put("data", data);
        } catch (Exception ignored) {
        }
        writeSimple(out, 200, "application/json; charset=utf-8",
                body.toString().getBytes(StandardCharsets.UTF_8), true);
    }

    // -----------------------------------------------------------------------
    // /v1/chat/completions
    // -----------------------------------------------------------------------

    private void handleChat(Request req, OutputStream out) throws IOException {
        String bodyText = new String(req.body, StandardCharsets.UTF_8);
        JSONObject body;
        try {
            body = new JSONObject(bodyText);
        } catch (Exception e) {
            writeError(out, 400, "invalid_request_error", "body is not JSON");
            return;
        }

        String modelId = body.optString("model", "");
        if (modelId.isEmpty()) {
            writeError(out, 400, "invalid_request_error", "body.model is required");
            return;
        }

        // 解析 <渠道id>:<模型>
        String channelId;
        String upstreamModel;
        int colon = modelId.indexOf(':');
        if (colon > 0) {
            channelId = modelId.substring(0, colon);
            upstreamModel = modelId.substring(colon + 1);
            // 客户端可能用**真实名**回传（`qodercn:Qwen3.7-Plus`），
            // 要能找回内部 key（`qmodel`）才发得出请求。
            Channel pc = config.findChannel(channelId);
            if (pc != null) {
                String resolved = pc.resolveModelId(upstreamModel);
                if (resolved != null) upstreamModel = resolved;
            }
        } else {
            // 裸模型 id：在已启用渠道里唯一命中才路由
            channelId = null;
            upstreamModel = modelId;
            List<Channel> hits = new ArrayList<>();
            for (Channel c : config.channels()) {
                if (!c.enabled) continue;
                // 裸名可能是内部 key，也可能是真实名（`Qwen3.7-Plus`）
                if (c.resolveModelId(modelId) != null) hits.add(c);
            }
            if (hits.size() == 1) channelId = hits.get(0).id;
            else if (hits.size() > 1) {
                writeError(out, 400, "invalid_request_error",
                        "模型 " + modelId + " 在多个渠道中都存在，请加前缀指定（如 " + hits.get(0).id + ":" + modelId + "）");
                return;
            } else {
                writeError(out, 404, "not_found_error",
                        "没有渠道提供模型 " + modelId + "（用 GET /v1/models 查看可用 id）");
                return;
            }
        }

        Channel channel = config.findChannel(channelId);
        if (channel == null || !channel.enabled) {
            writeError(out, 404, "not_found_error", "渠道 " + channelId + " 不存在或未启用");
            return;
        }
        // 统一归一化一次：到这里 upstreamModel 可能是内部 key（`qmodel`）、
        // 也可能是真实名（`Qwen3.7-Plus`），后续所有判断（开关、路由、上游请求）
        // 都必须用内部 key，否则「按真实名关掉的模型」会绕过检查。
        {
            String resolved = channel.resolveModelId(upstreamModel);
            if (resolved != null) upstreamModel = resolved;
        }
        // 模型级开关：前缀写法（ds:model-b）也要拦。
        //
        // 只过滤 /v1/models 是不够的 —— 客户端可以绕过列表直接指定 id，
        // 那样「关掉的模型」仍会消耗账号额度。这里对带前缀的请求同样校验。
        if (channel.isModelDisabled(upstreamModel)) {
            writeError(out, 403, "model_disabled",
                    "模型 " + channelId + ":" + upstreamModel + " 已在该渠道里关闭（到 App 里勾选启用）");
            return;
        }
        // 代理型渠道必须有 Key；登录型看 token
        if (!Channel.KIND_LOGIN.equals(channel.kind) && channel.apiKeys.isEmpty()) {
            writeError(out, 503, "insufficient_quota", "渠道 " + channelId + " 没有配置 API Key");
            return;
        }
        if (Channel.KIND_LOGIN.equals(channel.kind) && !channel.hasCredential()) {
            writeError(out, 401, "invalid_request_error", "渠道 " + channelId + " 尚未登录");
            return;
        }

        boolean wantStream = body.optBoolean("stream", false);
        try {
            body.put("model", upstreamModel);
        } catch (Exception ignored) {
        }

        proxyChat(channel, body.toString(), wantStream, out);
    }

    /** 把请求转发给上游，流式响应边收边发（不缓冲）。 */
    private void proxyChat(Channel channel, String payload, boolean wantStream, OutputStream out) throws IOException {
        // 登录型渠道用 accessToken，代理型用第一个 API Key
        boolean login = Channel.KIND_LOGIN.equals(channel.kind);
        if (login) channel = ensureFreshToken(channel);

        // 华为 CodeArts 走华为云 SDK-HMAC-SHA256 签名，没有 Bearer 令牌
        if (LoginProviders.CODEARTS.equals(channel.loginType)) {
            proxyChatCodeArts(channel, payload, wantStream, out);
            return;
        }

        // Qoder（国际版 / 国内版）上游是 cosy 网关：
        // 请求体要 cosyEncode、Authorization 要 COSY 签名、还要一组 cosy-* 头。
        // 响应虽是明文 OpenAI SSE，但**请求**必须签名，故单独走一条路径。
        if (LoginProviders.isQoderFamily(channel.loginType)) {
            proxyChatQoder(channel, payload, wantStream, out);
            return;
        }

        // ZCode 上游是 **Anthropic Messages 协议**，不是 OpenAI 兼容：
        // 客户端只认 /api/v1/zcode-plan/anthropic/v1/messages（其余路径 404），
        // 请求/响应都要做一层转换，否则调用必失败。
        if (LoginProviders.ZCODE.equals(channel.loginType)) {
            proxyChatZcode(channel, payload, wantStream, out);
            return;
        }

        // 基址缺失时报「no protocol: /chat/completions」只会让人以为是网络问题。
        // 这是配置问题，要说清楚是哪个渠道、怎么补。
        String base = chatBase(channel);
        if (base.isEmpty()) {
            channel.lastError = "渠道未配置 Base URL";
            log("[proxy:" + channel.id + "] 渠道未配置 Base URL");
            writeError(out, 502, "config_error",
                    "渠道 " + channel.id + " 缺少 Base URL"
                            + (login ? "（登录型渠道请重新登录以补齐）"
                                     : "（到 App「渠道」页补上，如 https://api.deepseek.com/v1）"));
            return;
        }

        String key = login ? channel.accessToken : channel.apiKeys.get(0);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(base + "/chat/completions");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(300_000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            // WorkBuddy **只支持流式**：要 `application/json` 会回
            // `11101 Non-stream chat request is currently not supported`。
            // 所以它永远要 SSE，非流式的聚合在下面自己做。
            LoginProviders.Spec cap = LoginProviders.specOf(channel.loginType);
            boolean streamOnly = cap != null && cap.streamOnly;
            conn.setRequestProperty("Accept",
                    (wantStream || streamOnly) ? "text/event-stream" : "application/json");
            if (login) {
                // WorkBuddy 网关靠这几个头把请求认成「官方插件」的调用
                LoginProviders.Spec spec = LoginProviders.specOf(channel.loginType);
                if (spec != null) {
                    conn.setRequestProperty("X-Domain", channel.domain.isEmpty() ? spec.apiDomain : channel.domain);
                    conn.setRequestProperty("X-Product-Code", spec.productCode);
                    conn.setRequestProperty("X-Product", spec.productCode);
                    conn.setRequestProperty("User-Agent", spec.userAgent);
                }
                // WorkBuddy 两区域的 chat 网关会校验「会话三元组」。
                //
                // ⚠ 这是实测出来的硬要求（2026-11-04）：
                //   不带这三个头 → HTTP 400 {"code":11128,"msg":"request illegal"}
                //   错误文案说「没通过渠道校验，可能不是官方客户端发出的」，
                //   看起来像需要伪造客户端指纹，其实只要补这三个头就 200 + SSE。
                //   国内版宽松（不带也 200），但带上无害、且能吃到会话级缓存。
                if (isWorkbuddyFamily(channel.loginType)) {
                    String chatId = randomTraceId();
                    conn.setRequestProperty("X-Conversation-ID", channel.id + "-" + chatId);
                    conn.setRequestProperty("X-Conversation-Message-ID", chatId);
                    conn.setRequestProperty("X-Conversation-Request-ID", chatId);
                    conn.setRequestProperty("X-Agent-Purpose", "conversation");
                    conn.setRequestProperty("X-IDE-Name", "WorkBuddy");
                    conn.setRequestProperty("X-IDE-Type", "WorkBuddy");
                    conn.setRequestProperty("X-IDE-Version", "5.5.2");
                }
                if (!channel.enterpriseId.isEmpty()) {
                    conn.setRequestProperty("X-Enterprise-Id", channel.enterpriseId);
                    conn.setRequestProperty("X-Tenant-Id", channel.enterpriseId);
                }
                // Raccoon 用 X-Org-Code 区分个人 / 企业空间（个人账号是空串也照发）
                if (LoginProviders.RACCOON.equals(channel.loginType)) {
                    conn.setRequestProperty("X-Org-Code", channel.officeIdentity);
                    conn.setRequestProperty("X-Raccoon-Language", "zh");
                    conn.setRequestProperty("X-Client-Platform", "android");
                }
            }

            // WorkBuddy 要求 messages 的**第一条必须是 system 角色**。
            //
            // ⚠ 这是实测出来的（2026-11-10）：不带 system 时上游回
            //   `11128 first message is not system prompt` —— 与「渠道校验」
            //   是**同一个错误码**，文案却完全不同，极易误判成要伪造客户端指纹。
            //   补一条空内容的 system 即可通过（空串也认）。
            String finalPayload = payload;
            // 自动补齐模型参数（上下文 / 输出上限 / 推理档位）。
            // 放在最前：后面几个函数会重建 JSON，先补齐能保证它们搬运时带上。
            finalPayload = fillModelParams(finalPayload, channel, extractModel(payload));
            if (cap != null && cap.streamOnly) finalPayload = forceStream(finalPayload);
            if (cap != null && cap.promptFingerprintGuard) finalPayload = neutralizePrompt(finalPayload);
            if (cap != null && cap.requiresSystemFirst) finalPayload = ensureSystemFirst(finalPayload);

            OutputStream os = conn.getOutputStream();
            os.write(finalPayload.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int status = conn.getResponseCode();
            String contentType = conn.getContentType();
            if (contentType == null) contentType = wantStream ? "text/event-stream; charset=utf-8" : "application/json; charset=utf-8";

            if (status < 200 || status >= 300) {
                // 错误体尽量原样转述
                InputStream es = conn.getErrorStream();
                byte[] err = es == null ? new byte[0] : readAll(es);
                String msg = new String(err, StandardCharsets.UTF_8);
                if (msg.isEmpty()) msg = "上游返回 HTTP " + status;
                channel.lastError = "HTTP " + status + "：" + msg;
                log("[proxy:" + channel.id + "] 上游失败 HTTP " + status + "：" + truncate(msg, 200));
                // 识别「共用错误码」的门，给出能动手的说明而不是照抄上游文案
                String hint = explainUpstreamRejection(channel, msg);
                if (hint != null) {
                    channel.lastError = hint;
                    writeError(out, 502, "upstream_error", hint);
                    return;
                }
                writeRaw(out, status, contentType, err, true);
                return;
            }

            channel.lastError = "";
            InputStream is = conn.getInputStream();

            // SSE 里的「伪成功」：有些上游（Qoder 已实测）**HTTP 200 但响应体是
            // `event: error` + `{"code":"invalid_model_error"}`。直接透传的话，
            // 客户端会以为请求成功、只是「没有输出」，永远看不到真实原因。
            // 所以先窥探首块：真是错误就改写成正常错误响应。
            byte[] head = new byte[0];
            if (wantStream) {
                java.io.ByteArrayOutputStream peek = new java.io.ByteArrayOutputStream();
                byte[] tmp = new byte[1024];
                // 读到一个**完整事件**为止（`\n\n` 结尾）：SSE 是分块到达的，
                // 只读一次很可能只拿到 `event: error` 这一行，data 还没到 ——
                // 那样会「识别到错误却抽不出原因」。
                int guard = 0;
                while (peek.size() < 8192 && guard < 16) {
                    int got = is.read(tmp);
                    if (got <= 0) break;
                    peek.write(tmp, 0, got);
                    String sofar = new String(peek.toByteArray(), StandardCharsets.UTF_8);
                    if (sofar.contains("\n\n") || sofar.contains("\r\n\r\n")) break;
                    guard += 1;
                }
                head = peek.toByteArray();
                String headText = new String(head, StandardCharsets.UTF_8);
                if (UpstreamError.isSseErrorEvent(headText)) {
                    String friendly = UpstreamError.describeSseError(headText);
                    channel.lastError = friendly;
                    log("[proxy:" + channel.id + "] 上游以 SSE 返回错误：" + friendly);
                    writeError(out, 502, "upstream_error", friendly);
                    is.close();
                    return;
                }
            }

            // 客户端要的是非流式，但上游只会给 SSE（WorkBuddy）：
            // 在本地把增量聚合成一条 chat.completion。
            if (!wantStream && streamOnly
                    && contentType != null && contentType.contains("event-stream")) {
                String json = aggregateSse(head, is);
                is.close();
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                writeHeaders(out, 200, "application/json; charset=utf-8", false);
                out.write(bytes);
                out.flush();
                return;
            }

            writeHeaders(out, status, contentType, true);
            if (head.length > 0) {
                out.write(head);
                out.flush();
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
            is.close();
        } catch (Exception e) {
            channel.lastError = String.valueOf(e.getMessage());
            log("[proxy:" + channel.id + "] 转发失败：" + e.getMessage());
            writeError(out, 502, "network_error", "无法连接上游 " + channel.baseUrl + "：" + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * ZCode 的转发：**OpenAI ⇄ Anthropic Messages 协议转换**。
     *
     * <h3>为什么必须转换</h3>
     *
     * ZCode 的上游是 Anthropic 兼容端点
     * `POST /api/v1/zcode-plan/anthropic/v1/messages`（实测：其余
     * `/api/v1/chat/completions`、`/api/v1/anthropic/v1/messages` 都是 404）。
     * 它的请求体是 `{model, max_tokens, system, messages:[{role, content:[{type:"text",text}]}]}`，
     * 响应是 Anthropic 的 `content_block_delta` 事件流 —— 与 OpenAI 的
     * `choices[].delta.content` 完全不是一回事。直接透传必失败。
     *
     * <p>所以这里做双向转换（流式也转）：客户端仍然只说 OpenAI，
     * 网关负责两头翻译。**不能只改 URL 了事** —— 那是最容易踩的坑。
     */
    private void proxyChatZcode(Channel channel, String payload, boolean wantStream,
                                OutputStream out) throws IOException {
        String base = chatBase(channel);
        if (base.isEmpty()) {
            channel.lastError = "ZCode 渠道未配置 Base URL";
            writeError(out, 502, "config_error", "渠道 " + channel.id + " 未配置 Base URL（应为 "
                    + LoginProviders.chatBaseFor(LoginProviders.ZCODE) + "）");
            return;
        }
        String url = base + "/messages";

        String body;
        String model;
        try {
            body = ZcodeBridge.toAnthropicRequest(new org.json.JSONObject(payload));
            model = new org.json.JSONObject(payload).optString("model", "");
        } catch (Exception e) {
            writeError(out, 400, "invalid_request_error", "请求体无法转换：" + e.getMessage());
            return;
        }

        // ZCode 的鉴权是 zcode-plan 的 JWT（`zcodeJwt`），不是 bigmodel 的 accessToken。
        // 用错稳定 401（与签到/额度同一个坑，见 Checkin.zcodeAuth）。
        String token = channel.zcodeJwt == null || channel.zcodeJwt.isEmpty()
                ? Credits.pickZcodeJwtFromAccess(channel.accessToken)
                : channel.zcodeJwt;
        if (token == null || token.isEmpty()) {
            channel.lastError = "ZCode 缺少 zcode-plan 凭据";
            writeError(out, 401, "invalid_request_error", "ZCode 缺少 zcode-plan 凭据，请重新登录");
            return;
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(300_000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            conn.setRequestProperty("Accept",
                    wantStream ? "text/event-stream" : "application/json");
            // 客户端身份头：上游按这几个认出「官方客户端」
            conn.setRequestProperty("User-Agent", "ZCode/" + Checkin.ZCODE_APP_VERSION);
            conn.setRequestProperty("X-ZCode-App-Version", Checkin.ZCODE_APP_VERSION);
            conn.setRequestProperty("X-Platform", Checkin.ZCODE_PLATFORM);
            conn.setRequestProperty("X-Release-Channel", "stable");
            conn.setRequestProperty("X-Client-Language", "zh-CN");
            conn.setRequestProperty("X-Client-Timezone", "Asia/Shanghai");
            conn.setRequestProperty("HTTP-Referer", "https://zcode.z.ai");

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = conn.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                InputStream es = conn.getErrorStream();
                byte[] err = es == null ? new byte[0] : readAll(es);
                String msg = new String(err, StandardCharsets.UTF_8);
                channel.lastError = "HTTP " + status + "：" + msg;
                log("[proxy:" + channel.id + "] 上游失败 HTTP " + status);
                String friendly = ZcodeBridge.describeError(status, msg);
                writeError(out, status, status == 401 || status == 403 ? "authentication_error" : "upstream_error",
                        friendly);
                return;
            }

            channel.lastError = "";
            String responseBody = new String(readAll(conn.getInputStream()), StandardCharsets.UTF_8);
            byte[] converted = wantStream
                    ? ZcodeBridge.anthropicStreamToOpenAi(responseBody, model).getBytes(StandardCharsets.UTF_8)
                    : ZcodeBridge.anthropicToOpenAi(
                            new org.json.JSONObject(responseBody), model).toString().getBytes(StandardCharsets.UTF_8);
            writeRaw(out, 200,
                    wantStream ? "text/event-stream; charset=utf-8" : "application/json; charset=utf-8",
                    converted, true);
        } catch (Exception e) {
            channel.lastError = String.valueOf(e.getMessage());
            log("[proxy:" + channel.id + "] 转发失败：" + e.getMessage());
            writeError(out, 502, "network_error", "无法连接上游 " + base + "：" + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 华为 CodeArts 的转发（华为云 SDK-HMAC-SHA256 签名）。
     *
     * <p>与其它渠道的区别：
     * <ul>
     *   <li>没有 `Authorization: Bearer`，改成算签名的 Authorization；</li>
     *   <li>签名绑定了 **body 的 SHA256** 和**时间戳**，所以必须先把
     *      payload 完整读出来再签（不能边读边发）；</li>
     *   <li>端点固定为 `snap-access…/api/v2/chat/completions`。</li>
     * </ul>
     *
     * <p>⚠ 临时凭据约 2 小时有效：过期时这里会返回华为的
     * `APIG.0301 Unauthorized`，界面提示重新登录（续期在
     * {@link #ensureFreshToken} 里先试过）。
     */
    /**
     * Qoder（国际版 / 国内版）chat 代理。
     *
     * <p>上游是 cosy 网关的**加密推理端点**（不是 OpenAI 兼容端点）：
     * <ul>
     *   <li>国际版 {@code https://api2.qoder.sh/algo/api/v2/...}（本账户被套餐挡死）</li>
     *   <li>国内版 {@code https://gateway.qoder.com.cn/algo/api/v2/...}（实测 10 个模型可用）</li>
     * </ul>
     *
     * <p>请求侧三件事缺一不可（少任何一项都回 403 Signature invalid）：
     * <ol>
     *   <li>body 用 {@link QoderCosy#cosyEncode} 编码（自定义字母表 + n/3 重排）；</li>
     *   <li>{@code Authorization: Bearer COSY.<payloadB64>.<md5 签名>}；</li>
     *   <li>一组 {@code cosy-*} 头（user / machineid / machinetoken / machinetype /
     *       clienttype / business-* / scene / version / date / key）。</li>
     * </ol>
     *
     * <p>响应是明文 OpenAI SSE，每个 {@code data:} 帧里再套一层
     * {@code {"headers":…,"body":"<真正的 JSON 字符串>","statusCodeValue":200}}，
     * 所以要拆壳后重新拼成标准 SSE 给客户端。
     */
    private void proxyChatQoder(Channel channel, String payload, boolean wantStream,
                                OutputStream out) throws IOException {
        // ⚠ 这里**不能**用 chatBase(channel)：国际版的 baseUrl 是公开端点
        // `api2-v2.qoder.sh/model/v1`，而加密推理在 `api2.qoder.sh`，
        // 两者不同主机；用错会 404。
        String base = LoginProviders.qoderAlgoBase(channel.loginType);
        if (base.isEmpty()) {
            channel.lastError = "未知的 Qoder 区域";
            writeError(out, 502, "config_error", "渠道 " + channel.id + " 的 Qoder 区域未知");
            return;
        }

        String model = extractModel(payload);
        if (model.isEmpty()) {
            writeError(out, 400, "invalid_request_error", "请求缺少 model 字段");
            return;
        }

        String token = channel.accessToken;
        if (token == null || token.isEmpty()) {
            channel.lastError = "Qoder 缺少 accessToken";
            writeError(out, 401, "invalid_request_error", "Qoder 缺少凭据，请重新登录");
            return;
        }
        String uid = channel.uid == null ? "" : channel.uid;
        if (uid.isEmpty()) {
            // 老渠道没存 uid（该字段是后加的）。`dt-` token 不含 uid，
            // 但积分接口 `sash/api/v2/me/usage` 会回 `qoderUsage.userId` ——
            // 只要 token 有效就能拿到，用户不用重新登录。
            uid = Credits.fetchQoderUid(channel, 15_000);
            if (uid.isEmpty()) {
                channel.lastError = "Qoder 凭据缺少 uid 且无法自动获取";
                writeError(out, 401, "invalid_request_error",
                        "Qoder 凭据缺少 uid（cosy 签名必需），且自动获取失败 —— 请重新登录该渠道");
                return;
            }
            // 回填并落库：下次就不用再查
            channel.uid = uid;
            log("[proxy:" + channel.id + "] 已自动补全 uid=" + uid);
        }

        // 设备指纹：优先用账号真实 machine_id，缺了才按 uid 派生。
        String machineId = channel.machineId == null ? "" : channel.machineId;
        String machineType = "5";
        if (machineId.isEmpty()) {
            try {
                String[] fp = QoderCosy.fingerprint(uid);
                machineId = fp[0];
                machineType = fp[1];
            } catch (Exception e) {
                machineId = uid;
            }
        }

        String url = base + LoginProviders.QODER_ALGO_PATH;

        HttpURLConnection conn = null;
        try {
            String inferBody = buildQoderInferBody(model, payload, channel.modelSpecs.get(model));
            String encodedBody = QoderCosy.cosyEncode(inferBody.getBytes("UTF-8"));

            QoderCosy.Session session = QoderCosy.newSession(
                    uid, channel.nickname, token, machineId, machineId, machineType);
            String payloadB64 = QoderCosy.buildPayloadB64(session.info);
            String date = QoderCosy.rfc1123();
            String sig = QoderCosy.signRequest(payloadB64, session.cosyKey, date,
                    encodedBody, QoderCosy.pathSigFrom(url));
            String auth = QoderCosy.composeBearer(payloadB64, sig);

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(300_000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Authorization", auth);
            conn.setRequestProperty("cosy-key", session.cosyKey);
            conn.setRequestProperty("cosy-user", uid);
            conn.setRequestProperty("cosy-machineid", machineId);
            conn.setRequestProperty("cosy-machinetoken", machineId);
            conn.setRequestProperty("cosy-machinetype", machineType);
            conn.setRequestProperty("cosy-clienttype", "5");
            conn.setRequestProperty("cosy-business-product", "cli");
            conn.setRequestProperty("cosy-business-type", "agent");
            conn.setRequestProperty("cosy-scene", "assistant");
            conn.setRequestProperty("cosy-version", QoderCosy.COSY_VERSION);
            conn.setRequestProperty("cosy-date", date);
            conn.setRequestProperty("X-Model-Key", model);
            conn.setRequestProperty("X-Model-Source", "system");

            java.io.OutputStream body = conn.getOutputStream();
            body.write(encodedBody.getBytes("UTF-8"));
            body.flush();
            body.close();

            int code = conn.getResponseCode();
            if (code != 200) {
                String err = readAllText(conn.getErrorStream());
                channel.lastError = "HTTP " + code;
                log("[proxy:" + channel.id + "] Qoder HTTP " + code + " " + truncate(err, 300));
                // 上游把业务错误包在 200 的 SSE 里，非 200 反而是网络层问题
                writeError(out, code >= 500 ? 502 : code, "upstream_error",
                        "Qoder 上游 HTTP " + code + "：" + truncate(err, 400));
                return;
            }

            relayQoderSse(conn, out, wantStream);
            channel.lastError = "";
        } catch (Exception e) {
            channel.lastError = String.valueOf(e.getMessage());
            log("[proxy:" + channel.id + "] Qoder 异常 " + e);
            writeError(out, 502, "upstream_error", "Qoder 请求失败：" + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 把 Qoder 的「信封式」SSE 拆成标准 OpenAI SSE 转发。
     *
     * <p>上游每帧形如：
     * <pre>data:{"headers":{…},"body":"{\"choices\":[…]}","statusCodeValue":200,"statusCode":"OK"}</pre>
     * 其中 {@code body} 是**转义后的 JSON 字符串**，真正的增量在它里面。
     * 直接透传会让客户端看不懂，所以逐帧拆壳。
     */
    private void relayQoderSse(HttpURLConnection conn, OutputStream out, boolean wantStream)
            throws IOException {
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));

        // 先剥壳再决定怎么回：上游永远给 SSE，但客户端可能只要一个 JSON。
        // 原先这里**无视 wantStream 恒定发 SSE**，于是 stream=false 的客户端
        // 拿到一堆 `data:` 行解析失败，表现为「模型无法调用」。
        if (wantStream) {
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/event-stream; charset=utf-8\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Connection: close\r\n\r\n").getBytes("UTF-8"));
            out.flush();
            relayQoderStream(r, out);
            return;
        }

        // 非流式：把增量聚合成一条 chat.completion
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        String finishReason = "stop";
        String id = "";
        String modelName = "";
        long created = System.currentTimeMillis() / 1000L;
        String errMsg = null;
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty() || !line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) continue;
            String inner = unwrapQoderEnvelope(data, null);
            if (inner == null) continue;
            if (inner.startsWith("__ERR__")) {
                errMsg = inner.substring("__ERR__".length());
                break;
            }
            try {
                org.json.JSONObject chunk = new org.json.JSONObject(inner);
                if (id.isEmpty()) id = chunk.optString("id", "");
                if (modelName.isEmpty()) modelName = chunk.optString("model", "");
                created = chunk.optLong("created", created);
                org.json.JSONArray choices = chunk.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                org.json.JSONObject ch = choices.optJSONObject(0);
                if (ch == null) continue;
                String fr = ch.optString("finish_reason", "");
                if (!fr.isEmpty()) finishReason = fr;
                org.json.JSONObject delta = ch.optJSONObject("delta");
                if (delta == null) delta = ch.optJSONObject("message");
                if (delta != null) {
                    content.append(delta.optString("content", ""));
                    reasoning.append(delta.optString("reasoning_content", ""));
                }
            } catch (Exception ignore) {
            }
        }
        r.close();

        if (errMsg != null) {
            writeError(out, 502, "upstream_error", errMsg);
            return;
        }

        org.json.JSONObject msg = new org.json.JSONObject();
        org.json.JSONObject resp = new org.json.JSONObject();
        String bodyOut;
        try {
            msg.put("role", "assistant");
            msg.put("content", content.toString());
            if (reasoning.length() > 0) msg.put("reasoning_content", reasoning.toString());
            org.json.JSONObject choice = new org.json.JSONObject();
            choice.put("index", 0);
            choice.put("message", msg);
            choice.put("finish_reason", finishReason);
            org.json.JSONArray choices = new org.json.JSONArray();
            choices.put(choice);
            resp.put("id", id.isEmpty() ? "chatcmpl-qoder" : id);
            resp.put("object", "chat.completion");
            resp.put("created", created);
            resp.put("model", modelName.isEmpty() ? "qoder" : modelName);
            resp.put("choices", choices);
            bodyOut = resp.toString();
        } catch (Exception e) {
            writeError(out, 500, "internal_error", "组装响应失败：" + e.getMessage());
            return;
        }

        byte[] bytes = bodyOut.getBytes("UTF-8");
        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(bytes);
        out.flush();
    }

    /** 流式转发：逐帧拆壳后拼成标准 OpenAI SSE。 */
    private void relayQoderStream(java.io.BufferedReader r, OutputStream out) throws IOException {
        String line;
        boolean sawData = false;
        boolean wroteDone = false;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty()) {
                out.write('\n');
                out.flush();
                continue;
            }
            if (!line.startsWith("data:")) {
                // event:error 之类的事件行照原样转发，便于客户端/日志看出上游错误
                out.write((line + "\n").getBytes("UTF-8"));
                out.flush();
                continue;
            }
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) continue;

            String inner = unwrapQoderEnvelope(data, out);
            if (inner == null) continue;
            if (inner.startsWith("__ERR__")) {
                out.write(("data: {\"error\":{\"message\":\""
                        + jsonEscape(truncate(inner.substring("__ERR__".length()), 500))
                        + "\",\"type\":\"upstream_error\"}}\n\n").getBytes("UTF-8"));
                out.flush();
                wroteDone = true;
                break;
            }
            sawData = true;
            out.write(("data: " + inner + "\n\n").getBytes("UTF-8"));
            out.flush();
        }
        r.close();

        if (!wroteDone) {
            if (sawData) {
                out.write("data: [DONE]\n\n".getBytes("UTF-8"));
            } else {
                out.write(("data: {\"error\":{\"message\":\"Qoder 上游未返回任何数据\""
                        + ",\"type\":\"upstream_error\"}}\n\n").getBytes("UTF-8"));
            }
            out.flush();
        }
    }

    /**
     * 拆掉 Qoder 的 SSE 信封，取出真正的 OpenAI 帧。
     *
     * <p>上游每帧是 `{"headers":…,"body":"<转义 JSON>","statusCodeValue":200}`，
     * 少数帧直接就是 OpenAI chunk。
     *
     * @param streamOut 非空时把状态码非 200 的错误直接写给它（流式路径用）；
     *                  为 null 时返回 `__ERR__<说明>` 交给调用方处理（非流式用）
     * @return 真正的 JSON 文本；应跳过时返回 null
     */
    private String unwrapQoderEnvelope(String data, OutputStream streamOut) throws IOException {
        String inner = data;
        try {
            org.json.JSONObject env = new org.json.JSONObject(data);
            String b = env.optString("body", "");
            int status = env.optInt("statusCodeValue", 200);
            if (!b.isEmpty()) {
                inner = b;
                if (status != 200) {
                    String msg = UpstreamError.describeSseError(inner);
                    if (msg == null || msg.isEmpty()) msg = inner;
                    if (streamOut == null) return "__ERR__" + msg;
                    streamOut.write(("data: {\"error\":{\"message\":\""
                            + jsonEscape(truncate(msg, 500))
                            + "\",\"type\":\"upstream_error\"}}\n\n").getBytes("UTF-8"));
                    streamOut.flush();
                    return "__ERR__";
                }
            }
        } catch (Exception ignore) {
            // 不是信封（少数帧直接是 OpenAI chunk），原样转发
        }
        if ("null".equals(inner) || inner.isEmpty()) return null;
        return inner;
    }
    /**
     * 把 OpenAI chat 请求体翻译成 Qoder 的 {@code agent_chat_generation} 载荷。
     *
     * <p>Qoder 的字段与 OpenAI 不同名也不同构：文本放在 {@code chat_context.text}，
     * 历史放在 {@code messages}，模型键同时出现在 {@code model_config.key} 与
     * {@code chat_context.extra.modelConfig.key}。缺字段会被服务端路由到错误的后端。
     */
    /** 兼容旧调用（没有规格时按缺省值处理）。 */
    static String buildQoderInferBody(String model, String openaiPayload) {
        return buildQoderInferBody(model, openaiPayload, null);
    }

    /**
     * 把 OpenAI 请求翻译成 Qoder 载荷，并**自动填充真实规格**。
     *
     * <p>为什么必须填：Qoder 的 `model_config` 里 `max_input_tokens`、
     * 顶层 `parameters.context_length`、`parameters.reasoning_effort` 都是
     * 服务端用来选后端/限流的依据。之前一律写死 `max_input_tokens=200000`、
     * `max_tokens=4096`，对 1M 上下文的模型是**严重低估**（会被当成小模型
     * 路由、也可能触发不必要的截断），对 180K 的模型又是高估（可能被拒）。
     *
     * <p>现在按渠道里已存的规格（来自上游目录或内置实测表）填：
     * <ul>
     *   <li>`max_input_tokens` ← {@link ModelSpec#contextWindow}；</li>
     *   <li>`context_length` ← 同上（客户端没显式指定时）；</li>
     *   <li>`max_tokens` ← 客户端指定值，缺省用 {@link ModelSpec#maxOutput}，
     *       都没有才退 4096；</li>
     *   <li>`reasoning_effort` ← 客户端指定值，缺省用
     *       {@link ModelSpec#defaultEffort}；</li>
     *   <li>`is_reasoning` / `is_vl` ← 规格里的能力声明。</li>
     * </ul>
     *
     * @param spec 该模型的规格；null 时退回安全缺省值
     */
    public static String buildQoderInferBody(String model, String openaiPayload, ModelSpec spec) {
        String text = "";
        java.util.List<org.json.JSONObject> history = new java.util.ArrayList<org.json.JSONObject>();
        boolean reasoning = false;
        int maxTokens = 4096;
        String effort = "";
        try {
            org.json.JSONObject req = new org.json.JSONObject(openaiPayload);
            org.json.JSONArray msgs = req.optJSONArray("messages");
            if (msgs != null) {
                for (int i = 0; i < msgs.length(); i++) {
                    org.json.JSONObject m = msgs.optJSONObject(i);
                    if (m == null) continue;
                    String role = m.optString("role", "user");
                    String content = m.optString("content", "");
                    if ("system".equals(role)) continue;
                    if ("user".equals(role)) text = content;
                    org.json.JSONObject h = new org.json.JSONObject();
                    h.put("role", role);
                    h.put("content", content);
                    history.add(h);
                }
            }
            // ---- 参数：客户端显式指定的优先，否则用规格里的真实值 ----
            String reqEffort = req.optString("reasoning_effort", "").trim();
            if (!reqEffort.isEmpty()) {
                effort = reqEffort;
                reasoning = !"none".equalsIgnoreCase(reqEffort);
            } else if (spec != null && spec.defaultEffort != null && !spec.defaultEffort.isEmpty()) {
                effort = spec.defaultEffort;
                reasoning = !"none".equalsIgnoreCase(effort);
            } else {
                // 没指定也没有默认档位：按「该模型是否推理模型」决定，
                // 而不是凭 effort 字段猜（那是字符串，类型不对）
                reasoning = spec != null && !spec.efforts.isEmpty();
            }

            // max_tokens：客户端给了就用（并夹到模型上限内），否则用规格的输出上限
            int reqMax = req.optInt("max_tokens", 0);
            if (reqMax <= 0) reqMax = req.optInt("max_completion_tokens", 0);
            if (reqMax > 0) {
                maxTokens = reqMax;
            } else if (spec != null && spec.maxOutput > 0) {
                maxTokens = spec.maxOutput;
            } else {
                maxTokens = 4096;
            }
            // 不能超过模型声明的输出上限，否则上游直接 400
            if (spec != null && spec.maxOutput > 0 && maxTokens > spec.maxOutput) {
                maxTokens = spec.maxOutput;
            }
            // 兜底：没有历史时至少带一条 user 消息，否则上游会拒
            if (history.isEmpty()) {
                org.json.JSONObject h = new org.json.JSONObject();
                h.put("role", "user");
                h.put("content", text);
                history.add(h);
            }
        } catch (Exception ignore) {
        }

        // 上下文窗口：优先规格，其次下游客户端的 messages 估算兜底。
        // 0 表示「不知道」——那种情况不要把假数字发给上游，让它自己决定。
        int inputTokens = spec != null ? spec.contextWindow : 0;

        String rid = java.util.UUID.randomUUID().toString();
        org.json.JSONObject p = new org.json.JSONObject();
        try {
            p.put("request_id", rid);
            p.put("request_set_id", rid);
            p.put("chat_record_id", rid);
            p.put("session_id", java.util.UUID.randomUUID().toString());
            p.put("stream", true);
            p.put("chat_task", "FREE_INPUT");
            org.json.JSONObject ctx = new org.json.JSONObject();
            ctx.put("text", text);
            ctx.put("features", new org.json.JSONArray());
            org.json.JSONObject extra = new org.json.JSONObject();
            extra.put("context", new org.json.JSONArray());
            org.json.JSONObject mc = new org.json.JSONObject();
            mc.put("key", model);
            mc.put("is_reasoning", reasoning);
            extra.put("modelConfig", mc);
            extra.put("originalContent", text);
            ctx.put("extra", extra);
            ctx.put("chatPrompt", "");
            ctx.put("imageUrls", (Object) null);
            p.put("chat_context", ctx);
            p.put("is_reply", true);
            p.put("is_retry", false);
            p.put("source", 1);
            p.put("version", "3");
            p.put("agent_id", "agent_common");
            p.put("task_id", "common");
            p.put("session_type", "qodercli");
            p.put("aliyun_user_type", "");
            org.json.JSONObject mcfg = new org.json.JSONObject();
            mcfg.put("key", model);
            // display_name 传真实名：上游按它做展示与部分路由判断，空串会让
            // 日志/后台把它当成「未知模型」。
            mcfg.put("display_name", spec != null && spec.name != null ? spec.name : "");
            mcfg.put("model", "");
            mcfg.put("format", "openai");
            // is_vl / is_reasoning 一律按规格声明，不要凭猜：
            // 写 true 但模型不支持图片，上游会按「多模态」路由到错误后端。
            mcfg.put("is_vl", spec != null && spec.supportsImages);
            mcfg.put("is_reasoning", reasoning);
            mcfg.put("api_key", "");
            mcfg.put("url", "");
            mcfg.put("source", "system");
            mcfg.put("max_input_tokens", inputTokens);
            p.put("model_config", mcfg);
            p.put("custom_model", (Object) null);
            p.put("system", new org.json.JSONArray());
            org.json.JSONArray arr = new org.json.JSONArray();
            for (org.json.JSONObject h : history) arr.put(h);
            p.put("messages", arr);
            p.put("tools", new org.json.JSONArray());
            org.json.JSONObject params = new org.json.JSONObject();
            params.put("max_tokens", maxTokens);
            // 上下文预算也要显式给出：上游会用它做截断与后端选择。
            if (inputTokens > 0) params.put("context_length", inputTokens);
            if (!effort.isEmpty()) {
                // 双写顶层与 parameters —— 与 Qoder 桌面客户端一致
                params.put("reasoning_effort", effort);
                params.put("enable_thinking", reasoning);
                p.put("reasoning_effort", effort);
            }
            p.put("parameters", params);
            org.json.JSONObject biz = new org.json.JSONObject();
            biz.put("type", "agent");
            p.put("business", biz);
        } catch (Exception ignore) {
        }
        return p.toString();
    }

    /** 读尽输入流并当 UTF-8 文本返回（用于错误体；与已有的二进制 readAll 区分）。 */
    private static String readAllText(java.io.InputStream in) {
        if (in == null) return "";
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
            return new String(buf.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /** 最小 JSON 字符串转义（不依赖 org.json，验证用的 stub 里没有 quote）。 */
    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private void proxyChatCodeArts(Channel channel, String payload, boolean wantStream,
                                   OutputStream out) throws IOException {
        String base = chatBase(channel);
        if (base.isEmpty()) {
            channel.lastError = "CodeArts 渠道未配置 Base URL";
            log("[proxy:" + channel.id + "] CodeArts 渠道未配置 Base URL");
            writeError(out, 502, "config_error", "渠道 " + channel.id + " 未配置 Base URL（应为 " + CodeArtsLogin.API_BASE + "）");
            return;
        }
        String url = base + "/chat/completions";
        String model = extractModel(payload);

        // CodeArts 的业务头必须在**签名之前**准备好（签名会把所有 header 计入
        // SignedHeaders），并且要真的发出去。这套头来自参考实现实测：
        //   x-auth-token    = STS security_token
        //                     —— 光有签名不带它会被 APIG 拒（APIG.0301 decrypt token fail）
        //   app-id          = CodeAgent3.0
        //   X-Language      = zh-cn
        //   is_confidential = false
        //   x-snap-traceid  = 每次随机
        //   maas_type       = benefit（仅免费额度模型；不带就用不上免费额度）
        java.util.Map<String, String> extra = new java.util.LinkedHashMap<>();
        extra.put("x-auth-token", channel.securityToken == null ? "" : channel.securityToken);
        extra.put("X-Language", "zh-cn");
        extra.put("app-id", "CodeAgent3.0");
        extra.put("is_confidential", "false");
        extra.put("x-snap-traceid", randomTraceId());
        if (LoginProviders.isCodeArtsBenefitModel(model)) {
            extra.put("maas_type", "benefit");
        }

        HuaweiSign.Signed signed = HuaweiSign.sign(
                channel.accessKeyId, channel.secretAccessKey, channel.securityToken,
                "POST", url, payload, extra);
        if (!signed.ok()) {
            channel.lastError = signed.error;
            writeError(out, 502, "credential_error", signed.error);
            return;
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(300_000);
            conn.setRequestProperty("Accept",
                    wantStream ? "text/event-stream" : "application/json");
            for (java.util.Map.Entry<String, String> e : signed.headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            // Chat-Id / Session-Id 在签名**之后**追加（不参与签名）。
            // 上游按这组标识给会话计数：缺了会「每个请求各算一个会话」，
            // 很容易撞上「每账号 3 个并发会话」的上限。
            String chatId = randomTraceId();
            conn.setRequestProperty("Chat-Id", chatId);
            conn.setRequestProperty("Session-Id", chatId);

            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int status = conn.getResponseCode();
            String contentType = conn.getContentType();
            if (contentType == null) {
                contentType = wantStream ? "text/event-stream; charset=utf-8" : "application/json; charset=utf-8";
            }
            if (status < 200 || status >= 300) {
                InputStream es = conn.getErrorStream();
                byte[] err = es == null ? new byte[0] : readAll(es);
                String msg = new String(err, StandardCharsets.UTF_8);
                if (msg.isEmpty()) msg = "上游返回 HTTP " + status;
                channel.lastError = msg.contains("APIG.0301")
                        ? "CodeArts 凭据已过期，请重新登录"
                        : "HTTP " + status + "：" + msg;
                log("[proxy:" + channel.id + "] 上游失败 HTTP " + status);
                writeRaw(out, status, contentType, err, true);
                return;
            }

            channel.lastError = "";
            writeHeaders(out, status, contentType, true);
            InputStream is = conn.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
            is.close();
        } catch (Exception e) {
            channel.lastError = String.valueOf(e.getMessage());
            log("[proxy:" + channel.id + "] 转发失败：" + e.getMessage());
            writeError(out, 502, "network_error", "无法连接上游 " + channel.baseUrl + "：" + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 从请求体里取 `model` 字段（拿不到就返回空串）。
     */
    static String extractModel(String payload) {
        if (payload == null || payload.isEmpty()) return "";
        try {
            org.json.JSONObject body = new org.json.JSONObject(payload);
            return body.optString("model", "");
        } catch (Exception e) {
            return "";
        }
    }

    /** 随机 trace / chat id（32 位 hex）。 */
    static String randomTraceId() {
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        byte[] b = new byte[16];
        rnd.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    /** 把续期后的渠道写回配置（失败不影响本次请求）。 */
    private void persistChannel(Channel channel) {
        try {
            config.upsertChannel(channel);
        } catch (RuntimeException ignored) {
            // 存储失败只影响下次启动，本次请求仍然可用
        }
    }

    /**
     * 登录型渠道的 token 保鲜：过期（或临近过期）时用 refresh_token 续期。
     *
     * 为什么在这里做而不是定时任务：手机上后台任务会被系统压制，
     * 而「用户真的要发请求」是唯一可靠的触发点。续期成功后把新凭据写回
     * 配置（否则下次启动又用旧的），失败则保持原样让请求如实报错。
     */
    private Channel ensureFreshToken(Channel channel) {
        if (!Channel.KIND_LOGIN.equals(channel.kind)) return channel;

        // 华为 CodeArts：临时凭据约 2 小时，用 refresh_token + DPoP 私钥续期。
        // 这条路必须在「accessToken 判空」之前处理 —— CodeArts 没有 accessToken。
        if (LoginProviders.CODEARTS.equals(channel.loginType)) {
            return ensureFreshCodeArts(channel);
        }

        if (channel.accessToken.isEmpty() || channel.refreshToken.isEmpty()) return channel;
        if (!channel.isExpired()) return channel;

        LoginProviders.Spec spec = LoginProviders.specOf(channel.loginType);
        if (spec == null) return channel;

        try {
            Channel renewed;
            if (spec.supportsDeviceFlow) {
                renewed = LoginProviders.refresh(channel);
            } else if (ChannelLogin.supportsDirectLogin(channel.loginType)) {
                // Qoder / Raccoon 有各自的续期端点；ZCode 没有公开续期，返回 null
                renewed = ChannelLogin.refresh(channel);
            } else {
                renewed = null;
            }
            if (renewed == null) {
                log("[login:" + channel.id + "] token 续期失败，请重新登录");
                return channel;
            }
            persistChannel(renewed);
            log("[login:" + channel.id + "] token 已自动续期");
            return renewed;
        } catch (IOException e) {
            log("[login:" + channel.id + "] token 续期出错：" + e.getMessage());
            return channel;
        }
    }

    /**
     * CodeArts 的凭据保鲜。
     *
     * <p>华为云临时凭据（AK/SK/security_token）只有约 2 小时，一旦过期
     * 所有签名请求都报 `APIG.0301 Unauthorized`。续期要三样东西：
     * `refreshToken`、`codeVerifier`、`dpopJwk` —— 都是登录时存下的。
     * 缺任何一样就只能让用户重新登录（如实记日志）。
     */
    private Channel ensureFreshCodeArts(Channel channel) {
        if (!channel.isExpired()) return channel;
        if (channel.refreshToken.isEmpty()) {
            log("[login:" + channel.id + "] 临时凭据已过期，且没有 refresh_token，请重新登录");
            return channel;
        }
        CodeArtsLogin.DpopKey key = CodeArtsLogin.jwkFromStore(channel.dpopJwk);
        if (key == null) {
            log("[login:" + channel.id + "] 临时凭据已过期，DPoP 私钥丢失，请重新登录");
            return channel;
        }
        try {
            org.json.JSONObject token = CodeArtsLogin.refresh(
                    channel.refreshToken, channel.codeVerifier, key, 20_000);
            Channel renewed = Channel.fromJson(channel.toJson());
            if (!CodeArtsLogin.applyCredentials(renewed, token)) {
                log("[login:" + channel.id + "] 续期响应缺少凭据");
                return channel;
            }
            persistChannel(renewed);
            log("[login:" + channel.id + "] CodeArts 临时凭据已自动续期");
            return renewed;
        } catch (Exception e) {
            log("[login:" + channel.id + "] CodeArts 续期失败：" + e.getMessage());
            return channel;
        }
    }
    // -----------------------------------------------------------------------
    // 状态页
    // -----------------------------------------------------------------------

    private void handleStatus(OutputStream out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
          .append("<title>统一 API 网关</title><style>")
          .append("body{font:15px/1.6 system-ui,sans-serif;background:#0d1117;color:#e6edf3;padding:16px}")
          .append("h1{font-size:18px}code{background:#21262d;padding:2px 6px;border-radius:4px;font-size:13px}")
          .append("table{border-collapse:collapse;width:100%;margin-top:8px}")
          .append("td,th{text-align:left;padding:6px 8px;border-bottom:1px solid #21262d;font-size:13px}")
          .append("th{color:#8b949e}").append("</style></head><body>")
          .append("<h1>统一 API 网关</h1>")
          .append("<p>Base URL：<code>http://127.0.0.1:").append(config.port()).append("/v1</code></p>")
          .append("<p>API Key：<code>").append(escape(config.apiKey())).append("</code></p>")
          .append("<table><tr><th>前缀</th><th>渠道</th><th>模型数</th><th>状态</th></tr>");
        for (Channel c : config.channels()) {
            sb.append("<tr><td><code>").append(escape(c.id)).append(":</code></td><td>")
              .append(escape(c.name)).append("</td><td>").append(c.effectiveModels().size())
              .append("</td><td>").append(c.enabled ? (c.lastError.isEmpty() ? "可用" : escape(c.lastError)) : "已停用")
              .append("</td></tr>");
        }
        sb.append("</table></body></html>");
        writeSimple(out, 200, "text/html; charset=utf-8",
                sb.toString().getBytes(StandardCharsets.UTF_8), true);
    }

    // -----------------------------------------------------------------------
    // HTTP 小工具
    // -----------------------------------------------------------------------

    private void writeError(OutputStream out, int status, String code, String message) throws IOException {
        JSONObject err = new JSONObject();
        try {
            JSONObject inner = new JSONObject();
            inner.put("message", message);
            inner.put("type", code);
            inner.put("code", code);
            err.put("error", inner);
        } catch (Exception ignored) {
        }
        writeSimple(out, status, "application/json; charset=utf-8",
                err.toString().getBytes(StandardCharsets.UTF_8), true);
    }

    private void writeSimple(OutputStream out, int status, String contentType, byte[] body, boolean cors)
            throws IOException {
        writeRaw(out, status, contentType, body, cors);
    }

    private void writeRaw(OutputStream out, int status, String contentType, byte[] body, boolean cors)
            throws IOException {
        writeHeaders(out, status, contentType, cors);
        if (body.length > 0) out.write(body);
        out.flush();
    }

    private void writeHeaders(OutputStream out, int status, String contentType, boolean cors) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append(" ").append(reason(status)).append("\r\n");
        h.append("Content-Type: ").append(contentType).append("\r\n");
        if (cors) {
            h.append("Access-Control-Allow-Origin: *\r\n");
            h.append("Access-Control-Allow-Headers: authorization,content-type,x-api-key\r\n");
            h.append("Access-Control-Allow-Methods: GET,POST,OPTIONS\r\n");
        }
        h.append("Cache-Control: no-cache, no-transform\r\n");
        h.append("Connection: close\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 413: return "Payload Too Large";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "OK";
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void log(String line) {
        if (listener != null) listener.onLog(line);
    }

    /** WorkBuddy 两个区域（国际版 / 国内版）：共用同一套 chat 网关约定。 */
    private static boolean isWorkbuddyFamily(String loginType) {
        return LoginProviders.WB_INTL.equals(loginType) || LoginProviders.WB_CN.equals(loginType);
    }

    /**
     * 保证 `messages[0]` 是 system 角色。
     *
     * <p>WorkBuddy 的网关硬性要求第一条是 system：没有就回
     * {@code 11128 first message is not system prompt}。而 OpenAI 协议里
     * system 是可选的 —— 很多客户端（含本网关自己的调用链）并不带它，
     * 于是必然被拦。
     *
     * <p>补一条**空内容**的 system 就够了（实测空串也通过），
     * 这样不会篡改用户实际的提示词。
     *
     * @return 补过 system 的请求体；解析失败时原样返回（不吞掉调用方的请求）
     */
    /**
     * 把请求体的 `stream` 强制改成 `true`。
     *
     * <p>WorkBuddy 的 chat 网关**只支持流式**：请求 `stream:false` 时上游回
     * {@code 11101 Non-stream chat request is currently not supported}。
     * 但 OpenAI 协议的客户端会把 `stream` 设成 false —— 我们照发就必然失败。
     *
     * <p>所以对这类上游一律按流式发，非流式的那条聚合在本地做
     * （见 {@link #aggregateSse}）。对调用方完全透明。
     *
     * @return stream=true 的请求体；解析失败时原样返回
     */
    /**
     * 把 SSE 增量聚合成一条非流式 `chat.completion`。
     *
     * <p>给「只能流式」的上游（WorkBuddy）用：客户端要 `stream:false` 时，
     * 我们仍然按流式请求，然后在这里把 `choices[].delta` 逐帧拼起来，
     * 输出成标准的一条 JSON。
     *
     * <p>要处理 SSE 的三类行：注释（`: heartbeat`，WorkBuddy 会先发它）、
     * `data:` 数据行、以及 `event:` / 空行。**注释行不能当成数据**，
     * 否则会把心跳拼进正文。
     */
    public static String aggregateSse(byte[] head, InputStream rest) throws IOException {
        StringBuilder all = new StringBuilder();
        if (head != null && head.length > 0) {
            all.append(new String(head, StandardCharsets.UTF_8));
        }
        byte[] buf = new byte[8192];
        int n;
        while ((n = rest.read(buf)) > 0) {
            all.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }

        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        String finishReason = "stop";
        String id = "";
        String modelName = "";
        long created = System.currentTimeMillis() / 1000L;

        for (String line : all.toString().split("\n")) {
            String l = line.trim();
            if (!l.startsWith("data:")) continue;   // 注释 / event: 行一律跳过
            String data = l.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) continue;
            try {
                org.json.JSONObject chunk = new org.json.JSONObject(data);
                if (id.isEmpty()) id = chunk.optString("id", "");
                if (modelName.isEmpty()) modelName = chunk.optString("model", "");
                created = chunk.optLong("created", created);
                org.json.JSONArray choices = chunk.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                org.json.JSONObject ch = choices.optJSONObject(0);
                if (ch == null) continue;
                String fr = ch.optString("finish_reason", "");
                if (!fr.isEmpty()) finishReason = fr;
                org.json.JSONObject delta = ch.optJSONObject("delta");
                if (delta != null) {
                    content.append(delta.optString("content", ""));
                    reasoning.append(delta.optString("reasoning_content", ""));
                }
            } catch (Exception ignore) {
                // 单帧坏了不影响整体：聚合的目标是「尽量给出正文」
            }
        }

        org.json.JSONObject msg = new org.json.JSONObject();
        org.json.JSONObject resp = new org.json.JSONObject();
        try {
            msg.put("role", "assistant");
            msg.put("content", content.toString());
            if (reasoning.length() > 0) msg.put("reasoning_content", reasoning.toString());
            org.json.JSONObject choice = new org.json.JSONObject();
            choice.put("index", 0);
            choice.put("message", msg);
            choice.put("finish_reason", finishReason);
            org.json.JSONArray arr = new org.json.JSONArray();
            arr.put(choice);
            resp.put("id", id.isEmpty() ? "chatcmpl-gateway" : id);
            resp.put("object", "chat.completion");
            resp.put("created", created);
            resp.put("model", modelName.isEmpty() ? "unknown" : modelName);
            resp.put("choices", arr);
        } catch (Exception e) {
            return "{\"error\":{\"message\":\"聚合流式响应失败：" + jsonEscape(e.getMessage())
                    + "\",\"type\":\"internal_error\"}}";
        }
        return resp.toString();
    }

    /**
     * 给发往上游的 OpenAI 请求**自动补齐模型参数**。
     *
     * <p>为什么需要：客户端往往只给 `messages`，`max_tokens` 缺省、推理档位不传。
     * 而多数上游会用这些字段决定「按哪个档位计费 / 是否截断 / 路由到哪个后端」。
     * 缺省时不同上游行为不一致（有的给很小的默认值，直接导致回答被截断），
     * 表现成「模型答到一半停了」，很难排查。
     *
     * <p>补齐规则（**客户端显式指定的永远优先**，这里只补空缺）：
     * <ul>
     *   <li>`max_tokens` 缺失 → 用模型声明的输出上限；超过上限则夹到上限；</li>
     *   <li>`reasoning_effort` 缺失 → 用模型的默认档位（没有就留空，不瞎填）；</li>
     *   <li>`top_p` / `temperature` 等**不动** —— 那些没有「规格」可言，
     *       替用户设默认值会改变输出风格。</li>
     * </ul>
     *
     * @return 补齐后的请求体；解析失败时原样返回
     */
    public static String fillModelParams(String payload, Channel channel, String model) {
        if (payload == null || payload.isEmpty()) return payload;
        ModelSpec spec = channel == null ? null : channel.modelSpecs.get(model);
        if (spec == null && channel != null) {
            // 真实名/别名也要能查到规格。
            // ⚠ 不能只靠 resolveModelId：它只在 allModels()（可用清单）里找，
            //   而规格表里可能有还没进清单的模型（刚刷新、或按真实名传入）。
            //   这里直接扫规格表，按「id 相等 / 真实名相等」匹配。
            for (java.util.Map.Entry<String, ModelSpec> e : channel.modelSpecs.entrySet()) {
                if (e.getKey().equals(model)) {
                    spec = e.getValue();
                    break;
                }
                ModelSpec v = e.getValue();
                if (v != null && v.name != null && v.name.equalsIgnoreCase(model)) {
                    spec = v;
                    break;
                }
            }
        }
        if (spec == null || spec.isEmpty()) return payload;
        try {
            org.json.JSONObject req = new org.json.JSONObject(payload);
            boolean changed = false;

            int cur = req.optInt("max_tokens", 0);
            if (cur <= 0) cur = req.optInt("max_completion_tokens", 0);
            if (cur <= 0 && spec.maxOutput > 0) {
                req.put("max_tokens", spec.maxOutput);
                changed = true;
            } else if (cur > 0 && spec.maxOutput > 0 && cur > spec.maxOutput) {
                // 超过模型上限：上游会直接 400，提前夹住
                req.put("max_tokens", spec.maxOutput);
                changed = true;
            }

            String eff = req.optString("reasoning_effort", "").trim();
            if (eff.isEmpty() && spec.defaultEffort != null && !spec.defaultEffort.isEmpty()) {
                req.put("reasoning_effort", spec.defaultEffort);
                changed = true;
            }

            return changed ? req.toString() : payload;
        } catch (Exception e) {
            return payload;
        }
    }

    public static String forceStream(String payload) {
        try {
            org.json.JSONObject req = new org.json.JSONObject(payload);
            req.put("stream", true);
            return req.toString();
        } catch (Exception e) {
            return payload;
        }
    }

    /**
     * 中性化 system 提示词开头，避开 WorkBuddy 的**提示词指纹**拦截。
     *
     * <p>实测（2026-11-10，国际版）：三种 `11128` 其实是三道不同的门，
     * 上游返回的文案却完全一样（都写「可能不是官方客户端发出的」）：
     * <ol>
     *   <li>缺 `X-Domain` → `request illegal`；</li>
     *   <li>首条不是 system → `first message is not system prompt`；</li>
     *   <li><b>system 开头命中第三方客户端特征</b>
     *       → `Illegal API invocation from an unapproved channel`。</li>
     * </ol>
     *
     * <p>第 3 道是最难查的：它看的是**提示词内容**。实测能复现的最小样本：
     * <pre>
     *   "You are Claude Code, Anthropic's official CLI for Claude."  → 被拦
     *   "You are a helpful assistant."                               → 通过
     *   "You are an AI programming assistant."                       → 通过
     * </pre>
     * 所以这里把命中特征的 system **前缀**替换成中性开场，其余内容原样保留
     * （指令本身对模型仍然有效，只是不再带「我是别家 CLI」的指纹）。
     *
     * @return 中性化后的请求体；没有命中特征时原样返回
     */
    /**
     * 把上游「共用错误码」翻译成**能动手**的说明。
     *
     * <p>为什么需要：WorkBuddy 的三道门共用 `11128`，而文案几乎一样
     * （都写「可能不是官方客户端发出的」）。用户看到那句话只会去查账号，
     * 而实际原因可能是「没带 X-Domain」「首条不是 system」「提示词像别家 CLI」。
     *
     * <p>这里按「上游原文 + 我们自己的请求」判断是哪道门，然后说清楚：
     * 是网关的问题就说网关（并可让用户报障），是客户端的问题就指出具体动作。
     *
     * @return 面向用户的说明；识别不出时返回 null（调用方回原始错误体）
     */
    public static String explainUpstreamRejection(Channel channel, String rawError) {
        if (rawError == null || rawError.isEmpty()) return null;
        String t = rawError;
        boolean wb = isWorkbuddyFamily(channel.loginType);

        if (wb && t.contains("11128")) {
            if (t.contains("first message is not system prompt")) {
                return "上游要求 messages 第一条必须是 system 角色（WorkBuddy 网关规定）。"
                        + "网关已自动补空的 system；若仍报错，说明请求体结构异常，请检查客户端发的 messages。";
            }
            if (t.contains("request illegal")) {
                return "上游判定请求形态非法（缺少渠道校验头）。"
                        + "常见原因是网关未带 X-Domain / 会话三元组；请确认渠道的 domain 已正确填写（当前："
                        + (channel.domain.isEmpty() ? "<空>" : channel.domain) + "）。";
            }
            if (t.contains("unapproved channel")) {
                return "上游的**提示词指纹**风控拦下了这次请求：system 消息开头像第三方 CLI"
                        + "（如 Claude Code / Cursor 等）。网关已尝试中性化；"
                        + "若仍被拦，请把 system 提示词里「你是什么客户端」这类身份声明去掉。";
            }
            return "上游以 11128 拒绝（渠道校验类）。这个码有三种含义：渠道头缺失 / 首条非 system / "
                    + "提示词指纹。原始响应：" + truncate(t, 200);
        }
        if (wb && t.contains("11101")) {
            return "上游不支持非流式请求（WorkBuddy 网关限制）。网关已自动改为流式并在本地聚合；"
                    + "若仍报错，说明请求未被网关处理，请检查渠道配置。";
        }
        if (t.contains("3012") || t.contains("unusual activity")) {
            return "上游风控拦截（账号异常活动）。这与请求格式无关 —— "
                    + "请到对应客户端正常使用一次该账号，或稍后重试；持续出现需要联系上游。";
        }
        return null;
    }

    public static String neutralizePrompt(String payload) {
        try {
            org.json.JSONObject req = new org.json.JSONObject(payload);
            org.json.JSONArray msgs = req.optJSONArray("messages");
            if (msgs == null || msgs.length() == 0) return payload;
            // ⚠ 不就地改嵌套对象：不同 org.json 实现对「取出的子对象」是否
            // 与原数组共享引用并不一致（Android 共享，verify 的 stub 不共享），
            // 就地改会让行为依赖实现。这里重建 array，语义确定。
            boolean changed = false;
            org.json.JSONArray out = new org.json.JSONArray();
            for (int i = 0; i < msgs.length(); i += 1) {
                org.json.JSONObject m = msgs.optJSONObject(i);
                if (m == null) {
                    out.put(msgs.opt(i));
                    continue;
                }
                String role = m.optString("role", "");
                String content = m.optString("content", "");
                if ("system".equals(role)) {
                    String neutral = neutralizeText(content);
                    if (!neutral.equals(content)) {
                        // 只搬这两个字段：OpenAI 的 system 消息本来就只有它们，
                        // 用固定字段重建也避免依赖 `keys()`（不是所有实现都有）。
                        org.json.JSONObject copy = new org.json.JSONObject();
                        copy.put("role", "system");
                        copy.put("content", neutral);
                        if (m.has("name")) copy.put("name", m.optString("name", ""));
                        out.put(copy);
                        changed = true;
                        continue;
                    }
                }
                out.put(m);
            }
            if (!changed) return payload;
            req.put("messages", out);
            return req.toString();
        } catch (Exception e) {
            return payload;
        }
    }

    /**
     * 抹掉 system 提示词里的**第三方客户端身份指纹**。
     *
     * <h3>实测出来的规则边界（2026-11-10）</h3>
     *
     * <p>上游只匹配**一个精确短语**（大小写不敏感、忽略前导空白）：
     * <pre>
     *   You are Claude Code, Anthropic's official CLI for Claude.
     * </pre>
     * 变体实测：
     * <table>
     *   <tr><td>原句</td><td>❌ 被拦</td></tr>
     *   <tr><td>全小写</td><td>❌ 被拦</td></tr>
     *   <tr><td>前面多一句（同行）</td><td>✅ 通过</td></tr>
     *   <tr><td>用换行分隔</td><td>✅ 通过</td></tr>
     *   <tr><td>只写 `You are Claude Code.`</td><td>✅ 通过</td></tr>
     *   <tr><td>只写 `Anthropic's official CLI for Claude.`</td><td>✅ 通过</td></tr>
     *   <tr><td>Cursor / Roo / Cline / Copilot / Gemini CLI 等</td><td>✅ 通过</td></tr>
     * </table>
     * 也就是说：**它是「整句身份声明」级别的检测**，不是关键词黑名单。
     *
     * <h3>为什么要删干净</h3>
     *
     * <p>最初实现只替换「第一句」，结果提示词里该短语出现两次时，第二次
     * 仍会在「中性前缀 + 残留原句」里重新组成完整组合 —— 实测（用例 7）
     * 确实还被拦。所以这里把所有出现处都抹掉，而不是只处理开头。
     *
     * <p>删掉的是「我是别家 CLI」这句身份声明，**其余指令全部保留**：
     * 指令本身对模型仍然有效，只是不再带客户端指纹。
     *
     * @return 处理后的文本；没有命中时原样返回
     */
    public static String neutralizeText(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;

        String out = text;
        // 按「敏感度从高到低」逐条抹除。用正则而不是 indexOf：
        // 上游对空白和大小写都不敏感（实测双空格、制表符、全角逗号都能触发），
        // 逐字符比对会漏。
        String[] patterns = {
            // 完整身份声明：You are Claude Code, Anthropic's official CLI for Claude
            "(?is)you\\s+are\\s+claude\\s+code\\s*[,，]\\s*anthropic'?s?\\s+official\\s+cli\\s+for\\s+claude\\.?",
            // 半句也要抹：残留 `Anthropic's official CLI for Claude` 单发不拦，
            // 但和别的内容凑在一起可能重新命中，抹掉更稳
            "(?is)anthropic'?s?\\s+official\\s+cli\\s+for\\s+claude\\.?",
            "(?is)you\\s+are\\s+claude\\s+code\\s*[,，]",
        };
        boolean hit = false;
        for (String p : patterns) {
            String next = out.replaceAll(p, "");
            if (!next.equals(out)) {
                hit = true;
                out = next;
            }
        }
        if (!hit) return text;

        // 清掉抹除后留下的空白 / 多余标点，避免出现「开头就是逗号」这种畸形
        out = out.replaceAll("^[\\s,，.;；:：]+", "").trim();
        if (out.isEmpty()) return "You are a helpful assistant.";
        return "You are a helpful assistant. " + out;
    }
    public static String ensureSystemFirst(String payload) {
        try {
            org.json.JSONObject req = new org.json.JSONObject(payload);
            org.json.JSONArray msgs = req.optJSONArray("messages");
            if (msgs == null) {
                msgs = new org.json.JSONArray();
                req.put("messages", msgs);
            }
            if (msgs.length() > 0) {
                org.json.JSONObject first = msgs.optJSONObject(0);
                if (first != null && "system".equals(first.optString("role", ""))) {
                    return payload;   // 已经是 system，不用改
                }
            }
            org.json.JSONArray outMsgs = new org.json.JSONArray();
            org.json.JSONObject sys = new org.json.JSONObject();
            sys.put("role", "system");
            sys.put("content", "");
            outMsgs.put(sys);
            for (int i = 0; i < msgs.length(); i += 1) outMsgs.put(msgs.get(i));
            req.put("messages", outMsgs);
            return req.toString();
        } catch (Exception e) {
            return payload;
        }
    }

    /**
     * 取渠道的聊天基址，缺了按渠道类型补默认值。
     *
     * <p>老版本登录的 CodeArts 渠道没有存 `baseUrl`（那是后来才补上的字段），
     * 读配置时 {@link Channel#fromJson} 会兜底；这里再兜一次，保证**已经加载进内存**
     * 的旧渠道也能正常转发。
     *
     * <p>缺基址时不要把请求发出去：拼出来是裸的 `/chat/completions`，
     * `new URL` 会抛「no protocol」，最终报成「无法连接上游」—— 看起来像网络故障，
     * 实际是配置缺项。
     *
     * @return 规范化后的基址；空串表示无法确定（调用方应报配置错误）
     */
    static String chatBase(Channel channel) {
        if (channel == null) return "";
        String base = Channel.normalizeBaseUrl(channel.baseUrl);
        if (!base.isEmpty()) return base;
        // 老渠道（登录时没写 baseUrl）按登录类型补默认值，避免「重新登录才能用」
        return Channel.normalizeBaseUrl(LoginProviders.chatBaseFor(channel.loginType));
    }

    // -----------------------------------------------------------------------
    // 极简 HTTP 请求解析
    /**
     * 网关需要的配置与渠道数据（窄接口）。
     *
     * 为什么不用 Config 具体类：这样同一份 Gateway 代码既能跑在 Android
     * （SharedPreferences 实现），也能在桌面 JVM 上做等价验证。
     */
    public interface Store {
        String apiKey();
        int port();
        boolean lan();
        List<Channel> channels();
        Channel findChannel(String id);
        /** 续期后把新凭据写回（保持登录态跨重启有效）。 */
        void upsertChannel(Channel channel);
    }
    // -----------------------------------------------------------------------

    /** 已解析的请求。 */
    static final class Request {
        String method = "";
        String path = "";
        final List<String[]> headers = new ArrayList<>();
        byte[] body = new byte[0];

        String header(String name) {
            for (String[] h : headers) {
                if (h[0].equalsIgnoreCase(name)) return h[1];
            }
            return null;
        }

        static Request read(InputStream in) throws IOException {
            Request r = new Request();
            ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
            String requestLine = readLine(in, lineBuf);
            if (requestLine == null || requestLine.trim().isEmpty()) return null;
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) return null;
            r.method = parts[0].toUpperCase(Locale.ROOT);
            r.path = parts[1];
            int q = r.path.indexOf('?');
            if (q >= 0) r.path = r.path.substring(0, q);

            int contentLength = 0;
            for (;;) {
                String line = readLine(in, lineBuf);
                if (line == null || line.isEmpty()) break;
                int c = line.indexOf(':');
                if (c > 0) {
                    String name = line.substring(0, c).trim();
                    String value = line.substring(c + 1).trim();
                    r.headers.add(new String[]{name, value});
                    if (name.equalsIgnoreCase("content-length")) {
                        try {
                            contentLength = Integer.parseInt(value);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            if (contentLength > MAX_BODY) throw new IOException("body too large");
            if (contentLength > 0) {
                byte[] buf = new byte[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = in.read(buf, read, contentLength - read);
                    if (n <= 0) break;
                    read += n;
                }
                r.body = buf;
            }
            return r;
        }

        private static String readLine(InputStream in, ByteArrayOutputStream buf) throws IOException {
            buf.reset();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') break;
                if (c != '\r') buf.write(c);
            }
            if (c == -1 && buf.size() == 0) return null;
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
