package com.wbgateway;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 各登录型渠道的协议实现：扫码登录、轮询换 token、续期、拉模型。
 *
 * 事实来源（都是从生产包逆向确认的，不是猜的）：
 *
 * ## WorkBuddy（国际版 / 国内版）
 * ```
 * POST {endpoint}/v2/plugin/auth/state?platform={platform}
 *   头部：X-Domain / X-No-Authorization: true / X-No-User-Id: true
 *         / X-No-Enterprise-Id: true / X-No-Department-Info: true
 *   → {code:0, data:{state, authUrl}}      authUrl 就是要给用户打开的登录页
 * GET  {endpoint}/v2/plugin/auth/token?state={state}
 *   → 未扫码：{code:11217, msg:"login ing..."}
 *   → 已扫码：{code:0, data:{access_token, refresh_token, ...}}
 * POST {endpoint}/v2/plugin/auth/token/refresh   body: {refresh_token}
 * GET  {endpoint}/console/enterprises/personal/models   拉模型清单
 * ```
 *
 * ## Qoder / Qoder 中国版
 * 官方走 PKCE 授权码 + 本地回环 + DPoP，手机上无法复用回环端口，
 * 因此提供「粘贴 refresh_token / access_token」式的凭据导入（见 importToken）。
 *
 * ## ZCode / Raccoon
 * 都是网页登录，导入 access_token 即可；模型清单用内置静态表。
 */
public class LoginProviders {

    /** 单次请求默认超时。 */
    private static final int TIMEOUT_MS = 25_000;

    /** 一个登录渠道的静态描述。 */
    public static class Spec {
        public final String loginType;
        public final String displayName;
        public final String endpoint;
        public final String apiDomain;
        public final String platform;
        public final String productCode;
        public final String userAgent;
        public final String loginUrlTemplate;
        /** 是否支持扫码登录（WorkBuddy 两区域）。 */
        public final boolean supportsDeviceFlow;
        /**
         * 「开放 API」基址。
         *
         * <p>为什么与 `endpoint` 分开：Qoder 的网页与服务端**不在同一个域名下** ——
         * 授权页在 `qoder.com`，换 token / 续期在 `openapi.qoder.sh`；
         * 混用只会得到 404，看起来像「登录接口不存在」。
         *
         * <p>不设成 final：验证要把它临时指到假上游，真机上也可能要换环境。
         */
        public String openApiBase;

        /**
         * 该上游是否**只接受流式**请求。
         *
         * <p>为 true 时网关强制 `stream:true` 发出去，非流式客户端在本地聚合
         * （见 Gateway.aggregateSse）。WorkBuddy 实测如此：要 application/json
         * 会回 11101 Non-stream chat request is currently not supported。
         *
         * <p>默认 false —— 不要凭直觉给所有渠道打开：多一次聚合就多一次
         * 出错机会，而多数 OpenAI 兼容上游本来就支持非流式。
         */
        public boolean streamOnly = false;

        /**
         * 该上游是否要求 messages[0] 必须是 system 角色。
         *
         * <p>WorkBuddy 实测必需（否则 11128 first message is not system prompt）。
         * 其他渠道目前没观察到，默认 false：给不需要的上游插一条空 system
         * 属于无谓篡改，可能影响某些模型的指令遵循。
         */
        public boolean requiresSystemFirst = false;

        /**
         * 该上游是否有「提示词指纹」风控 —— system 开头像第三方 CLI 就被拒。
         *
         * <p>WorkBuddy 实测有（11128 Illegal API invocation from an unapproved
         * channel）。默认 false：中性化会改写用户的 system 文本，
         * 对没有该限制的上游是有害的。
         */
        public boolean promptFingerprintGuard = false;

        Spec(String loginType, String displayName, String endpoint, String apiDomain, String platform,
             String productCode, String userAgent, String loginUrlTemplate, boolean supportsDeviceFlow) {
            this(loginType, displayName, endpoint, endpoint, apiDomain, platform, productCode,
                    userAgent, loginUrlTemplate, supportsDeviceFlow);
        }

        Spec(String loginType, String displayName, String endpoint, String openApiBase, String apiDomain,
             String platform, String productCode, String userAgent, String loginUrlTemplate,
             boolean supportsDeviceFlow) {
            this.loginType = loginType;
            this.displayName = displayName;
            this.endpoint = endpoint;
            this.openApiBase = openApiBase;
            this.apiDomain = apiDomain;
            this.platform = platform;
            this.productCode = productCode;
            this.userAgent = userAgent;
            this.loginUrlTemplate = loginUrlTemplate;
            this.supportsDeviceFlow = supportsDeviceFlow;
        }
    }

    public static final String WB_INTL = "workbuddy";
    public static final String WB_CN = "workbuddy-cn";
    public static final String QODER = "qoder";
    public static final String QODER_CN = "qodercn";
    public static final String ZCODE = "zcode";
    public static final String RACCOON = "raccoon";
    /** 华为 CodeArts（华为云）。 */
    public static final String CODEARTS = "codearts";

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    static {
        // WorkBuddy 国际版：platform=workbuddy-ai，登录页 workbuddy.ai/login
        SPECS.put(WB_INTL, new Spec(
                WB_INTL, "WorkBuddy AI（国际版）",
                "https://www.workbuddy.ai", "www.workbuddy.ai",
                "workbuddy-ai", "workbuddy",
                "WorkBuddy/5.5.2 WorkBuddy AI/5.5.2 CLI/5.5.2",
                "https://www.workbuddy.ai/login?platform=workbuddy-ai&state={state}",
                true));

        // WorkBuddy 国内版：platform=ide，登录页 codebuddy.cn
        SPECS.put(WB_CN, new Spec(
                WB_CN, "WorkBuddy（国内版）",
                "https://copilot.tencent.com", "copilot.tencent.com",
                "ide", "buddy",
                "CodeBuddyIDE/1.106.1",
                "https://www.codebuddy.cn/login?state={state}",
                true));

        // （WorkBuddy 的开关在各自 Spec 注册之后由 markWorkbuddyQuirks 打上）
        // Qoder：设备码式 PKCE（授权页在 qoder.com，换 token 在 openapi.qoder.sh）
        SPECS.put(QODER, new Spec(
                QODER, "Qoder",
                "https://qoder.com", "https://openapi.qoder.sh", "qoder.com",
                "qoder", "qoder", "qoder/1.0.0", "https://qoder.com/", false));

        SPECS.put(QODER_CN, new Spec(
                QODER_CN, "Qoder（中国版）",
                "https://qoder.cn", "https://openapi.qoder.com.cn", "qoder.cn",
                "qoder", "qoder", "qoder/1.0.0", "https://qoder.cn/", false));

        SPECS.put(ZCODE, new Spec(
                ZCODE, "ZCode（智谱）",
                "https://zcode.z.ai", "https://zcode.z.ai", "zcode.z.ai",
                "zcode", "zcode", "ZCode/3.14.3", "https://zcode.z.ai/", false));

        SPECS.put(RACCOON, new Spec(
                RACCOON, "Raccoon（商汤）",
                "https://xiaohuanxiong.com", "https://xiaohuanxiong.com", "xiaohuanxiong.com",
                "raccoon", "raccoon", "Raccoon Work/1.0.35 (Windows)",
                "https://xiaohuanxiong.com/", false));

        // 华为 CodeArts：华为云 STS OAuth + AK/SK 签名调 InferHub。
        // 聊天端点在 snap-access（华为云 API 网关），登录走 codearts.huaweicloud.com。
        SPECS.put(CODEARTS, new Spec(
                CODEARTS, "华为 CodeArts",
                "https://snap-access.cn-north-4.myhuaweicloud.com", "https://codearts.huaweicloud.com",
                "codearts.huaweicloud.com",
                "codearts", "codearts", "CodeArts/5.2.0",
                "https://codearts.huaweicloud.com/portal/login", false));
        // WorkBuddy 两区域：三道门全部实测踩过（2026-11-10），统一打标
        markWorkbuddyQuirks(WB_INTL);
        markWorkbuddyQuirks(WB_CN);
    }


    /**
     * 给 WorkBuddy 两区域打上「网关怪癖」标记。
     *
     * <p>为什么要单独一个方法：这三个开关都是从**真实请求**里逐个撞出来的
     * （见各自字段注释），集中在一处方便对照与复核，也避免在 Spec 构造里
     * 塞一串布尔字面量（读不出语义）。
     */
    private static void markWorkbuddyQuirks(String loginType) {
        Spec s = SPECS.get(loginType);
        if (s == null) return;
        s.streamOnly = true;              // 11101：只支持流式
        s.requiresSystemFirst = true;     // 11128：首条必须 system
        s.promptFingerprintGuard = true;  // 11128：system 开头不能像第三方 CLI
    }

    /** 取某个渠道的能力开关（渠道不存在时返回一个全 false 的默认值）。 */
    public static Spec specOf(String loginType) {
        return SPECS.get(loginType);
    }

    public static Map<String, Spec> allSpecs() {
        return SPECS;
    }

    // -----------------------------------------------------------------------
    // WorkBuddy 扫码登录
    // -----------------------------------------------------------------------

    /** 发起扫码登录的结果。 */
    public static class DeviceState {
        public String state = "";
        /** 给用户打开的登录页（App 里用 WebView 打开，或复制到浏览器）。 */
        public String authUrl = "";
    }

    /**
     * 第一步：申请一个登录 state 与登录页 URL。
     *
     * 实测返回（国际版）：
     * `{"code":0,"msg":"OK","data":{"state":"…","authUrl":"https://www.workbuddy.ai/login?platform=workbuddy-ai&state=…"}}`
     */
    public static DeviceState requestDeviceState(String loginType) throws IOException {
        Spec spec = specOf(loginType);
        if (spec == null || !spec.supportsDeviceFlow) throw new IOException("该渠道不支持扫码登录");

        String url = spec.endpoint + "/v2/plugin/auth/state?platform=" + spec.platform;
        JSONObject body = requestJson("POST", url, spec, null, "{}", 25_000);
        JSONObject data = body.optJSONObject("data");
        if (data == null) throw new IOException("auth/state 响应缺少 data：" + body);
        DeviceState ds = new DeviceState();
        ds.state = data.optString("state", "");
        ds.authUrl = data.optString("authUrl", "");
        if (ds.state.isEmpty()) throw new IOException("auth/state 未返回 state");
        if (ds.authUrl.isEmpty()) ds.authUrl = spec.loginUrlTemplate.replace("{state}", ds.state);
        return ds;
    }

    /**
     * 第二步：轮询换取 token。
     *
     * @return 已登录返回填充好凭据的 Channel 副本；仍在等待返回 null。
     * @throws IOException 网络或协议错误
     */
    public static Channel pollToken(Channel channel) throws IOException {
        Spec spec = specOf(channel.loginType);
        if (spec == null) throw new IOException("未知登录类型：" + channel.loginType);

        String url = spec.endpoint + "/v2/plugin/auth/token?state=" + urlEncode(channel.loginState);
        JSONObject body = requestJson("GET", url, spec, null, null, 20_000);
        int code = body.optInt("code", -1);
        // 11217 = 等待用户完成登录；12151 = 账号信息尚未就绪
        if (code == 11217 || code == 12151) return null;
        if (code != 0) {
            throw new IOException("登录失败（code " + code + "）：" + body.optString("msg", ""));
        }
        JSONObject data = body.optJSONObject("data");
        if (data == null) throw new IOException("auth/token 响应缺少 data：" + body);

        Channel c = copyOf(channel);
        c.accessToken = firstNonEmpty(data, "access_token", "accessToken", "token");
        c.refreshToken = firstNonEmpty(data, "refresh_token", "refreshToken");
        if (c.accessToken.isEmpty()) throw new IOException("auth/token 未返回 access_token");
        c.domain = firstNonEmpty(data, "domain");
        if (c.domain.isEmpty()) c.domain = spec.apiDomain;
        c.nickname = firstNonEmpty(data, "nickname", "user_name", "userName");
        c.enterpriseId = firstNonEmpty(data, "enterprise_id", "enterpriseId");
        long expiresIn = data.optLong("expires_in", 0L);
        if (expiresIn > 0) c.expiresAt = System.currentTimeMillis() + expiresIn * 1000L;
        if (c.expiresAt <= 0) c.expiresAt = jwtExpiresAt(c.accessToken);
        if (c.nickname.isEmpty()) c.nickname = jwtNickname(c.accessToken);
        // 取不到真实昵称时留空（渠道名不当昵称，否则界面显示成「名字 · 名字」）

        // 聊天基址：WorkBuddy 的 chat 端点是 `{endpoint}/v2/chat/completions`，
        // 不写下来网关就不知道往哪发（表现是「渠道未配置 Base URL」）。
        c.baseUrl = chatBaseFor(c.loginType);

        // 顺手拉一次模型清单
        try {
            c.cachedModels = fetchModels(c);
            c.modelsUpdatedAt = System.currentTimeMillis();
        } catch (IOException ignored) {
            // 拉不到不影响登录成功
        }
        return c;
    }

    /** 用 refresh_token 续期（WorkBuddy）。 */
    public static Channel refresh(Channel channel) throws IOException {
        Spec spec = specOf(channel.loginType);
        if (spec == null || channel.refreshToken.isEmpty()) return null;

        String url = spec.endpoint + "/v2/plugin/auth/token/refresh";
        JSONObject payload = new JSONObject();
        try {
            payload.put("refresh_token", channel.refreshToken);
        } catch (Exception ignored) {
        }
        JSONObject body = requestJson("POST", url, spec, channel, payload.toString(), 20_000);
        int code = body.optInt("code", -1);
        if (code != 0) return null;
        JSONObject data = body.optJSONObject("data");
        JSONObject source = data != null ? data : body;

        String access = firstNonEmpty(source, "access_token", "accessToken", "token");
        if (access.isEmpty()) return null;
        Channel c = copyOf(channel);
        c.accessToken = access;
        String nextRefresh = firstNonEmpty(source, "refresh_token", "refreshToken");
        if (!nextRefresh.isEmpty()) c.refreshToken = nextRefresh;
        long expiresIn = source.optLong("expires_in", 0L);
        c.expiresAt = expiresIn > 0
                ? System.currentTimeMillis() + expiresIn * 1000L
                : jwtExpiresAt(access);
        return c;
    }

    /**
     * 各登录渠道的**聊天基址**（网关在它后面拼 `/chat/completions`）。
     *
     * <p>为什么需要这个：WorkBuddy 的 chat 端点是 `{endpoint}/v2/chat/completions`，
     * 与模型目录、鉴权共用同一个 `endpoint`，但**登录时没人写 `baseUrl`** ——
     * 于是调用时网关报「渠道未配置 Base URL」，看起来像配置缺失。
     * 现在登录落库时就写好；`Channel.fromJson` 对老渠道也会兜底。
     *
     * <p>⚠ 两个 Qoder 区域的基址**语义不同**（2026-11-05 实测）：
     * <ul>
     *   <li>国际版 `https://api2-v2.qoder.sh/model/v1` —— 公开 OpenAI 兼容端点，
     *       网关拼 `/chat/completions` 直接可用（6 个模型）。</li>
     *   <li>国内版 `https://gateway.qoder.com.cn` —— 这是**根地址**：CN 没有公开
     *       OpenAI 端点（`/model/v1/chat/completions` 实测 404），只能走
     *       `/algo/api/v2/service/pro/sse/agent_chat_generation`，路径由
     *       {@code Gateway.proxyChatQoder} 自己拼，不在这里拼。</li>
     * </ul>
     */
    public static String chatBaseFor(String loginType) {
        if (WB_INTL.equals(loginType)) return "https://www.workbuddy.ai/v2";
        if (WB_CN.equals(loginType)) return "https://www.codebuddy.cn/v2";
        if (QODER.equals(loginType)) return "https://api2-v2.qoder.sh/model/v1";
        if (QODER_CN.equals(loginType)) return "https://gateway.qoder.com.cn";
        if (ZCODE.equals(loginType)) return "https://zcode.z.ai/api/v1/zcode-plan/anthropic/v1";
        if (RACCOON.equals(loginType)) return "https://xiaohuanxiong.com/api/web/llm/v2";
        if (CODEARTS.equals(loginType)) return CodeArtsLogin.API_BASE;
        return "";
    }

    /**
     * Qoder / Qoder 中国版**真实可用**的模型 id。
     *
     * <p>⚠ 这一条是**实测出来的**（2026-11-04，真实 `dt-` token 逐个打
     * `POST /model/v1/chat/completions`）：
     * <pre>
     * 200 OK : ultimate, qmodel, kmodel, gmodel, dmodel, mmodel
     * 400    : cmodel, smodel, qmodel_38max, qfmodel, qmodel_latest, kmodel_latest,
     *          gfmodel, dfmodel          ← "Unsupported model"
     * 429    : auto, efficient           ← "All backends failed"（服务端后端不可用）
     * </pre>
     *
     * <p>所以内置表里那 17 条**多数是错的**：它们是「目录里声明过的」，
     * 不代表这个端点接受。界面若列出来，用户点了必失败 —— 现在只留 200 的那 6 个。
     *
     * @return 真实可调用的模型 id（保序）
     */
    public static java.util.List<String> qoderUsableModels() {
        return qoderUsableModels(QODER);
    }

    /**
     * 按区域返回**实测可用**的模型 id。
     *
     * <p>两区域的可用集**完全不同**（2026-11-05 用 cosy 签名逐个实测）：
     *
     * <pre>
     * 国内版 gateway.qoder.com.cn（10 个）
     *   200 OK : qmodel, gmodel, kmodel, dmodel, mmodel,
     *            dfmodel, gfmodel, qmodel_38max, qmodel_latest, kmodel_latest
     *   429    : cmodel, smodel        ← provider_error（后端不可用，非权限）
     *   429    : qfmodel               ← RESOURCE_LIMIT
     *   400    : ultimate              ← Unsupported model
     *
     * 国际版 api2.qoder.sh
     *   明文 /model/v1（6 个，走的是按量计费通道）
     *   200 OK : ultimate, qmodel, kmodel, gmodel, dmodel, mmodel
     *   加密 /algo   ← 当时全部回 RESOURCE_LIMIT，但后来实测是**排队**（会自行恢复）
     * </pre>
     *
     * <p>所以国内版反而比国际版**多 4 个可用模型**，这与「国际版更强」的直觉相反。
     */
    public static java.util.List<String> qoderUsableModels(String loginType) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (QODER_CN.equals(loginType)) {
            // 国内版：2026-11-10 用 cosy 签名逐个实测，这 11 个能出正文。
            // ⚠ 这份清单会随账号额度变，不能当成永久事实 —— 界面上的
            //   「繁忙」标记由 QoderProbe 运行时探测写，见 ModelCatalog。
            out.add("qmodel");
            out.add("gmodel");
            out.add("kmodel");
            out.add("dmodel");
            out.add("mmodel");
            out.add("smodel");
            out.add("dfmodel");
            out.add("gfmodel");
            out.add("qmodel_38max");
            out.add("qmodel_latest");
            out.add("qfmodel");
        } else {
            // 国际版：公开 /model/v1 端点的 6 个
            out.add("ultimate");
            out.add("qmodel");
            out.add("kmodel");
            out.add("gmodel");
            out.add("dmodel");
            out.add("mmodel");
        }
        return out;
    }

    /** Qoder 两个区域（国际版 / 国内版）：共用同一套 cosy 协议。 */
    public static boolean isQoderFamily(String loginType) {
        return QODER.equals(loginType) || QODER_CN.equals(loginType);
    }

    /**
     * 从 Qoder 凭据里尽力推断 uid。
     *
     * <p>`dt-` 形态的 token 本身不含 uid（它不是 JWT，只有 27 字符），
     * 所以这里主要处理 **JWT 形态**的凭据；都推不出来时返回空串，
     * 由网关报「请重新登录」——不在这里假装成功。
     */
    public static String inferQoderUid(String token) {
        if (token == null || token.isEmpty()) return "";
        String[] seg = token.split("\\.");
        if (seg.length != 3) return "";
        try {
            String payload = seg[1];
            int pad = (4 - payload.length() % 4) % 4;
            StringBuilder sb = new StringBuilder(payload);
            for (int i = 0; i < pad; i += 1) sb.append('=');
            byte[] raw = java.util.Base64.getUrlDecoder().decode(sb.toString());
            String json = new String(raw, "UTF-8");
            for (String k : new String[] {"uid", "user_id", "userId", "sub"}) {
                String v = regexString(json, k);
                if (!v.isEmpty()) return v;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 从 JSON 文本里抠字符串字段（容错优先，不依赖 org.json）。 */
    public static String regexString(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        return m.find() ? m.group(1) : "";
    }

    /**
    /**
     * Qoder 里**容易进入排队**、值得逐个探测的模型 id。
     *
     * <p>⚠ 这份清单只是「探测候选」，**不是「需要订阅」的结论**。
     *
     * <p>曾经的错误判断：把 `RESOURCE_LIMIT` / 10605 说成「该模型需要订阅套餐」。
     * 实测（2026-11-10）推翻了这个结论 —— 同一账号连打 `kmodel_latest` 五轮，
     * **全部正常出正文**，而当时额度还有 2079/2500。
     * 10605 是**排队码**（参考实现里就叫 QUEUE_BUSINESS_CODE）：模型繁忙时排队，
     * 过一会儿就通，与套餐、额度都无关。
     *
     * <p>所以界面上的标记是「繁忙」（可重试），不是「需订阅」（要付费）。
     * 真实状态由运行时探测写（见 QoderProbe），这里只决定「哪些值得探」。
     */
    public static java.util.List<String> qoderProbeCandidates(String loginType) {
        java.util.List<String> out = new java.util.ArrayList<>();
        // 历史上出现过排队的模型；两区域不同，分别列。
        if (QODER_CN.equals(loginType)) {
            out.add("kmodel_latest");
            out.add("qfmodel");
        } else {
            out.add("cmodel");
            out.add("smodel");
            out.add("qfmodel");
            out.add("qmodel_38max");
            out.add("qmodel_latest");
            out.add("kmodel_latest");
            out.add("gfmodel");
            out.add("dfmodel");
        }
        return out;
    }
    /**
     * Qoder 加密推理端点（`/algo/...`）的**根地址**。
     *
     * <p>⚠ 与国际版的公开端点 `api2-v2.qoder.sh` 是**不同主机**：
     * 加密推理在国际版走 `api2.qoder.sh`、国内版走 `gateway.qoder.com.cn`。
     * 拿公开端点的基址去拼 algo 路径会 404，反之亦然。
     */
    public static String qoderAlgoBase(String loginType) {
        if (QODER_CN.equals(loginType)) return "https://gateway.qoder.com.cn";
        if (QODER.equals(loginType)) return "https://api2.qoder.sh";
        return "";
    }

    /** Qoder 加密推理路径（不含主机）。 */
    public static final String QODER_ALGO_PATH =
        "/algo/api/v2/service/pro/sse/agent_chat_generation"
      + "?FetchKeys=llm_model_result&AgentId=agent_common&Encode=1";

    /** 拉当前账号可见的模型清单（WorkBuddy）。 */

    /**
     * 拉取该账号**真实可用**的模型清单。
     *
     * ## 为什么之前的实现永远返回空
     *
     * 上游 `/console/enterprises/personal/models` 的真实结构是：
     *
     * ```json
     * { "code":0, "data": { "agents": [
     *     { "name":"cli", "models":["auto","hy4-preview","glm-5.3", …] },
     *     { "name":"Explore", "models":["…"] }, …
     * ] } }
     * ```
     *
     * 旧代码却在找 `data` 数组或 `data.models` —— 两个都不存在，于是**永远解析出 0 个**，
     * 界面只能退回静态兜底表，那里面一半模型上游根本没有（实测：静态表 22 个，
     * 账号真实可用只有 16 个）。
     *
     * 现在按 `data.agents[].models` 汇总去重，拿到的就是账号真正能调的集合。
     *
     * @param channel 已登录渠道
     * @return 去重后的模型 id（可能为空，表示该账号确实没有可用模型）
     */
    public static java.util.List<String> fetchModels(Channel channel) throws IOException {
        return fetchModelsDetailed(channel).ids;
    }

    /** 拉取结果：模型 id（保序）+ 每个 id 的上游规格。 */
    public static final class Catalog {
        public final java.util.List<String> ids = new java.util.ArrayList<>();
        public final java.util.Map<String, ModelSpec> specs = new java.util.LinkedHashMap<>();
    }

    /**
     * 拉模型清单**并解析上游下发的规格**。
     *
     * <h3>响应结构（实测 2026-11-04，国内版 `www.codebuddy.cn` 200 / 30KB）</h3>
     *
     * <pre>
     * {code:0, data:{
     *    agents:[{name, models:[id…], …}],        // 13 个 agent，共 17 个 id
     *    models:[{id, name, maxInputTokens, maxOutputTokens, supportsImages,
     *             reasoning:{defaultEffort, supportedEfforts}, vendor, …}]  // 34 条带规格
     * }}
     * </pre>
     *
     * <p>两条路径的取舍：
     * <ul>
     *   <li>`data.models[]` 有**完整规格**（34 条），是首选；</li>
     *   <li>`data.agents[].models[]` 是**该账号能用的**（17 个 id），更贴近实际权限。</li>
     * </ul>
     * 所以：**清单以 agents 为准**（权限内），**规格从 models 里查**
     * （agents 里有个别 id 如 `lite` 不在 models 里，那就不带规格，界面照常显示）。
     *
     * <p>⚠ 国际版（`www.workbuddy.ai`）这个端点目前**稳定返回 500**
     * （加任何头都一样，国内版同路径 200），所以国际版会走失败分支，
     * 由调用方退回内置表 —— 界面会如实说明，不假装成功。
     */
    public static Catalog fetchModelsDetailed(Channel channel) throws IOException {
        Spec spec = specOf(channel.loginType);
        if (spec == null) throw new IOException("未知登录类型");
        if (!hasLiveModelCatalog(channel.loginType)) {
            throw new IOException("该渠道没有模型目录接口");
        }
        if (channel.accessToken.isEmpty()) throw new IOException("缺少 access_token");

        // 用渠道自己的域名（登录时写入），回退到 spec.endpoint
        String host = channel.domain != null && !channel.domain.isEmpty() ? channel.domain : spec.endpoint;
        String base = host.startsWith("http") ? host : "https://" + host;

        // ① 先试 scoped 端点（带权限范围，最准）
        String url = base + "/console/enterprises/personal/models";
        try {
            Catalog scoped = parseCommerceModels(
                    requestJson("GET", url, spec, channel, null, 20_000));
            if (!scoped.ids.isEmpty()) return mergeWithConfig(channel, spec, base, scoped);
        } catch (IOException e) {
            // 国际版（www.workbuddy.ai）这个端点**稳定 500**：
            // 与账号、鉴权、请求头都无关（国内版同路径 200）。
            // 不能就此判失败 —— 还有 /v3/config 这条路。
        }

        // ② 退回 `/v3/config`：那是客户端真正用来出模型列表的端点
        Catalog fromConfig = parseConfigModels(
                requestJson("GET", base + "/v3/config", spec, channel, null, 20_000));
        if (!fromConfig.ids.isEmpty()) return fromConfig;

        // ③ 两条都空：如实报错，让调用方退回内置表并说明原因
        throw new IOException("上游没有返回任何模型（该账号可能确实没有可用模型）");
    }

    /**
     * 拉 scoped 端点并补上 `/v3/config` 里的试用模型。
     *
     * <p>为什么还要合并 config：scoped 端点只给「账号已开通」的，而 config 里的
     * `ModelTrialBanner.banners[].targetModelId` 是服务端推荐的试用模型 —— 它们
     * 实际可调，但不会出现在 scoped 里。漏掉会让用户少看到几个能用的模型。
     */
    private static Catalog mergeWithConfig(Channel channel, Spec spec, String base, Catalog scoped) {
        try {
            Catalog extra = parseConfigModels(
                    requestJson("GET", base + "/v3/config", spec, channel, null, 20_000));
            for (String id : extra.ids) {
                if (!scoped.ids.contains(id)) scoped.ids.add(id);
            }
            for (java.util.Map.Entry<String, ModelSpec> e : extra.specs.entrySet()) {
                if (!scoped.specs.containsKey(e.getKey())) scoped.specs.put(e.getKey(), e.getValue());
            }
        } catch (Exception ignored) {
            // 合并失败不影响已有结果
        }
        return scoped;
    }

    /**
     * 解析 `/v3/config` 的模型清单。
     *
     * <h3>响应结构（实测 2026-11-04，国际版 200 / 18.8KB）</h3>
     *
     * <pre>
     * {code:0, data:{
     *    agents:[{name:"cli", models:["default-model","hy3",…]}],   // 21 个 id
     *    models:[{id, name, maxInputTokens, maxOutputTokens, supportsImages,
     *             reasoning:{defaultEffort, supportedEfforts}, vendor}]  // 23 条规格
     * }}
     * </pre>
     *
     * <p>与 scoped 端点结构相同，所以复用同一套解析；区别只在 ids 来源优先级。
     */
    static Catalog parseConfigModels(JSONObject body) {
        return parseCommerceModels(body);
    }

    /**
     * 解析 `{code, data:{agents:[{models:[…]}], models:[{规格}]}}`。
     *
     * <p>scoped 端点与 `/v3/config` 是**同一个形状**（实测），所以共用。
     *
     * <p>过滤规则来自参考实现：`auto` / `default` 是自动选择别名，不是真模型；
     * 出图、补全类（`nes-` / `completion-` / `maxOutputTokens ≤ 256`）也不是聊天模型。
     * 不过滤会让 `/v1/models` 里出现一堆调用必失败的名字。
     */
    static Catalog parseCommerceModels(JSONObject body) {
        Catalog out = new Catalog();
        JSONObject data = body == null ? null : body.optJSONObject("data");

        // ① 先把 data.models[] 的规格收进 map（id → 规格）
        if (data != null) {
            org.json.JSONArray defs = data.optJSONArray("models");
            if (defs != null) {
                for (int i = 0; i < defs.length(); i += 1) {
                    JSONObject m = defs.optJSONObject(i);
                    if (m == null) continue;
                    String id = m.optString("id", "").trim();
                    if (id.isEmpty()) continue;
                    ModelSpec ms = ModelSpec.fromWorkbuddy(m);
                    if (!ms.isEmpty()) out.specs.put(id, ms);
                }
            }
        }

        // ② 清单以 agents[].models 为准（账号权限内）
        if (data != null) {
            org.json.JSONArray agents = data.optJSONArray("agents");
            if (agents != null) {
                for (int i = 0; i < agents.length(); i += 1) {
                    JSONObject agent = agents.optJSONObject(i);
                    if (agent == null) continue;
                    org.json.JSONArray models = agent.optJSONArray("models");
                    if (models == null) continue;
                    for (int k = 0; k < models.length(); k += 1) {
                        addChatModel(models.optString(k, "").trim(), out);
                    }
                }
            }
        }

        // ③ agents 为空时才退回 data.models[] 的 id（有些账号没有 agent 配置）
        if (out.ids.isEmpty()) {
            for (String id : out.specs.keySet()) addChatModel(id, out);
        }

        // ④ 仍为空 → 兼容其它形态（data 直接是数组 / 顶层 models 数组）
        if (out.ids.isEmpty()) {
            collectModelIds(body == null ? null : body.optJSONArray("data"), out.ids);
            if (out.ids.isEmpty()) collectModelIds(body == null ? null : body.optJSONArray("models"), out.ids);
        }

        // ⑤ 促销价：`modelPromotions[]` 里「当前生效」的会覆盖 `credits`
        if (data != null) applyPromotions(data.optJSONArray("modelPromotions"), out);
        return out;
    }

    /**
     * 把生效中的促销倍率写进规格（`discountedCreditRate`）。
     *
     * <p>实测形态（国际版 `/v3/config`）：
     * <pre>
     * {id:"hy4-free-trial-202611", enabled:true, priority:200,
     *  modelIds:["hy4free"],
     *  discount:{discountedCredits:"0x", factor:0},
     *  schedule:{validFrom:"2026-11-04T00:00:00+08:00", validUntil:"2026-11-16T00:00:00+08:00"}}
     * </pre>
     * 时间窗**必须判**：过期促销若照显示，用户会以为某个贵模型现在免费，
     * 结果一调就扣积分。所以超出窗口一律忽略。
     */
    static void applyPromotions(org.json.JSONArray promotions, Catalog out) {
        if (promotions == null || out == null) return;
        long now = System.currentTimeMillis();
        for (int i = 0; i < promotions.length(); i += 1) {
            JSONObject promo = promotions.optJSONObject(i);
            if (promo == null) continue;
            if (!promo.optBoolean("enabled", true)) continue;
            if (!promotionActiveNow(promo, now)) continue;
            String rate = ModelSpec.normalizeRate(promoDiscountRate(promo));
            if (rate.isEmpty()) continue;
            org.json.JSONArray modelIds = promo.optJSONArray("modelIds");
            if (modelIds == null) continue;
            for (int k = 0; k < modelIds.length(); k += 1) {
                String id = modelIds.optString(k, "").trim();
                if (id.isEmpty()) continue;
                ModelSpec spec = out.specs.get(id);
                if (spec == null) continue;
                // 同一模型有多个促销时取第一个命中的（上游 priority 已排好，按数组顺序）
                if (spec.discountedCreditRate.isEmpty()) spec.discountedCreditRate = rate;
            }
        }
    }

    /** 促销的折后倍率：优先 `discountedCredits`，回退 `factor`。 */
    private static String promoDiscountRate(JSONObject promo) {
        JSONObject discount = promo.optJSONObject("discount");
        if (discount == null) return "";
        String value = discount.optString("discountedCredits", "");
        if (!value.isEmpty()) return value;
        Object factor = discount.opt("factor");
        if (factor instanceof Number) {
            double d = ((Number) factor).doubleValue();
            return d <= 0 ? "0x" : d + "x";
        }
        return "";
    }

    /** 促销是否处在时间窗内（`validFrom` / `validUntil`，缺省视为不限）。 */
    static boolean promotionActiveNow(JSONObject promo, long nowMs) {
        JSONObject schedule = promo.optJSONObject("schedule");
        if (schedule == null) return true;
        long from = parseIsoTime(schedule.optString("validFrom", ""));
        long until = parseIsoTime(schedule.optString("validUntil", ""));
        if (from > 0 && nowMs < from) return false;
        if (until > 0 && nowMs >= until) return false;
        return true;
    }

    /** 解析 `2026-11-04T00:00:00+08:00` 这类带时区的时间。 */
    static long parseIsoTime(String raw) {
        if (raw == null || raw.isEmpty()) return 0;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US);
            java.util.Date d = f.parse(raw);
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 入列前过滤：别名与出图 / 补全类模型不算聊天模型。 */
    private static void addChatModel(String id, Catalog out) {
        if (id == null || id.isEmpty()) return;
        if ("auto".equals(id) || "default".equals(id)) return;
        if (id.startsWith("nes-") || id.startsWith("completion-") || id.startsWith("codewise-")) return;
        ModelSpec meta = out.specs.get(id);
        if (meta != null && meta.maxOutput > 0 && meta.maxOutput <= 256) return;
        if (!out.ids.contains(id)) out.ids.add(id);
    }

    /** 从「对象数组」里抽 id 并入 out。 */
    private static void collectModelIds(org.json.JSONArray arr, java.util.List<String> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i += 1) {
            JSONObject m = arr.optJSONObject(i);
            String id = m == null ? "" : firstNonEmpty(m, "id", "model", "name");
            if (!id.isEmpty() && !out.contains(id)) out.add(id);
        }
    }

    // -----------------------------------------------------------------------
    // 手工导入凭据（Qoder / ZCode / Raccoon 等无扫码的渠道）
    // -----------------------------------------------------------------------

    /**
     * 用粘贴进来的凭据填充渠道。
     *
     * 支持三种粘贴形态：
     *   1. 纯 access_token；
     *   2. JSON（含 access_token / refresh_token / domain 等字段）；
     *   3. `access=…&refresh=…` 键值串。
     */
    public static Channel importCredential(Channel channel, String pasted) throws IOException {
        Spec spec = specOf(channel.loginType);
        if (spec == null) throw new IOException("未知登录类型");

        Channel c = copyOf(channel);
        String text = pasted == null ? "" : pasted.trim();
        if (text.isEmpty()) throw new IOException("请粘贴 access_token 或凭据 JSON");

        if (text.startsWith("{")) {
            JSONObject o;
            try {
                o = new JSONObject(text);
            } catch (Exception e) {
                throw new IOException("粘贴的 JSON 无法解析");
            }
            c.accessToken = firstNonEmpty(o, "access_token", "accessToken", "token", "security_oauth_token");
            c.refreshToken = firstNonEmpty(o, "refresh_token", "refreshToken");
            String domain = firstNonEmpty(o, "domain");
            if (!domain.isEmpty()) c.domain = domain;
            String nick = firstNonEmpty(o, "nickname", "user_name");
            if (!nick.isEmpty()) c.nickname = nick;
            String ent = firstNonEmpty(o, "enterprise_id", "enterpriseId");
            if (!ent.isEmpty()) c.enterpriseId = ent;
            // Qoder 的 cosy 签名要 uid：凭据里带了就存下来，
            // 否则会出现「登录成功、一调用就报凭据缺少 uid」。
            String u = firstNonEmpty(o, "uid", "user_id", "userId");
            if (!u.isEmpty()) c.uid = u;
            String mid = firstNonEmpty(o, "machine_id", "machineId");
            if (!mid.isEmpty()) c.machineId = mid;        } else if (text.contains("=") && text.contains("&")) {
            for (String pair : text.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String k = pair.substring(0, eq).trim();
                String v = pair.substring(eq + 1).trim();
                if (k.equals("access_token") || k.equals("access")) c.accessToken = v;
                else if (k.equals("refresh_token") || k.equals("refresh")) c.refreshToken = v;
                else if (k.equals("domain")) c.domain = v;
            }
        } else {
            c.accessToken = text;
        }

        if (c.accessToken.isEmpty()) throw new IOException("凭据里没有 access_token");
        // 老渠道没存 uid（该字段是后加的）：先尽力从凭据推断，
        // 推不出来也不在这里拦——报错交给网关，文案会说明「请重新登录」。
        if (isQoderFamily(c.loginType) && (c.uid == null || c.uid.isEmpty())) {
            c.uid = inferQoderUid(c.accessToken);
        }        if (c.domain.isEmpty()) c.domain = spec.apiDomain;
        if (c.nickname.isEmpty()) c.nickname = jwtNickname(c.accessToken);
        // 取不到真实昵称时留空（渠道名不当昵称，否则界面显示成「名字 · 名字」）
        if (c.expiresAt <= 0) c.expiresAt = jwtExpiresAt(c.accessToken);

        if (spec.supportsDeviceFlow) {
            try {
                c.cachedModels = fetchModels(c);
                c.modelsUpdatedAt = System.currentTimeMillis();
            } catch (IOException ignored) {
            }
        }
        // 同 PollLoginTask 的规则：有实时目录的渠道不用内置表凑数
        if (c.cachedModels.isEmpty() && c.manualModels.isEmpty()
                && !hasLiveModelCatalog(c.loginType)) {
            c.manualModels = defaultModelsFor(c.loginType);
            // 带规格 / 倍率的内置表一并写入（否则手工导入的渠道看不到倍率）
            if (QODER.equals(c.loginType) || QODER_CN.equals(c.loginType)) {
                c.modelSpecs.putAll(qoderBuiltinSpecs(c.loginType));
            } else if (RACCOON.equals(c.loginType)) {
                c.modelSpecs.putAll(raccoonBuiltinSpecs());
            } else if (CODEARTS.equals(c.loginType)) {
                c.modelSpecs.putAll(codeartsBuiltinSpecs());
            }
        }
        return c;
    }

    /**
     * Qoder 的真实模型表（**17 条，带完整规格**）。
     *
     * <p>数据来源：`$DSH_HOME/.qoder-catalog.qoder.json`（桌面版从上游拉下来缓存的目录），
     * 字段逐条核对过 —— `key` 是调用用的 id，`id` 才是展示名（两者是**反的**，别搞混）。
     *
     * <p>为什么内置而不是实时拉：实测 Qoder **没有模型目录端点**。
     * 同一 token 调 `/sash/api/v1/me/campaigns` 与 `/sash/api/v2/me/usage` 都是 200，
     * 而 `/sash/api/v1/me/models`、`/sash/api/v2/models` 等全部 404 —— 说明不是权限问题，
     * 是这个接口不存在。
     *
     * @return id → 规格（保序）
     */
    public static java.util.Map<String, ModelSpec> qoderBuiltinSpecs() {
        return qoderBuiltinSpecs(QODER);
    }

    /**
     * 按区域返回内置规格表，只保留**实测可调用**的模型。
     *
     * <p>目录里声明的 17 个里多数打不通（400 Unsupported model 或 429 后端不可用），
     * 给它们配规格只会让界面「看起来能用」而点了就失败。
     */
    public static java.util.Map<String, ModelSpec> qoderBuiltinSpecs(String loginType) {
        java.util.Map<String, ModelSpec> usable = new java.util.LinkedHashMap<>();
        java.util.Map<String, ModelSpec> all = qoderAllSpecs();

        // 先列可直接用的
        for (String id : qoderUsableModels(loginType)) {
            ModelSpec known = all.get(id);
            if (known == null) continue;
            // 复制一份：all 是共享模板，直接改会污染另一个区域的判断
            usable.put(id, known.copy());
        }

        // 再补候选：用户要能看到「升级套餐能多用什么」，所以不能藏起来。
        // ⚠ 这里**不打标记** —— 标记由运行时探测（QoderProbe）按真实响应写，
        //   否则会把「此刻恰好能用」误报成「需要订阅」（实测确实会来回变）。
        for (String id : qoderProbeCandidates(loginType)) {
            if (usable.containsKey(id)) continue;
            ModelSpec known = all.get(id);
            if (known == null) continue;
            usable.put(id, known.copy());
        }
        return usable;
    }
    /** 目录里声明过的全部 Qoder 模型（含打不通的那些，仅供内部查规格）。 */
    static java.util.Map<String, ModelSpec> qoderAllSpecs() {
        java.util.Map<String, ModelSpec> m = new java.util.LinkedHashMap<>();
        // key, 展示名, 上下文, 输出(上游未报→0), 图片, 推理, 档位, 价格倍率, 免费
        addQoder(m, "auto", "Auto", 200000, 0, true, false, "", 0.5, false);
        addQoder(m, "ultimate", "Ultimate", 1000000, 0, true, true, "xhigh/high/low/max/medium", 2, false);
        addQoder(m, "performance", "Performance", 1000000, 0, true, true, "xhigh/high/low/max/medium", 1.1, false);
        addQoder(m, "efficient", "Efficient", 200000, 0, true, false, "", 0.3, false);
        addQoder(m, "smodel", "Sonus", 180000, 0, true, true, "xhigh/high/low/max/medium", 8, false);
        addQoder(m, "cmodel", "Cantus", 180000, 0, true, true, "xhigh/high/low/max/medium", 4, false);
        addQoder(m, "qmodel_38max", "Qwen3.8-Max", 180000, 0, true, true, "xhigh/low/medium", 0.5, false);
        addQoder(m, "qfmodel", "Qwen3.8-Flash", 180000, 0, true, true, "xhigh/low/medium", 0, true);
        addQoder(m, "qmodel_latest", "Qwen3.7-Max", 1000000, 0, true, true, "", 0.5, false);
        addQoder(m, "qmodel", "Qwen3.7-Plus", 1000000, 0, true, true, "", 0.1, false);
        addQoder(m, "kmodel_latest", "Kimi-K3", 180000, 0, true, true, "high/low/max", 1.4, false);
        // 上游 maxInputTokens 报 0（未上报），用 defaultContextWindow 兜底
        addQoder(m, "kmodel", "Kimi-K2.8-Preview", 200000, 0, true, true, "high/low/max", 0.8, false);
        addQoder(m, "gmodel", "GLM-5.3", 180000, 0, true, true, "high/low/max", 0.8, false);
        addQoder(m, "gfmodel", "GLM-5.3-Flash", 1000000, 0, true, true, "high/max", 0.1, false);
        addQoder(m, "dmodel", "DeepSeek-V4-Pro", 1000000, 0, true, true, "high/max", 0.5, false);
        addQoder(m, "dfmodel", "DeepSeek-Flash", 1000000, 0, true, true, "high/max/low", 0.1, false);
        addQoder(m, "mmodel", "MiniMax-M3", 180000, 0, true, false, "", 0.2, false);
        return m;
    }

    private static void addQoder(java.util.Map<String, ModelSpec> m, String id, String name,
                                 int ctx, int out, boolean images, boolean reasoning,
                                 String efforts, double price, boolean free) {
        ModelSpec s = new ModelSpec();
        s.name = name;
        s.contextWindow = ctx;
        s.maxOutput = out;
        s.supportsImages = images;
        s.priceFactor = price;
        s.free = free;
        // 倍率统一成 `xN` 形态（界面只认这一种）
        s.creditRate = free ? "x0" : ModelSpec.formatRate(price);
        if (!efforts.isEmpty()) {
            for (String e : efforts.split("/")) {
                if (!e.isEmpty()) s.efforts.add(e);
            }
        } else if (reasoning) {
            s.efforts.add("default");
        }
        m.put(id, s);
    }

    /** 各登录渠道的兜底模型清单（拉不到远端时用）。 */
    /**
     * 没有模型目录接口的渠道的内置清单。
     *
     * ## 只在「拉不到真实清单」时用，而且不算「可用」
     *
     * 这些渠道上游**没有**可靠的模型目录接口（Raccoon 只有静态表、ZCode 同理），
     * 所以清单只能内置。但它们**不代表账号一定有权限**——界面必须把来源标出来，
     * 否则又变成上一版那样「看着有、一用就错」。
     *
     * WorkBuddy 两个区域**不走这里**：它有 `/console/enterprises/personal/models`，
     * 一律以真实返回为准。
     *
     * @param loginType 登录渠道类型
     * @return 内置清单（可能为空）
     */
    public static java.util.List<String> defaultModelsFor(String loginType) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (ZCODE.equals(loginType)) {
            // ZCode 的模型来自 GLM 系列（BigModel 门户），实测账号通常只有这两个可用
            out.add("GLM-5.3");
            out.add("GLM-5.3-Flash");
        } else if (RACCOON.equals(loginType)) {
            out.addAll(raccoonBuiltinSpecs().keySet());
        } else if (QODER.equals(loginType) || QODER_CN.equals(loginType)) {
            // 列**实测能出正文的**模型（两区域可用集不同，见 qoderUsableModels）。
            out.addAll(qoderUsableModels(loginType));
            // 再补「易排队」的模型：它们可能暂时点了失败，但必须可见 ——
            // 否则用户不知道升级套餐能多用什么，只会以为网关少给了模型。
            // 界面会在名字后标「繁忙」，见 ModelSpec.queueBadge()。
            for (String id : qoderProbeCandidates(loginType)) {
                if (!out.contains(id)) out.add(id);
            }
        } else if (CODEARTS.equals(loginType)) {
            out.addAll(codeartsBuiltinSpecs().keySet());
        }
        return out;
    }

    /**
     * Raccoon 的内置模型表（目录端点不可用时的兜底，**带积分倍率**）。
     *
     * <p>数据来自上游 `model_catalog` 的真实下发（2026-11-04 实测）：
     * <pre>
     * sn-sensenova-6-8-flash        SenseNova-6.8-Flash        x0（免费）
     * sn-sensenova-6-8-flash-lite   SenseNova-6.8-Flash-Lite   x0（免费）
     * sn-glm-5-3                    GLM-5-3                    x0.75
     * sn-kimi-k3                    Kimi-K3                    x1
     * sn-glm-5-3-flash              GLM-5-3-Flash              x0.2 → x0.1
     * sn-deepseek-v4-1-flash        DeepSeek-V4.1-Flash        x0.25
     * </pre>
     *
     * <p>⚠ `x1` 也要显示：用户明确要求「Kimi-K3 是一倍，1 倍也要显示倍率」，
     * 否则会被当成「这个模型没有倍率信息」。
     */
    public static java.util.Map<String, ModelSpec> raccoonBuiltinSpecs() {
        java.util.Map<String, ModelSpec> m = new java.util.LinkedHashMap<>();
        addRaccoon(m, "sn-sensenova-6-8-flash", "SenseNova-6.8-Flash", 256000, 63999, "x0", "");
        addRaccoon(m, "sn-sensenova-6-8-flash-lite", "SenseNova-6.8-Flash-Lite", 256000, 63999, "x0", "");
        addRaccoon(m, "sn-glm-5-3", "GLM-5-3", 1000000, 100000, "x0.75", "");
        addRaccoon(m, "sn-kimi-k3", "Kimi-K3", 1000000, 100000, "x1", "");
        addRaccoon(m, "sn-glm-5-3-flash", "GLM-5-3-Flash", 1000000, 100000, "x0.2", "x0.1");
        addRaccoon(m, "sn-deepseek-v4-1-flash", "DeepSeek-V4.1-Flash", 1000000, 100000, "x0.25", "");
        return m;
    }

    private static void addRaccoon(java.util.Map<String, ModelSpec> m, String id, String name,
                                   int ctx, int out, String rate, String discounted) {
        ModelSpec s = new ModelSpec();
        s.name = name;
        s.contextWindow = ctx;
        s.maxOutput = out;
        // Raccoon 没有逐条的模态字段；这两条实测能看图（见 README），
        // 其它按文本处理，不臆造能力。
        s.supportsImages = "sn-deepseek-v4-1-flash".equals(id) || "sn-glm-5-3-flash".equals(id);
        s.creditRate = rate == null ? "" : rate;
        s.discountedCreditRate = discounted == null ? "" : discounted;
        m.put(id, s);
    }

    /**
     * 拉 Raccoon 的真实模型目录（带积分倍率）。
     *
     * <p>端点是 `GET /api/web/llm/v2/model_catalog`（实测 401 = 存在且要鉴权；
     * 而 `/llm/v2/models` 是 404 —— 两个路径只差一个词，前者才对）。
     *
     * @return 解析结果；失败时返回空 Catalog，由调用方退回内置表
     */
    public static Catalog fetchRaccoonModels(Channel channel) {
        Catalog out = new Catalog();
        try {
            Spec spec = specOf(RACCOON);
            if (spec == null || channel == null || channel.accessToken.isEmpty()) return out;
            String url = spec.endpoint + "/api/web/llm/v2/model_catalog";
            JSONObject body = requestJsonWithHeaders("GET", url, spec, channel, null, 20_000,
                    new String[][]{{"X-Raccoon-Language", "zh"}});
            return parseRaccoonCatalog(body);
        } catch (Exception e) {
            return out;
        }
    }

    /**
     * 解析 Raccoon 目录响应（纯函数，便于离线验证）。
     *
     * <pre>
     * {code:0, data:{categories:[{type:"chat", models:[…]}]}}
     * </pre>
     * 只看 `type == "chat"` 那一类：其它类是绘图 / 视频，走 chat 接口必然失败。
     */
    static Catalog parseRaccoonCatalog(JSONObject body) {
        Catalog out = new Catalog();
        if (body == null) return out;
        if (body.has("code") && body.optInt("code", -1) != 0) return out;
        JSONObject data = body.optJSONObject("data");
        if (data == null) return out;
        org.json.JSONArray categories = data.optJSONArray("categories");
        if (categories == null) return out;
        for (int i = 0; i < categories.length(); i += 1) {
            JSONObject cat = categories.optJSONObject(i);
            if (cat == null || !"chat".equals(cat.optString("type", ""))) continue;
            org.json.JSONArray models = cat.optJSONArray("models");
            if (models == null) continue;
            for (int k = 0; k < models.length(); k += 1) {
                JSONObject raw = models.optJSONObject(k);
                if (raw == null) continue;
                if (!raw.optBoolean("visible", true)) continue;
                String id = raw.optString("name", "").trim();
                if (id.isEmpty() || out.ids.contains(id)) continue;
                ModelSpec spec = ModelSpec.fromRaccoon(raw);
                if (spec.isEmpty()) continue;
                out.ids.add(id);
                out.specs.put(id, spec);
            }
        }
        return out;
    }

    /**
     * 该渠道的模型清单能否从上游**实时核对**。
     *
     * 只有 WorkBuddy 两区域有目录接口；其余渠道只能靠内置表，
     * 界面据此决定要不要标「内置清单，未与账号权限核对」。
     *
     * @param loginType 登录渠道类型
     * @return true 表示清单来自上游真实返回
     */
    public static boolean hasLiveModelCatalog(String loginType) {
        // CodeArts 也有模型清单端点（`/v1/model/builtin`，需 AK/SK 签名），
        // 但它的实现在 ModelCatalog 里单独走（签名不同于 Bearer）
        return WB_INTL.equals(loginType) || WB_CN.equals(loginType);
    }

    /** CodeArts 的模型清单端点（需 AK/SK 签名）。 */
    private static final String CODEARTS_MODELS_URL =
            "https://snap-access.cn-north-4.myhuaweicloud.com/v1/model/builtin";

    /**
     * 拉 CodeArts 的真实模型清单（签名 GET）。
     *
     * <p>响应结构（实测自客户端解析逻辑）：
     * <pre>
     * { "builtinModels": [ {"model_id": "GLM-5.2", "model_name": "GLM-5.2"}, … ] }
     * </pre>
     * 客户端会跳过 `-VL-` / 结尾 `-VL` 的多模态变体（那是视觉专用 id，
     * 走 chat 会失败），这里保持一致。
     *
     * <p>⚠ 失败时返回空 Catalog，由调用方退回内置表 —— 不能因为一次网络问题
     * 就让「刷新模型」报错，CodeArts 的临时凭据本来就容易过期。
     */
    public static Catalog fetchCodeArtsModels(Channel channel) {
        Catalog out = new Catalog();
        try {
            String text = HuaweiSign.request(
                    channel.accessKeyId, channel.secretAccessKey, channel.securityToken,
                    "GET", CODEARTS_MODELS_URL, "", 20_000);
            JSONObject root = new JSONObject(text);
            org.json.JSONArray arr = root.optJSONArray("builtinModels");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i += 1) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                String id = m.optString("model_id", "").trim();
                if (id.isEmpty()) continue;
                // VL 变体是视觉专用 id，走 chat 会失败（与客户端一致跳过）
                if (id.contains("-VL-") || id.endsWith("-VL")) continue;
                if (out.ids.contains(id)) continue;
                out.ids.add(id);

                String name = m.optString("model_name", "").trim();
                ModelSpec spec = new ModelSpec();
                spec.name = name.isEmpty() ? id : name;
                // 上游只给 id/name，规格沿用内置表里已知的（有就补上）
                ModelSpec known = codeartsBuiltinSpecs().get(id);
                if (known != null) {
                    spec.contextWindow = known.contextWindow;
                    spec.maxOutput = known.maxOutput;
                    spec.efforts.addAll(known.efforts);
                    spec.supportsImages = known.supportsImages;
                }
                // 上游本身也给上下文 / 输出 / 图片，优先用它（比内置表新）
                int ctx = m.optInt("context_window", 0);
                if (ctx > 0) spec.contextWindow = ctx;
                int maxTok = m.optInt("max_tokens", 0);
                if (maxTok > 0) spec.maxOutput = maxTok;
                if (m.optBoolean("supports_images", false)) spec.supportsImages = true;
                // 积分倍率：`credit[]` 每档一条，取 `ratio_display`（如 `0.7x`）
                applyCodeArtsCredit(spec, m.optJSONArray("credit"));
                out.specs.put(id, spec);
            }
        } catch (Exception e) {
            // 交给调用方决定（通常是退回内置表）
            return out;
        }
        return out;
    }

    /**
     * CodeArts 的积分倍率。
     *
     * <p>上游 `credit[]` 是**分档**的，每条带 `input_from` / `input_to` 与
     * `ratio_display`（如 `0.7x`）：
     * <pre>
     * {"input_from":0,"input_to":32000,"ratio":"0.028","ratio_display":"0.7x"}
     * {"input_from":32000,"input_to":-1,"ratio":"0.05","ratio_display":"1.2x"}
     * </pre>
     * 多档时只取第一档做「主倍率」，并在备注里说明是分档计费 ——
     * 硬塞两行到小字里没人看，摘要留一个数最有用。
     */
    static void applyCodeArtsCredit(ModelSpec spec, org.json.JSONArray credit) {
        if (spec == null || credit == null || credit.length() == 0) return;
        JSONObject first = credit.optJSONObject(0);
        if (first == null) return;
        String rate = ModelSpec.normalizeRate(first.optString("ratio_display", ""));
        if (rate.isEmpty()) rate = ModelSpec.normalizeRate(first.optString("ratio", ""));
        if (rate.isEmpty()) return;
        spec.creditRate = rate;
        if (credit.length() > 1) {
            int boundary = first.optInt("input_to", -1);
            spec.creditNote = boundary > 0
                    ? "分档，" + ModelMeta.shortTokens(boundary) + " 以内"
                    : "分档计费";
        }
    }

    /**
     * CodeArts 的**免费额度（benefit）模型**。
     *
     * <p>华为对 CodeArts 用的是「积分 / 免费额度」机制：这些模型不扣套餐，
     * 而是消耗 benefit 额度，按 **UTC+8 自然日**重置。用它们发请求时，
     * 签名里必须带 `maas_type: benefit`，否则走不到免费通道。
     *
     * <p>列表来自 CodeArts 客户端：
     * <pre>
     * CODEARTS_BENEFIT_FALLBACK = ["glm-5.3-flash", "deepseek-v4.1-flash"]
     * </pre>
     * 客户端还会把 gateway config 里下发的 benefit id 并进来（那份列表是动态的），
     * 这里用固定后备，够了 —— 多了也用不上，少了会在聊天时明确报额度不足。
     */
    private static final java.util.Set<String> CODEARTS_BENEFIT_MODELS =
            new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                    "glm-5.3-flash", "deepseek-v4.1-flash"));

    /** 该模型是否走 CodeArts 的免费额度（benefit）。 */
    public static boolean isCodeArtsBenefitModel(String model) {
        if (model == null) return false;
        return CODEARTS_BENEFIT_MODELS.contains(model.trim().toLowerCase(java.util.Locale.US));
    }

    /** 免费额度模型列表（界面展示用）。 */
    public static java.util.List<String> codeArtsBenefitModels() {
        return new java.util.ArrayList<>(CODEARTS_BENEFIT_MODELS);
    }

    /**
     * CodeArts 的内置模型表（清单端点不可用时的兜底）。
     *
     * <p>来自 CodeArts 客户端 `DEFAULT_MODELS` 与 `CONTEXT_WINDOWS`（实测）。
     */
    public static java.util.Map<String, ModelSpec> codeartsBuiltinSpecs() {
        java.util.Map<String, ModelSpec> m = new java.util.LinkedHashMap<>();
        // 倍率来自上游 `/v1/model/builtin` 的 `credit[].ratio_display`（实测 2026-11-04）；
        // 这里只是兜底值，刷新模型时会被上游真实值覆盖。
        addCodeArts(m, "GLM-5.2", "GLM-5.2", 202752, true, "x0.7");
        addCodeArts(m, "GLM-5.1", "GLM-5.1", 0, true, "x1.2");
        addCodeArts(m, "GLM-5", "GLM-5", 0, true, "x1.2");
        addCodeArts(m, "glm-5.3-flash", "GLM-5.3-Flash", 1048576, true, "x0.08");
        addCodeArts(m, "openpangu-2.0-flash", "openPangu-2.0-Flash", 0, false, "x0.32");
        addCodeArts(m, "openpangu-2.0-pro", "openPangu-2.0-Pro", 0, false, "x0.7");
        addCodeArts(m, "deepseek-v4-flash", "DeepSeek-V4-Flash", 1048576, true, "x0.18");
        addCodeArts(m, "deepseek-v4-pro", "DeepSeek-V4-Pro", 1048576, true, "x0.79");
        addCodeArts(m, "deepseek-v4.1-flash", "DeepSeek-V4.1-Flash", 1000000, true, "x0.18");
        return m;
    }

    private static void addCodeArts(java.util.Map<String, ModelSpec> m, String id, String name,
                                    int ctx, boolean reasoning) {
        addCodeArts(m, id, name, ctx, reasoning, "");
    }

    private static void addCodeArts(java.util.Map<String, ModelSpec> m, String id, String name,
                                    int ctx, boolean reasoning, String creditRate) {
        ModelSpec s = new ModelSpec();
        s.name = name;
        s.contextWindow = ctx;
        // CodeArts 的模型都支持工具调用；图片能力上游未逐条声明，按客户端的
        // 默认表保守处理（不标支持，避免误导）
        s.supportsImages = false;
        if (reasoning) s.efforts.add("default");
        s.creditRate = creditRate == null ? "" : creditRate;
        m.put(id, s);
    }

    // -----------------------------------------------------------------------
    // HTTP 工具
    // -----------------------------------------------------------------------

    /**
     * 发一个 JSON 请求，返回解析后的响应体。
     *
     * 全部用平台无关的 HttpURLConnection（Android 与桌面 JVM 都能跑）。
     */
    static JSONObject requestJson(String method, String url, Spec spec, Channel credential,
                                  String body, int timeoutMs) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", spec.userAgent);
            // 这两个头是 WorkBuddy 网关识别「插件请求」的关键
            conn.setRequestProperty("X-Domain", credential != null && !credential.domain.isEmpty()
                    ? credential.domain : spec.apiDomain);
            conn.setRequestProperty("X-Product-Code", spec.productCode);
            conn.setRequestProperty("X-Product", spec.productCode);

            if (credential == null || credential.accessToken.isEmpty()) {
                // 未登录阶段：明确告诉网关「不要校验授权/用户/企业/部门」
                conn.setRequestProperty("X-No-Authorization", "true");
                conn.setRequestProperty("X-No-User-Id", "true");
                conn.setRequestProperty("X-No-Enterprise-Id", "true");
                conn.setRequestProperty("X-No-Department-Info", "true");
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + credential.accessToken);
                if (!credential.enterpriseId.isEmpty()) {
                    conn.setRequestProperty("X-Enterprise-Id", credential.enterpriseId);
                    conn.setRequestProperty("X-Tenant-Id", credential.enterpriseId);
                }
            }

            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();
            }

            int status = conn.getResponseCode();
            InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            String text = is == null ? "" : new String(readAll(is), StandardCharsets.UTF_8);
            if (text.trim().isEmpty()) {
                if (status == 401) throw new IOException("HTTP 401（凭据无效或已过期）");
                throw new IOException("HTTP " + status + "（响应为空）");
            }
            try {
                return new JSONObject(text);
            } catch (Exception e) {
                throw new IOException("响应不是 JSON（HTTP " + status + "）："
                        + text.substring(0, Math.min(200, text.length())));
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 带额外请求头的 JSON 请求（积分接口要 `Cosy-ClientType` 这类头）。
     *
     * @param extraHeaders 二维数组 {{name, value}, …}
     */
    static JSONObject requestJsonWithHeaders(String method, String url, Spec spec, Channel credential,
                                            String body, int timeoutMs, String[][] extraHeaders) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", spec.userAgent);
            conn.setRequestProperty("X-Domain", credential != null && !credential.domain.isEmpty()
                    ? credential.domain : spec.apiDomain);
            conn.setRequestProperty("X-Product-Code", spec.productCode);
            conn.setRequestProperty("X-Product", spec.productCode);
            if (credential == null || credential.accessToken.isEmpty()) {
                conn.setRequestProperty("X-No-Authorization", "true");
                conn.setRequestProperty("X-No-User-Id", "true");
                conn.setRequestProperty("X-No-Enterprise-Id", "true");
                conn.setRequestProperty("X-No-Department-Info", "true");
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + credential.accessToken);
                if (!credential.enterpriseId.isEmpty()) {
                    conn.setRequestProperty("X-Enterprise-Id", credential.enterpriseId);
                    conn.setRequestProperty("X-Tenant-Id", credential.enterpriseId);
                }
            }
            if (extraHeaders != null) {
                for (String[] header : extraHeaders) {
                    if (header != null && header.length >= 2) conn.setRequestProperty(header[0], header[1]);
                }
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();
            }
            int status = conn.getResponseCode();
            InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            String text = is == null ? "" : new String(readAll(is), StandardCharsets.UTF_8);
            if (text.trim().isEmpty()) {
                if (status == 401) throw new IOException("HTTP 401（凭据无效或已过期）");
                throw new IOException("HTTP " + status + "（响应为空）");
            }
            try {
                return new JSONObject(text);
            } catch (Exception e) {
                throw new IOException("响应不是 JSON（HTTP " + status + "）："
                        + text.substring(0, Math.min(200, text.length())));
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 解析日期字符串（ISO 8601 / `yyyy-MM-dd HH:mm:ss` / RFC1123 等常见形态）。
     *
     * @return 毫秒时间戳；解析失败返回 0。
     */
    static long parseDate(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        String s = text.trim();
        // ISO 8601
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                return java.time.Instant.parse(s).toEpochMilli();
            }
        } catch (Exception ignored) {
        }
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd",
        };
        for (String pattern : patterns) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(pattern, java.util.Locale.US);
                f.setLenient(true);
                java.util.Date d = f.parse(s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }
    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    static String firstNonEmpty(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** 从 JWT 读取过期时间（毫秒）；解析失败返回 0。 */
    static long jwtExpiresAt(String token) {
        String payload = jwtPayloadJson(token);
        if (payload == null) return 0L;
        try {
            JSONObject o = new JSONObject(payload);
            long exp = o.optLong("exp", 0L);
            return exp > 0 ? exp * 1000L : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 从 JWT 读取昵称；解析失败返回空串。 */
    static String jwtNickname(String token) {
        String payload = jwtPayloadJson(token);
        if (payload == null) return "";
        try {
            JSONObject o = new JSONObject(payload);
            return firstNonEmpty(o, "nickname", "preferred_username", "name");
        } catch (Exception e) {
            return "";
        }
    }

    static String jwtPayloadJson(String token) {
        if (token == null) return null;
        String[] parts = token.split("\\.");
        if (parts.length < 2) return null;
        try {
            String b64 = parts[1].replace('-', '+').replace('_', '/');
            while (b64.length() % 4 != 0) b64 = b64 + "=";
            byte[] decoded = java.util.Base64.getDecoder().decode(b64);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 复制渠道（避免就地改 UI 持有的对象）。 */
    static Channel copyOf(Channel src) {
        return Channel.fromJson(src.toJson());
    }
}
