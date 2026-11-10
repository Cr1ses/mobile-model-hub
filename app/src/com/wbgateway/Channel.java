package com.wbgateway;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个账号渠道。
 *
 * 两类渠道共用这个结构：
 *   - **登录型**（kind = LOGIN）：WorkBuddy / Qoder / ZCode / Raccoon，
 *     凭据由 App 内的扫码登录或网页登录获得，`credential` 里存 token；
 *   - **代理型**（kind = PROXY）：手动填 BaseURL + API Key 的 OpenAI 兼容上游。
 *
 * 渠道 id 同时是对外模型前缀：id=cn 时模型 id 写作 cn:glm-5.3。
 */
public class Channel {

    /** 代理型：手动填 Key。 */
    public static final String KIND_PROXY = "proxy";
    /** 登录型：App 内登录后自动拿到凭据。 */
    public static final String KIND_LOGIN = "login";

    /** 渠道 id：小写字母开头，只含小写字母/数字/短横线，≤16 字符。 */
    public String id = "";
    /** 展示名。 */
    public String name = "";
    /** KIND_PROXY / KIND_LOGIN。 */
    public String kind = KIND_PROXY;
    /** 上游基址，如 https://api.deepseek.com/v1（末尾不带斜杠）。 */
    public String baseUrl = "";
    /** 上游 API Key 池；代理型用。 */
    public List<String> apiKeys = new ArrayList<>();
    /** 是否启用。 */
    public boolean enabled = true;
    /**
     * 手动指定的模型清单（逗号分隔）。
     * 上游 /models 拉不到时用它兜底（例如某些网关不实现该端点）。
     */
    public List<String> manualModels = new ArrayList<>();
    /** 上次从上游拉到的模型清单（缓存）。 */
    public List<String> cachedModels = new ArrayList<>();
    /**
     * 上游下发的模型规格（模型 id → 展示名/上下文/输出/图片/推理档位）。
     *
     * <p>为什么要存下来：上游的 `data.models[]` 里带了
     * `name` / `maxInputTokens` / `maxOutputTokens` / `supportsImages` / `reasoning`，
     * 这是**账号真实可用**的规格。`ModelMeta` 里那张静态表只是兜底，
     * 遇到新模型会显示成「未知」。存下上游的值，界面就能显示真实名与真实窗口。
     *
     * <p>用 Map 而不是并入 {@link #cachedModels}：模型清单要保序（界面上按上游顺序排），
     * 而且规格是「附加信息」，缺失时界面仍应正常显示模型 id。
     */
    public java.util.Map<String, ModelSpec> modelSpecs = new java.util.LinkedHashMap<>();
    /** 缓存时间戳（毫秒）。 */
    public long modelsUpdatedAt = 0L;
    /** 上次错误信息（仅展示用）。 */
    public String lastError = "";

    /**
     * 界面标题：`渠道名 · 昵称`，**昵称与渠道名相同时不重复拼接**。
     *
     * <p>为什么需要这个判断：登录时若上游没给真实昵称，早期版本会回填成
     * `spec.displayName`（渠道名）。拼出来就是
     * 「Qoder（中国版） · Qoder（中国版）」—— 用户会以为界面重复渲染了，
     * 其实是同一个值显示了两次。
     *
     * @return 可直接显示的标题（纯函数，便于离线验证）
     */
    public String displayTitle() {
        String base = name.isEmpty() ? loginType : name;
        if (nickname.isEmpty()) return base;
        // 昵称没有信息量时不拼：等于渠道名，或等于登录类型
        if (nickname.equals(name) || nickname.equals(loginType)) return base;
        return base + " · " + nickname;
    }
    /**
     * 被用户关掉的模型 id 集合。
     *
     * 存「关掉的」而不是「打开的」：新增模型（上游更新清单）默认可用，
     * 用户不必每次刷新后重新勾选一遍。
     */
    public List<String> disabledModels = new ArrayList<>();

    // ---- 登录型渠道专用 ----

    /** 登录渠道的类型标识：workbuddy / workbuddy-cn / qoder / qodercn / zcode / raccoon。 */
    public String loginType = "";
    /** access token（登录后写入）。 */
    public String accessToken = "";
    /** refresh token。 */
    public String refreshToken = "";
    /** token 过期时间（毫秒时间戳，0 表示未知）。 */
    public long expiresAt = 0L;
    /** 凭据归属站点（如 www.workbuddy.ai）。 */
    public String domain = "";
    /** 账号昵称（登录后从 token 或账号接口读出）。 */
    public String nickname = "";
    /** 企业/租户 id（部分上游要用）。 */
    public String enterpriseId = "";
    /** 扫码登录过程中的 state（仅登录期间用，不持久化）。 */
    public String loginState = "";
    /**
     * Qoder 的设备 machine_id。
     *
     * 为什么必须存：Qoder 的令牌与 machine_id 绑定，续期时要原样带回，
     * 换一个 id 续期会被上游拒绝（表现为「刷新一直失败，只能重新登录」）。
     */
    public String machineId = "";
    /**
     * Qoder 的账号 uid。
     *
     * <p>为什么必须存：cosy 签名的 {@code info} 载荷里要带 uid，且
     * {@code cosy-user} 头也用它；缺了就签不出能被网关接受的请求
     * （表现为 403 Signature invalid）。
     */
    public String uid = "";
    /** refresh_token 自身的过期时间（毫秒时间戳，0 表示未知）。 */
    public long refreshExpiresAt = 0L;
    /** ZCode 的 zcode JWT（与业务 access_token 分开存）。 */
    public String zcodeJwt = "";
    /** Raccoon 的组织标识（个人账号为空串，但客户端总是发这个头）。 */
    public String officeIdentity = "";

    // —— 华为 CodeArts（华为云临时凭据 + AK/SK 签名）——
    //
    // 为什么不用 accessToken：CodeArts 调 InferHub 走的是华为云
    // SDK-HMAC-SHA256 签名，要 AK / SK / security_token 三件套；
    // 而这三件有效期约 2 小时，靠 refreshToken + DPoP 私钥续期。
    /** 临时凭据：access key。 */
    public String accessKeyId = "";
    /** 临时凭据：secret key。 */
    public String secretAccessKey = "";
    /** 临时凭据：安全令牌（不带它华为会说「access key not found」）。 */
    public String securityToken = "";
    /** DPoP 私钥 JWK（续期时复用，丢了就只能重新登录）。 */
    public String dpopJwk = "";
    /** 登录时生成的 PKCE code_verifier（续期要带同一个）。 */
    public String codeVerifier = "";

    /** 当前可用的模型清单：缓存优先，其次手动，去重。 */
    /** 渠道提供的**全部**模型（含被关掉的），去重。 */
    public List<String> allModels() {
        List<String> out = new ArrayList<>();
        for (String m : cachedModels) {
            if (m != null && !m.trim().isEmpty() && !out.contains(m.trim())) out.add(m.trim());
        }
        for (String m : manualModels) {
            if (m != null && !m.trim().isEmpty() && !out.contains(m.trim())) out.add(m.trim());
        }
        return out;
    }

    /** 界面里该渠道的模型清单是否展开（仅内存态，不持久化）。 */
    private boolean modelsExpanded = false;

    public boolean modelsExpanded() {
        return modelsExpanded;
    }

    public void setModelsExpanded(boolean expanded) {
        this.modelsExpanded = expanded;
    }

    /** 该模型是否被用户关掉。 */
    public boolean isModelDisabled(String modelId) {
        return modelId != null && disabledModels.contains(modelId);
    }

    /** 打开 / 关闭某个模型（返回是否发生了变化）。 */
    public boolean setModelEnabled(String modelId, boolean enabled) {
        if (modelId == null || modelId.trim().isEmpty()) return false;
        String id = modelId.trim();
        if (enabled) {
            return disabledModels.remove(id);
        }
        if (disabledModels.contains(id)) return false;
        disabledModels.add(id);
        return true;
    }

    /**
     * 生效的模型清单：`allModels()` 去掉被关掉的。
     *
     * 只在这一处做过滤 —— `/v1/models`、裸 id 路由、模型计数都走它，
     * 保证「界面里关掉的模型」= 「对外不可见 + 不可调用」。
     */
    /**
     * 取模型的**对外展示名**（真实名）。
     *
     * <p>为什么不能用内部 key：`qodercn:qmodel` 对用户毫无意义，
     * 而它真实是 `Qwen3.7-Plus`。客户端模型选择器里应该照真实名显示。
     *
     * <p>回退顺序：
     * <ol>
     *   <li>上游/内置规格表里的 `name`（最准）；</li>
     *   <li>{@link ModelMeta} 的本地已知名；</li>
     *   <li>都没有就退回 id 本身（不编造）。</li>
     * </ol>
     */
    public String displayNameOf(String modelId) {
        if (modelId == null || modelId.isEmpty()) return "";
        ModelSpec spec = modelSpecs.get(modelId);
        if (spec != null && spec.name != null && !spec.name.isEmpty()) return spec.name;
        ModelMeta.Info info = ModelMeta.lookup(modelId);
        if (info != null && info.name != null && !info.name.isEmpty()) return info.name;
        return modelId;
    }

    /**
     * 由**展示名**反查内部模型 id（路由用）。
     *
     * <p>客户端拿到的是真实名（`Qwen3.7-Plus`），但它回传时要能找回
     * `qmodel` 才能发请求。这里做反向映射。
     *
     * <p>匹配规则：先精确匹配 id，再精确匹配展示名，最后忽略大小写匹配。
     * 匹配不到返回 null（调用方再走别的兜底）。
     */
    public String resolveModelId(String requested) {
        if (requested == null || requested.isEmpty()) return null;
        List<String> all = allModels();
        if (all.contains(requested)) return requested;
        for (String id : all) {
            if (requested.equals(displayNameOf(id))) return id;
        }
        for (String id : all) {
            if (requested.equalsIgnoreCase(displayNameOf(id))) return id;
            if (requested.equalsIgnoreCase(id)) return id;
        }
        return null;
    }

    public List<String> effectiveModels() {
        List<String> out = new ArrayList<>();
        for (String m : allModels()) {
            if (!disabledModels.contains(m)) out.add(m);
        }
        return out;
    }

    public Channel() {
    }

    /** 便捷构造：代理型渠道。 */
    public Channel(String id, String name, String baseUrl) {
        this.id = id;
        this.name = name;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.kind = KIND_PROXY;
    }

    /**
     * 是否已有可用凭据（登录型看 token，代理型看 Key）。
     *
     * <p>CodeArts 是**唯一没有 Bearer token 的登录型渠道**：它的凭据是华为云
     * 临时三件套（AK / SK / security_token）。只判 `accessToken` 会把它当成
     * 「没登录」—— 表现是登录成功后界面仍是「登录」按钮、网关回 401、
     * 积分/签到都点不动（这三处都先过这个方法）。
     */
    public boolean hasCredential() {
        if (KIND_LOGIN.equals(kind)) {
            if (accessToken != null && !accessToken.isEmpty()) return true;
            // 临时凭据也是凭据：三件套齐全即可用（过期由 isExpired 判断）
            return !accessKeyId.isEmpty() && !secretAccessKey.isEmpty() && !securityToken.isEmpty();
        }
        return !apiKeys.isEmpty();
    }

    /** token 是否已过期（提前 60 秒判定，避免临界失败）。 */
    public boolean isExpired() {
        if (expiresAt <= 0) return false;
        return System.currentTimeMillis() >= expiresAt - 60_000L;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("kind", kind);
            o.put("baseUrl", baseUrl);
            o.put("apiKeys", new JSONArray(apiKeys));
            o.put("enabled", enabled);
            o.put("manualModels", new JSONArray(manualModels));
            o.put("cachedModels", new JSONArray(cachedModels));
            o.put("disabledModels", new JSONArray(disabledModels));
            o.put("modelsUpdatedAt", modelsUpdatedAt);
            // 上游下发的真实规格（模型 id → 规格）；空时不写，省体积
            if (!modelSpecs.isEmpty()) {
                JSONObject specs = new JSONObject();
                for (java.util.Map.Entry<String, ModelSpec> e : modelSpecs.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    specs.put(e.getKey(), e.getValue().toJson());
                }
                o.put("modelSpecs", specs);
            }
            o.put("loginType", loginType);
            o.put("accessToken", accessToken);
            o.put("refreshToken", refreshToken);
            o.put("expiresAt", expiresAt);
            o.put("domain", domain);
            o.put("nickname", nickname);
            o.put("enterpriseId", enterpriseId);
            o.put("machineId", machineId);
            if (!uid.isEmpty()) o.put("uid", uid);
            o.put("refreshExpiresAt", refreshExpiresAt);
            o.put("zcodeJwt", zcodeJwt);
            o.put("officeIdentity", officeIdentity);
            // CodeArts：临时凭据 + DPoP 私钥（续期必需，丢了得重新登录）
            if (!accessKeyId.isEmpty()) o.put("accessKeyId", accessKeyId);
            if (!secretAccessKey.isEmpty()) o.put("secretAccessKey", secretAccessKey);
            if (!securityToken.isEmpty()) o.put("securityToken", securityToken);
            if (!dpopJwk.isEmpty()) o.put("dpopJwk", dpopJwk);
            if (!codeVerifier.isEmpty()) o.put("codeVerifier", codeVerifier);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Channel fromJson(JSONObject o) {
        Channel c = new Channel();
        c.id = o.optString("id", "");
        c.name = o.optString("name", c.id);
        c.kind = o.optString("kind", KIND_PROXY);
        c.baseUrl = normalizeBaseUrl(o.optString("baseUrl", ""));
        c.enabled = o.optBoolean("enabled", true);
        c.modelsUpdatedAt = o.optLong("modelsUpdatedAt", 0L);
        c.apiKeys = readStringArray(o.optJSONArray("apiKeys"));
        c.manualModels = readStringArray(o.optJSONArray("manualModels"));
        c.cachedModels = readStringArray(o.optJSONArray("cachedModels"));
        c.disabledModels = readStringArray(o.optJSONArray("disabledModels"));
        // 上游下发的真实规格
        JSONObject specs = o.optJSONObject("modelSpecs");
        if (specs != null) {
            java.util.Iterator<String> keys = specs.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                JSONObject one = specs.optJSONObject(k);
                if (k == null || one == null) continue;
                c.modelSpecs.put(k, ModelSpec.fromJson(one));
            }
        }
        c.loginType = o.optString("loginType", "");
        c.accessToken = o.optString("accessToken", "");
        c.refreshToken = o.optString("refreshToken", "");
        c.expiresAt = o.optLong("expiresAt", 0L);
        c.domain = o.optString("domain", "");
        c.nickname = o.optString("nickname", "");
        c.enterpriseId = o.optString("enterpriseId", "");
        c.machineId = o.optString("machineId", "");
        c.uid = o.optString("uid", "");
        c.refreshExpiresAt = o.optLong("refreshExpiresAt", 0L);
        c.zcodeJwt = o.optString("zcodeJwt", "");
        c.officeIdentity = o.optString("officeIdentity", "");
        c.accessKeyId = o.optString("accessKeyId", "");
        c.secretAccessKey = o.optString("secretAccessKey", "");
        c.securityToken = o.optString("securityToken", "");
        c.dpopJwk = o.optString("dpopJwk", "");
        c.codeVerifier = o.optString("codeVerifier", "");
        // 迁移：早期 CodeArts 登录没有写 `baseUrl`，存下来的渠道会把请求拼成
        // 裸的 `/chat/completions`（报「no protocol: /chat/completions」）。
        // 读到时就补上，用户不用重新登录。
        if (c.baseUrl.isEmpty()) {
            c.baseUrl = LoginProviders.chatBaseFor(c.loginType);
        }
        // 迁移：Qoder 的 `uid` 字段是后加的，老渠道没存 —— 读到时尽力补。
        //
        // 不补的后果是「升级后一调用就报凭据缺少 uid」，看起来像要重新登录，
        // 其实凭据是好的。`dt-` 形态推不出来时留空，由网关如实报错。
        if (LoginProviders.isQoderFamily(c.loginType) && c.uid.isEmpty()) {
            c.uid = LoginProviders.inferQoderUid(c.accessToken);
        }
        if (c.baseUrl.isEmpty() && LoginProviders.CODEARTS.equals(c.loginType)) {
            c.baseUrl = CodeArtsLogin.API_BASE;
        }
        return c;
    }

    /**
     * 规范化 Base URL：去首尾空白、去掉末尾的 `/`。
     *
     * <p>末尾带斜杠时拼出来是 `//chat/completions`，部分上游会 404；
     * 用户从文档里复制地址时经常带斜杠，所以在入口处统一处理。
     */
    static String normalizeBaseUrl(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static List<String> readStringArray(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            String v = arr.optString(i, "");
            if (!v.trim().isEmpty()) out.add(v.trim());
        }
        return out;
    }

    /** 校验渠道 id 是否合法（同时是模型前缀，不能与保留字冲突）。 */
    public static boolean isValidId(String id) {
        if (id == null) return false;
        if (!id.matches("^[a-z][a-z0-9-]{0,15}$")) return false;
        return !(id.equals("v1") || id.equals("admin"));
    }
}
