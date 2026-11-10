import com.wbgateway.Channel;
import com.wbgateway.Checkin;
import com.wbgateway.LoginProviders;
import com.wbgateway.CheckinProbe;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * 签到的桌面等价验证：跑**同一份** {@link Checkin}，上游换成假的。
 *
 * <p>为什么值得单独验：签到是**写操作**（真的会改上游状态），而且它的失败形态
 * 特别容易搞错 —— 上游用业务码区分「已签过 / 无资格 / 活动结束」，
 * 判错不会抛异常，只会给用户一句误导的提示。
 *
 * <p>覆盖：
 * <ol>
 *   <li>业务码映射：10001/1001 = 已签（**不是失败**）、1002/1003 = 无资格；</li>
 *   <li>完整链路：查状态 → 已签就直接返回（不发领取请求）；未签 → 领取成功；</li>
 *   <li>Qoder 活动筛选：只挑 CLAIM_BENEFIT + CLAIMABLE；</li>
 *   <li>支持矩阵：WorkBuddy 两区域 + Qoder 两版本可签，ZCode / Raccoon 如实说不支持。</li>
 * </ol>
 */
public class VerifyCheckin {

    static int pass = 0;
    static int fail = 0;

    static void check(String label, boolean ok, String detail) {
        if (ok) {
            pass += 1;
            System.out.println("  PASS  " + label + (detail.isEmpty() ? "" : " -- " + detail));
        } else {
            fail += 1;
            System.out.println("  FAIL  " + label + (detail.isEmpty() ? "" : " -- " + detail));
        }
    }

    /** 假上游：可切换三种剧本（未签 / 已签 / 无资格）。 */
    static class FakeCheckinServer {
        HttpServer server;
        String base;
        /** unchecked / checked / inactive */
        volatile String scenario = "unchecked";
        /** 领取端点被调用的次数（验证「已签时不该再写」）。 */
        volatile int claimCalls = 0;
        volatile String lastBody = "";

        void start() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            int port = server.getAddress().getPort();
            base = "http://127.0.0.1:" + port;

            server.createContext("/v2/billing/meter/checkin-activity-status", exchange -> {
                if ("inactive".equals(scenario)) {
                    send(exchange, 200, "{\"code\":1003,\"msg\":\"活动已结束\"}");
                    return;
                }
                boolean checked = "checked".equals(scenario);
                send(exchange, 200, "{\"code\":0,\"data\":{"
                        + "\"active\":true,"
                        + "\"today_checked_in\":" + checked + ","
                        + "\"streak_days\":3,"
                        + "\"daily_credit\":10,"
                        + "\"today_credit\":" + (checked ? 10 : 0) + ","
                        + "\"is_streak_day\":false,"
                        + "\"total_credits\":100,"
                        + "\"activity_name\":\"每日签到\"}}");
            });

            server.createContext("/v2/billing/meter/daily-checkin", exchange -> {
                claimCalls += 1;
                lastBody = readBody(exchange);
                if ("checked".equals(scenario)) {
                    send(exchange, 200, "{\"code\":10001,\"msg\":\"今天已签到\"}");
                    return;
                }
                if ("inactive".equals(scenario)) {
                    send(exchange, 200, "{\"code\":1002,\"msg\":\"无领取资格\"}");
                    return;
                }
                send(exchange, 200, "{\"code\":0,\"data\":{"
                        + "\"credit\":10,\"streak_days\":4,\"is_streak_day\":true}}");
            });

            server.start();
        }

        void stop() {
            if (server != null) server.stop(0);
        }
    }

    static void send(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
        exchange.close();
    }

    static String readBody(HttpExchange exchange) throws java.io.IOException {
        InputStream in = exchange.getRequestBody();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 造一个已登录的 WorkBuddy 渠道。 */
    static Channel wbChannel() {
        Channel c = new Channel();
        c.id = "cn";
        c.loginType = LoginProviders.WB_CN;
        c.kind = Channel.KIND_LOGIN;
        c.accessToken = "test-token";
        c.domain = "copilot.tencent.com";
        return c;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 1. 业务码映射（最易错的一段）===");
        Checkin.Claim c10001 = CheckinProbe.classifyWb(10001, "");
        check("10001 → 今天已签过（不是失败）", "already-claimed".equals(c10001.kind), c10001.kind);
        check("10001 算成功", c10001.isSuccess(), c10001.message);
        Checkin.Claim c1001 = CheckinProbe.classifyWb(1001, "");
        check("1001 → 今天已签过（老版本码）", "already-claimed".equals(c1001.kind), c1001.kind);
        Checkin.Claim c1002 = CheckinProbe.classifyWb(1002, "");
        check("1002 → 无资格", "inactive".equals(c1002.kind), c1002.kind);
        check("无资格不算成功", !c1002.isSuccess(), "");
        Checkin.Claim c1003 = CheckinProbe.classifyWb(1003, "");
        check("1003 → 活动结束（归入无资格）", "inactive".equals(c1003.kind), c1003.kind);
        Checkin.Claim cOther = CheckinProbe.classifyWb(500, "");
        check("其它码 → 失败", "failed".equals(cOther.kind), cOther.kind);
        check("其它码不算成功", !cOther.isSuccess(), "");
        check("上游 msg 优先于默认文案",
                "自定义提示".equals(CheckinProbe.classifyWb(10001, "自定义提示").message), "");

        System.out.println();
        System.out.println("=== 2. Qoder 活动筛选 ===");
        JSONObject claimable = new JSONObject("{\"campaignId\":\"c1\",\"actionType\":\"CLAIM_BENEFIT\","
                + "\"claimStatus\":\"CLAIMABLE\",\"benefit\":{\"amount\":100}}");
        JSONObject claimed = new JSONObject("{\"campaignId\":\"c2\",\"actionType\":\"CLAIM_BENEFIT\","
                + "\"claimStatus\":\"CLAIMED\"}");
        JSONObject otherType = new JSONObject("{\"campaignId\":\"c3\",\"actionType\":\"DO_SOMETHING\","
                + "\"claimStatus\":\"CLAIMABLE\"}");
        JSONObject notYet = new JSONObject("{\"campaignId\":\"c4\",\"actionType\":\"CLAIM_BENEFIT\","
                + "\"claimStatus\":\"LOCKED\"}");
        check("CLAIM_BENEFIT + CLAIMABLE → 可领", CheckinProbe.isClaimable(claimable), "");
        check("CLAIM_BENEFIT + CLAIMED → 不算可领", !CheckinProbe.isClaimable(claimed), "");
        check("其它 actionType 不算可领", !CheckinProbe.isClaimable(otherType), "");
        check("非 CLAIMABLE 状态不算可领", !CheckinProbe.isClaimable(notYet), "");
        check("CLAIMED 能被识别为已领", CheckinProbe.isClaimed(claimed), "");
        check("可领的不算已领", !CheckinProbe.isClaimed(claimable), "");
        check("null 活动不崩", !CheckinProbe.isClaimable(null) && !CheckinProbe.isClaimed(null), "");

        System.out.println();
        System.out.println("=== 3. 支持矩阵 ===");
        check("WorkBuddy 国际版可签", Checkin.supported(LoginProviders.WB_INTL), "");
        check("WorkBuddy 国内版可签", Checkin.supported(LoginProviders.WB_CN), "");
        check("Qoder 可签", Checkin.supported(LoginProviders.QODER), "");
        check("Qoder 中国版可签", Checkin.supported(LoginProviders.QODER_CN), "");
        // ZCode 没有「每日签到」，但有「可领额度」，并入同一套自动领取流程
        check("ZCode 可领（并入签到矩阵）", Checkin.supported(LoginProviders.ZCODE), "");
        check("Raccoon 不可签", !Checkin.supported(LoginProviders.RACCOON), "");
        check("Raccoon 的原因说清「没有每日签到」",
                Checkin.unsupportedReason(LoginProviders.RACCOON).contains("没有每日签到"),
                Checkin.unsupportedReason(LoginProviders.RACCOON));

        System.out.println();
        System.out.println("=== 4. 未登录 / 非登录型渠道的拒绝 ===");
        Channel noToken = new Channel();
        noToken.id = "cn";
        noToken.loginType = LoginProviders.WB_CN;
        noToken.kind = Channel.KIND_LOGIN;
        Checkin.Claim r1 = Checkin.run(noToken);
        check("没有 token → 拒绝且说明原因", "failed".equals(r1.kind) && r1.message.contains("尚未登录"), r1.message);
        Channel proxy = new Channel("ds", "代理", "https://api.deepseek.com/v1");
        proxy.apiKeys.add("sk-x");
        Checkin.Claim r2 = Checkin.run(proxy);
        check("代理渠道没有签到", "failed".equals(r2.kind) && r2.message.contains("API Key"), r2.message);

        System.out.println();
        System.out.println("=== 5. 完整链路（假上游）===");
        FakeCheckinServer up = new FakeCheckinServer();
        up.start();
        CheckinProbe.overrideWorkbuddyBase(up.base);
        try {
            // 5.1 已签：查状态后应直接返回，**不再发领取请求**
            up.scenario = "checked";
            up.claimCalls = 0;
            Checkin.Claim already = Checkin.run(wbChannel());
            check("已签 → already-claimed", "already-claimed".equals(already.kind), already.message);
            check("已签时不再发领取请求（省一次写）", up.claimCalls == 0, "claim 调用 " + up.claimCalls + " 次");
            check("已签时带回已领额度", already.credit == 10, "credit=" + already.credit);

            // 5.2 未签：领取成功
            up.scenario = "unchecked";
            up.claimCalls = 0;
            Checkin.Claim ok = Checkin.run(wbChannel());
            check("未签 → claimed", ok.isClaimed(), ok.message);
            check("拿到额度 10", ok.credit == 10, "credit=" + ok.credit);
            check("拿到连续天数 4", ok.streakDays == 4, "streak=" + ok.streakDays);
            check("确实发了领取请求", up.claimCalls == 1, "claim 调用 " + up.claimCalls + " 次");
            check("请求体是 {}", "{}".equals(up.lastBody.trim()), up.lastBody);
            check("提示里带额度与连签", ok.message.contains("+10") && ok.message.contains("连续"),
                    ok.message);

            // 5.3 活动结束：无资格，不该报「失败」
            up.scenario = "inactive";
            up.claimCalls = 0;
            Checkin.Claim inactive = Checkin.run(wbChannel());
            check("活动结束 → inactive（不是 failed）", "inactive".equals(inactive.kind), inactive.message);
            check("活动结束时不发领取请求", up.claimCalls == 0, "claim 调用 " + up.claimCalls + " 次");

            // 5.4 status() 的字段落位
            up.scenario = "unchecked";
            Checkin.Status st = Checkin.status(wbChannel());
            check("status.ok", st.ok, "");
            check("status.active", st.active, "");
            check("status 未签", !st.todayCheckedIn, "");
            check("status 连续 3 天", st.streakDays == 3, "streak=" + st.streakDays);
            check("status 每日额度 10", st.dailyCredit == 10, "daily=" + st.dailyCredit);
            check("status 摘要含「未签到」", st.summary().contains("未签到"), st.summary());

            up.scenario = "checked";
            Checkin.Status st2 = Checkin.status(wbChannel());
            check("已签状态摘要含「已签到」", st2.summary().contains("已签到"), st2.summary());
            check("已签状态 todayCheckedIn", st2.todayCheckedIn, "");
        } finally {
            CheckinProbe.overrideWorkbuddyBase(null);
            up.stop();
        }

        System.out.println();
        System.out.println("=== 6. ZCode：有可领额度才领（幂等）===");
        check("ZCode 并入签到支持矩阵", Checkin.supported(LoginProviders.ZCODE), "");
        // ZCode 没有「今天签没签」状态位，只能靠 preview 是否还有 plan 判断
        Channel zc = new Channel();
        zc.id = "zcode";
        zc.loginType = LoginProviders.ZCODE;
        zc.kind = Channel.KIND_LOGIN;
        zc.accessToken = "x";
        check("ZCode 有 token 时不因「不支持」被拒",
                !Checkin.run(zc).message.contains("没有每日签到"), Checkin.run(zc).message);

        System.out.println();
        System.out.println("=== 7. 自动签到的筛选规则（写错会当天不再签）===");
        java.util.List<Channel> all = new java.util.ArrayList<>();
        Channel wb1 = wbChannel();
        wb1.id = "cn";
        all.add(wb1);
        Channel qd = new Channel();
        qd.id = "qoder";
        qd.loginType = LoginProviders.QODER;
        qd.kind = Channel.KIND_LOGIN;
        qd.accessToken = "t";
        all.add(qd);
        Channel zc2 = new Channel();
        zc2.id = "zcode";
        zc2.loginType = LoginProviders.ZCODE;
        zc2.kind = Channel.KIND_LOGIN;
        zc2.accessToken = "t";
        all.add(zc2);
        Channel rc = new Channel();
        rc.id = "raccoon";
        rc.loginType = LoginProviders.RACCOON;
        rc.kind = Channel.KIND_LOGIN;
        rc.accessToken = "t";
        all.add(rc);            // 不支持签到 → 不该入选
        Channel proxy2 = new Channel("ds", "代理", "https://api.deepseek.com/v1");
        proxy2.apiKeys.add("sk");  // 代理渠道 → 不该入选
        all.add(proxy2);
        Channel noTok = new Channel();
        noTok.id = "qodercn";
        noTok.loginType = LoginProviders.QODER_CN;
        noTok.kind = Channel.KIND_LOGIN;   // 没有 token → 不该入选
        all.add(noTok);

        java.util.List<Channel> picked = com.wbgateway.AutoCheckinProbe.selectPending(all, new java.util.HashSet<String>());
        check("首次运行选出 3 个（cn/qoder/zcode）", picked.size() == 3, "选出 " + picked.size() + " 个");
        boolean hasRaccoon = false;
        boolean hasProxy = false;
        boolean hasNoToken = false;
        for (Channel c : picked) {
            if ("raccoon".equals(c.id)) hasRaccoon = true;
            if ("ds".equals(c.id)) hasProxy = true;
            if ("qodercn".equals(c.id)) hasNoToken = true;
        }
        check("Raccoon 不进自动签到", !hasRaccoon, "");
        check("代理渠道不进自动签到", !hasProxy, "");
        check("没 token 的不进自动签到", !hasNoToken, "");

        // 用户要求「打开应用就签到一次」→ 不再按「今天做过」跳过。
        // 已签过的渠道由上游回答 already-claimed，界面显示「今天已经签过了」。
        java.util.Set<String> done = new java.util.HashSet<>();
        done.add("cn");
        java.util.List<Channel> picked2 = com.wbgateway.AutoCheckinProbe.selectPending(all, done);
        check("传了「今天已做过」也照样选（打开就签）",
                picked2.size() == 3, "选出 " + picked2.size() + " 个");
        boolean stillHasCn = false;
        for (Channel c : picked2) if ("cn".equals(c.id)) stillHasCn = true;
        check("已签过的渠道仍会去问上游（拿 already-claimed）", stillHasCn, "");
        // 幂等性由上游保证：重复签只会得到 already-claimed，不会重复加分
        check("重复签到归为「今天已签过」而不是失败",
                "already-claimed".equals(CheckinProbe.classifyWb(10001, "").kind), "");

        System.out.println();
        System.out.println("=== 8. 结果分类与摘要文案 ===");
        check("成功才记账（claimed）",
                com.wbgateway.AutoCheckinProbe.shouldMark(CheckinProbe.claim("claimed", "ok")), "");
        check("本来已签也记账（already-claimed）",
                com.wbgateway.AutoCheckinProbe.shouldMark(CheckinProbe.claim("already-claimed", "已签")), "");
        check("失败不算「成功」",
                !com.wbgateway.AutoCheckinProbe.shouldMark(CheckinProbe.claim("failed", "网络错误")), "");
        check("无资格不算「成功」",
                !com.wbgateway.AutoCheckinProbe.shouldMark(CheckinProbe.claim("inactive", "无资格")), "");

        java.util.Map<String, Checkin.Claim> onlyAlready = new java.util.LinkedHashMap<>();
        onlyAlready.put("cn", CheckinProbe.claim("already-claimed", "已签"));
        check("全是「本来就签过」时不打扰用户（摘要为空）",
                com.wbgateway.AutoCheckinProbe.summarize(onlyAlready).isEmpty(),
                "[" + com.wbgateway.AutoCheckinProbe.summarize(onlyAlready) + "]");

        java.util.Map<String, Checkin.Claim> mixed = new java.util.LinkedHashMap<>();
        mixed.put("cn", CheckinProbe.claim("claimed", "ok"));
        mixed.put("qoder", CheckinProbe.claim("already-claimed", "已签"));
        String sum = com.wbgateway.AutoCheckinProbe.summarize(mixed);
        check("有成功时摘要含「成功」", sum.contains("成功") && sum.contains("已签"), sum);

        java.util.Map<String, Checkin.Claim> failedOnly = new java.util.LinkedHashMap<>();
        failedOnly.put("cn", CheckinProbe.claim("failed", "网络错误"));
        check("有失败时摘要含「失败」",
                com.wbgateway.AutoCheckinProbe.summarize(failedOnly).contains("失败"),
                com.wbgateway.AutoCheckinProbe.summarize(failedOnly));

        // 「1 个失败」真实报障：inactive（无活动 / 无订阅）被算成了失败
        java.util.Map<String, Checkin.Claim> oneInactive = new java.util.LinkedHashMap<>();
        oneInactive.put("zcode", CheckinProbe.claim("inactive", "该账号没有 zcode-plan 订阅"));
        String sumInactive = com.wbgateway.AutoCheckinProbe.summarize(oneInactive);
        check("inactive 不报「失败」", !sumInactive.contains("失败"), "[" + sumInactive + "]");
        check("只有 inactive 时不打扰用户（摘要为空）", sumInactive.isEmpty(), "[" + sumInactive + "]");

        // 有成功 + 有 inactive：各自分开说
        java.util.Map<String, Checkin.Claim> mixedInactive = new java.util.LinkedHashMap<>();
        mixedInactive.put("cn", CheckinProbe.claim("claimed", "ok"));
        mixedInactive.put("zcode", CheckinProbe.claim("inactive", "无订阅"));
        String sumMixed = com.wbgateway.AutoCheckinProbe.summarize(mixedInactive);
        check("成功 + 无资格 各自表述",
                sumMixed.contains("成功") && sumMixed.contains("无可领额度")
                        && !sumMixed.contains("失败"), sumMixed);

        // inactive 要记账（否则每次打开都重打上游）
        check("inactive 应记「今天做过了」",
                com.wbgateway.AutoCheckinProbe.shouldSettle(CheckinProbe.claim("inactive", "无订阅")), "");
        check("failed 不应记账（留着重试）",
                !com.wbgateway.AutoCheckinProbe.shouldSettle(CheckinProbe.claim("failed", "网络")), "");
        check("claimed 应记账",
                com.wbgateway.AutoCheckinProbe.shouldSettle(CheckinProbe.claim("claimed", "ok")), "");

        check("空结果不产生摘要",
                com.wbgateway.AutoCheckinProbe.summarize(
                        com.wbgateway.AutoCheckinProbe.newResultMap()).isEmpty(), "");
        check("日期串是 yyyy-MM-dd 形态",
                com.wbgateway.AutoCheckinProbe.today().matches("\\d{4}-\\d{2}-\\d{2}"),
                com.wbgateway.AutoCheckinProbe.today());

        System.out.println();
        System.out.println("=== 9. ZCode 额度查询（原来误报「没有接口」）===");
        // 实测：无 token/假 token → 401，真 token → 200，说明端点存在且要鉴权。
        // 之前 Credits 把 ZCode 判成「不支持」，界面显示「ZCode 没有积分查询接口」。
        check("ZCode 现在算支持积分查询",
                com.wbgateway.Credits.supported(LoginProviders.ZCODE), "");
        check("ZCode 不再被说成「没有接口」",
                !com.wbgateway.Credits.unsupportedReason(LoginProviders.ZCODE).contains("没有积分查询接口"),
                com.wbgateway.Credits.unsupportedReason(LoginProviders.ZCODE));
        // 用户要求：不显示「该账号没有订阅」这类解释，直接显示额度
        com.wbgateway.Credits.Result zcZero = com.wbgateway.CreditsProbe.makeResult(0, "", "", 0, "");
        String zcText = com.wbgateway.CreditsProbe.summary(zcZero);
        check("额度为 0 时不出现「订阅」字样", !zcText.contains("订阅"), "[" + zcText + "]");
        check("额度为 0 时不出现「凭据」字样", !zcText.contains("凭据"), "[" + zcText + "]");

        System.out.println();
        System.out.println("=== 10. 积分摘要只给数字与到期，不提资源包 ===");
        // 用户明确要求：只显示「多少积分」和「何时过期」，不要「N 个资源包」
        long expiry = System.currentTimeMillis() + 7L * 24 * 3600 * 1000;
        com.wbgateway.Credits.Result r = com.wbgateway.CreditsProbe.makeResult(
                6000, "credits", "A|1000|credits|0;B|2000|credits|0;C|3000|credits|0", expiry, "");
        String summary = com.wbgateway.CreditsProbe.summary(r);
        check("摘要不含「资源包」", !summary.contains("资源包"), summary);
        check("摘要含总数", summary.contains("6,000"), summary);
        check("摘要含到期", summary.contains("到期"), summary);
        // 明细也不该出现内部包名，只给数字+到期
        String detail = com.wbgateway.CreditsProbe.detail(r);
        check("明细不含包名 A/B/C", !detail.contains("A\n") && !detail.contains("B\n"), detail.replace("\n", " / "));
        check("明细按行给数字", detail.split("\n").length == 3, detail.split("\n").length + " 行");

        // 单包时没有明细可展开（列出来和总数一样，没意义）
        com.wbgateway.Credits.Result singlePkg = com.wbgateway.CreditsProbe.makeResult(
                500, "credits", "X|500|credits|0", 0, "");
        check("单包时 detail 为空", com.wbgateway.CreditsProbe.detail(singlePkg).isEmpty(), "");

        // ZCode 的额度单位不确定 → summary 不该出现多余空格
        com.wbgateway.Credits.Result noUnit = com.wbgateway.CreditsProbe.makeResult(
                2000000, "", "z|2000000||0", 0, "");
        String s2 = com.wbgateway.CreditsProbe.summary(noUnit);
        check("单位为空时不留尾随空格", !s2.endsWith(" ") && !s2.contains("  "), "[" + s2 + "]");

        System.out.println();
        System.out.println("=== 11. ZCode 令牌不能取错（这条错了就永远 401）===");
        // 实测：zcode_jwt → 200，bigmodel → 401。
        // 用户报「重新登录也没用」，根因就是这里取了 bigmodel 令牌。
        String zJwt = com.wbgateway.ZcodeTokenProbe.fakeZcodeJwt();
        String bigmodel = com.wbgateway.ZcodeTokenProbe.fakeBigmodelToken();

        // 正常情况：zcodeJwt 有值 → 用它
        Channel fresh = com.wbgateway.ZcodeTokenProbe.channel(
                LoginProviders.ZCODE, bigmodel, zJwt);
        String used = com.wbgateway.ZcodeTokenProbe.authToken(fresh);
        check("有 zcodeJwt 时用 zcodeJwt（不是 accessToken）",
                used.equals(zJwt) && !used.equals(bigmodel), "用的是 " + tag(used));

        // 老版本：zcodeJwt 空，但 accessToken 恰好是 zcode JWT → 认出来
        Channel legacy = com.wbgateway.ZcodeTokenProbe.channel(
                LoginProviders.ZCODE, zJwt, "");
        check("zcodeJwt 空但 accessToken 是 zcode JWT → 认出并用它",
                com.wbgateway.ZcodeTokenProbe.authToken(legacy).equals(zJwt), "");

        // 最坏情况：zcodeJwt 空、accessToken 是 bigmodel → 不能拿它去发请求
        Channel worst = com.wbgateway.ZcodeTokenProbe.channel(
                LoginProviders.ZCODE, bigmodel, "");
        String worstUsed = com.wbgateway.ZcodeTokenProbe.authToken(worst);
        check("两者都是 bigmodel 时不硬用（留空让上层提示重新登录）",
                worstUsed.isEmpty(), "[" + tag(worstUsed) + "]");

        // 识别函数本身
        check("能认出 ZCode JWT 特征（user_id + token_version）",
                !com.wbgateway.ZcodeTokenProbe.pickJwtFromAccess(zJwt).isEmpty(), "");
        check("不会把 bigmodel 令牌误认成 ZCode JWT",
                com.wbgateway.ZcodeTokenProbe.pickJwtFromAccess(bigmodel).isEmpty(), "");
        check("非 JWT 字符串不崩",
                com.wbgateway.ZcodeTokenProbe.pickJwtFromAccess("not-a-jwt").isEmpty(), "");
        check("空串不崩",
                com.wbgateway.ZcodeTokenProbe.pickJwtFromAccess("").isEmpty()
                        && com.wbgateway.ZcodeTokenProbe.pickJwtFromAccess(null).isEmpty(), "");

        System.out.println();
        System.out.println("=== 12. 自动签到必须真的跑（打开即签）===");
        // 用户报：打开 App 后行上仍是「点一下领取今日奖励」。
        // 两个根因：① 空配置时把「已跑过」置位了，之后再也不跑；
        //          ② 跑完只 toast 不刷新界面，行上永远显示旧文案。
        java.util.List<Channel> three = new java.util.ArrayList<>();
        for (String t : new String[]{LoginProviders.WB_INTL, LoginProviders.WB_CN,
                LoginProviders.QODER, LoginProviders.QODER_CN}) {
            Channel c = new Channel();
            c.id = t;
            c.loginType = t;
            c.kind = Channel.KIND_LOGIN;
            c.accessToken = "tok";
            three.add(c);
        }
        java.util.List<Channel> pendingAll = com.wbgateway.AutoCheckinProbe.selectPending(
                three, new java.util.HashSet<String>());
        check("四个可签渠道全部进自动签到",
                pendingAll.size() == 4, "选出 " + pendingAll.size() + " 个");
        // 空配置时不该「置位」（否则冷启动那一次被空列表消耗掉）
        java.util.List<Channel> pendingEmpty = com.wbgateway.AutoCheckinProbe.selectPending(
                new java.util.ArrayList<Channel>(), new java.util.HashSet<String>());
        check("空配置返回空（调用方据此不置位）", pendingEmpty.isEmpty(),
                "选出 " + pendingEmpty.size() + " 个");

        System.out.println();
        System.out.println("=== 结果：PASS " + pass + " / FAIL " + fail + " ===");
        if (fail > 0) System.exit(1);
    }

    /** 令牌太长，日志里只给前 12 位便于对照。 */
    static String tag(String token) {
        if (token == null || token.isEmpty()) return "(空)";
        return token.substring(0, Math.min(12, token.length())) + "…";
    }
}
