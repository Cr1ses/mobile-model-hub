package com.wbgateway;

import org.json.JSONObject;

/**
 * 签到功能的桌面验证桥接。
 *
 * <p>`Checkin` 本身没有 Android 依赖，可以直接跑；但里面几个**最容易出错的判定**
 * 是包内可见的，需要从这里暴露出来做逐条断言 —— 尤其是
 * 「`10001` = 今天已签过，**不是失败**」这一条：判错的症状是界面提示
 * 「签到失败」，而用户额度其实早就到账了，用户会以为功能坏了。
 */
public final class CheckinProbe {

    private CheckinProbe() {
    }

    /** 见 {@link Checkin#classifyWorkbuddyCode(int, String)}。 */
    public static Checkin.Claim classifyWb(int code, String msg) {
        return Checkin.classifyWorkbuddyCode(code, msg);
    }

    /** 见 {@link Checkin#isClaimableCampaign(JSONObject)}。 */
    public static boolean isClaimable(JSONObject campaign) {
        return Checkin.isClaimableCampaign(campaign);
    }

    /** 见 {@link Checkin#isClaimedCampaign(JSONObject)}。 */
    public static boolean isClaimed(JSONObject campaign) {
        return Checkin.isClaimedCampaign(campaign);
    }

    /** 造一个 `Claim`（给断言构造期望值用）。 */
    public static Checkin.Claim claim(String kind, String message) {
        return Checkin.Claim.of(kind, message);
    }

    /** 把 WorkBuddy 基址指到假上游（传 null 恢复）。 */
    public static void overrideWorkbuddyBase(String baseUrl) {
        Checkin.workbuddyBaseUrlOverride = baseUrl;
    }

    /** 把 CodeArts 基址指到假上游（传 null 恢复）。 */
    public static void overrideCodeartsBase(String baseUrl) {
        Checkin.codeartsBaseUrlOverride = baseUrl;
    }

    /** 见 {@link Checkin#findDailyActivity(org.json.JSONArray)}。 */
    public static JSONObject codeartsDailyActivity(org.json.JSONArray items) {
        return Checkin.findDailyActivity(items);
    }

    /** 见 {@link Checkin#isClaimableActivity(JSONObject)}。 */
    public static boolean codeartsClaimable(JSONObject activity) {
        return Checkin.isClaimableActivity(activity);
    }

    /** 见 {@link Checkin#isClaimedActivity(JSONObject)}。 */
    public static boolean codeartsClaimed(JSONObject activity) {
        return Checkin.isClaimedActivity(activity);
    }

    /** 见 {@link Checkin#activityId(JSONObject)}。 */
    public static String codeartsActivityId(JSONObject activity) {
        return Checkin.activityId(activity);
    }
}
