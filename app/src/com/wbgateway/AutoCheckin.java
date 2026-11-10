package com.wbgateway;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 自动签到：进 App 时、以及每天首次打开时，把「还没签的」签掉。
 *
 * <h3>为什么不做后台定时任务</h3>
 *
 * 手机上定时任务会被系统压制（Doze / 厂商省电），设一个「每天 9 点签到」的
 * AlarmManager 在国产 ROM 上基本不会准时触发 —— 用了反而给人「已自动签到」的
 * 错觉。这里走**触发式**：
 *
 * <ol>
 *   <li>App 启动 / 回到前台时跑一次；</li>
 *   <li>同一渠道**每天最多跑一次**（记在本地，按本地日期判断），避免每开一次界面就写一次上游；</li>
 *   <li>手动点「一键签到」不受这个限制（用户显式要求就该执行）。</li>
 * </ol>
 *
 * <h3>「未签到自动签到」是怎么判定的</h3>
 *
 * 各渠道的判定条件不同，统一由 {@link Checkin#run} 内部处理：
 * <ul>
 *   <li>WorkBuddy：先查 `today_checked_in`，false 才去领；</li>
 *   <li>Qoder：只看有没有 `CLAIMABLE` 的活动，有才领；</li>
 *   <li>ZCode：preview 里有 plan 就领，领完 preview 自然变空（天然幂等）；</li>
 *   <li>CodeArts：看一下 `USER_LOGIN` 活动的 `claimable`，true 才领
 *       （已领的状态是 `CLAIMED/CONFIRMED/CONSUMED`）。</li>
 * </ul>
 * 所以这里不需要再判一次「该不该签」—— 已经把判断放在协议层，
 * 避免两处各判一遍然后不一致。
 */
public final class AutoCheckin {

    private static final String PREF = "modelhub_auto_checkin";
    /** 渠道 id → 上次自动签到成功的本地日期（yyyy-MM-dd）。 */
    private static final String KEY_LAST = "last_";

    private AutoCheckin() {
    }

    /** 本地日期串（按设备时区，跨时区不会误判成同一天）。 */
    static String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
    }

    /**
     * 从全部渠道里挑出「该跑自动签到」的（**纯函数**，不碰 SharedPreferences）。
     *
     * <p>抽出来是为了能离线验证：判定规则写错的表现很隐蔽 ——
     * 多跑一次只是多一次写请求，少跑一次则**当天再也不签到**，
     * 而界面上两者都看不出来。
     *
     * @param channels  全部渠道
     * @param doneToday 已记录「今天做过」的渠道 id
     */
    /**
     * 选出「需要跑自动签到」的渠道。
     *
     * <p>⚠ **不再按「今天已做过」跳过**（用户要求：打开应用就签到一次）。
     * 现在的策略是每次都问上游：已签过的渠道会返回 `already-claimed`，
     * 界面显示「今天已经签过了」—— 这反而是个确认信号。
     *
     * <p>为什么不依赖本地日期记录：那个记录一旦写错（比如把「无资格」当成
     * 「已处理」），当天就再也不会签到，而界面上看不出来。让上游做权威判断
     * 更可靠，代价只是每次打开多几个幂等请求。
     *
     * @param channels  全部渠道
     * @param doneToday 保留参数（历史调用方传空集合即可；逻辑已不使用）
     */
    static java.util.List<Channel> selectPending(java.util.List<Channel> channels,
                                                java.util.Set<String> doneToday) {
        java.util.List<Channel> out = new java.util.ArrayList<>();
        if (channels == null) return out;
        for (Channel c : channels) {
            if (c == null) continue;
            if (!Channel.KIND_LOGIN.equals(c.kind)) continue;      // 代理渠道没有签到
            if (!c.hasCredential()) continue;                       // 没登录
            if (!Checkin.supported(c.loginType)) continue;          // 该渠道没有签到
            out.add(c);
        }
        return out;
    }

    /** 该渠道今天是否已经自动签到过（仅用于展示，不再决定是否跳过）。 */
    static boolean doneToday(Context context, String channelId) {
        SharedPreferences p = context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return today().equals(p.getString(KEY_LAST + channelId, ""));
    }

    /** 记下今天已处理（保留：便于排查「最后一次成功是哪天」）。 */
    static void markDone(Context context, String channelId) {
        SharedPreferences p = context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        p.edit().putString(KEY_LAST + channelId, today()).commit();
    }

    /**
     * 选出需要跑自动签到的渠道。
     *
     * <p>每次都返回所有已登录、且该渠道支持签到的渠道 —— 不去重、不跳过。
     * 是否「已经签过」由上游回答。
     */
    public static java.util.List<Channel> pending(Context context, java.util.List<Channel> channels) {
        return selectPending(channels, null);
    }

    /**
     * 跑一轮自动签到（**阻塞**，必须在后台线程调用）。
     *
     * <p>什么时候记「今天做过了」：
     * <ul>
     *   <li>`claimed`（真领到）/ `already-claimed`（本来已签）→ **记**；</li>
     *   <li>`inactive`（无活动 / 无订阅 / 无资格）→ **也记**。
     *       这是账号属性，不会因为多试几次就变成能领；不记的话每次打开 App
     *       都要重新打一轮上游请求、还会反复弹「无可领额度」；</li>
     *   <li>`failed`（网络 / 凭据）→ **不记**，留着下次重试 ——
     *       否则一次网络抖动就让当天再也不自动签到。</li>
     * </ul>
     */
    public static java.util.Map<String, Checkin.Claim> runPending(Context context,
                                                                 java.util.List<Channel> channels) {
        java.util.Map<String, Checkin.Claim> out = new java.util.LinkedHashMap<>();
        for (Channel c : pending(context, channels)) {
            Checkin.Claim r = Checkin.run(c);
            out.put(c.id, r);
            boolean settled = r.isSuccess() || "inactive".equals(r.kind);
            if (settled) markDone(context, c.id);
        }
        return out;
    }

    /**
     * 一句话摘要（没有事情发生时返回空串，界面就不打扰用户）。
     *
     * <p>⚠ `inactive`（无活动 / 无订阅 / 无资格）**不是失败**：
     * 那是「这个账号本来就没得领」，属于正常状态。把它算进失败，
     * 用户每次打开 App 都会看到「自动签到：1 个失败」，
     * 却找不到任何需要处理的问题 —— 之前就是被这么报的。
     *
     * <p>只有真正的 `failed`（网络错误 / 凭据失效 / 上游异常）才计入失败。
     */
    public static String summarize(java.util.Map<String, Checkin.Claim> results) {
        if (results == null || results.isEmpty()) return "";
        int claimed = 0;
        int already = 0;
        int inactive = 0;
        int failed = 0;
        for (Checkin.Claim r : results.values()) {
            if (r == null) continue;
            if (r.isClaimed()) claimed += 1;
            else if ("already-claimed".equals(r.kind)) already += 1;
            else if ("inactive".equals(r.kind)) inactive += 1;
            else failed += 1;
        }
        // 没有「成功」也没有「真失败」时不打扰：已签 / 无活动都是每天的常态
        if (claimed == 0 && failed == 0) return "";
        StringBuilder sb = new StringBuilder("自动签到：");
        if (claimed > 0) sb.append(claimed).append(" 个成功");
        if (already > 0) sb.append(sb.charAt(sb.length() - 1) == '：' ? "" : "，").append(already).append(" 个已签");
        if (inactive > 0) sb.append(sb.charAt(sb.length() - 1) == '：' ? "" : "，").append(inactive).append(" 个无可领额度");
        if (failed > 0) sb.append(sb.charAt(sb.length() - 1) == '：' ? "" : "，").append(failed).append(" 个失败");
        return sb.toString();
    }
}
