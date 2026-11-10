package com.wbgateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自动签到的桌面验证桥接。
 *
 * <p>`AutoCheckin` 依赖 `SharedPreferences`（Android API），桌面跑不了实例部分；
 * 但它的**判定规则**已抽成纯函数 `selectPending`，这里暴露出来验证 ——
 * 判定写错的表现很隐蔽：多跑一次只是多一次写请求，少跑一次则**当天再也不签到**，
 * 界面上两者都看不出来。
 */
public final class AutoCheckinProbe {

    private AutoCheckinProbe() {
    }

    /** 本地日期串（yyyy-MM-dd）。 */
    public static String today() {
        return AutoCheckin.today();
    }

    /** 见 {@link AutoCheckin#selectPending(List, Set)}。 */
    public static List<Channel> selectPending(List<Channel> channels, Set<String> doneToday) {
        return AutoCheckin.selectPending(channels, doneToday);
    }

    /** 见 {@link AutoCheckin#summarize(Map)}。 */
    public static String summarize(Map<String, Checkin.Claim> results) {
        return AutoCheckin.summarize(results);
    }

    /** 该结果是否应该记「今天做过了」（成功或本来已签）。 */
    public static boolean shouldMark(Checkin.Claim claim) {
        return claim != null && claim.isSuccess();
    }

    /** 造一个空结果表。 */
    public static Map<String, Checkin.Claim> newResultMap() {
        return new java.util.LinkedHashMap<>();
    }

    /**
     * 该结果是否应记「今天做过了」（不再重试）。
     *
     * <p>与 {@link #shouldMark} 的区别：`inactive`（无活动/无订阅）也要记账 ——
     * 那是账号属性，多试几次不会变成能领；不记的话每次打开 App 都重打上游、
     * 还反复弹提示。只有真失败才留着重试。
     */
    public static boolean shouldSettle(Checkin.Claim claim) {
        if (claim == null) return false;
        return claim.isSuccess() || "inactive".equals(claim.kind);
    }

    /** 造一组渠道（便于构造用例）。 */
    public static List<Channel> list() {
        return new ArrayList<>();
    }
}
