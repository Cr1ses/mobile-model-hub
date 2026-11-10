package com.wbgateway;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 每日签到（领取每日免费额度）。
 *
 * <p>每种渠道的「签到」其实是两件事之一：**领取每日奖励**（WorkBuddy）
 * 或**领取活动权益**（Qoder）。这里用同一套接口把两者收敛起来，
 * 界面上都叫「签到」。
 *
 * <h3>协议（均来自桌面版插件里已实测的实现，不是猜的）</h3>
 *
 * <pre>
 * WorkBuddy（国内 / 国际，同协议、只有域名不同）
 *   POST https://{domain}/v2/billing/meter/checkin-activity-status   body {}
 *     → {code:0, data:{active, today_checked_in, streak_days, daily_credit,
 *                      today_credit, is_streak_day, total_credits, checkin_dates[],
 *                      activity_name, theme_name, end_time}}
 *   POST https://{domain}/v2/billing/meter/daily-checkin             body {}
 *     → code 0        : {data:{credit, streak_days, is_streak_day, message?}}
 *     → 10001 / 1001  : **今天已签过**（不是失败！）
 *     → 1002 / 1003   : 无资格 / 活动已结束
 *
 * Qoder / Qoder 中国版
 *   GET  {openApiBase}/sash/api/v1/me/campaigns
 *        头：Cosy-ClientType: 10 （桌面端身份，不带就拿不到活动下发）
 *     → {showCampaign, claimable, campaigns:[{campaignId, actionType, claimStatus, benefit:{amount}}]}
 *   POST {openApiBase}/sash/api/v1/me/campaigns/{campaignId}/claim    body ""
 *     → {status:"CLAIMED", replayed:bool, benefit:{amount}}
 *     可领的条件：actionType == "CLAIM_BENEFIT" 且 claimStatus == "CLAIMABLE"
 *
 * 华为 CodeArts（AK/SK 签名，不走 Bearer）
 *   GET  /snap-manager/v1/statistics/plugin
 *     → {package:{is_credit_package, is_token_package, package_name_cn}, metrics:[…]}
 *   GET  /v1/ops/delivery?channel=IDE
 *     → {items:[{campaignId, type, title, claimable, status, benefitAmount}]}
 *     每日签到就是 type == "USER_LOGIN" 那一条；campaignId 实测是**数字** 1。
 *   POST /v1/ops/claim    body {campaignId, channel:"IDE"} → {id, benefitAmount}
 *   POST /v1/ops/confirm  body {campaignId}
 *     已领状态：CLAIMED / CONFIRMED / CONSUMED。
 * </pre>
 *
 * <h3>两条必须遵守的判据</h3>
 *
 * <ol>
 *   <li><b>「今天已签过」不是失败</b>。WorkBuddy 用 10001/1001 表示重复领取，
 *       Qoder 用 <code>replayed=true</code> 表示重放。把它们当错误报给用户，
 *       会出现「点了签到提示失败、但其实额度已经到账」的误导；</li>
 *   <li><b>无资格要如实说</b>。1002/1003 是「账号没有资格 / 活动结束」，
 *       和网络失败必须区分开，否则用户会一直重试一个根本不会成功的操作。</li>
 * </ol>
 *
 * <p>Raccoon 没有每日签到：它只有一次性的桌面端登录奖励
 * （`/desktop/v1/login/points/grant`），不是每日的。这里如实返回「不支持」，
 * 不给假成功。
 *
 * <p>⚠ 曾经写过「ZCode 与 CodeArts 没有签到」—— 两条都是**错的**。
 * ZCode 有可领的套餐额度（preview → claim），CodeArts 有 `USER_LOGIN`
 * 活动（delivery → claim → confirm），都已经接上。
 */
public final class Checkin {

    /** 单次请求超时。签到是交互动作，等太久不如让用户重试。 */
    private static final int TIMEOUT_MS = 20_000;

    /**
     * CodeArts：过期了就续一次（无副作用；失败时保持原样）。
     *
     * <p>签到 / 积分都可能在凭据过期后才被点开。不先续期的话，它们会直接
     * 拿到 401 并报「凭据过期」—— 而用户什么都没做错。
     */
    private static void freshenCodeArts(Channel channel) {
        CodeArtsLogin.refreshIfExpired(channel, TIMEOUT_MS);
    }

    /** WorkBuddy：今天已领过（不同上游版本两种码都出现过）。 */
    private static final int WB_ALREADY_CLAIMED = 10001;
    private static final int WB_ALREADY_CLAIMED_ALT = 1001;
    /** WorkBuddy：账号无资格。 */
    private static final int WB_NO_QUALIFICATION = 1002;
    /** WorkBuddy：活动已结束。 */
    private static final int WB_ACTIVITY_ENDED = 1003;

    private Checkin() {
    }

    // -----------------------------------------------------------------------
    // 结果类型
    // -----------------------------------------------------------------------

    /** 签到状态（查询结果）。 */
    public static final class Status {
        public boolean ok;
        /** 活动是否在进行。 */
        public boolean active;
        /** 今天是否已签。 */
        public boolean todayCheckedIn;
        /** 连续签到天数。 */
        public int streakDays;
        /** 每日可领额度。 */
        public double dailyCredit;
        /** 今天已领额度。 */
        public double todayCredit;
        /** 成功时的说明（可能为空）。 */
        public String message = "";

        static Status fail(String message) {
            Status s = new Status();
            s.ok = false;
            s.message = message;
            return s;
        }

        /** 一行摘要（界面用）。 */
        public String summary() {
            if (!ok) return message;
            if (!active) return message.isEmpty() ? "当前没有签到活动" : message;
            if (todayCheckedIn) {
                StringBuilder sb = new StringBuilder("今日已签到");
                if (streakDays > 0) sb.append(" · 连续 ").append(streakDays).append(" 天");
                if (todayCredit > 0) sb.append(" · 已领 ").append(Credits.formatNumber(todayCredit));
                return sb.toString();
            }
            StringBuilder sb = new StringBuilder("今日未签到");
            if (dailyCredit > 0) sb.append(" · 可领 ").append(Credits.formatNumber(dailyCredit));
            if (streakDays > 0) sb.append(" · 连续 ").append(streakDays).append(" 天");
            return sb.toString();
        }
    }

    /** 领取结果。 */
    public static final class Claim {
        /** claimed / already-claimed / inactive / failed */
        public String kind = "failed";
        /** 本次领到的额度（0 表示未知或没有）。 */
        public double credit;
        /** 连续天数（上游给了才有）。 */
        public int streakDays;
        /** 给用户看的一句话。 */
        public String message = "";

        public boolean isSuccess() {
            return "claimed".equals(kind) || "already-claimed".equals(kind);
        }

        public boolean isClaimed() {
            return "claimed".equals(kind);
        }

        static Claim of(String kind, String message) {
            Claim c = new Claim();
            c.kind = kind;
            c.message = message;
            return c;
        }
    }

    // -----------------------------------------------------------------------
    // 支持矩阵
    // -----------------------------------------------------------------------

    /** 该渠道是否有每日签到。 */
    public static boolean supported(String loginType) {
        if (loginType == null) return false;
        return LoginProviders.WB_INTL.equals(loginType)
                || LoginProviders.WB_CN.equals(loginType)
                || LoginProviders.QODER.equals(loginType)
                || LoginProviders.QODER_CN.equals(loginType)
                // ZCode 不是「每日签到」，但同属「有可领额度就自动领」，
                // 对用户来说是一件事，所以并入同一套按钮与自动流程。
                || LoginProviders.ZCODE.equals(loginType)
                // CodeArts 的「每日登录奖励」是一个 campaign（`USER_LOGIN`），
                // 走 /v1/ops/delivery → /v1/ops/claim → /v1/ops/confirm。
                // 之前以为它没有签到，是漏看了这条链路（见类注释里的协议）。
                || LoginProviders.CODEARTS.equals(loginType);
    }

    /** 不支持时的原因（如实说明，不用「暂不支持」敷衍）。 */
    public static String unsupportedReason(String loginType) {
        if (LoginProviders.RACCOON.equals(loginType)) {
            return "Raccoon 没有每日签到（只有一次性登录奖励）";
        }
        return "该渠道没有每日签到";
    }

    // -----------------------------------------------------------------------
    // 对外入口
    // -----------------------------------------------------------------------

    /**
     * 查签到状态（只读，不领）。
     *
     * @param channel 已登录渠道
     * @return 状态；失败时 `ok=false` 且带原因，不抛异常
     */
    public static Status status(Channel channel) {
        if (channel == null) return Status.fail("渠道不存在");
        if (!Channel.KIND_LOGIN.equals(channel.kind)) return Status.fail("API Key 渠道没有签到");
        if (!supported(channel.loginType)) return Status.fail(unsupportedReason(channel.loginType));
        if (!channel.hasCredential()) {
            return Status.fail("尚未登录");
        }
        try {
            if (LoginProviders.WB_INTL.equals(channel.loginType)
                    || LoginProviders.WB_CN.equals(channel.loginType)) {
                return statusWorkbuddy(channel);
            }
            if (LoginProviders.ZCODE.equals(channel.loginType)) {
                return statusZcode(channel);
            }
            if (LoginProviders.CODEARTS.equals(channel.loginType)) {
                return statusCodeArts(channel);
            }
            return statusQoder(channel);
        } catch (Exception e) {
            return Status.fail("查询失败：" + String.valueOf(e.getMessage()));
        }
    }

    /**
     * 领取今日签到奖励（会先查状态：已签过就直接返回，不重复打扰上游）。
     *
     * @param channel 已登录渠道
     * @return 领取结果
     */
    public static Claim run(Channel channel) {
        if (channel == null) return Claim.of("failed", "渠道不存在");
        if (!Channel.KIND_LOGIN.equals(channel.kind)) return Claim.of("failed", "API Key 渠道没有签到");
        if (!supported(channel.loginType)) {
            return Claim.of("failed", unsupportedReason(channel.loginType));
        }
        if (!channel.hasCredential()) {
            // 注意：不能只看 accessToken —— CodeArts 没有它，凭据是 AK/SK/security_token
            return Claim.of("failed", "尚未登录");
        }
        try {
            if (LoginProviders.WB_INTL.equals(channel.loginType)
                    || LoginProviders.WB_CN.equals(channel.loginType)) {
                return claimWorkbuddy(channel);
            }
            if (LoginProviders.ZCODE.equals(channel.loginType)) {
                return claimZcode(channel);
            }
            if (LoginProviders.CODEARTS.equals(channel.loginType)) {
                return claimCodeArts(channel);
            }
            return claimQoder(channel);
        } catch (Exception e) {
            return Claim.of("failed", "签到失败：" + String.valueOf(e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------
    // WorkBuddy
    // -----------------------------------------------------------------------

    private static String wbHost(Channel channel) {
        LoginProviders.Spec spec = LoginProviders.specOf(channel.loginType);
        String host = channel.domain != null && !channel.domain.isEmpty()
                ? channel.domain
                : (spec == null ? "copilot.tencent.com" : spec.apiDomain);
        return host;
    }

    /**
     * WorkBuddy 基址（生产恒为 `https://{登录域名}`）。
     *
     * <p>`workbuddyBaseUrlOverride` 只给验证用：桌面测试要把请求指到本地假上游，
     * 而假上游是 http。留这个接缝，就能离线跑**完整**的查状态 → 领取链路，
     * 而不是只测纯函数。
     */
    static String workbuddyBaseUrlOverride = null;

    private static String wbBaseUrl(Channel channel) {
        if (workbuddyBaseUrlOverride != null) return workbuddyBaseUrlOverride;
        return "https://" + wbHost(channel);
    }

    private static Status statusWorkbuddy(Channel channel) throws java.io.IOException {
        LoginProviders.Spec spec = LoginProviders.specOf(channel.loginType);
        if (spec == null) return Status.fail("未知登录类型");

        String url = wbBaseUrl(channel) + "/v2/billing/meter/checkin-activity-status";
        JSONObject resp = LoginProviders.requestJson("POST", url, spec, channel, "{}", TIMEOUT_MS);

        int code = resp.optInt("code", -1);
        if (code != 0) {
            String msg = resp.optString("msg", "");
            // 活动接口不可用不是错误：如实说「没有活动」
            if (code == WB_NO_QUALIFICATION || code == WB_ACTIVITY_ENDED) {
                Status s = new Status();
                s.ok = true;
                s.active = false;
                s.message = msg.isEmpty() ? "当前没有签到活动" : msg;
                return s;
            }
            return Status.fail(msg.isEmpty() ? "业务码 " + code : msg);
        }

        JSONObject data = resp.optJSONObject("data");
        if (data == null) return Status.fail("签到状态响应缺少 data");

        Status s = new Status();
        s.ok = true;
        s.active = data.optBoolean("active", false);
        s.todayCheckedIn = data.optBoolean("today_checked_in", false);
        s.streakDays = data.optInt("streak_days", 0);
        s.dailyCredit = readNumber(data, "daily_credit");
        s.todayCredit = readNumber(data, "today_credit");
        if (!s.active) s.message = "当前没有签到活动";
        return s;
    }

    private static Claim claimWorkbuddy(Channel channel) throws java.io.IOException {
        LoginProviders.Spec spec = LoginProviders.specOf(channel.loginType);
        if (spec == null) return Claim.of("failed", "未知登录类型");

        // 先查状态：已签过就直接返回，省一次写请求（也避免把「重复领取」当错误）
        Status before = statusWorkbuddy(channel);
        if (before.ok && before.todayCheckedIn) {
            Claim c = Claim.of("already-claimed", "今天已经签过了");
            c.credit = before.todayCredit;
            c.streakDays = before.streakDays;
            return c;
        }
        if (before.ok && !before.active) {
            return Claim.of("inactive", before.message.isEmpty() ? "当前没有签到活动" : before.message);
        }

        String url = wbBaseUrl(channel) + "/v2/billing/meter/daily-checkin";
        JSONObject resp = LoginProviders.requestJson("POST", url, spec, channel, "{}", TIMEOUT_MS);

        int code = resp.optInt("code", -1);
        String msg = resp.optString("msg", "");

        // 非 0 业务码的归一律在这里（纯函数，可离线验证）
        if (code != 0) {
            Claim mapped = classifyWorkbuddyCode(code, msg);
            if ("already-claimed".equals(mapped.kind) && before.ok) {
                mapped.credit = before.todayCredit;
                mapped.streakDays = before.streakDays;
            }
            return mapped;
        }

        JSONObject data = resp.optJSONObject("data");
        if (data == null) return Claim.of("failed", "领取响应缺少 data");

        Claim c = Claim.of("claimed", "签到成功");
        c.credit = readNumber(data, "credit");
        c.streakDays = data.optInt("streak_days", 0);
        boolean streakDay = data.optBoolean("is_streak_day", false);
        String delayed = data.optString("message", "");
        StringBuilder sb = new StringBuilder("签到成功");
        if (c.credit > 0) sb.append(" · +").append(Credits.formatNumber(c.credit));
        if (c.streakDays > 0) sb.append(" · 连续 ").append(c.streakDays).append(" 天");
        if (streakDay) sb.append(" · 连签奖励");
        if (!delayed.isEmpty()) sb.append("（").append(delayed).append("）");
        c.message = sb.toString();
        return c;
    }

    /**
     * WorkBuddy 非 0 业务码 → 统一的领取结果（**纯函数**，便于离线验证）。
     *
     * <p>这里的映射是整个签到功能最容易搞错的地方：
     * <ul>
     *   <li>`10001` / `1001` = **今天已签过**，是正常状态，不是失败。
     *       当成失败会提示「签到失败」，但额度其实早就到账了；</li>
     *   <li>`1002` = 账号无资格，`1003` = 活动已结束。这两个要单独归类，
     *       否则用户会对着一个永远不会成功的操作反复重试。</li>
     * </ul>
     *
     * @param code 上游业务码（非 0）
     * @param msg  上游提示（可能为空）
     */
    static Claim classifyWorkbuddyCode(int code, String msg) {
        if (code == WB_ALREADY_CLAIMED || code == WB_ALREADY_CLAIMED_ALT) {
            return Claim.of("already-claimed", msg.isEmpty() ? "今天已经签过了" : msg);
        }
        if (code == WB_NO_QUALIFICATION || code == WB_ACTIVITY_ENDED) {
            return Claim.of("inactive", msg.isEmpty() ? "当前账号无领取资格" : msg);
        }
        return Claim.of("failed", msg.isEmpty() ? "领取失败（业务码 " + code + "）" : msg);
    }

    /**
     * 这条活动是不是「可领的签到奖励」。
     *
     * <p>两个条件都要满足：`actionType == CLAIM_BENEFIT`（是领取权益，不是别的活动）
     * 且 `claimStatus == CLAIMABLE`（现在可领）。少判一个就会去 POST 一个领不了的活动，
     * 得到一个看不懂的错误。
     */
    static boolean isClaimableCampaign(JSONObject campaign) {
        if (campaign == null) return false;
        if (!"CLAIM_BENEFIT".equals(campaign.optString("actionType", ""))) return false;
        return "CLAIMABLE".equals(campaign.optString("claimStatus", ""));
    }

    /** 这条活动是不是「已经领过」。 */
    static boolean isClaimedCampaign(JSONObject campaign) {
        if (campaign == null) return false;
        if (!"CLAIM_BENEFIT".equals(campaign.optString("actionType", ""))) return false;
        return "CLAIMED".equals(campaign.optString("claimStatus", ""));
    }

    // -----------------------------------------------------------------------
    // Qoder / Qoder 中国版
    // -----------------------------------------------------------------------

    private static String qoderOpenApi(Channel channel) {
        return LoginProviders.QODER.equals(channel.loginType)
                ? "https://openapi.qoder.sh"
                : "https://openapi.qoder.com.cn";
    }

    /** Qoder 活动接口要桌面端身份头，否则活动不下发。 */
    private static final String[][] QODER_HEADERS = {{"Cosy-ClientType", "10"}};

    private static Status statusQoder(Channel channel) throws java.io.IOException {
        LoginProviders.Spec spec = LoginProviders.specOf(channel.loginType);
        if (spec == null) return Status.fail("未知登录类型");

        String url = qoderOpenApi(channel) + "/sash/api/v1/me/campaigns";
        JSONObject resp;
        try {
            resp = LoginProviders.requestJsonWithHeaders("GET", url, spec, channel, null, TIMEOUT_MS, QODER_HEADERS);
        } catch (java.io.IOException e) {
            return Status.fail("活动查询失败：" + e.getMessage());
        }

        JSONArray campaigns = resp.optJSONArray("campaigns");
        boolean showCampaign = resp.optBoolean("showCampaign", false);
        int claimable = 0;
        int claimed = 0;
        double amount = 0;
        if (campaigns != null) {
            for (int i = 0; i < campaigns.length(); i += 1) {
                JSONObject item = campaigns.optJSONObject(i);
                if (isClaimableCampaign(item)) {
                    claimable += 1;
                    amount += benefitAmount(item);
                } else if (isClaimedCampaign(item)) {
                    claimed += 1;
                }
            }
        }

        Status s = new Status();
        s.ok = true;
        s.active = showCampaign || claimable > 0 || claimed > 0;
        // Qoder 的「今天签没签」只能从活动状态推：没有可领的、但有已领的 = 今天签过
        s.todayCheckedIn = claimable == 0 && claimed > 0;
        // 可领总额（Qoder 不提供「每日固定额度」，只能给当前可领的合计）
        s.dailyCredit = amount;
        if (!s.active) s.message = "当前没有可签到的活动";
        return s;
    }

    private static Claim claimQoder(Channel channel) throws java.io.IOException {
        LoginProviders.Spec spec = LoginProviders.specOf(channel.loginType);
        if (spec == null) return Claim.of("failed", "未知登录类型");

        String listUrl = qoderOpenApi(channel) + "/sash/api/v1/me/campaigns";
        JSONObject resp = LoginProviders.requestJsonWithHeaders("GET", listUrl, spec, channel,
                null, TIMEOUT_MS, QODER_HEADERS);

        JSONArray campaigns = resp.optJSONArray("campaigns");
        if (campaigns == null) return Claim.of("failed", "活动列表响应缺少 campaigns");

        java.util.List<String> claimableIds = new java.util.ArrayList<>();
        java.util.List<Double> claimableAmounts = new java.util.ArrayList<>();
        int claimedCount = 0;
        for (int i = 0; i < campaigns.length(); i += 1) {
            JSONObject item = campaigns.optJSONObject(i);
            if (isClaimableCampaign(item)) {
                String id = item.optString("campaignId", "");
                if (!id.isEmpty()) {
                    claimableIds.add(id);
                    claimableAmounts.add(benefitAmount(item));
                }
            } else if (isClaimedCampaign(item)) {
                claimedCount += 1;
            }
        }

        if (claimableIds.isEmpty()) {
            if (claimedCount > 0) return Claim.of("already-claimed", "今天已经签过了");
            return Claim.of("inactive", "当前没有可领取的签到活动");
        }

        double total = 0;
        int claimedNow = 0;
        boolean anyReplayed = false;
        String lastFail = "";
        for (int i = 0; i < claimableIds.size(); i += 1) {
            String id = claimableIds.get(i);
            String url = qoderOpenApi(channel) + "/sash/api/v1/me/campaigns/"
                    + urlEncodePath(id) + "/claim";
            JSONObject body;
            try {
                body = LoginProviders.requestJsonWithHeaders("POST", url, spec, channel,
                        "", TIMEOUT_MS, new String[][]{{"Cosy-ClientType", "10"}, {"Content-Type", "application/json"}});
            } catch (java.io.IOException e) {
                lastFail = e.getMessage();
                continue;
            }
            if (body.optBoolean("replayed", false)) {
                anyReplayed = true;
                continue;
            }
            String status = body.optString("status", "");
            if (!status.isEmpty() && !"CLAIMED".equals(status)) {
                lastFail = "领取未成功（status=" + status + "）";
                continue;
            }
            double got = benefitAmount(body);
            if (got <= 0) got = claimableAmounts.get(i);
            total += got;
            claimedNow += 1;
        }

        if (claimedNow > 0) {
            Claim c = Claim.of("claimed", total > 0
                    ? "签到成功 · +" + Credits.formatNumber(total)
                    : (claimedNow > 1 ? "已领取 " + claimedNow + " 个奖励" : "签到成功"));
            c.credit = total;
            return c;
        }
        if (anyReplayed) return Claim.of("already-claimed", "今天已经签过了");
        return Claim.of("failed", lastFail.isEmpty() ? "领取失败" : lastFail);
    }

    // -----------------------------------------------------------------------
    // ZCode（智谱）：有可领的「手动领取」额度就领
    // -----------------------------------------------------------------------

    /**
     * ZCode 的「签到」其实是**手动领取套餐额度**，两步走：
     *
     * <pre>
     * GET  /api/v1/zcode-plan/billing/preview?app_version=…&amp;platform=…
     *   → {code:0, data:{server_time, plans:[{plan_id, name, …, entitlements:[…]}]}}
     * POST /api/v1/zcode-plan/billing/claim
     *   body: {plan_id}
     *   头：Authorization / Content-Type / X-ZCode-App-Version / X-Platform
     *   → {code:0, data:{plan:{user_plan_id, plan_id, status, starts_at, ends_at}}}
     * </pre>
     *
     * <p>⚠ 与 WorkBuddy/Qoder 的差别：它**没有「今天签没签」的状态位**，
     * 只有「当前能不能领」的套餐列表。所以这里的判定是
     * 「preview 里有 plan 就视为可领」，领完再 preview 会自然变空 ——
     * 这样自动流程天然幂等，不会重复领。
     *
     * <p>`platform` 用 `android-arm64`：客户端原值是
     * `${process.platform}-${process.arch}`，手机上报成 android 才符合事实。
     */
    private static String zcodeBase(Channel channel) {
        LoginProviders.Spec spec = LoginProviders.specOf(channel.loginType);
        return spec == null ? "https://zcode.z.ai" : spec.endpoint;
    }

    /** ZCode 客户端的版本号（上游会校验）。 */
    public static final String ZCODE_APP_VERSION = "3.17.2";

    /** ZCode 的客户端平台标识。 */
    public static final String ZCODE_PLATFORM = "android-arm64";

    /** ZCode 请求头（billing 接口都要这三样）。 */
    private static String[][] zcodeHeaders() {
        return new String[][]{
                {"X-ZCode-App-Version", ZCODE_APP_VERSION},
                {"X-Platform", ZCODE_PLATFORM},
        };
    }

    /**
     * 取 ZCode 的**正确令牌**用于 zcode-plan 接口。
     *
     * <p>⚠ ZCode 有两个令牌，用错就稳定 401（实测确认）：
     * <pre>
     * accessToken = bigmodel 业务令牌 → 调 billing 返回 401
     * zcodeJwt    = zcode-plan 令牌   → 调 billing 返回 200   ← 要的是这个
     * </pre>
     * 之前直接用 `channel` 发请求（内部取 accessToken），于是签到和额度
     * 全部 401 —— 表现就是「重新登录也没用」，因为问题不在登录，在取错了字段。
     *
     * <p>老版本登录的渠道 `zcodeJwt` 可能为空，用
     * {@link Credits#pickZcodeJwtFromAccess} 从 accessToken 里再认一次。
     */
    static Channel zcodeAuth(Channel channel) {
        Channel auth = new Channel();
        String token = "";
        if (channel != null) {
            if (channel.zcodeJwt != null && !channel.zcodeJwt.isEmpty()) {
                token = channel.zcodeJwt;
            } else {
                token = Credits.pickZcodeJwtFromAccess(channel.accessToken);
            }
        }
        auth.accessToken = token == null ? "" : token;
        return auth;
    }

    /**
     * 拉一次可领套餐列表。
     *
     * @return 可领套餐 id 列表（空表示当前没有可领的）
     */
    static java.util.List<String> zcodeClaimablePlanIds(Channel channel) throws java.io.IOException {
        LoginProviders.Spec spec = LoginProviders.specOf(channel.loginType);
        if (spec == null) return new java.util.ArrayList<>();
        String url = zcodeBase(channel) + "/api/v1/zcode-plan/billing/preview"
                + "?app_version=" + urlEncodePath(ZCODE_APP_VERSION)
                + "&platform=" + urlEncodePath(ZCODE_PLATFORM);

        JSONObject resp = LoginProviders.requestJsonWithHeaders("GET", url, spec, zcodeAuth(channel),
                null, TIMEOUT_MS, zcodeHeaders());
        java.util.List<String> out = new java.util.ArrayList<>();
        int code = resp.optInt("code", 0);
        if (resp.has("code") && code != 0) return out;
        JSONObject data = resp.optJSONObject("data");
        JSONArray plans = data == null ? null : data.optJSONArray("plans");
        if (plans == null) return out;
        for (int i = 0; i < plans.length(); i += 1) {
            JSONObject plan = plans.optJSONObject(i);
            if (plan == null) continue;
            String id = plan.optString("plan_id", "");
            if (!id.trim().isEmpty() && !out.contains(id.trim())) out.add(id.trim());
        }
        return out;
    }

    private static Status statusZcode(Channel channel) throws java.io.IOException {
        java.util.List<String> ids;
        try {
            ids = zcodeClaimablePlanIds(channel);
        } catch (java.io.IOException e) {
            return Status.fail("额度查询失败：" + e.getMessage());
        }
        Status s = new Status();
        s.ok = true;
        s.active = !ids.isEmpty();
        s.todayCheckedIn = ids.isEmpty();
        if (ids.isEmpty()) {
            s.message = "当前没有可领额度";
        } else {
            s.message = "有 " + ids.size() + " 个额度可领";
        }
        return s;
    }

    private static Claim claimZcode(Channel channel) throws java.io.IOException {
        LoginProviders.Spec spec = LoginProviders.specOf(channel.loginType);
        if (spec == null) return Claim.of("failed", "未知登录类型");

        java.util.List<String> ids;
        try {
            ids = zcodeClaimablePlanIds(channel);
        } catch (java.io.IOException e) {
            return Claim.of("failed", "额度查询失败：" + e.getMessage());
        }
        if (ids.isEmpty()) return Claim.of("already-claimed", "当前没有可领额度");

        String url = zcodeBase(channel) + "/api/v1/zcode-plan/billing/claim";
        int claimedNow = 0;
        String lastFail = "";
        for (String planId : ids) {
            JSONObject body = new JSONObject();
            try {
                body.put("plan_id", planId);
            } catch (Exception ignored) {
            }
            JSONObject resp;
            try {
                resp = LoginProviders.requestJsonWithHeaders("POST", url, spec, zcodeAuth(channel),
                        body.toString(), TIMEOUT_MS,
                        new String[][]{{"X-ZCode-App-Version", ZCODE_APP_VERSION},
                                {"X-Platform", ZCODE_PLATFORM},
                                {"Content-Type", "application/json"}});
            } catch (java.io.IOException e) {
                lastFail = e.getMessage();
                continue;
            }
            int code = resp.optInt("code", -1);
            if (code != 0) {
                String msg = resp.optString("msg", "");
                lastFail = msg.isEmpty() ? "业务码 " + code : msg;
                continue;
            }
            JSONObject data = resp.optJSONObject("data");
            JSONObject plan = data == null ? null : data.optJSONObject("plan");
            if (plan == null) {
                lastFail = "领取响应缺少 plan";
                continue;
            }
            claimedNow += 1;
        }

        if (claimedNow > 0) {
            Claim c = Claim.of("claimed", claimedNow > 1
                    ? "已领取 " + claimedNow + " 个额度包"
                    : "已领取额度");
            return c;
        }
        return Claim.of("failed", lastFail.isEmpty() ? "领取失败" : lastFail);
    }

    // -----------------------------------------------------------------------
    // 华为 CodeArts：每日登录奖励（积分 campaign）
    // -----------------------------------------------------------------------

    /**
     * CodeArts 服务基址。
     *
     * <p>`codeartsBaseUrlOverride` 只给验证用：桌面验证要把请求指到本地假上游，
     * 而假上游是 http。留这个接缝才能离线跑完整链路（假上游不校验签名）。
     */
    static String codeartsBaseUrlOverride = null;

    private static String codeartsBase() {
        if (codeartsBaseUrlOverride != null) return codeartsBaseUrlOverride;
        return "https://snap-access.cn-north-4.myhuaweicloud.com";
    }

    /** 渠道标识（上游固定要求 `IDE`）。 */
    static final String CODEARTS_OPS_CHANNEL = "IDE";

    /** 每日登录奖励的活动类型。 */
    static final String CODEARTS_DAILY_TYPE = "USER_LOGIN";

    /** 已领取过的状态（上游用这三个词）。 */
    static final String[] CODEARTS_CLAIMED_STATUSES = {"CLAIMED", "CONFIRMED", "CONSUMED"};

    /**
     * 签到链路附带的**不参与签名**的头。
     *
     * <p>参考实现是签完名再补这两个头；签进去反而与上游不一致。
     */
    private static java.util.Map<String, String> snapExtraHeaders() {
        java.util.Map<String, String> h = new java.util.LinkedHashMap<>();
        h.put("Agent-Type", "PromptCenter");
        h.put("X-Language", "zh-cn");
        return h;
    }

    /**
     * 发一个 AK/SK 签名的 snap 请求，并拆掉 `{code,data}` 信封。
     *
     * <p>上游两种形态都出现过：有的接口直接返回数据对象，有的包一层
     * `{code:0,data:{…}}`。这里统一处理，调用方只面对数据本身。
     */
    private static JSONObject signedSnap(String method, String url, String body, Channel channel)
            throws java.io.IOException {
        String text = HuaweiSign.request(
                channel.accessKeyId, channel.secretAccessKey, channel.securityToken,
                method, url, body == null ? "" : body, TIMEOUT_MS, snapExtraHeaders());
        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (Exception e) {
            throw new java.io.IOException("响应不是 JSON");
        }
        if (!root.has("code")) return root;
        int code = root.optInt("code", -1);
        if (code != 0) {
            String msg = root.optString("message", "");
            if (msg.isEmpty()) msg = root.optString("msg", "");
            throw new java.io.IOException(msg.isEmpty() ? "业务码 " + code : msg);
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) throw new java.io.IOException("响应缺少 data");
        return data;
    }

    private static Status statusCodeArts(Channel channel) {
        // 凭据可能已过期（2 小时）：先静默续一次，续不上就让请求如实报错
        freshenCodeArts(channel);
        JSONObject info;
        try {
            info = signedSnap("GET",
                    codeartsBase() + "/snap-manager/v1/statistics/plugin", null, channel);
        } catch (java.io.IOException e) {
            return Status.fail("账号信息查询失败：" + describeCodeArtsError(e));
        }

        JSONObject pkg = info.optJSONObject("package");
        boolean creditPackage = readBool(pkg, "is_credit_package");
        boolean tokenPackage = readBool(pkg, "is_token_package");
        if (!creditPackage) {
            // Token 计费账号没有积分活动：如实说，不要假装「今天已签」
            Status s = new Status();
            s.ok = true;
            s.active = false;
            s.message = tokenPackage
                    ? "Token 计费账户，不在积分活动范围"
                    : "非积分计费账户，不在积分活动范围";
            return s;
        }

        JSONObject activity;
        try {
            JSONObject delivery = signedSnap("GET",
                    codeartsBase() + "/v1/ops/delivery?channel=" + CODEARTS_OPS_CHANNEL,
                    null, channel);
            activity = findDailyActivity(delivery.optJSONArray("items"));
        } catch (java.io.IOException e) {
            return Status.fail("活动列表查询失败：" + describeCodeArtsError(e));
        }

        Status s = new Status();
        s.ok = true;
        if (activity == null) {
            s.active = false;
            s.message = "未找到每日签到活动";
            return s;
        }
        if (isClaimableActivity(activity)) {
            s.active = true;
            s.todayCheckedIn = false;
            s.dailyCredit = activityAmount(activity);
            s.message = s.dailyCredit > 0
                    ? "可领 " + Credits.formatNumber(s.dailyCredit) + " 积分"
                    : "有奖励可领";
            return s;
        }
        if (isClaimedActivity(activity)) {
            s.active = true;
            s.todayCheckedIn = true;
            s.message = "今天已领取";
            return s;
        }
        s.active = false;
        s.message = "当前不可领取（status=" + activity.optString("status", "") + "）";
        return s;
    }

    private static Claim claimCodeArts(Channel channel) {
        Status before;
        try {
            before = statusCodeArts(channel);
        } catch (Exception e) {
            return Claim.of("failed", "签到状态查询失败：" + e.getMessage());
        }
        if (!before.ok) return Claim.of("failed", before.message);
        if (!before.active) {
            return Claim.of("inactive", before.message.isEmpty() ? "当前没有签到活动" : before.message);
        }
        if (before.todayCheckedIn) return Claim.of("already-claimed", "今天已经签过了");

        // 重新拉一次活动，拿 campaignId（状态查询与领取之间可能刚好被别的端领掉）
        JSONObject activity;
        try {
            JSONObject delivery = signedSnap("GET",
                    codeartsBase() + "/v1/ops/delivery?channel=" + CODEARTS_OPS_CHANNEL,
                    null, channel);
            activity = findDailyActivity(delivery.optJSONArray("items"));
        } catch (java.io.IOException e) {
            return Claim.of("failed", "活动列表查询失败：" + describeCodeArtsError(e));
        }
        if (activity == null || !isClaimableActivity(activity)) {
            // 刚刚还能领、现在不能了：按「已领」处理，不是失败
            if (isClaimedActivity(activity)) return Claim.of("already-claimed", "今天已经签过了");
            return Claim.of("inactive", "当前不可领取");
        }

        String campaignId = activityId(activity);
        if (campaignId.isEmpty()) return Claim.of("failed", "活动缺少 campaignId，无法领取");

        JSONObject claimedBody = new JSONObject();
        JSONObject claim;
        try {
            claimedBody.put("campaignId", campaignId);
            claimedBody.put("channel", CODEARTS_OPS_CHANNEL);
            claim = signedSnap("POST", codeartsBase() + "/v1/ops/claim",
                    claimedBody.toString(), channel);
        } catch (Exception e) {
            return Claim.of("failed", "领取失败：" + describeCodeArtsError(e));
        }

        // 领取后要 confirm 一次；confirm 失败不影响「已经领到」这个事实
        try {
            JSONObject confirm = new JSONObject();
            confirm.put("campaignId", campaignId);
            signedSnap("POST", codeartsBase() + "/v1/ops/confirm", confirm.toString(), channel);
        } catch (Exception ignored) {
        }

        double credit = readNumber(claim, "benefitAmount");
        if (credit <= 0) credit = readNumber(claim, "credit");
        if (credit <= 0) credit = activityAmount(activity);
        Claim c = Claim.of("claimed", credit > 0
                ? "签到成功 · +" + Credits.formatNumber(credit)
                : "签到成功");
        c.credit = credit;
        return c;
    }

    /** 华为的错误信息通常只有错误码，翻译成人话。 */
    private static String describeCodeArtsError(Exception e) {
        String msg = String.valueOf(e.getMessage());
        if (msg.contains("0613")) {
            return "华为云鉴权未通过（APIG.0613）：凭据可能无效或已过期，请重新登录";
        }
        if (msg.contains("0301") || msg.contains("401") || msg.contains("Unauthorized")) {
            return "CodeArts 临时凭据已过期，请重新登录";
        }
        return msg;
    }

    // ---- 纯判定（可离线验证）----

    /** 从活动列表里找「每日登录奖励」。 */
    static JSONObject findDailyActivity(JSONArray items) {
        if (items == null) return null;
        for (int i = 0; i < items.length(); i += 1) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && CODEARTS_DAILY_TYPE.equals(item.optString("type", ""))) return item;
        }
        return null;
    }

    /** 活动当前是否可领。 */
    static boolean isClaimableActivity(JSONObject activity) {
        return readBool(activity, "claimable");
    }

    /** 活动是否已经领过（`CLAIMED` / `CONFIRMED` / `CONSUMED`）。 */
    static boolean isClaimedActivity(JSONObject activity) {
        if (activity == null) return false;
        String status = activity.optString("status", "");
        for (String s : CODEARTS_CLAIMED_STATUSES) {
            if (s.equals(status)) return true;
        }
        return false;
    }

    /** 活动 id（实测是**数字** 1，不能只按字符串读）。 */
    static String activityId(JSONObject activity) {
        if (activity == null) return "";
        Object raw = activity.opt("campaignId");
        if (raw instanceof Number) return String.valueOf(((Number) raw).longValue());
        if (raw instanceof String) return ((String) raw).trim();
        return "";
    }

    /** 可领/已领的积分数（上游字段名是 `benefitAmount`）。 */
    static double activityAmount(JSONObject activity) {
        if (activity == null) return 0;
        double v = readNumber(activity, "benefitAmount");
        if (v <= 0) v = readNumber(activity, "amount");
        if (v <= 0) v = readNumber(activity, "creditAmount");
        return v;
    }

    private static boolean readBool(JSONObject source, String key) {
        if (source == null) return false;
        Object raw = source.opt(key);
        if (raw instanceof Boolean) return ((Boolean) raw).booleanValue();
        if (raw instanceof String) return "true".equalsIgnoreCase(((String) raw).trim());
        return false;
    }

    /** 从 `benefit.amount` 读额度。 */
    private static double benefitAmount(JSONObject item) {
        if (item == null) return 0;
        JSONObject benefit = item.optJSONObject("benefit");
        return benefit == null ? 0 : readNumber(benefit, "amount");
    }
    private static double readNumber(JSONObject source, String key) {
        if (source == null) return 0;
        Object raw = source.opt(key);
        if (raw instanceof Number) {
            double v = ((Number) raw).doubleValue();
            return Double.isFinite(v) ? v : 0;
        }
        if (raw instanceof String) {
            try {
                double v = Double.parseDouble(((String) raw).trim());
                return Double.isFinite(v) ? v : 0;
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /** 路径段编码（campaignId 可能含特殊字符）。 */
    private static String urlEncodePath(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }
}
