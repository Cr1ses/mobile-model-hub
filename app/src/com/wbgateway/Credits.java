package com.wbgateway;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 积分 / 余额查询。
 *
 * 只做一件事：跟上游要数字，不碰界面。协议来自桌面版插件里**已实测**的实现
 * （`src/credits.js`），端点与信封形状都是抓包确认的，不是猜的：
 *
 * ```text
 * WorkBuddy（国内/国际，同协议只有域名不同）
 *   POST https://{domain}/v2/billing/meter/get-user-resource
 *   body: { PageNumber:1, PageSize:100, ProductCode:"p_tcaca", Status:[0,3],
 *           PackageEndTimeRangeBegin:"YYYY-MM-DD HH:mm:ss", …End:"…" }
 *   → data.Response.Data.Accounts[]   每个账号一条资源包
 *
 * Qoder / Qoder 中国版
 *   GET https://{openApiBase}/sash/api/v2/me/usage
 *   头：Cosy-ClientType: 10
 *   → qoderUsage.userQuota / addOnQuota
 *
 * Raccoon（商汤）
 *   GET https://xiaohuanxiong.com/api/web/points/v1/balance
 *   → data.available_points（+ 奖励/每日/会员/充值分项）
 *
 * ZCode：上游没有积分查询接口（如实说明，不给假数据）
 * ```
 *
 * ## 两个必须注意的点
 *
 * 1. **WorkBuddy 的月包与一次性包要分开算**：一次性包已过期或已用完的不计入
 *    总额，否则余额会虚高（参考实现踩过这个坑）；
 * 2. **失败要说清原因**：查不到时返回带原因的结果，界面才能显示
 *    「企业账号不显示积分」「ZCode 没有该接口」而不是一片空白。
 */
public final class Credits {

    /** 单次查询超时：积分是辅助信息，等太久不如不给。 */
    private static final int TIMEOUT_MS = 15_000;

    /** 一个资源包（或积分分项）。 */
    public static final class Package {
        public final String name;
        public final double remaining;
        public final String unit;
        public final boolean monthly;
        public final long expiresAt;

        Package(String name, double remaining, String unit, boolean monthly, long expiresAt) {
            this.name = name;
            this.remaining = remaining;
            this.unit = unit;
            this.monthly = monthly;
            this.expiresAt = expiresAt;
        }
    }

    /** 查询结果。 */
    public static final class Result {
        /** 是否查到了数字（false 时看 {@link #message}）。 */
        public final boolean ok;
        /** 总额（各资源包相加）。 */
        public final double total;
        /** 单位：credits / points。 */
        public final String unit;
        /** 明细。 */
        public final List<Package> packages;
        /** 最近到期时间（毫秒），0 表示无。 */
        public final long nearestExpiry;
        /** 失败原因（ok=true 时为空）。 */
        public final String message;

        Result(boolean ok, double total, String unit, List<Package> packages,
               long nearestExpiry, String message) {
            this.ok = ok;
            this.total = total;
            this.unit = unit;
            this.packages = packages;
            this.nearestExpiry = nearestExpiry;
            this.message = message;
        }

        static Result fail(String message) {
            return new Result(false, 0, "", new ArrayList<>(), 0, message);
        }

        /** 给离线验证构造结果用（生产代码不调用）。 */
        static Result demo(boolean ok, double total, String unit, List<Package> packages,
                           long nearestExpiry, String message) {
            return new Result(ok, total, unit, packages, nearestExpiry, message);
        }

        /**
         * 一句话摘要：**只给数字与到期时间，不提资源包**。
         *
         * <p>例如「1,240 credits · 2026-11-01 到期」。
         *
         * <p>为什么不显示「N 个资源包」：那是内部记账单位，用户关心的是
         * 「还剩多少、什么时候过期」。国内版有 36 个资源包，写出来只是噪音
         * （需要看明细时点「明细」即可）。
         */
        public String summary() {
            if (!ok) return message;
            StringBuilder sb = new StringBuilder();
            sb.append(formatNumber(total));
            // 单位可能为空（ZCode 的额度单位不固定，界面用「还剩 X」表述）
            if (unit != null && !unit.isEmpty()) sb.append(' ').append(unit);
            if (nearestExpiry > 0) {
                sb.append(" · ").append(formatDate(nearestExpiry)).append(" 到期");
            }
            return sb.toString();
        }

        /**
         * 明细（展开时看）。
         *
         * <p>只列**每一笔的剩余与到期**，不写「资源包」这种内部说法。
         * 单笔时分项与总数一样，列出来没意义，返回空串让界面只显示摘要。
         */
        public String detail() {
            if (!ok || packages.size() <= 1) return "";
            StringBuilder sb = new StringBuilder();
            for (Package p : packages) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(formatNumber(p.remaining));
                if (!p.unit.isEmpty()) sb.append(' ').append(p.unit);
                if (p.expiresAt > 0) sb.append(" · ").append(formatDate(p.expiresAt)).append(" 到期");
            }
            return sb.toString();
        }
    }

    private Credits() {
    }

    /** 该渠道是否支持查积分（供界面决定是否显示按钮）。 */
    public static boolean supported(String loginType) {
        if (loginType == null) return false;
        return LoginProviders.WB_INTL.equals(loginType)
                || LoginProviders.WB_CN.equals(loginType)
                || LoginProviders.QODER.equals(loginType)
                || LoginProviders.QODER_CN.equals(loginType)
                || LoginProviders.RACCOON.equals(loginType)
                // ZCode 有额度接口（`zcode-plan/billing/current`）。
                // 之前写「ZCode 没有积分查询接口」是**错的** —— 实测：
                // 无 token / 假 token → 401，真 token → 200（结构正常）。
                || LoginProviders.ZCODE.equals(loginType)
                // CodeArts 有积分接口（`/snap-manager/v1/statistics/plugin`，AK/SK 签名）。
                // 之前写「没有查询接口」是错的，见 queryCodeArts 的注释。
                || LoginProviders.CODEARTS.equals(loginType);
    }

    /** 不支持时的原因说明（每个渠道如实写清，不留一句泛泛的「不支持」）。 */
    public static String unsupportedReason(String loginType) {
        return "该渠道没有额度查询接口";
    }

    /**
     * CodeArts 的额度说明。
     *
     * <p>⚠ 之前写过「华为云没有额度查询接口」——**那是错的**。参考实现
     * （dsh-codearts）从官方 CodeArts Agent 客户端里挖出了接口并实测通过：
     *
     * <pre>
     * GET /snap-manager/v1/statistics/plugin
     *   → {metrics:[{name:"usageTotalPackageCredit",
     *                package_credit_amount, package_credit_used,
     *                package_credit_remain, package_credit_expiring_amount}, …],
     *      package:{package_name_cn, spec_code, status, is_credit_package},
     *      start_date, end_date}
     * </pre>
     *
     * <p>华为确实是**积分机制**：`usageTotalPackageCredit` 是总积分，
     * 另有赠送 / 基础套餐 / 按需三个分项。这里取总积分口径展示「剩余 / 总量」。
     *
     * <p>鉴权必须带 AK/SK 签名（只带 `x-auth-token` 会被 APIG 拒）。
     */
    private static Result queryCodeArts(Channel channel) throws java.io.IOException {
        // 临时凭据约 2 小时：过期了先静默续一次，续不上再如实报「请重新登录」
        CodeArtsLogin.refreshIfExpired(channel, TIMEOUT_MS);
        String url = "https://snap-access.cn-north-4.myhuaweicloud.com"
                + "/snap-manager/v1/statistics/plugin";
        String text;
        try {
            text = HuaweiSign.request(
                    channel.accessKeyId, channel.secretAccessKey, channel.securityToken,
                    "GET", url, "", TIMEOUT_MS);
        } catch (java.io.IOException e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("0613")) {
                return Result.fail("华为云鉴权未通过（APIG.0613）：凭据可能无效或已过期，请重新登录");
            }
            if (msg.contains("0301") || msg.contains("401")) {
                return Result.fail("CodeArts 临时凭据已过期，请重新登录");
            }
            return Result.fail("积分查询失败：" + msg);
        }

        JSONObject body;
        try {
            body = new JSONObject(text);
        } catch (Exception e) {
            return Result.fail("积分响应无法解析");
        }
        // 上游两种形态都出现过：直接给数据对象，或包一层 {code:0,data:{…}}
        if (body.has("code")) {
            int code = body.optInt("code", -1);
            if (code != 0) {
                String msg = body.optString("message", "");
                if (msg.isEmpty()) msg = body.optString("msg", "");
                return Result.fail(msg.isEmpty() ? "积分查询失败（业务码 " + code + "）" : msg);
            }
            JSONObject data = body.optJSONObject("data");
            if (data == null) return Result.fail("额度响应缺少 data");
            body = data;
        }

        // 找 usageTotalPackageCredit 这条 metric
        JSONObject total = null;
        org.json.JSONArray metrics = body.optJSONArray("metrics");
        if (metrics != null) {
            for (int i = 0; i < metrics.length(); i += 1) {
                JSONObject m = metrics.optJSONObject(i);
                if (m == null) continue;
                if ("usageTotalPackageCredit".equals(m.optString("name", ""))) {
                    total = m;
                    break;
                }
            }
        }
        if (total == null) {
            // 账号没有积分体系（或上游改了结构）：如实说，不显示一排 0 假装是真的
            return Result.fail("该账号没有积分数据");
        }

        double amount = num(total, "package_credit_amount");
        double used = num(total, "package_credit_used");
        double remain = num(total, "package_credit_remain");
        double expiring = num(total, "package_credit_expiring_amount");

        // 套餐名（界面用它说明「这是什么额度」）
        JSONObject pkg = body.optJSONObject("package");
        String planName = "";
        if (pkg != null) {
            planName = pkg.optString("package_name_cn", "");
            if (planName.isEmpty()) planName = pkg.optString("package_name_en", "");
        }

        List<Package> packages = new ArrayList<>();
        String name = planName.isEmpty() ? "CodeArts 积分" : planName;
        packages.add(new Package(name, Math.max(0, remain), "积分", false, 0));

        // 周期结束日就是积分重置日 —— 用 nearestExpiry 表达，界面会显示「X 到期」
        long resetMs = parseWbTime(body.optString("end_date", ""));

        StringBuilder msg = new StringBuilder();
        if (amount > 0) {
            msg.append("总量 ").append(formatNumber(amount))
                    .append(" · 已用 ").append(formatNumber(used));
            if (expiring > 0) msg.append(" · 即将过期 ").append(formatNumber(expiring));
        }
        return new Result(true, Math.max(0, remain), "积分", packages, resetMs, msg.toString());
    }

    /**
     * 查一个渠道账号的积分。
     *
     * @param channel 已登录的渠道
     * @return 结果（失败时 ok=false 且带原因，不抛异常）
     */
    public static Result query(Channel channel) {
        if (channel == null) return Result.fail("渠道不存在");
        if (!Channel.KIND_LOGIN.equals(channel.kind)) return Result.fail("API Key 渠道没有积分");
        if (!supported(channel.loginType)) return Result.fail(unsupportedReason(channel.loginType));
        if (!channel.hasCredential()) {
            return Result.fail("尚未登录");
        }
        try {
            if (LoginProviders.WB_INTL.equals(channel.loginType)
                    || LoginProviders.WB_CN.equals(channel.loginType)) {
                return queryWorkbuddy(channel);
            }
            if (LoginProviders.QODER.equals(channel.loginType)
                    || LoginProviders.QODER_CN.equals(channel.loginType)) {
                return queryQoder(channel);
            }
            if (LoginProviders.RACCOON.equals(channel.loginType)) {
                return queryRaccoon(channel);
            }
            if (LoginProviders.ZCODE.equals(channel.loginType)) {
                return queryZcode(channel);
            }
            if (LoginProviders.CODEARTS.equals(channel.loginType)) {
                return queryCodeArts(channel);
            }
            return Result.fail(unsupportedReason(channel.loginType));
        } catch (Exception e) {
            return Result.fail("查询失败：" + String.valueOf(e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------
    // WorkBuddy（国内 / 国际）
    // -----------------------------------------------------------------------

    private static Result queryWorkbuddy(Channel channel) throws java.io.IOException {
        LoginProviders.Spec spec = LoginProviders.specOf(channel.loginType);
        if (spec == null) return Result.fail("未知登录类型");

        String host = channel.domain != null && !channel.domain.isEmpty() ? channel.domain : spec.apiDomain;
        String url = "https://" + host + "/v2/billing/meter/get-user-resource";

        long now = System.currentTimeMillis();
        JSONObject body = new JSONObject();
        try {
            body.put("PageNumber", 1);
            body.put("PageSize", 100);
            body.put("ProductCode", "p_tcaca");
            JSONArray status = new JSONArray();
            status.put(0);
            status.put(3);
            body.put("Status", status);
            body.put("PackageEndTimeRangeBegin", wbStamp(now));
            // 与参考实现一致：往后看 36.8 年（3185136000 秒），覆盖所有长期包
            body.put("PackageEndTimeRangeEnd", wbStamp(now + 3185136000L * 1000L));
        } catch (Exception ignored) {
        }

        JSONObject resp = LoginProviders.requestJson("POST", url, spec, channel, body.toString(), TIMEOUT_MS);
        int code = resp.optInt("code", -1);
        if (code != 0) {
            String msg = resp.optString("msg", "");
            return Result.fail(msg.isEmpty() ? "业务码 " + code : msg);
        }
        // 双层信封：data.Response.Data.Accounts
        JSONObject data = resp.optJSONObject("data");
        JSONObject response = data == null ? null : data.optJSONObject("Response");
        JSONObject respData = response == null ? null : response.optJSONObject("Data");
        JSONArray accounts = respData == null ? null : respData.optJSONArray("Accounts");
        if (accounts == null) {
            // 有些账号走扁平结构，兜底再找一次
            accounts = findAccounts(resp);
        }
        if (accounts == null) return Result.fail("积分响应缺少 Accounts");

        double total = 0;
        long nearestExpiry = 0;
        List<Package> packages = new ArrayList<>();
        for (int i = 0; i < accounts.length(); i += 1) {
            JSONObject account = accounts.optJSONObject(i);
            if (account == null) continue;
            boolean monthly = num(account, "CapacityType") == 4;
            double remain;
            if (account.has("CycleCapacityRemain") || account.has("CycleCapacityRemainPrecise")) {
                remain = num(account, "CycleCapacityRemain");
            } else {
                remain = num(account, "CapacityRemain");
            }
            double capped = Math.max(0, remain);

            long expiry = 0;
            String rawExpiry = monthly
                    ? account.optString("CycleEndTime", "")
                    : firstNonEmpty(account.optString("ExpiredTime", ""), account.optString("CycleEndTime", ""));
            long expiryMs = parseWbTime(rawExpiry);
            boolean expired = expiryMs > 0 && expiryMs <= now;

            // 一次性包：已用完或已过期的不计入总额（否则余额虚高）
            if (!monthly && (capped <= 0 || expired)) continue;

            total += capped;
            if (expiryMs > 0 && (nearestExpiry == 0 || expiryMs < nearestExpiry)) nearestExpiry = expiryMs;
            String name = account.optString("PackageName", "");
            if (name.isEmpty()) name = monthly ? "月度积分包" : "积分包";
            packages.add(new Package(name, capped, "credits", monthly, expiryMs));
        }
        return new Result(true, total, "credits", packages, nearestExpiry, "");
    }

    /** 在任意嵌套里找 `Accounts` 数组（兜底）。 */
    private static JSONArray findAccounts(JSONObject root) {
        if (root == null) return null;
        JSONArray direct = root.optJSONArray("Accounts");
        if (direct != null) return direct;
        for (String key : new String[]{"data", "Response", "Data", "result"}) {
            JSONObject child = root.optJSONObject(key);
            JSONArray found = findAccounts(child);
            if (found != null) return found;
        }
        return null;
    }

    /** WorkBuddy 计费接口要的时间格式：`YYYY-MM-DD HH:mm:ss`（本地时区）。 */
    private static String wbStamp(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(ms));
    }

    /** 解析 WorkBuddy 的时间字段（可能是 ISO 串、也可能已是毫秒数）。 */
    private static long parseWbTime(String raw) {
        if (raw == null || raw.isEmpty()) return 0;
        String trimmed = raw.trim();
        if (trimmed.matches("\\d{10,}")) {
            long v = Long.parseLong(trimmed);
            return v < 1_000_000_000_000L ? v * 1000L : v;
        }
        long parsed = LoginProviders.parseDate(trimmed);
        return Math.max(0, parsed);
    }

    // -----------------------------------------------------------------------
    // Qoder / Qoder 中国版
    // -----------------------------------------------------------------------

    private static Result queryQoder(Channel channel) throws java.io.IOException {
        LoginProviders.Spec spec = LoginProviders.specOf(channel.loginType);
        if (spec == null) return Result.fail("未知登录类型");

        // Qoder 的积分走 openApiBase（与推理端点不同）
        String base = LoginProviders.QODER.equals(channel.loginType)
                ? "https://openapi.qoder.sh"
                : "https://openapi.qoder.com.cn";
        String url = base + "/sash/api/v2/me/usage";

        JSONObject resp = LoginProviders.requestJsonWithHeaders("GET", url, spec, channel,
                null, TIMEOUT_MS, new String[][]{{"Cosy-ClientType", "10"}});

        String displayMode = resp.optString("displayMode", "");
        if ("enterprise".equals(displayMode)) return Result.fail("企业账号不显示积分");

        JSONObject usage = resp.optJSONObject("qoderUsage");
        if (usage == null) return Result.fail("积分响应缺少 qoderUsage");

        List<Package> packages = new ArrayList<>();
        Package userQuota = toQoderPackage("套餐额度", usage.optJSONObject("userQuota"));
        if (userQuota != null) packages.add(userQuota);
        Package addOn = toQoderPackage("资源包", usage.optJSONObject("addOnQuota"));
        if (addOn != null) packages.add(addOn);

        if (packages.isEmpty()) return Result.fail("积分响应里没有可用额度字段");
        double total = 0;
        for (Package p : packages) total += p.remaining;
        return new Result(true, total, "credits", packages, 0, "");
    }

    /**
     * 用 Qoder 凭据反查 uid。
     *
     * <p>为什么需要：cosy 签名必须带 uid，而 `dt-` 形态的 token **不含 uid**
     * （它不是 JWT）。老版本登录没存 uid，升级后会报「凭据缺少 uid」，
     * 看起来像要重新登录 —— 其实凭据是好的。
     *
     * <p>积分接口 `sash/api/v2/me/usage` 会回 `qoderUsage.userId`，
     * 只要 token 有效就能拿到。这是**只读**查询，不消耗额度。
     *
     * @return uid；失败时返回空串（调用方据此提示重新登录）
     */
    public static String fetchQoderUid(Channel channel, int timeoutMs) {
        try {
            LoginProviders.Spec spec = LoginProviders.specOf(channel.loginType);
            if (spec == null) return "";
            String base = LoginProviders.QODER.equals(channel.loginType)
                    ? "https://openapi.qoder.sh"
                    : "https://openapi.qoder.com.cn";
            JSONObject resp = LoginProviders.requestJsonWithHeaders("GET",
                    base + "/sash/api/v2/me/usage", spec, channel, null, timeoutMs,
                    new String[][]{{"Cosy-ClientType", "10"}});
            JSONObject usage = resp.optJSONObject("qoderUsage");
            if (usage == null) return "";
            return usage.optString("userId", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static Package toQoderPackage(String name, JSONObject quota) {
        if (quota == null) return null;
        if (!quota.has("total") && !quota.has("used") && !quota.has("remaining")) return null;
        double total = num(quota, "total");
        double used = num(quota, "used");
        double remaining = quota.has("remaining") ? num(quota, "remaining") : Math.max(0, total - used);
        String unit = quota.optString("unit", "");
        if (unit.isEmpty()) unit = "credits";
        return new Package(name, Math.max(0, remaining), unit, true, 0);
    }

    // -----------------------------------------------------------------------
    // Raccoon（商汤）
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // ZCode（智谱）：`zcode-plan` 订阅额度
    // -----------------------------------------------------------------------

    /** ZCode 客户端版本（billing 接口会校验）。 */
    private static final String ZCODE_VER = "3.17.2";
    /** ZCode 的平台标识。 */
    private static final String ZCODE_PLATFORM = "android-arm64";

    /**
     * 查 ZCode 的额度。
     *
     * <pre>
     * GET https://zcode.z.ai/api/v1/zcode-plan/billing/current
     *   头：Authorization: Bearer &lt;zcode_jwt&gt;
     *       X-ZCode-App-Version: 3.14.3
     *       X-Platform: android-arm64
     *   → {code:0, data:{server_time, plans:[…], balances:[
     *        {user_plan_id, plan_id, show_name, capabilities:[…], …}]}}
     * </pre>
     *
     * <h3>三种「没有数据」要区分开</h3>
     *
     * <ol>
     *   <li><b>401</b>：凭据过期，得重新登录（提示去重新登录）；</li>
     *   <li><b>200 但没有 data / balances 为空</b>：凭据有效，但这个账号
     *       **没有 zcode-plan 订阅**（ZCode 的额度是订阅制的）。
     *       这是正常状态，不能报「查询失败」——否则用户会以为 App 坏了；</li>
     *   <li>网络/解析错误：才算真的失败。</li>
     * </ol>
     *
     * <p>实测依据（2026-11-04）：无 token / 假 token 均 401、真 token 200，
     * 说明端点在且要鉴权；而真 token 返回 `{"code":0}` 无 data，
     * 是因为那个账号没有订阅。
     */
    /**
     * 老版本登录的渠道里 `zcodeJwt` 是空的，尝试从 `accessToken` 里认出来。
     *
     * <p>背景：早期登录实现把 `data.token`（zcode-plan JWT）只写进了
     * `zcodeJwt`，而 `accessToken` 存的是 bigmodel 令牌。但如果那时的代码
     * 没写 `zcodeJwt`，两个字段都会是 bigmodel —— 那种情况只能**重新登录**。
     *
     * <p>识别的依据是 ZCode 那个 JWT 的载荷特征（实测）：
     * ```json
     * {"user_id":"...","token_version":0,"sub":"...","iat":...}
     * ```
     * 也就是「含 user_id 且含 token_version」——bigmodel 的令牌没有这两个字段。
     *
     * @return 认得出才返回，否则返回空串（让上层提示重新登录）
     */
    static String pickZcodeJwtFromAccess(String accessToken) {
        if (accessToken == null || accessToken.isEmpty()) return "";
        String payload = LoginProviders.jwtPayloadJson(accessToken);
        if (payload == null || payload.isEmpty()) return "";
        if (payload.contains("\"user_id\"") && payload.contains("\"token_version\"")) {
            return accessToken;
        }
        return "";
    }

    private static Result queryZcode(Channel channel) throws java.io.IOException {
        LoginProviders.Spec spec = LoginProviders.specOf(channel.loginType);
        if (spec == null) return Result.fail("未知登录类型");

        String url = "https://zcode.z.ai/api/v1/zcode-plan/billing/current";
        JSONObject resp;
        // ⚠ ZCode 有两个令牌，别用错：
        //   accessToken  = bigmodel 业务令牌（调聊天/模型用）→ 调 billing 会 401
        //   zcodeJwt     = zcode-plan 令牌（调 billing / 额度用）→ 200
        //
        // 实测确认（2026-11-04）：
        //   zcode_jwt   → 200 {"code":0}
        //   bigmodel    → 401
        //
        // 之前这里直接用 channel（内部取 accessToken），于是拿 bigmodel 令牌
        // 去调 zcode-plan 接口，结果稳定 401 —— 用户「重新登录也没用」，
        // 因为问题不在登录，在取错了字段。
        String token = channel.zcodeJwt != null && !channel.zcodeJwt.isEmpty()
                ? channel.zcodeJwt
                : pickZcodeJwtFromAccess(channel.accessToken);
        if (token == null || token.isEmpty()) {
            return Result.fail("ZCode 缺少 zcode-plan 凭据，请重新登录");
        }
        // 借一个只带目标令牌的副本，避免改动原渠道
        Channel auth = new Channel();
        auth.accessToken = token;

        try {
            resp = LoginProviders.requestJsonWithHeaders("GET", url, spec, auth, null, TIMEOUT_MS,
                    new String[][]{
                            {"X-ZCode-App-Version", ZCODE_VER},
                            {"X-Platform", ZCODE_PLATFORM},
                    });
        } catch (java.io.IOException e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("401")) return Result.fail("ZCode 凭据已过期，请重新登录");
            return Result.fail("额度查询失败：" + msg);
        }

        int code = resp.optInt("code", -1);
        if (code != 0) {
            String msg = resp.optString("msg", "");
            return Result.fail(msg.isEmpty() ? "额度查询失败（业务码 " + code + "）" : msg);
        }

        JSONObject data = resp.optJSONObject("data");
        if (data == null) {
            // 200 但完全没有 data：这个账号当前没有 Start Plan 额度。
            //
            // ⚠ 不要写「没有订阅」之类的话：用户明确要求**直接显示额度**，
            // 不要解释性文案。额度为 0 就显示 0，界面上不出现「订阅」字样。
            return new Result(true, 0, "", new ArrayList<Package>(), 0, "");
        }

        // 额度可能出现在 balances（按桶记）或 plans[].entitlements 里，
        // 两种都看一遍，避免「有订阅但显示没有额度」。
        org.json.JSONArray balances = data.optJSONArray("balances");
        org.json.JSONArray plans = data.optJSONArray("plans");

        double total = 0;
        long nearestExpiry = 0;
        List<Package> packages = new ArrayList<>();

        // ① balances[]：最直接（字段名来自 ZCode 客户端 buildZaiStartPlanQuota）
        if (balances != null) {
            for (int i = 0; i < balances.length(); i += 1) {
                JSONObject b = balances.optJSONObject(i);
                if (b == null) continue;
                double remain = num(b, "remaining_units");
                if (remain <= 0) {
                    // 上游有时只给 total/used，remaining 要自己算
                    double tot = num(b, "total_units");
                    double used = num(b, "used_units");
                    if (tot > 0) remain = Math.max(0, tot - used);
                }
                if (remain <= 0) continue;      // 已用完的桶不计入「还剩」
                total += remain;

                long expiryMs = parseWbTime(b.optString("expires_at", ""));
                if (expiryMs > 0 && (nearestExpiry == 0 || expiryMs < nearestExpiry)) {
                    nearestExpiry = expiryMs;
                }
                String name = b.optString("show_name", "");
                if (name.isEmpty()) name = b.optString("meter", "");
                if (name.isEmpty()) name = b.optString("plan_id", "");
                if (name.isEmpty()) name = "zcode-plan";
                String unit = b.optString("unit_type", "");
                if (unit.isEmpty()) unit = "units";
                packages.add(new Package(name, remain, unit, false, expiryMs));
            }
        }

        // ② balances 为空时，看 plans[].entitlements（有些套餐只在 plans 里给总额度）
        if (packages.isEmpty() && plans != null) {
            for (int i = 0; i < plans.length(); i += 1) {
                JSONObject plan = plans.optJSONObject(i);
                if (plan == null) continue;
                long planEnd = parseWbTime(plan.optString("ends_at", ""));
                org.json.JSONArray ents = plan.optJSONArray("entitlements");
                if (ents == null) continue;
                for (int k = 0; k < ents.length(); k += 1) {
                    JSONObject ent = ents.optJSONObject(k);
                    if (ent == null) continue;
                    double remain = num(ent, "remaining_units");
                    if (remain <= 0) remain = num(ent, "grant_units");
                    if (remain <= 0) continue;
                    total += remain;
                    if (planEnd > 0 && (nearestExpiry == 0 || planEnd < nearestExpiry)) {
                        nearestExpiry = planEnd;
                    }
                    String name = ent.optString("show_name", "");
                    if (name.isEmpty()) name = plan.optString("name", "");
                    if (name.isEmpty()) name = "zcode-plan";
                    String unit = ent.optString("unit_type", "");
                    if (unit.isEmpty()) unit = "units";
                    packages.add(new Package(name, remain, unit, false, planEnd));
                }
            }
        }

        if (packages.isEmpty()) {
            // 有 data 但两处都没有额度：额度为 0。
            // 不写解释文案（用户要求直接显示额度，不要「没订阅」这类说明）。
            return new Result(true, 0, "", new ArrayList<Package>(), 0, "");
        }

        // 单位：ZCode 的额度单位通常是 token 数，统一用「额度」表述，单位留空避免歧义
        return new Result(true, total, "", packages, nearestExpiry, "");
    }

    private static Result queryRaccoon(Channel channel) throws java.io.IOException {
        LoginProviders.Spec spec = LoginProviders.specOf(channel.loginType);
        if (spec == null) return Result.fail("未知登录类型");
        String url = "https://xiaohuanxiong.com/api/web/points/v1/balance";

        JSONObject resp = LoginProviders.requestJson("GET", url, spec, channel, null, TIMEOUT_MS);
        JSONObject data = resp.optJSONObject("data");
        if (data == null) data = resp;
        if (!data.has("available_points")) return Result.fail("积分响应缺少 available_points");

        double available = num(data, "available_points");
        List<Package> packages = new ArrayList<>();
        String[][] parts = {
                {"reward_points", "奖励积分"},
                {"daily_points", "每日积分"},
                {"monthly_points", "会员积分"},
                {"topup_points", "充值积分"},
        };
        for (String[] part : parts) {
            double value = num(data, part[0]);
            if (value <= 0) continue;
            packages.add(new Package(part[1], value, "points", "monthly_points".equals(part[0]), 0));
        }
        if (packages.isEmpty()) {
            packages.add(new Package("可用积分", available, "points", false, 0));
        }
        return new Result(true, available, "points", packages, 0, "");
    }

    // -----------------------------------------------------------------------
    // 小工具
    // -----------------------------------------------------------------------

    /** 读数字字段，缺失或非法返回 0。 */
    private static double num(JSONObject o, String key) {
        if (o == null || !o.has(key)) return 0;
        Object raw = o.opt(key);
        if (raw instanceof Number) return ((Number) raw).doubleValue();
        if (raw instanceof String) {
            try {
                return Double.parseDouble(((String) raw).trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        return b == null ? "" : b;
    }

    /** 千分位格式化，整数不带小数点。 */
    public static String formatNumber(double value) {
        long rounded = Math.round(value);
        if (Math.abs(value - rounded) < 0.01) {
            return String.format(Locale.getDefault(), "%,d", rounded);
        }
        return String.format(Locale.getDefault(), "%,.1f", value);
    }

    public static String formatDate(long ms) {
        return new SimpleDateFormat("MM-dd", Locale.getDefault()).format(new Date(ms));
    }
}
