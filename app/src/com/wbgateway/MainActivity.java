package com.wbgateway;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 主界面（卡片式深色 UI）。
 *
 * 结构：
 *   ① 状态卡 —— 运行状态 + 启动/停止 + Base URL / API Key（可复制）
 *   ② 登录平台账号卡 —— 六个平台，扫码登录或粘贴 token
 *   ③ 已配置渠道卡 —— 模型清单、状态、管理入口
 *   ④ 设置卡 —— 端口 / 局域网
 *   ⑤ 日志卡 —— 可折叠
 *
 * ⚠ 全文件**没有任何匿名内部类**：build-tools 34 的 d8 处理匿名类时会崩
 * （`NullPointerException: String.length()`）。所有监听器 / Runnable 都是
 * 具名静态类 + WeakReference。
 */
public class MainActivity extends Activity {

    private Config config;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // 需要随状态刷新的视图
    private View statusDot;
    private TextView statusTitle;
    private TextView statusSub;
    private Button startBtn;
    private Button stopBtn;
    /** 局域网地址行（随开关与网络变化重建）。 */
    private View lanRow;
    private LinearLayout loginContainer;
    private LinearLayout channelContainer;
    private TextView logText;
    private LinearLayout logContainer;
    private TextView portValue;
    private TextView lanValue;
    /**
     * 当前展开模型清单的渠道 id 集合。
     *
     * 为什么存在 Activity 上而不是 Channel 里：`Config.channels()` 每次调用都
     * 从 SharedPreferences 反序列化出**新对象**，把展开状态挂在 Channel 上会在
     * 下一次刷新时丢失（点了「模型」没反应就是这个原因）。
     */
    private final java.util.Set<String> expandedChannels = new java.util.HashSet<>();
    /**
     * 积分缓存：渠道 id → 上次查询结果。
     *
     * 为什么缓存：积分查询要走网络（15 秒超时），每次刷新界面都去查会卡顿。
     * 改为「进入界面时用缓存 + 点一下才刷新」，符合「辅助信息」的定位。
     */
    private final java.util.Map<String, Credits.Result> creditsCache = new java.util.HashMap<>();
    /** 正在查积分的渠道 id（界面显示「查询中…」，也防重复提交）。 */
    private final java.util.Set<String> creditsRunning = new java.util.HashSet<>();
    /** 自动查积分是否已在本次前台周期跑过。 */
    private boolean creditsAutoStarted = false;
    /** 已展开积分明细的渠道 id（默认收起，避免 30+ 行资源包撑满界面）。 */
    private final java.util.Set<String> expandedCredits = new java.util.HashSet<>();
    /**
     * 签到结果缓存：渠道 id → 上次签到 / 查状态的结论。
     *
     * <p>为什么缓存：签到是**写操作**，不能每次刷新界面都调一次上游
     * （那等于每次进界面都点一次签到）。所以只在用户主动点击时执行，
     * 结果留在这里给界面显示。
     */
    private final java.util.Map<String, Checkin.Claim> checkinCache = new java.util.HashMap<>();
    /** 正在签到的渠道 id（防止连点重复提交）。 */
    private final java.util.Set<String> checkinRunning = new java.util.HashSet<>();
    /** 自动签到是否已在本次前台周期里跑过（避免 onResume 反复触发）。 */
    private boolean autoCheckinStarted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = new Config(this);
        // CodeArts 临时凭据续期后要落盘（否则下次启动又拿旧的过期凭据）
        CodeArtsLogin.renewListener = new RenewedChannelWriter(this);
        setContentView(buildRoot());
        // refreshAll 内部会先 rebuildPage 再填数据，所以这里只调它一次
        // （重复调会白白重建一遍视图）
        refreshAll();
        GatewayService.setUiListener(new UiListener(this));
        // 配置已就绪 → 这里再触发一次。
        // onResume 可能比 onCreate 早（或此时配置还没读出来），
        // 只靠 onResume 会漏掉「冷启动」这一次。
        maybeAutoCheckin();
        maybeAutoQueryCredits();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从后台切回来 = 用户又「打开」了一次 → 允许再签一次。
        // （首次启动时 paused 还是 false，不动标记，避免与 onCreate 连跑两遍）
        if (wentToBackground) {
            wentToBackground = false;
            autoCheckinStarted = false;
            creditsAutoStarted = false;
        }
        maybeAutoCheckin();
        maybeAutoQueryCredits();
    }

    @Override
    protected void onPause() {
        super.onPause();
        wentToBackground = true;
    }

    /** 是否经历过「退到后台」（用来区分冷启动与切回前台）。 */
    private boolean wentToBackground = false;

    /**
     * 需要时跑自动签到（**每次打开 / 回到前台都跑一次**）。
     *
     * <p>用户要求：打开应用就签到一次。所以不再按「今天做过没有」跳过，
     * 每次都问上游 —— 已签过的会返回 `already-claimed`，界面显示
     * 「今天已经签过了」，这本身是有效的确认。
     *
     * <p>防抖只做一层：`autoCheckinStarted` 防止 `onCreate` + `onResume`
     * 在同一次启动里连跑两遍；用户在 App 内切页不会重置它。
     * 从后台切回来（真正的新一次打开）会重置，从而再签一次。
     */
    private void maybeAutoCheckin() {
        if (autoCheckinStarted) return;
        java.util.List<Channel> targets = AutoCheckin.pending(this, config.channels());
        if (targets.isEmpty()) {
            // 没有目标就**不置位**：可能是配置还没加载完，等下一次 onResume 再试
            return;
        }
        autoCheckinStarted = true;
        for (Channel c : targets) checkinRunning.add(c.id);
        refreshLoginsIfVisible();
        new Thread(new AutoCheckinTask(this, targets), "auto-checkin").start();
    }

    /** 后台自动签到的任务。 */
    private static final class AutoCheckinTask implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final java.util.List<Channel> targets;

        AutoCheckinTask(MainActivity a, java.util.List<Channel> targets) {
            this.ref = new WeakReference<>(a);
            this.targets = targets;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            java.util.Map<String, Checkin.Claim> results = new java.util.LinkedHashMap<>();
            for (Channel c : targets) {
                Checkin.Claim r = Checkin.run(c);
                results.put(c.id, r);
                // 「成功 / 已签 / 无资格」都算已处理（不再重试）；
                // 只有真失败（网络 / 凭据）才留着重试 —— 否则一次抖动就整天不签。
                // ⚠ 无资格（inactive）必须记账，否则每次打开 App 都重打一轮上游。
                boolean settled = r.isSuccess() || "inactive".equals(r.kind);
                if (settled) AutoCheckin.markDone(a, c.id);
            }
            a.ui.post(new AutoCheckinDoneTask(a, results));
        }
    }

    /** 自动签到完成 → 落缓存、刷新界面、按需提示。 */
    private static final class AutoCheckinDoneTask implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final java.util.Map<String, Checkin.Claim> results;

        AutoCheckinDoneTask(MainActivity a, java.util.Map<String, Checkin.Claim> results) {
            this.ref = new WeakReference<>(a);
            this.results = results;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            for (java.util.Map.Entry<String, Checkin.Claim> e : results.entrySet()) {
                a.checkinRunning.remove(e.getKey());
                // 结果进缓存就够了：切到「账号」页时 refreshLogins() 会从缓存读，
                // 显示的是「今日已签到」而不是「点一下领取」。
                a.checkinCache.put(e.getKey(), e.getValue());
            }
            // 只在**已经停在账号页**时立即重绘。
            // 不在账号页时不做任何重绘：重建首页在启动瞬间会闪一下，
            // 而且没有意义 —— 缓存已经写好，切过去自然会看到最新状态。
            if (a.selectedTab == 1 && a.loginContainer != null) {
                a.refreshLogins();
            }
            String summary = AutoCheckin.summarize(results);
            // 全是「本来就签过 / 无资格」时不打扰（那是每天的正常状态）
            if (!summary.isEmpty()) {
                Toast.makeText(a, summary, Toast.LENGTH_LONG).show();
            }
        }
    }

    // -----------------------------------------------------------------------
    // 界面构建：底部导航 + 分类页
    // -----------------------------------------------------------------------

    /** 当前选中的分类（0=首页 1=账号 2=渠道 3=设置）。 */
    private int selectedTab = 0;
    /** 分类页容器：切页时只换这里面的内容。 */
    private LinearLayout pageContainer;
    /** 底部导航的四个按钮（用来刷新选中态）。 */
    private final java.util.List<TextView> tabViews = new java.util.ArrayList<>();
    /** 各分类的滚动位置，切回来时恢复（不让人每次都从头滚）。 */
    private final int[] tabScrollY = new int[4];

    private static final String[] TAB_TITLES = {"首页", "账号", "渠道", "设置"};

    /**
     * 根布局：上方标题 + 中间可滚动内容 + 底部固定导航。
     *
     * <p>为什么改成分类而不是继续堆一长条：原来五张卡片竖着排，
     * 手机上要滑两三屏才能到「设置」，日志更是几乎没人会翻到底。
     * 分类之后每个页面只放一件事，底部导航随时可切。
     *
     * <p>⚠ 内容区必须是 `weight=1` 的 ScrollView，不能再用
     * `fillViewport` 撑满：否则底部导航会被顶出屏幕。
     */
    private View buildRoot() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Ui.BG);

        // —— 标题 ——
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        header.setPadding(pad, Ui.dp(this, 14), pad, Ui.dp(this, 8));
        header.addView(Ui.pageTitle(this, "统一 API 网关"));
        TextView sub = Ui.caption(this, "登录各平台账号，或用 API Key 接上游，聚合成一个端点");
        sub.setPadding(0, Ui.dp(this, 4), 0, 0);
        header.addView(sub);
        shell.addView(header);

        // —— 内容区（随分类切换）——
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.setId(VIEW_ID_CONTENT);
        pageContainer = new LinearLayout(this);
        pageContainer.setOrientation(LinearLayout.VERTICAL);
        pageContainer.setPadding(pad, 0, pad, Ui.dp(this, 16));
        pageContainer.setClipToPadding(false);
        scroll.addView(pageContainer);
        shell.addView(scroll);

        // —— 底部导航 ——
        shell.addView(buildBottomNav());

        return shell;
    }

    /** 内容 ScrollView 的 id（用代码生成，避免动 res/values）。 */
    private static final int VIEW_ID_CONTENT = 0x7f0f0001;

    /** 底部四分类导航。 */
    private View buildBottomNav() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Ui.CARD);
        bar.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));

        // 顶部一条细描边，和内容区分开
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Ui.CARD);
        bar.setBackground(bg);

        tabViews.clear();
        for (int i = 0; i < TAB_TITLES.length; i += 1) {
            TextView tab = new TextView(this);
            tab.setText(TAB_TITLES[i]);
            tab.setGravity(android.view.Gravity.CENTER);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tab.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            tab.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tab.setOnClickListener(new TabClickListener(this, i));
            tabViews.add(tab);
            bar.addView(tab);
        }
        updateTabStyles();

        // 固定高度，避免内容变化时导航条跳动
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 54)));
        return bar;
    }

    /** 刷新底部导航的选中态（选中=亮色加粗，未选=灰）。 */
    private void updateTabStyles() {
        for (int i = 0; i < tabViews.size(); i += 1) {
            TextView tab = tabViews.get(i);
            boolean on = i == selectedTab;
            tab.setTextColor(on ? Ui.BLUE : Ui.MUTED);
            tab.setTypeface(Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    /** 切换分类：记住当前页滚动位置、换内容、恢复目标页位置。 */
    private void selectTab(int index) {
        if (index < 0 || index >= TAB_TITLES.length) return;
        if (index == selectedTab && pageContainer != null && pageContainer.getChildCount() > 0) {
            return;
        }
        // 记住离开时的滚动位置
        ScrollView sv = findViewById(VIEW_ID_CONTENT);
        if (sv != null) tabScrollY[selectedTab] = sv.getScrollY();

        selectedTab = index;
        updateTabStyles();

        // ⚠ 建完视图还必须**填一次数据**：rebuildPage 只把卡片骨架建出来，
        // 里面的列表（登录入口 / 渠道清单）要靠 refreshXxx 填充。
        // 少了这一步的表现是「切过去只有标题和说明文字，列表是空的」。
        refreshAll();

        if (sv != null) {
            final int target = Math.max(0, tabScrollY[index]);
            sv.post(new ScrollRestoreTask(sv, target));
        }
    }

    /** 恢复滚动位置（等布局完成后再设，否则会被重置为 0）。 */
    private static final class ScrollRestoreTask implements Runnable {
        private final ScrollView scroll;
        private final int y;

        ScrollRestoreTask(ScrollView scroll, int y) {
            this.scroll = scroll;
            this.y = y;
        }

        @Override
        public void run() {
            if (scroll != null) scroll.scrollTo(0, y);
        }
    }

    private static final class TabClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;
        private final int index;

        TabClickListener(MainActivity a, int index) {
            this.ref = new WeakReference<>(a);
            this.index = index;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a != null) a.selectTab(index);
        }
    }

    /**
     * 重建某一分类的内容。
     *
     * <p>⚠ 用 if-else 而不是 switch：对 int 做 switch 时 javac 会生成
     * 合成类（`MainActivity$N`），build-tools 34 的 d8 碰到它就崩
     * （`NullPointerException: String.length()`）。项目里已有多处踩过这个坑。
     */
    private void rebuildPage(int index) {
        if (pageContainer == null) return;
        pageContainer.removeAllViews();
        if (index == 0) {
            pageContainer.addView(buildStatusCard());
            pageContainer.addView(buildLogCard());
            bindLogView();   // 把切页期间攒下的日志回填进视图
        } else if (index == 1) {
            pageContainer.addView(buildLoginCard());
        } else if (index == 2) {
            pageContainer.addView(buildChannelsCard());
        } else if (index == 3) {
            pageContainer.addView(buildSettingsCard());
        }
    }

    /** ① 状态卡。 */
    private View buildStatusCard() {
        LinearLayout card = Ui.card(this);

        LinearLayout statusRow = Ui.row(this);
        statusDot = Ui.dot(this, Ui.MUTED);
        statusRow.addView(statusDot);

        LinearLayout statusTexts = new LinearLayout(this);
        statusTexts.setOrientation(LinearLayout.VERTICAL);
        statusTexts.setLayoutParams(Ui.weight1());
        statusTitle = Ui.body(this, "已停止");
        statusTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusSub = Ui.caption(this, "点「启动」让网关在后台常驻");
        statusTexts.addView(statusTitle);
        statusTexts.addView(statusSub);
        statusRow.addView(statusTexts);

        card.addView(statusRow);

        // 按钮单独一行：窄屏上与状态文字并排会被压成两行
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, Ui.dp(this, 10), 0, 0);
        startBtn = Ui.button(this, "启动网关", Ui.GREEN);
        startBtn.setOnClickListener(new StartClickListener(this));
        stopBtn = Ui.button(this, "停止", 0);
        stopBtn.setOnClickListener(new StopClickListener(this));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnRow.addView(startBtn, btnLp);
        LinearLayout.LayoutParams btnLp2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnLp2.leftMargin = Ui.dp(this, 8);
        btnRow.addView(stopBtn, btnLp2);
        card.addView(btnRow);

        card.addView(Ui.divider(this));

        card.addView(valueRow("Base URL", new EndpointValue(this)));
        card.addView(valueRow("API Key", new ApiKeyValue(this)));

        // 局域网地址：只在开启「允许局域网访问」时显示。
        //
        // 为什么必须显示：`127.0.0.1` 是**本机回环**，另一台设备填了必然连不上。
        // 之前界面只给 127.0.0.1，用户拿别的手机试只会失败，且不知道为什么。
        lanRow = buildLanAddressRow();
        card.addView(lanRow);

        TextView hint = Ui.caption(this,
                "在其他 AI 应用里把供应商类型选「OpenAI 兼容」，填入上面两行。");
        hint.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(hint);

        return card;
    }

    /**
     * 构建「局域网地址」行，供**其他设备**填 Base URL。
     *
     * <p>三种状态分别处理，不留空白让用户猜：
     * <ol>
     *   <li>未开启局域网 → 提示去设置里打开；</li>
     *   <li>已开启但拿不到地址（没连 Wi-Fi、没开共享）→ 如实说；</li>
     *   <li>拿到地址 → 显示完整 URL + 复制按钮；有多个候选时列出来，
     *       因为 USB 共享和 Wi-Fi 的地址不一样，用户得挑对的那个。</li>
     * </ol>
     */
    private View buildLanAddressRow() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));

        if (!config.lan()) {
            TextView off = Ui.caption(this, "局域网未开启：其他设备无法访问。到「设置」里打开「允许局域网访问」。");
            off.setTextColor(Ui.MUTED);
            box.addView(off);
            return box;
        }

        java.util.List<LanAddress.Candidate> all = LanAddress.list();
        if (all.isEmpty()) {
            TextView none = Ui.caption(this,
                    "已开启局域网，但当前没有可用地址 —— 请连接 Wi-Fi 或开启 USB 网络共享。");
            none.setTextColor(Ui.YELLOW);
            box.addView(none);
            return box;
        }

        TextView title = Ui.caption(this, "局域网地址（其他设备填这个）");
        box.addView(title);

        // 最多列 3 个，避免蜂窝/虚拟网卡把界面撑爆
        int shown = 0;
        for (LanAddress.Candidate cand : all) {
            if (shown >= 3) break;
            shown += 1;

            LinearLayout row = Ui.row(this);
            row.setPadding(0, Ui.dp(this, 2), 0, 0);

            String url = "http://" + cand.ip + ":" + config.port() + "/v1";
            TextView value = Ui.mono(this, url);
            value.setLayoutParams(Ui.weight1());
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            row.addView(value);

            Button copy = Ui.linkButton(this, "复制", Ui.BLUE);
            copy.setOnClickListener(new CopyClickListener(this, new FixedValue(url)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = Ui.dp(this, 6);
            row.addView(copy, lp);
            box.addView(row);

            // 标注网卡来源；蜂窝地址别的设备连不上，必须说明
            String note = cand.iface + (LanAddress.looksReachable(cand.iface)
                    ? "" : "（该网卡通常无法被其他设备访问）");
            TextView cap = Ui.caption(this, note);
            cap.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            cap.setTextColor(LanAddress.looksReachable(cand.iface) ? Ui.MUTED : Ui.YELLOW);
            cap.setPadding(Ui.dp(this, 62), 0, 0, 0);
            box.addView(cap);
        }
        return box;
    }

    /** 一行「标签 + 等宽值 + 复制按钮」。 */
    private View valueRow(String label, ValueSource source) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));

        TextView tag = Ui.caption(this, label);
        tag.setMinWidth(Ui.dp(this, 62));
        row.addView(tag);

        TextView value = Ui.mono(this, source.get());
        value.setLayoutParams(Ui.weight1());
        value.setTextIsSelectable(false);
        // API Key 有 40+ 字符，固定 13sp 在窄屏上会从中间断行；
        // 自动缩小字号 + 单行，才能保持「一眼能看全、可整段选中」。
        value.setSingleLine(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            value.setAutoSizeTextTypeUniformWithConfiguration(8, 13, 1, TypedValue.COMPLEX_UNIT_SP);
        } else {
            // API 24/25 没有自适应字号：退回小字号 + 省略中间
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        }
        row.addView(value);

        Button copy = Ui.linkButton(this, "复制", Ui.BLUE);
        copy.setOnClickListener(new CopyClickListener(this, source));
        row.addView(copy);

        return row;
    }

    /** ② 登录平台账号卡。 */
    private View buildLoginCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "登录平台账号"));

        TextView hint = Ui.caption(this,
                "七个渠道都支持在 App 内直接登录：\n"
                        + "· WorkBuddy 两区域：打开登录页，扫码或账号登录后自动识别\n"
                        + "· Qoder / Qoder 中国版：打开登录页，选完账号自动完成\n"
                        + "· ZCode：App 内页面登录，自动截获 zcode:// 回调\n"
                        + "· Raccoon：在 App 内页面里用手机号 + 短信验证码登录\n"
                        + "· CodeArts：App 内页面登录华为账号，自动换临时凭据并续期");
        hint.setPadding(0, 0, 0, Ui.dp(this, 8));
        card.addView(hint);

        // 一键签到：能签的渠道逐个点太麻烦，而且「今天签没签」用户不会记得
        LinearLayout checkinRow = Ui.row(this);
        checkinRow.setPadding(0, 0, 0, Ui.dp(this, 4));
        TextView checkinHint = Ui.caption(this, "每日自动签到（WorkBuddy / Qoder / ZCode / CodeArts），每次打开 App 补签一次");
        checkinHint.setLayoutParams(Ui.weight1());
        checkinHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        checkinRow.addView(checkinHint);
        Button checkinAll = Ui.linkButton(this, "一键签到", Ui.BLUE);
        checkinAll.setOnClickListener(new CheckinAllClickListener(this));
        checkinRow.addView(checkinAll);
        card.addView(checkinRow);

        loginContainer = new LinearLayout(this);
        loginContainer.setOrientation(LinearLayout.VERTICAL);
        card.addView(loginContainer);

        return card;
    }

    /** 「一键签到」按钮。 */
    private static final class CheckinAllClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;

        CheckinAllClickListener(MainActivity a) {
            this.ref = new WeakReference<>(a);
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a != null) a.startCheckinAll();
        }
    }

    /** ③ 已配置渠道卡。 */
    private View buildChannelsCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "已配置渠道"));

        channelContainer = new LinearLayout(this);
        channelContainer.setOrientation(LinearLayout.VERTICAL);
        card.addView(channelContainer);

        Button add = Ui.button(this, "+ 添加 API Key 渠道", 0);
        add.setOnClickListener(new AddChannelClickListener(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        card.addView(add, lp);

        return card;
    }

    /** ④ 设置卡。 */
    private View buildSettingsCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "设置"));

        LinearLayout portRow = Ui.row(this);
        TextView portLabel = Ui.body(this, "监听端口");
        portLabel.setLayoutParams(Ui.weight1());
        portRow.addView(portLabel);
        portValue = Ui.mono(this, String.valueOf(config.port()));
        portRow.addView(portValue);
        Button editPort = Ui.linkButton(this, "修改", Ui.BLUE);
        editPort.setOnClickListener(new EditPortClickListener(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 8);
        portRow.addView(editPort, lp);
        card.addView(portRow);

        LinearLayout lanRow = Ui.row(this);
        lanRow.setPadding(0, Ui.dp(this, 8), 0, 0);
        LinearLayout lanTexts = new LinearLayout(this);
        lanTexts.setOrientation(LinearLayout.VERTICAL);
        lanTexts.setLayoutParams(Ui.weight1());
        lanTexts.addView(Ui.body(this, "允许局域网访问"));
        lanTexts.addView(Ui.caption(this, "关闭时只监听 127.0.0.1（更安全）"));
        lanRow.addView(lanTexts);
        lanValue = Ui.caption(this, config.lan() ? "已开启" : "已关闭");
        lanValue.setTextColor(config.lan() ? Ui.GREEN : Ui.MUTED);
        lanRow.addView(lanValue);
        Button toggleLan = Ui.linkButton(this, "切换", Ui.BLUE);
        toggleLan.setOnClickListener(new ToggleLanClickListener(this));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.leftMargin = Ui.dp(this, 8);
        lanRow.addView(toggleLan, lp2);
        card.addView(lanRow);

        TextView note = Ui.caption(this, "改端口或局域网开关后需要重启网关生效。");
        note.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(note);

        // ---- 后台保活 ----
        //
        // 为什么单独一块：前台服务只能挡住「普通后台回收」，挡不住
        // 电池优化 / 厂商自启动策略 —— 那两层是**系统策略**，App 自己改不了，
        // 只能带用户去对应设置页。所以这里如实显示当前状态并给跳转入口，
        // 而不是假装「已保活」。
        LinearLayout keepTitle = Ui.row(this);
        keepTitle.setPadding(0, Ui.dp(this, 16), 0, 0);
        TextView kt = Ui.body(this, "后台保活");
        kt.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        keepTitle.addView(kt);
        card.addView(keepTitle);

        // 电池优化状态（这条最影响「息屏后还能不能用」）
        LinearLayout battRow = Ui.row(this);
        battRow.setPadding(0, Ui.dp(this, 8), 0, 0);
        LinearLayout battTexts = new LinearLayout(this);
        battTexts.setOrientation(LinearLayout.VERTICAL);
        battTexts.setLayoutParams(Ui.weight1());
        battTexts.addView(Ui.body(this, "忽略电池优化"));
        boolean ignoring = KeepAlive.isIgnoringBatteryOptimizations(this);
        battTexts.addView(Ui.caption(this, ignoring
                ? "已加入白名单，息屏后不会被冻结"
                : "未加入白名单，息屏几分钟后可能断连"));
        battRow.addView(battTexts);
        TextView battValue = Ui.caption(this, ignoring ? "已开启" : "未开启");
        battValue.setTextColor(ignoring ? Ui.GREEN : Ui.YELLOW);
        battRow.addView(battValue);
        Button battBtn = Ui.linkButton(this, ignoring ? "已设置" : "去设置", Ui.BLUE);
        battBtn.setOnClickListener(new KeepAliveClickListener(this, KeepAliveClickListener.BATTERY));
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp3.leftMargin = Ui.dp(this, 8);
        battRow.addView(battBtn, lp3);
        card.addView(battRow);

        // 厂商自启动（国产 ROM 的必配项）
        LinearLayout autoRow = Ui.row(this);
        autoRow.setPadding(0, Ui.dp(this, 8), 0, 0);
        LinearLayout autoTexts = new LinearLayout(this);
        autoTexts.setOrientation(LinearLayout.VERTICAL);
        autoTexts.setLayoutParams(Ui.weight1());
        autoTexts.addView(Ui.body(this, "自启动 / 后台运行"));
        autoTexts.addView(Ui.caption(this, "国产 ROM 需要手动允许，否则划掉任务后会被清理"));
        autoRow.addView(autoTexts);
        Button autoBtn = Ui.linkButton(this, "去设置", Ui.BLUE);
        autoBtn.setOnClickListener(new KeepAliveClickListener(this, KeepAliveClickListener.AUTOSTART));
        LinearLayout.LayoutParams lp4 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp4.leftMargin = Ui.dp(this, 8);
        autoRow.addView(autoBtn, lp4);
        card.addView(autoRow);

        // 厂商专属路径提示（各家菜单名不一样，直接说清去哪点最省事）
        TextView hint = Ui.caption(this, KeepAlive.autoStartHint());
        hint.setPadding(0, Ui.dp(this, 8), 0, 0);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        card.addView(hint);

        return card;
    }

    /** ⑤ 日志卡（点「收起」折叠）。 */
    private View buildLogCard() {
        LinearLayout card = Ui.card(this);

        LinearLayout head = Ui.row(this);
        TextView title = Ui.sectionTitle(this, "日志");
        title.setPadding(0, 0, 0, 0);
        title.setLayoutParams(Ui.weight1());
        head.addView(title);
        TextView toggle = Ui.caption(this, "收起");
        head.addView(toggle);
        card.addView(head);

        logContainer = new LinearLayout(this);
        logContainer.setOrientation(LinearLayout.VERTICAL);
        logText = new TextView(this);
        logText.setTextColor(Ui.MUTED);
        logText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setLineSpacing(Ui.dp(this, 2), 1f);
        logText.setText("（暂无）");
        logContainer.addView(logText);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 6);
        card.addView(logContainer, lp);

        Button clear = Ui.linkButton(this, "清空", Ui.MUTED);
        clear.setOnClickListener(new ClearLogClickListener(this));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = Ui.dp(this, 8);
        card.addView(clear, lp2);

        toggle.setOnClickListener(new LogToggleClickListener(this, toggle));
        return card;
    }

    // -----------------------------------------------------------------------
    // 刷新
    // -----------------------------------------------------------------------

    /**
     * 按当前分类刷新界面。
     *
     * <p>⚠ 分类改造带来的关键差别：**只有当前可见的那一页的视图存在**，
     * 其余页的字段（statusTitle / loginContainer / channelContainer …）是 null。
     * 所以刷新前先确认它们属于当前页，否则会 NPE 或刷到已经不存在的视图上。
     *
     * <p>做法：当前页先重建（拿到新视图），再逐个刷新；其它页的字段置空，
     * 等切过去时 `rebuildPage` + `refreshAll` 再填。
     */
    private void refreshAll() {
        if (pageContainer == null) return;
        // 清空旧视图引用，避免刷到已经 detach 的控件上
        statusDot = null;
        statusTitle = null;
        statusSub = null;
        startBtn = null;
        stopBtn = null;
        loginContainer = null;
        channelContainer = null;
        portValue = null;
        lanValue = null;
        logText = null;
        logContainer = null;

        rebuildPage(selectedTab);

        if (selectedTab == 0) {
            refreshStatus();
        } else if (selectedTab == 1) {
            refreshLogins();
            // 进「账号」页就自动查一轮积分，不必逐个点
            maybeAutoQueryCredits();
        } else if (selectedTab == 2) {
            refreshChannels();
        } else {
            refreshSettings();
        }
    }

    /**
     * 局部刷新登录列表（不重建整页）。
     *
     * <p>为什么需要它：签到 / 积分这类操作完成时只想更新一行文字，
     * 走 `refreshAll` 会把整页拆了重建 —— 滚动位置丢失、正在展开的对话框也会闪。
     */
    private void refreshLoginsIfVisible() {
        if (selectedTab == 1 && loginContainer != null) refreshLogins();
    }

    private void refreshStatus() {
        Gateway gw = GatewayService.getGateway();
        boolean running = gw != null && gw.isRunning();
        if (statusTitle == null) return;
        statusTitle.setText(running ? "运行中" : "已停止");
        statusSub.setText(running
                ? "端口 " + config.port() + (config.lan() ? " · 局域网可访问" : " · 仅本机")
                : "点「启动」让网关在后台常驻");
        statusDot.setBackground(Ui.circle(running ? Ui.GREEN : Ui.MUTED));
        startBtn.setEnabled(!running);
        stopBtn.setEnabled(running);
    }

    private void refreshSettings() {
        if (portValue == null) return;
        portValue.setText(String.valueOf(config.port()));
        lanValue.setText(config.lan() ? "已开启" : "已关闭");
        lanValue.setTextColor(config.lan() ? Ui.GREEN : Ui.MUTED);
    }

    /**
     * 积分 / 额度行的文字。
     *
     * <p>只显示**数字 + 到期时间**，例如：
     * <pre>
     * 积分：4,300 credits · 2026-11-05 到期
     * 额度：还剩 2,000（ZCode）
     * </pre>
     *
     * <p>不写「N 个资源包」：那是内部记账单位，用户只关心剩多少、何时过期
     * （要看明细点「明细」）。
     *
     * <p>ZCode 用「额度」而不是「积分」：它给的是 zcode-plan 订阅额度，
     * 说「积分」会让人误以为是可累积的积分。
     */
    private String creditsText(Channel channel) {
        boolean isZcode = LoginProviders.ZCODE.equals(channel.loginType);
        boolean isCodeArts = LoginProviders.CODEARTS.equals(channel.loginType);
        String label = isZcode ? "额度：" : "积分：";
        if (creditsRunning.contains(channel.id)) return label + "查询中…";
        Credits.Result r = creditsCache.get(channel.id);
        if (r == null) return label + "点一下可重查";
        if (!r.ok) return label + r.message;

        String brief;
        if (isCodeArts) {
            // CodeArts 是**积分机制**：显示「剩余积分」+ 套餐名 + 重置日。
            // 华为的积分按套餐周期重置，所以这里把周期结束日标成「重置」而不是「到期」。
            brief = label + "剩余 " + Credits.formatNumber(r.total) + " 积分";
            if (!r.packages.isEmpty() && !r.packages.get(0).name.isEmpty()) {
                brief += " · " + r.packages.get(0).name;
            }
            if (r.nearestExpiry > 0) {
                brief += " · " + Credits.formatDate(r.nearestExpiry) + " 重置";
            }
            // 总量/已用/即将过期这些明细放在下一行（点明细可见）
            if (!r.message.isEmpty()) brief += "\n" + r.message;
            return brief;
        }
        if (isZcode) {
            // ZCode：只显示 Start Plan 的剩余额度（用户视角）。
            // 额度是 token 数，量级大，用 1.2M / 340K 这种写法更好读。
            // 为 0 时也照常显示「还剩 0」，不换成解释文案。
            brief = label + "还剩 " + compactUnits(r.total);
            if (r.nearestExpiry > 0) {
                brief += " · " + Credits.formatDate(r.nearestExpiry) + " 到期";
            }
        } else {
            brief = label + r.summary();
        }
        // ZCode 只要一行「还剩多少」，不挂 [明细]（它本来就是单一额度桶）
        // ZCode / CodeArts 都是「一行说清」（单一额度桶），不挂 [明细]
        if (isZcode || isCodeArts) return brief;
        if (!expandedCredits.contains(channel.id)) return brief + "  [明细]";
        String detail = r.detail();
        return detail.isEmpty() ? brief : brief + "  [收起]\n" + detail;
    }

    private int creditsColor(Channel channel) {
        if (creditsRunning.contains(channel.id)) return Ui.MUTED;
        Credits.Result r = creditsCache.get(channel.id);
        if (r == null) return Ui.BLUE;
        // 失败标红；成功标绿。凭据过期属于失败（ok=false），会显示成红色提示
        return r.ok ? Ui.GREEN : Ui.RED;
    }

    /**
     * 大数字压缩显示：`1.2M` / `340K` / `1200`。
     *
     * <p>ZCode 的额度是 token 数（常见 20 万～200 万），原样显示
     * 「还剩 2000000」占满一行且难读。这里只做**展示**压缩，实际数值不变。
     */
    private static String compactUnits(double v) {
        if (v >= 1000000) {
            double m = v / 1000000.0;
            return (m == Math.floor(m) ? String.valueOf((long) m) : String.format(java.util.Locale.US, "%.1f", m)) + "M";
        }
        if (v >= 1000) {
            double k = v / 1000.0;
            return (k == Math.floor(k) ? String.valueOf((long) k) : String.format(java.util.Locale.US, "%.1f", k)) + "K";
        }
        return Credits.formatNumber(v);
    }

    // -----------------------------------------------------------------------
    // 签到
    // -----------------------------------------------------------------------

    /** 签到行的文字。 */
    private String checkinText(Channel channel) {
        if (checkinRunning.contains(channel.id)) return "签到：正在领取…";
        Checkin.Claim c = checkinCache.get(channel.id);
        if (c != null) {
            if (c.isClaimed()) return "签到：" + c.message;
            if ("already-claimed".equals(c.kind)) return "签到：" + c.message;
            if ("inactive".equals(c.kind)) return "签到：" + c.message;
            return "签到：" + c.message + "  [重试]";
        }
        return "签到：点一下领取今日奖励";
    }

    /** 签到行的颜色：已签 / 可签 / 失败分色，一眼能看出状态。 */
    private int checkinColor(Channel channel) {
        if (checkinRunning.contains(channel.id)) return Ui.MUTED;
        Checkin.Claim c = checkinCache.get(channel.id);
        if (c == null) return Ui.BLUE;
        if (c.isClaimed()) return Ui.GREEN;
        if ("already-claimed".equals(c.kind)) return Ui.MUTED;
        if ("inactive".equals(c.kind)) return Ui.MUTED;
        return Ui.RED;
    }

    /** 点签到行 → 后台领取。 */
    private static final class CheckinClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;
        private final Channel channel;

        CheckinClickListener(MainActivity a, Channel channel) {
            this.ref = new WeakReference<>(a);
            this.channel = channel;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a == null) return;
            if (!a.checkinRunning.add(channel.id)) return;   // 已在跑，忽略连点
            a.refreshLoginsIfVisible();
            new Thread(new CheckinTask(a, channel), "checkin").start();
        }
    }

    /** 后台签到（单渠道）。 */
    private static final class CheckinTask implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final Channel channel;

        CheckinTask(MainActivity a, Channel channel) {
            this.ref = new WeakReference<>(a);
            this.channel = channel;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            Checkin.Claim result = Checkin.run(channel);
            a.ui.post(new CheckinDoneTask(a, channel.id, result, null));
        }
    }

    /** 签到完成 → 落缓存、刷新界面、提示。 */
    private static final class CheckinDoneTask implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final String channelId;
        private final Checkin.Claim result;
        private final String batchSummary;

        CheckinDoneTask(MainActivity a, String channelId, Checkin.Claim result, String batchSummary) {
            this.ref = new WeakReference<>(a);
            this.channelId = channelId;
            this.result = result;
            this.batchSummary = batchSummary;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            if (channelId != null) {
                a.checkinRunning.remove(channelId);
                if (result != null) a.checkinCache.put(channelId, result);
            }
            a.refreshLoginsIfVisible();
            String msg = batchSummary != null
                    ? batchSummary
                    : (result == null ? "签到结束" : result.message);
            if (msg != null && !msg.isEmpty()) {
                Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 一键签到：把所有已登录、且支持签到的渠道跑一遍。
     *
     * <p>为什么要有：六个渠道里能签的只有四个，逐个点很烦；
     * 而且「今天签没签」这种事用户不会记得。一次全扫过去最省事。
     */
    private void startCheckinAll() {
        java.util.List<Channel> targets = new java.util.ArrayList<>();
        for (Channel c : config.channels()) {
            if (!Channel.KIND_LOGIN.equals(c.kind)) continue;
            if (!c.hasCredential()) continue;
            if (!Checkin.supported(c.loginType)) continue;
            if (checkinRunning.contains(c.id)) continue;
            targets.add(c);
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, "没有可签到的已登录渠道", Toast.LENGTH_SHORT).show();
            return;
        }
        for (Channel c : targets) checkinRunning.add(c.id);
        refreshLoginsIfVisible();
        new Thread(new CheckinAllTask(this, targets), "checkin-all").start();
    }

    /** 后台批量签到。 */
    private static final class CheckinAllTask implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final java.util.List<Channel> targets;

        CheckinAllTask(MainActivity a, java.util.List<Channel> targets) {
            this.ref = new WeakReference<>(a);
            this.targets = targets;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            int claimed = 0;
            int already = 0;
            int failed = 0;
            java.util.Map<String, Checkin.Claim> results = new java.util.LinkedHashMap<>();
            for (Channel c : targets) {
                Checkin.Claim r = Checkin.run(c);
                results.put(c.id, r);
                if (r.isClaimed()) claimed += 1;
                else if ("already-claimed".equals(r.kind)) already += 1;
                else failed += 1;
            }
            StringBuilder sb = new StringBuilder();
            if (claimed > 0) sb.append(claimed).append(" 个签到成功");
            if (already > 0) sb.append(sb.length() > 0 ? "，" : "").append(already).append(" 个今天已签");
            if (failed > 0) sb.append(sb.length() > 0 ? "，" : "").append(failed).append(" 个未成功");
            if (sb.length() == 0) sb.append("没有可签到的渠道");
            a.ui.post(new CheckinAllDoneTask(a, results, sb.toString()));
        }
    }

    /** 批量签到完成 → 全部落缓存并刷新。 */
    private static final class CheckinAllDoneTask implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final java.util.Map<String, Checkin.Claim> results;
        private final String summary;

        CheckinAllDoneTask(MainActivity a, java.util.Map<String, Checkin.Claim> results, String summary) {
            this.ref = new WeakReference<>(a);
            this.results = results;
            this.summary = summary;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            for (java.util.Map.Entry<String, Checkin.Claim> e : results.entrySet()) {
                a.checkinRunning.remove(e.getKey());
                a.checkinCache.put(e.getKey(), e.getValue());
            }
            a.refreshLoginsIfVisible();
            Toast.makeText(a, summary, Toast.LENGTH_LONG).show();
        }
    }

    /** 点积分那一行 → 后台查询并刷新。 */
    private static final class RefreshCreditsClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;
        private final Channel channel;

        RefreshCreditsClickListener(MainActivity a, Channel channel) {
            this.ref = new WeakReference<>(a);
            this.channel = channel;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a == null) return;
            Credits.Result cached = a.creditsCache.get(channel.id);
            if (cached != null && cached.ok) {
                // 已有数据 → 切换明细展开/收起
                if (!a.expandedCredits.add(channel.id)) a.expandedCredits.remove(channel.id);
                a.refreshLoginsIfVisible();
                return;
            }
            // 没数据或上次失败 → 重新查询
            a.creditsCache.remove(channel.id);
            a.refreshLoginsIfVisible();
            new Thread(new CreditsTask(a, channel), "credits").start();
        }
    }

    /**
     * 自动查询所有已登录渠道的积分 / 额度。
     *
     * <p>为什么要有：原来积分必须是「点一下才查」，用户进「账号」页看到的是
     * 「积分：点击查询」——六个渠道就要点六次。改成进页面时自动查一轮，
     * 结果进缓存后界面直接显示数字。
     *
     * <p>只在**没有缓存**时查（`creditsCache` 里已有就不重复打），
     * 且每个渠道只查一次；同一次前台周期内不重复触发（`creditsAutoStarted`）。
     */
    private void maybeAutoQueryCredits() {
        if (creditsAutoStarted) return;
        creditsAutoStarted = true;
        java.util.List<Channel> targets = new java.util.ArrayList<>();
        for (Channel c : config.channels()) {
            if (!Channel.KIND_LOGIN.equals(c.kind)) continue;
            if (!c.hasCredential()) continue;
            if (!Credits.supported(c.loginType)) continue;
            if (creditsCache.containsKey(c.id)) continue;   // 已有结果，不必再查
            targets.add(c);
        }
        if (targets.isEmpty()) return;
        for (Channel c : targets) creditsRunning.add(c.id);
        refreshLoginsIfVisible();
        new Thread(new AutoCreditsTask(this, targets), "auto-credits").start();
    }

    /** 后台批量查积分。 */
    private static final class AutoCreditsTask implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final java.util.List<Channel> targets;

        AutoCreditsTask(MainActivity a, java.util.List<Channel> targets) {
            this.ref = new WeakReference<>(a);
            this.targets = targets;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            java.util.Map<String, Credits.Result> results = new java.util.LinkedHashMap<>();
            for (Channel c : targets) {
                results.put(c.id, Credits.query(c));
            }
            a.ui.post(new AutoCreditsDoneTask(a, results));
        }
    }

    /** 批量查积分完成 → 落缓存并刷新。 */
    private static final class AutoCreditsDoneTask implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final java.util.Map<String, Credits.Result> results;

        AutoCreditsDoneTask(MainActivity a, java.util.Map<String, Credits.Result> results) {
            this.ref = new WeakReference<>(a);
            this.results = results;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            for (java.util.Map.Entry<String, Credits.Result> e : results.entrySet()) {
                a.creditsRunning.remove(e.getKey());
                a.creditsCache.put(e.getKey(), e.getValue());
            }
            a.refreshLoginsIfVisible();
        }
    }

    /** 后台查积分。 */
    private static final class CreditsTask implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final Channel channel;

        CreditsTask(MainActivity a, Channel channel) {
            this.ref = new WeakReference<>(a);
            this.channel = channel;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            Credits.Result result = Credits.query(channel);
            a.creditsCache.put(channel.id, result);
            a.ui.post(new CreditsDoneTask(a, result));
        }
    }

    /** 查询完成 → 刷新界面 + 提示。 */
    private static final class CreditsDoneTask implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final Credits.Result result;

        CreditsDoneTask(MainActivity a, Credits.Result result) {
            this.ref = new WeakReference<>(a);
            this.result = result;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            a.refreshLoginsIfVisible();
            if (!result.ok) {
                Toast.makeText(a, result.message, Toast.LENGTH_SHORT).show();
            }
        }
    }
    private void refreshLogins() {
        if (loginContainer == null) return;
        loginContainer.removeAllViews();
        for (Map.Entry<String, LoginProviders.Spec> entry : LoginProviders.allSpecs().entrySet()) {
            String loginType = entry.getKey();
            LoginProviders.Spec spec = entry.getValue();
            Channel loggedIn = findChannelByLoginType(loginType);

            // 每个渠道一个纵向块：上面是名称与状态，下面是操作按钮。
            // 为什么不放在同一行：右侧已经要放「管理 / 登录」，再加「登录页」
            // 在窄屏上会把文字压成两行甚至截断。
            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));

            LinearLayout row = Ui.row(this);
            row.addView(Ui.dot(this, loggedIn != null ? Ui.GREEN : Ui.BORDER));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setLayoutParams(Ui.weight1());
            texts.addView(Ui.body(this, spec.displayName));
            if (loggedIn != null) {
                // 昵称等于渠道名时不重复显示（取不到真实昵称时会回填成渠道名，
                // 拼出来是「Qoder（中国版） · Qoder（中国版） · 模型 17 个」）
                boolean nickUseful = !loggedIn.nickname.isEmpty()
                        && !loggedIn.nickname.equals(spec.displayName)
                        && !loggedIn.nickname.equals(loginType);
                String who = nickUseful ? loggedIn.nickname : "已登录";
                texts.addView(Ui.caption(this, who + " · 模型 " + loggedIn.effectiveModels().size() + " 个"));
                // 积分：有缓存就显示，没有就显示「点击刷新」；不支持查询的渠道如实说明
                if (Credits.supported(loggedIn.loginType)) {
                    TextView creditsView = Ui.caption(this, creditsText(loggedIn));
                    creditsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                    creditsView.setTextColor(creditsColor(loggedIn));
                    creditsView.setOnClickListener(new RefreshCreditsClickListener(this, loggedIn));
                    texts.addView(creditsView);
                } else {
                    TextView unsupported = Ui.caption(this, Credits.unsupportedReason(loggedIn.loginType));
                    unsupported.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                    texts.addView(unsupported);
                }

                // 签到：只有真支持每日签到的渠道才显示（Raccoon 如实说不支持）
                if (Checkin.supported(loggedIn.loginType)) {
                    TextView checkinView = Ui.caption(this, checkinText(loggedIn));
                    checkinView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                    checkinView.setTextColor(checkinColor(loggedIn));
                    checkinView.setOnClickListener(new CheckinClickListener(this, loggedIn));
                    texts.addView(checkinView);
                }
            } else if (spec.supportsDeviceFlow) {
                texts.addView(Ui.caption(this, "点击登录 · 浏览器完成自动识别"));
            } else if (LoginProviders.RACCOON.equals(loginType)) {
                texts.addView(Ui.caption(this, "手机号 + 短信验证码 · App 内登录"));
            } else if (LoginProviders.ZCODE.equals(loginType)) {
                texts.addView(Ui.caption(this, "App 内页面登录 · 自动识别"));
            } else if (LoginProviders.CODEARTS.equals(loginType)) {
                texts.addView(Ui.caption(this, "华为账号 · App 内页面登录 · 自动续期"));
            } else if (ChannelLogin.supportsDirectLogin(loginType)) {
                texts.addView(Ui.caption(this, "点击登录 · 浏览器完成后自动识别"));
            } else {
                texts.addView(Ui.caption(this, "粘贴 token"));
            }
            row.addView(texts);

            Button action = Ui.linkButton(this, loggedIn != null ? "管理" : "登录",
                    loggedIn != null ? Ui.GREEN : Ui.BLUE);
            if (loggedIn != null) {
                action.setOnClickListener(new ManageLoginClickListener(this, loggedIn));
            } else {
                action.setOnClickListener(new LoginClickListener(this, loginType));
            }
            row.addView(action);
            block.addView(row);

            // 分隔线（最后一行不加，避免和卡片底边贴在一起）
            if (loginContainer.getChildCount() < LoginProviders.allSpecs().size() - 1) {
                block.addView(Ui.divider(this));
            }

            loginContainer.addView(block);
        }
    }

    private Channel findChannelByLoginType(String loginType) {
        for (Channel c : config.channels()) {
            if (Channel.KIND_LOGIN.equals(c.kind) && loginType.equals(c.loginType) && c.hasCredential()) return c;
        }
        return null;
    }

    private void refreshChannels() {
        if (channelContainer == null) return;
        channelContainer.removeAllViews();
        List<Channel> channels = config.channels();
        if (channels.isEmpty()) {
            TextView empty = Ui.caption(this, "还没有渠道。先登录平台账号，或添加 API Key 渠道。");
            empty.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            channelContainer.addView(empty);
            return;
        }
        for (Channel c : channels) {
            LinearLayout row = Ui.row(this);
            row.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7));

            boolean ok = c.enabled && c.lastError.isEmpty() && c.hasCredential();
            row.addView(Ui.dot(this, ok ? Ui.GREEN : Ui.YELLOW));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setLayoutParams(Ui.weight1());

            String title;
            if (Channel.KIND_LOGIN.equals(c.kind)) {
                // displayTitle() 会跳过「昵称等于渠道名」的情况，
                // 否则显示成「Qoder（中国版） · Qoder（中国版）」，看着像重复
                title = c.displayTitle();
            } else {
                title = c.name + "  (" + c.id + ":)";
            }
            texts.addView(Ui.body(this, title));

            int total = c.allModels().size();
            int on = c.effectiveModels().size();
            String detail = "模型 " + on + (on == total ? "" : " / " + total) + " 个已启用";
            if (Channel.KIND_PROXY.equals(c.kind)) detail = c.baseUrl + "\n" + detail;
            if (!c.lastError.isEmpty()) detail += "\n⚠ " + c.lastError;
            texts.addView(Ui.caption(this, detail));
            row.addView(texts);

            Button toggleModels = Ui.linkButton(this, expandedChannels.contains(c.id) ? "收起" : "模型", Ui.BLUE);
            toggleModels.setOnClickListener(new ToggleModelsClickListener(this, c));
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp1.leftMargin = Ui.dp(this, 6);
            row.addView(toggleModels, lp1);

            Button manage = Ui.linkButton(this,
                    Channel.KIND_LOGIN.equals(c.kind) ? "管理" : "编辑", Ui.BLUE);
            if (Channel.KIND_LOGIN.equals(c.kind)) {
                manage.setOnClickListener(new ManageLoginClickListener(this, c));
            } else {
                manage.setOnClickListener(new EditChannelClickListener(this, c));
            }
            row.addView(manage, lp1);

            channelContainer.addView(row);

            // 展开时渲染模型勾选清单
            if (expandedChannels.contains(c.id)) {
                channelContainer.addView(buildModelList(c));
            }
        }
    }

    /**
     * 渲染某个渠道的模型勾选清单。
     *
     * 每个模型一行：勾选框 + 模型 id。勾上=对外可用，取消=从 /v1/models 移除
     * 且服务端拒绝调用 —— 过滤在 Channel.effectiveModels() 里统一做，
     * 界面、目录、路由三处共享同一份判定。
     */
    private View buildModelList(Channel channel) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), 0, 0, Ui.dp(this, 8));

        List<String> models = channel.allModels();
        boolean live = LoginProviders.hasLiveModelCatalog(channel.loginType);
        if (models.isEmpty()) {
            // 有实时目录却拉到空 → 说明该账号确实没有可用模型（或接口暂时失败），
            // 绝不能拿内置表凑数。这里给出明确原因和操作入口。
            String why = live
                    ? "未获取到可用模型清单。\n可能是账号确实没有可用模型，或接口暂时失败 —— 点「刷新模型」重试。"
                    : "该渠道没有模型清单。";
            box.addView(Ui.caption(this, why));
            Button refresh = Ui.linkButton(this, "刷新模型", Ui.BLUE);
            refresh.setOnClickListener(new RefreshModelsClickListenerForModels(this, channel));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rl.topMargin = Ui.dp(this, 6);
            box.addView(refresh, rl);
            return box;
        }

        // 清单来源说明：三种可信度不同，必须写清楚。
        //
        // ⚠ Qoder 没有目录接口，但会**逐个模型真打一次**探可用性
        //   （见 QoderProbe）。那种情况说「未与账号核对」是错的 ——
        //   它恰恰是实测过的，只是清单本身来自内置表。
        boolean probed = LoginProviders.isQoderFamily(channel.loginType);
        String srcText;
        int srcColor;
        if (live) {
            srcText = "来源：上游实时返回（该账号真实可用）";
            srcColor = Ui.GREEN;
        } else if (probed) {
            srcText = "来源：内置清单 + 逐个实测（刷新模型时重新探测可用性）";
            srcColor = Ui.GREEN;
        } else {
            srcText = "来源：内置清单，未与账号实际权限核对";
            srcColor = Ui.YELLOW;
        }
        TextView source = Ui.caption(this, srcText);
        source.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        source.setTextColor(srcColor);
        source.setPadding(0, Ui.dp(this, 2), 0, 0);
        box.addView(source);

        // 全选 / 全不选
        LinearLayout bulk = Ui.row(this);
        Button allOn = Ui.linkButton(this, "全选", Ui.BLUE);
        allOn.setOnClickListener(new BulkModelToggleClickListener(this, channel, true));
        Button allOff = Ui.linkButton(this, "全不选", Ui.MUTED);
        allOff.setOnClickListener(new BulkModelToggleClickListener(this, channel, false));
        bulk.addView(allOn);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 8);
        bulk.addView(allOff, lp);
        TextView counter = Ui.caption(this,
                channel.effectiveModels().size() + " / " + models.size() + " 已启用");
        counter.setLayoutParams(Ui.weight1());
        counter.setGravity(android.view.Gravity.END);
        bulk.addView(counter);
        box.addView(bulk);

        for (String model : models) {
            boolean enabled = !channel.isModelDisabled(model);
            // 规格优先用**上游下发的真实值**（刷新/登录时落进 channel.modelSpecs），
            // 只有上游没给才退回本地静态表 —— 静态表会随上游上新模型而过期，
            // 表现为「新模型显示未知」，所以不能当主来源。
            ModelSpec upstream = channel.modelSpecs.get(model);
            String shownName;
            String spec;
            if (upstream != null && !upstream.isEmpty()) {
                shownName = upstream.name.isEmpty() ? model : upstream.name;
                spec = upstream.specLine();
            } else {
                shownName = ModelMeta.displayName(model);
                spec = ModelMeta.specLine(model);
            }

            // 一行模型 = 勾选框 + 两行文字：
            //   第一行：友好名（查不到元数据时原样显示 id）
            //   第二行：规格（上下文 / 输出 / 推理档位 / 是否支持图片）
            // 上游 id 只在「友好名与 id 不同」时作为第二行的前缀显示，
            // 这样既看得懂、又能在配置里对上号。
            LinearLayout rowBox = new LinearLayout(this);
            rowBox.setOrientation(LinearLayout.HORIZONTAL);
            rowBox.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));

            android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setText("");
            cb.setChecked(enabled);
            cb.setPadding(0, 0, 0, 0);
            cb.setMinWidth(Ui.dp(this, 36));
            cb.setButtonTintList(android.content.res.ColorStateList.valueOf(
                    enabled ? Ui.GREEN : Ui.MUTED));
            cb.setOnCheckedChangeListener(new ModelCheckListener(this, channel, model, shownName));
            rowBox.addView(cb);

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setLayoutParams(Ui.weight1());

            TextView nameView = Ui.body(this, shownName);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            nameView.setTextColor(enabled ? Ui.TEXT : Ui.MUTED);
            texts.addView(nameView);

            // 积分倍率**直接跟在模型名后面**（用户要求的形态：`DeepSeek-V4.1-Flash x0.11`），
            // 不放第二行 —— 选模型时第一眼就要看到「贵不贵」。
            // 单独占宽屏会让名字被挤压，所以用同一条 TextView 硬拼。
            //
            // 「繁忙」也拼在这里：它和倍率是同一类信息（此刻能不能用），
            // 分两行反而要用户去对照。形态：`Qwen3.8-Flash  x0  繁忙`
            String rateLabel = creditRateText(upstream);
            String subLabel = upstream == null ? "" : upstream.queueBadge();
            StringBuilder nameLine = new StringBuilder(shownName);
            if (!rateLabel.isEmpty()) nameLine.append("  ").append(rateLabel);
            if (!subLabel.isEmpty()) nameLine.append("  ").append(subLabel);
            if (nameLine.length() > shownName.length()) {
                nameView.setText(nameLine.toString());
            }

            StringBuilder detail = new StringBuilder();
            // 有上游规格、或本地静态表认得这个模型 → 算「已知」
            boolean known = upstream != null && !upstream.isEmpty()
                    || ModelMeta.lookup(model) != null;
            boolean nameDiffers = !shownName.equalsIgnoreCase(model);
            // 名字与内部 id 不一致时把 id 显示出来（便于排查「这个名字对应哪个上游 key」），
            // 但要标明它是内部标识，别让人以为那就是模型名。
            if (nameDiffers) detail.append("内部标识 ").append(model);
            if (spec.length() > 0) {
                if (detail.length() > 0) detail.append(" · ");
                detail.append(spec);
            }
            if (!known) {
                // 用户自加的代理渠道模型 / 上游新上、两边都没规格：明确说明而不是留白
                detail.append(detail.length() > 0 ? "\n" : "")
                        .append("上游自定义模型（无规格信息）");
            }
            if (detail.length() > 0) {
                TextView specView = Ui.caption(this, detail.toString());
                specView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                texts.addView(specView);
            }
            rowBox.addView(texts);

            // 点击整行也能切换勾选（CheckBox 本身较小，整行更好点）
            rowBox.setOnClickListener(new ModelRowClickListener(cb));

            box.addView(rowBox);
        }
        return box;
    }

    /**
     * 模型行末尾的积分倍率文案。
     *
     * <p>为什么不直接用 {@link ModelSpec#creditRateLabel()}：那个带「积分」前缀与
     * 分档备注，适合放第二行；模型名后面要的是**最短形态** `x0.11`。
     * 有分档备注时退回长文案，避免把「分档，32K 以内」这种关键信息丢掉。
     *
     * @param upstream 上游规格；可能为 null（此时没有倍率可言）
     */
    static String creditRateText(ModelSpec upstream) {
        if (upstream == null) return "";
        if (!upstream.creditNote.isEmpty()) return upstream.creditRateLabel();
        return upstream.creditRateBadge();
    }

    // -----------------------------------------------------------------------
    // 模型开关监听器
    // -----------------------------------------------------------------------

    /**
     * 保活相关按钮的点击处理。
     *
     * <p>为什么用带参数的具名类（而不是两个类）：这两件事走的是同一条路径
     * ——「跳到某个系统设置页并把结果告诉用户」—— 只差目标页不同。
     */
    private static final class KeepAliveClickListener implements View.OnClickListener {
        static final int BATTERY = 0;
        static final int AUTOSTART = 1;

        private final WeakReference<MainActivity> ref;
        private final int kind;

        KeepAliveClickListener(MainActivity a, int kind) {
            this.ref = new WeakReference<>(a);
            this.kind = kind;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a == null) return;
            boolean ok;
            if (kind == BATTERY) {
                ok = KeepAlive.requestIgnoreBatteryOptimizations(a);
                if (ok) {
                    Toast.makeText(a, "在系统页面里选「允许」后返回即可", Toast.LENGTH_LONG).show();
                }
            } else {
                ok = KeepAlive.openAutoStartSettings(a);
                if (ok) {
                    Toast.makeText(a, "把本应用的自启动 / 后台运行打开", Toast.LENGTH_LONG).show();
                }
            }
            if (!ok) {
                Toast.makeText(a, "没能打开系统设置页，请手动到「应用管理」里设置",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 展开 / 收起某个渠道的模型清单。 */
    private static final class ToggleModelsClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;
        private final Channel channel;

        ToggleModelsClickListener(MainActivity a, Channel channel) {
            this.ref = new WeakReference<>(a);
            this.channel = channel;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a == null) return;
            if (!a.expandedChannels.add(channel.id)) a.expandedChannels.remove(channel.id);
            a.refreshChannels();
        }
    }

    /** 模型清单为空时的「刷新模型」入口。 */
    private static final class RefreshModelsClickListenerForModels implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;
        private final Channel channel;

        RefreshModelsClickListenerForModels(MainActivity a, Channel channel) {
            this.ref = new WeakReference<>(a);
            this.channel = channel;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a == null) return;
            Toast.makeText(a, "正在刷新…", Toast.LENGTH_SHORT).show();
            new Thread(new RefreshModelsTask(a, null, channel), "refresh-models").start();
        }
    }
    /** 整行可点：点文字区也能切换勾选。 */
    private static final class ModelRowClickListener implements View.OnClickListener {
        private final android.widget.CheckBox box;

        ModelRowClickListener(android.widget.CheckBox box) {
            this.box = box;
        }

        @Override
        public void onClick(View v) {
            box.toggle();
        }
    }

    /** 单个模型的勾选变化。 */
    private static final class ModelCheckListener implements android.widget.CompoundButton.OnCheckedChangeListener {
        private final WeakReference<MainActivity> ref;
        private final Channel channel;
        private final String model;
        private final String shownName;

        ModelCheckListener(MainActivity a, Channel channel, String model, String shownName) {
            this.ref = new WeakReference<>(a);
            this.channel = channel;
            this.model = model;
            this.shownName = shownName;
        }

        @Override
        public void onCheckedChanged(android.widget.CompoundButton button, boolean checked) {
            MainActivity a = ref.get();
            if (a == null) return;
            if (!channel.setModelEnabled(model, checked)) return;
            a.config.upsertChannel(channel);
            // 只改颜色 + 更新计数，不重建列表 —— 重建会让勾选框被销毁、滚动位置跳动
            a.updateModelCounter(channel);
        }
    }

    /** 批量开 / 关某个渠道的全部模型。 */
    private static final class BulkModelToggleClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;
        private final Channel channel;
        private final boolean enable;

        BulkModelToggleClickListener(MainActivity a, Channel channel, boolean enable) {
            this.ref = new WeakReference<>(a);
            this.channel = channel;
            this.enable = enable;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a == null) return;
            List<String> models = channel.allModels();
            if (enable) {
                channel.disabledModels.clear();
            } else {
                channel.disabledModels.clear();
                channel.disabledModels.addAll(models);
            }
            a.config.upsertChannel(channel);
            a.refreshChannelsKeepingScroll();
            Toast.makeText(a, (enable ? "已启用 " : "已关闭 ") + models.size() + " 个模型", Toast.LENGTH_SHORT).show();
        }
    }
    /**
     * 只重建渠道列表区域（模型勾选后用）。
     *
     * 为什么不整个界面刷新：refreshAll() 会重建登录区等大块视图，
     * 正在操作的勾选框会被销毁，手指下面的位置就会跳。
     */
    private void refreshChannelsKeepingScroll() {
        if (channelContainer == null) return;
        refreshChannels();
    }

    /**
     * 就地更新某个渠道的「x / y 已启用」计数。
     *
     * 不重建视图：勾选模型时若重建整个清单，勾选框会被销毁重建，
     * 用户手指下的位置就会跳走（实测体验很差）。这里只在已渲染的
     * 视图树里找到那个 TextView 改文字。
     */
    private void updateModelCounter(Channel channel) {
        if (channelContainer == null) return;
        int total = channel.allModels().size();
        int on = channel.effectiveModels().size();
        String text = on + " / " + total + " 已启用";
        for (int i = 0; i < channelContainer.getChildCount(); i += 1) {
            View child = channelContainer.getChildAt(i);
            if (child instanceof LinearLayout && child != null && ((LinearLayout) child).getChildCount() > 0) {
                LinearLayout box = (LinearLayout) child;
                // 模型清单块：第一个子是「全选/全不选/计数」行
                View first = box.getChildAt(0);
                if (first instanceof LinearLayout) {
                    LinearLayout bulk = (LinearLayout) first;
                    for (int j = 0; j < bulk.getChildCount(); j += 1) {
                        View item = bulk.getChildAt(j);
                        if (item instanceof TextView) {
                            TextView tv = (TextView) item;
                            CharSequence cur = tv.getText();
                            if (cur != null && cur.toString().contains("已启用")) {
                                tv.setText(text);
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 追加一行日志。
     *
     * <p>⚠ 分类改造后日志只在「首页」可见：在别的分类上时 `logText` 是 null，
     * 直接 return 会让这段日志**永久丢失**（用户切回首页只看到更早的内容，
     * 于是以为网关没在工作）。所以先攒在内存里，等首页建好时回填。
     */
    private void appendLog(String line) {
        // 先记到内存（不管当前在哪一页）
        if (lineLog.length() > 0) lineLog.append('\n');
        lineLog.append(line);
        if (lineLog.length() > 6000) {
            lineLog.delete(0, lineLog.length() - 6000);
        }
        if (logText == null) return;   // 不在首页，等切回去时回填
        logText.setText(lineLog.toString());
    }

    /** 内存里的日志（跨分类保留，最多 6000 字符）。 */
    private final StringBuilder lineLog = new StringBuilder();

    /** 首页建好后把内存日志灌进视图。 */
    private void bindLogView() {
        if (logText == null) return;
        if (lineLog.length() == 0) {
            logText.setText("（暂无）");
        } else {
            logText.setText(lineLog.toString());
        }
    }

    // -----------------------------------------------------------------------
    // 值来源（用于复制）
    // -----------------------------------------------------------------------

    private interface ValueSource {
        String get();
    }

    /** Base URL（随端口变化）。 */
    private static final class EndpointValue implements ValueSource {
        private final WeakReference<MainActivity> ref;

        EndpointValue(MainActivity a) {
            this.ref = new WeakReference<>(a);
        }

        @Override
        public String get() {
            MainActivity a = ref.get();
            if (a == null) return "";
            return "http://127.0.0.1:" + a.config.port() + "/v1";
        }
    }

    /** API Key。 */
    private static final class ApiKeyValue implements ValueSource {
        private final WeakReference<MainActivity> ref;

        ApiKeyValue(MainActivity a) {
            this.ref = new WeakReference<>(a);
        }

        @Override
        public String get() {
            MainActivity a = ref.get();
            return a == null ? "" : a.config.apiKey();
        }
    }

    /** 固定字符串（复制链接用）。 */
    private static final class FixedValue implements ValueSource {
        private final String value;

        FixedValue(String value) {
            this.value = value;
        }

        @Override
        public String get() {
            return value;
        }
    }

    // -----------------------------------------------------------------------
    // 监听器（全部具名静态类）
    // -----------------------------------------------------------------------

    private static final class UiListener implements Gateway.Listener {
        private final WeakReference<MainActivity> ref;

        UiListener(MainActivity activity) {
            this.ref = new WeakReference<>(activity);
        }

        @Override
        public void onLog(String line) {
            MainActivity a = ref.get();
            if (a != null) a.ui.post(new LogRunnable(a, line));
        }

        @Override
        public void onStateChanged(boolean running, int port) {
            MainActivity a = ref.get();
            if (a != null) a.ui.post(new RefreshRunnable(a));
        }
    }

    /**
     * CodeArts 续期成功后把新凭据写回配置。
     *
     * <p>不写回的话，界面上的这条渠道还是旧凭据：下次签到 / 查积分又得续一次，
     * 下次启动 App 更是直接拿过期凭据去请求。
     */
    private static final class RenewedChannelWriter implements CodeArtsLogin.RenewListener {
        private final WeakReference<MainActivity> ref;

        RenewedChannelWriter(MainActivity activity) {
            this.ref = new WeakReference<>(activity);
        }

        @Override
        public void onRenewed(Channel channel) {
            MainActivity a = ref.get();
            if (a == null || channel == null) return;
            a.config.upsertChannel(channel);
        }
    }

    private static final class LogRunnable implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final String line;

        LogRunnable(MainActivity a, String line) {
            this.ref = new WeakReference<>(a);
            this.line = line;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a != null) a.appendLog(line);
        }
    }

    private static final class RefreshRunnable implements Runnable {
        private final WeakReference<MainActivity> ref;

        RefreshRunnable(MainActivity a) {
            this.ref = new WeakReference<>(a);
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a != null) a.refreshAll();
        }
    }

    private static final class StartClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;

        StartClickListener(MainActivity a) {
            ref = new WeakReference<>(a);
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a == null) return;
            // 先探端口：服务「在运行」但 accept 线程死掉时，只发 START Intent
            // 什么都不会发生（onStartCommand 的启动分支已经跳过了）。
            // 实测（2026-11-10）：端口 LISTEN、状态显示「运行中」、请求全部超时。
            boolean alive = GatewayService.healthy(a.config.port());
            if (!alive && GatewayService.getGateway() != null) {
                Toast.makeText(a, "检测到网关无响应，正在重启…", Toast.LENGTH_SHORT).show();
                boolean ok = GatewayService.forceRestart();
                a.appendLog(ok ? "网关已重启" : "网关重启失败，请点「停止」后再启动");
            }
            // 保险起见仍发一次 START（处理服务未创建的情况）
            Intent i = new Intent(a, GatewayService.class);
            i.setAction(GatewayService.ACTION_START);
            a.startForegroundService(i);
        }
    }

    private static final class StopClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;

        StopClickListener(MainActivity a) {
            ref = new WeakReference<>(a);
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a == null) return;
            Intent i = new Intent(a, GatewayService.class);
            i.setAction(GatewayService.ACTION_STOP);
            a.startService(i);
        }
    }

    private static final class CopyClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;
        private final ValueSource source;

        CopyClickListener(MainActivity a, ValueSource source) {
            this.ref = new WeakReference<>(a);
            this.source = source;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a == null) return;
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("value", source.get()));
                Toast.makeText(a, "已复制", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static final class ClearLogClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;

        ClearLogClickListener(MainActivity a) {
            ref = new WeakReference<>(a);
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a == null) return;
            // 内存缓冲也要清，否则下次切回首页旧日志又冒出来
            a.lineLog.setLength(0);
            if (a.logText != null) a.logText.setText("（暂无）");
        }
    }

    private static final class LogToggleClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;
        private final TextView toggle;

        LogToggleClickListener(MainActivity a, TextView toggle) {
            this.ref = new WeakReference<>(a);
            this.toggle = toggle;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a == null || a.logContainer == null) return;
            boolean showing = a.logContainer.getVisibility() == View.VISIBLE;
            a.logContainer.setVisibility(showing ? View.GONE : View.VISIBLE);
            toggle.setText(showing ? "展开" : "收起");
        }
    }

    private static final class EditPortClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;

        EditPortClickListener(MainActivity a) {
            ref = new WeakReference<>(a);
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a != null) a.showPortDialog();
        }
    }

    private static final class ToggleLanClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;

        ToggleLanClickListener(MainActivity a) {
            ref = new WeakReference<>(a);
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a == null) return;
            a.config.setLan(!a.config.lan());
            a.refreshSettings();
            Toast.makeText(a, a.config.lan()
                    ? "已开启局域网访问（重启网关生效）"
                    : "已关闭局域网访问（重启网关生效）", Toast.LENGTH_SHORT).show();
        }
    }

    private static final class AddChannelClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;

        AddChannelClickListener(MainActivity a) {
            ref = new WeakReference<>(a);
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a != null) a.showProxyDialog(null);
        }
    }

    private static final class EditChannelClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;
        private final Channel channel;

        EditChannelClickListener(MainActivity a, Channel channel) {
            this.ref = new WeakReference<>(a);
            this.channel = channel;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a != null) a.showProxyDialog(channel);
        }
    }

    private static final class LoginClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;
        private final String loginType;

        LoginClickListener(MainActivity a, String loginType) {
            this.ref = new WeakReference<>(a);
            this.loginType = loginType;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a == null) return;
            LoginProviders.Spec spec = LoginProviders.specOf(loginType);
            if (spec == null) return;
            if (spec.supportsDeviceFlow) {
                a.startDeviceLogin(loginType);
            } else if (LoginProviders.RACCOON.equals(loginType)) {
                // Raccoon 走 WebView 网页登录：它的短信登录带人机验证，
                // captcha_token 绑定页面来源，原生表单发不出去（见 RaccoonLoginActivity）
                a.startRaccoonLogin();
            } else if (LoginProviders.ZCODE.equals(loginType)) {
                // ZCode 也走内置 WebView：官方回调是 zcode://oauth/callback，
                // 在 WebView 里截住即可自动换 token，用户不用再粘 code
                a.startZcodeLogin();
            } else if (LoginProviders.CODEARTS.equals(loginType)) {
                // 华为 CodeArts：回调是 http://127.0.0.1/oauth/callback，同样 WebView 内截获
                a.startCodeArtsLogin();
            } else if (ChannelLogin.supportsDirectLogin(loginType)) {
                a.startDirectLogin(loginType);
            } else {
                a.showImportTokenDialog(loginType);
            }
        }
    }

    private static final class ManageLoginClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;
        private final Channel channel;

        ManageLoginClickListener(MainActivity a, Channel channel) {
            this.ref = new WeakReference<>(a);
            this.channel = channel;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a != null) a.showManageLoginDialog(channel);
        }
    }

    private static final class DismissClickListener implements View.OnClickListener {
        private final AlertDialog dialog;

        DismissClickListener(AlertDialog dialog) {
            this.dialog = dialog;
        }

        @Override
        public void onClick(View v) {
            dialog.dismiss();
        }
    }

    /** 占位：真正的动作在 onShow 里重绑，这里避免对话框默认关闭。 */
    private static final class SilentClickListener implements android.content.DialogInterface.OnClickListener {
        @Override
        public void onClick(android.content.DialogInterface d, int which) {
            // 由对应的 onShow 监听器重新绑定
        }
    }

    /** 统一的输入式对话框（深色主题、自定义按钮行为）。 */
    private AlertDialog buildInputDialog(String title, View content, String positive) {
        return new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setView(content)
                .setPositiveButton(positive, new SilentClickListener())
                .setNegativeButton("取消", new SilentClickListener())
                .create();
    }

    // -----------------------------------------------------------------------
    // 端口设置
    // -----------------------------------------------------------------------

    private void showPortDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        box.setPadding(pad, pad, pad, pad);

        box.addView(Ui.caption(this, "建议 1024–65535。改完需要重启网关。"));
        EditText input = new EditText(this);
        input.setText(String.valueOf(config.port()));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(input);

        AlertDialog dialog = buildInputDialog("监听端口", box, "保存");
        dialog.setOnShowListener(new PortDialogListener(this, dialog, input));
        dialog.show();
    }

    private static final class PortDialogListener implements android.content.DialogInterface.OnShowListener {
        private final WeakReference<MainActivity> ref;
        private final AlertDialog dialog;
        private final EditText input;

        PortDialogListener(MainActivity a, AlertDialog dialog, EditText input) {
            this.ref = new WeakReference<>(a);
            this.dialog = dialog;
            this.input = input;
        }

        @Override
        public void onShow(android.content.DialogInterface d) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(new SavePortClickListener(this));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setOnClickListener(new DismissClickListener(dialog));
        }
    }

    private static final class SavePortClickListener implements View.OnClickListener {
        private final PortDialogListener owner;

        SavePortClickListener(PortDialogListener owner) {
            this.owner = owner;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = owner.ref.get();
            if (a == null) return;
            int port;
            try {
                port = Integer.parseInt(owner.input.getText().toString().trim());
            } catch (NumberFormatException e) {
                Toast.makeText(a, "端口必须是数字", Toast.LENGTH_SHORT).show();
                return;
            }
            if (port < 1024 || port > 65535) {
                Toast.makeText(a, "端口范围 1024–65535", Toast.LENGTH_SHORT).show();
                return;
            }
            a.config.setPort(port);
            a.refreshSettings();
            a.refreshStatus();
            owner.dialog.dismiss();
            Toast.makeText(a, "已保存，重启网关后生效", Toast.LENGTH_SHORT).show();
        }
    }

    // -----------------------------------------------------------------------
    // 扫码登录
    // -----------------------------------------------------------------------

    private void startDeviceLogin(String loginType) {
        new Thread(new DeviceLoginStarter(this, loginType), "login-start").start();
    }

    /**
     * WorkBuddy 两区域的登录对话框。
     *
     * <p>结构与 {@link #showDirectLoginDialog} **保持一致**：说明两行 + 主按钮
     * 「打开登录页」+ 域名/复制链接 + 状态行。差别只在主按钮打开的是
     * **设备码 authUrl**（带 state，登录完成 App 自动轮询到令牌），
     * 而不是官网首页 —— 那个 authUrl 就是官方给这个流程用的入口。
     */
    private void showLoginProgress(String loginType, LoginProviders.Spec spec,
                                   LoginProviders.DeviceState ds) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        box.setPadding(pad, pad, pad, pad);

        box.addView(Ui.body(this, "1. 点「打开登录页」完成登录\n2. 回到本页，会自动变成已登录"));

        Button openPage = Ui.button(this, "打开登录页", Ui.BLUE);
        openPage.setOnClickListener(new OpenUrlClickListener(this, ds.authUrl));
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        openLp.topMargin = Ui.dp(this, 12);
        box.addView(openPage, openLp);

        LinearLayout linkRow = Ui.row(this);
        linkRow.setPadding(0, Ui.dp(this, 6), 0, 0);
        TextView hostView = Ui.caption(this, "官网：" + LoginPage.hostOf(loginType));
        hostView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        hostView.setLayoutParams(Ui.weight1());
        linkRow.addView(hostView);
        Button copy = Ui.linkButton(this, "复制链接", Ui.MUTED);
        copy.setOnClickListener(new CopyClickListener(this, new FixedValue(ds.authUrl)));
        linkRow.addView(copy);
        box.addView(linkRow);

        TextView stateView = Ui.caption(this, "等待扫码…");
        stateView.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        box.addView(stateView);

        // 底部按钮改成「关闭」：打开登录页已经是中间那个主按钮，
        // 底部再放同名按钮会让用户以为点了才会打开
        AlertDialog dialog = buildInputDialog("登录 " + spec.displayName, box, "关闭");
        dialog.setOnShowListener(new LoginDialogListener(this, dialog, loginType, ds, stateView));
        dialog.show();
    }

    private static final class LoginDialogListener implements android.content.DialogInterface.OnShowListener {
        private final WeakReference<MainActivity> ref;
        private final AlertDialog dialog;
        private final String loginType;
        private final LoginProviders.DeviceState ds;
        private final TextView stateView;

        LoginDialogListener(MainActivity a, AlertDialog dialog, String loginType,
                            LoginProviders.DeviceState ds, TextView stateView) {
            this.ref = new WeakReference<>(a);
            this.dialog = dialog;
            this.loginType = loginType;
            this.ds = ds;
            this.stateView = stateView;
        }

        @Override
        public void onShow(android.content.DialogInterface d) {
            MainActivity a = ref.get();
            if (a == null) return;
            // 底部按钮现在是「关闭」：打开登录页已由对话框中间的主按钮负责，
            // 这里再绑「打开登录页」会出现两个同名入口
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(new DismissClickListener(dialog));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setOnClickListener(new DismissClickListener(dialog));
            a.startPolling(loginType, ds, stateView, dialog);
        }
    }

    private static final class OpenLoginPageClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;
        private final LoginProviders.DeviceState ds;

        OpenLoginPageClickListener(MainActivity a, LoginProviders.DeviceState ds) {
            this.ref = new WeakReference<>(a);
            this.ds = ds;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a == null) return;
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ds.authUrl)));
            } catch (Exception e) {
                Toast.makeText(a, "没有可用浏览器，请复制链接", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startPolling(String loginType, LoginProviders.DeviceState ds,
                              TextView stateView, AlertDialog dialog) {
        new Thread(new PollLoginTask(this, loginType, ds, stateView, dialog), "login-poll").start();
    }

    // -----------------------------------------------------------------------
    // 直接登录（Qoder / Qoder CN / ZCode / Raccoon）
    // -----------------------------------------------------------------------

    /** 发起一次「App 内直接登录」。扫码类先建二维码，浏览器类先拼授权页。 */
    private void startDirectLogin(String loginType) {
        new Thread(new DirectLoginStarter(this, loginType), "direct-login-start").start();
    }

    // -----------------------------------------------------------------------
    // Raccoon：WebView 网页登录
    // -----------------------------------------------------------------------

    /** 请求码：Raccoon 登录页。 */
    private static final int REQ_RACCOON_LOGIN = 4101;

    /** 打开 Raccoon 登录页（WebView 里完成手机号 + 短信验证码）。 */
    private void startRaccoonLogin() {
        try {
            startActivityForResult(new Intent(this, RaccoonLoginActivity.class), REQ_RACCOON_LOGIN);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开登录页：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 请求码：ZCode 登录页。 */
    private static final int REQ_ZCODE_LOGIN = 4102;

    /**
     * 打开 ZCode 登录页（内置 WebView，自动捕获 `zcode://oauth/callback`）。
     *
     * <p>为什么从「弹窗 + 粘 code」改成这个：回调是我们自己再接的，
     * 用户登录完不需要再做任何操作 —— 与其它渠道的体验一致。
     */
    private void startZcodeLogin() {
        try {
            startActivityForResult(new Intent(this, ZcodeLoginActivity.class), REQ_ZCODE_LOGIN);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开登录页：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 请求码：华为 CodeArts 登录页。 */
    private static final int REQ_CODEARTS_LOGIN = 4103;

    /**
     * 打开 CodeArts 登录页（内置 WebView，自动捕获 `127.0.0.1/oauth/callback`）。
     *
     * <p>登录后会一次性存下 AK/SK/security_token、refresh_token、code_verifier
     * 与 DPoP 私钥 —— 后三样是**自动续期**的关键（临时凭据只有约 2 小时）。
     */
    private void startCodeArtsLogin() {
        try {
            startActivityForResult(new Intent(this, CodeArtsLoginActivity.class), REQ_CODEARTS_LOGIN);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开登录页：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_RACCOON_LOGIN && requestCode != REQ_ZCODE_LOGIN
                && requestCode != REQ_CODEARTS_LOGIN) return;
        if (resultCode != RESULT_OK || data == null) {
            // 用户取消：不打扰，界面保持原样
            return;
        }
        String json;
        if (requestCode == REQ_ZCODE_LOGIN) {
            json = data.getStringExtra(ZcodeLoginActivity.EXTRA_CHANNEL_JSON);
        } else if (requestCode == REQ_CODEARTS_LOGIN) {
            json = data.getStringExtra(CodeArtsLoginActivity.EXTRA_CHANNEL_JSON);
        } else {
            json = data.getStringExtra(RaccoonLoginActivity.EXTRA_CHANNEL_JSON);
        }
        if (json == null || json.trim().isEmpty()) {
            Toast.makeText(this, "没有收到登录结果，请重试", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Channel channel = Channel.fromJson(new org.json.JSONObject(json));
            if (!channel.hasCredential()) {
                Toast.makeText(this, "登录结果里没有凭据，请重试", Toast.LENGTH_LONG).show();
                return;
            }
            config.upsertChannel(channel);
            refreshAll();
            Toast.makeText(this, "已登录：" + channel.nickname, Toast.LENGTH_SHORT).show();
            // 刚登录的渠道当天还没签到过 → 立刻补一次（不必等下次打开 App）
            autoCheckinStarted = false;
            maybeAutoCheckin();
        } catch (Exception e) {
            Toast.makeText(this, "保存登录结果失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 直接登录的进度对话框（Qoder / Qoder 中国版）。
     *
     * <p>**所有走这里的渠道弹窗长得一模一样**：说明两行 + 「打开登录页」按钮 +
     * 官网域名 + 复制链接 + 状态行。差别只在文案里的「回本页会自动识别」。
     *
     * <p>ZCode 与 Raccoon 不在这里：它们需要内置 WebView（前者截自定义 scheme 回调，
     * 后者要过阿里云人机验证），各自有独立的 Activity，但**交互与这里一致** ——
     * 点一下、登录、自动回 App。
     *
     * <p>「打开登录页」给的是**渠道官网**（{@link LoginPage}），不是设备码模板 ——
     * 模板带 `{state}` 占位符，打开就是错误页。
     */
    private void showDirectLoginDialog(String loginType, ChannelLogin.LoginSession session) {
        LoginProviders.Spec spec = LoginProviders.specOf(loginType);
        String title = spec == null ? loginType : spec.displayName;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        box.setPadding(pad, pad, pad, pad);

        String mode = ChannelLogin.loginMode(loginType);
        TextView stateView = Ui.caption(this, "正在准备…");
        String pageUrl = LoginPage.urlOf(loginType);

        if ("qr".equals(mode)) {
            box.addView(Ui.body(this, "1. 用手机相机 / 微信扫描下面的二维码\n2. 在手机上确认登录"));
            android.widget.ImageView qrView = new android.widget.ImageView(this);
            qrView.setAdjustViewBounds(true);
            int qrPx = Ui.dp(this, 220);
            LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(qrPx, qrPx);
            qrLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            qrLp.topMargin = Ui.dp(this, 10);
            qrLp.bottomMargin = Ui.dp(this, 10);
            qrView.setLayoutParams(qrLp);
            try {
                QrCode qr = QrCode.encode(session.loginUrl, QrCode.Ecc.M);
                qrView.setImageBitmap(QrRenderer.toBitmap(qr, qrPx, 0xFFFFFFFF, 0xFF000000));
            } catch (Exception e) {
                box.addView(Ui.caption(this, "二维码生成失败：" + e.getMessage()));
            }
            box.addView(qrView);
        } else {
            box.addView(Ui.body(this, "1. 点「打开登录页」在浏览器里完成登录\n"
                    + "2. 回到本页，会自动变成已登录"));

            // 主按钮：打开该渠道的官网登录页
            Button openPage = Ui.button(this, "打开登录页", Ui.BLUE);
            openPage.setOnClickListener(new OpenUrlClickListener(this, pageUrl));
            LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            openLp.topMargin = Ui.dp(this, 12);
            box.addView(openPage, openLp);

            // 官网域名 + 备用「复制链接」
            LinearLayout linkRow = Ui.row(this);
            linkRow.setPadding(0, Ui.dp(this, 6), 0, 0);
            TextView hostView = Ui.caption(this, "官网：" + LoginPage.hostOf(loginType));
            hostView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            hostView.setLayoutParams(Ui.weight1());
            linkRow.addView(hostView);
            Button copy = Ui.linkButton(this, "复制链接", Ui.MUTED);
            copy.setOnClickListener(new CopyClickListener(this, new FixedValue(pageUrl)));
            linkRow.addView(copy);
            box.addView(linkRow);
        }

        stateView.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        box.addView(stateView);

        String positive = "qr".equals(mode) ? "我已扫码" : "完成登录";
        AlertDialog dialog = buildInputDialog("登录 " + title, box, positive);
        dialog.setOnShowListener(new DirectLoginDialogListener(
                this, dialog, loginType, session, stateView, null));
        dialog.show();
    }

    /** 打开一个 URL（弹窗内用；失败时把地址复制到剪贴板）。 */
    private static final class OpenUrlClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;
        private final String url;

        OpenUrlClickListener(MainActivity a, String url) {
            this.ref = new WeakReference<>(a);
            this.url = url;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a == null) return;
            a.openExternalUrl(url);
        }
    }

    /** 打开外部 URL；没有浏览器时复制到剪贴板（而不是只弹一句失败）。 */
    private void openExternalUrl(String url) {
        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "没有可打开的地址", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("loginUrl", url));
                    Toast.makeText(this, "没有可用浏览器，地址已复制", Toast.LENGTH_LONG).show();
                    return;
                }
            } catch (Exception ignored) {
            }
            Toast.makeText(this, "无法打开：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static final class DirectLoginDialogListener
            implements android.content.DialogInterface.OnShowListener {
        private final WeakReference<MainActivity> ref;
        private final AlertDialog dialog;
        private final String loginType;
        private final ChannelLogin.LoginSession session;
        private final TextView stateView;
        private final EditText codeInput;

        DirectLoginDialogListener(MainActivity a, AlertDialog dialog, String loginType,
                                  ChannelLogin.LoginSession session, TextView stateView,
                                  EditText codeInput) {
            this.ref = new WeakReference<>(a);
            this.dialog = dialog;
            this.loginType = loginType;
            this.session = session;
            this.stateView = stateView;
            this.codeInput = codeInput;
        }

        @Override
        public void onShow(android.content.DialogInterface d) {
            MainActivity a = ref.get();
            if (a == null) return;
            String mode = ChannelLogin.loginMode(loginType);
            if ("browser".equals(mode)) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new OpenSessionUrlClickListener(a, session));
                if (codeInput == null) {
                    // Qoder：轮询自动完成，无需用户干预
                    a.startDirectPolling(session, stateView, dialog, null);
                } else {
                    // ZCode：用户填了 code 再换 token
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                            .setOnClickListener(new ZcodeSubmitClickListener(a, dialog, session, codeInput, stateView));
                }
            } else {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new DismissClickListener(dialog));
                a.startDirectPolling(session, stateView, dialog, null);
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setOnClickListener(new DismissClickListener(dialog));
        }
    }

    /** 打开浏览器登录页（直接登录版）。 */
    private static final class OpenSessionUrlClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;
        private final ChannelLogin.LoginSession session;

        OpenSessionUrlClickListener(MainActivity a, ChannelLogin.LoginSession session) {
            this.ref = new WeakReference<>(a);
            this.session = session;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a == null) return;
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(session.loginUrl)));
            } catch (Exception e) {
                Toast.makeText(a, "没有可用浏览器，请复制链接", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** ZCode：提交用户粘贴回来的 code。 */
    private static final class ZcodeSubmitClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;
        private final AlertDialog dialog;
        private final ChannelLogin.LoginSession session;
        private final EditText input;
        private final TextView stateView;

        ZcodeSubmitClickListener(MainActivity a, AlertDialog dialog, ChannelLogin.LoginSession session,
                                 EditText input, TextView stateView) {
            this.ref = new WeakReference<>(a);
            this.dialog = dialog;
            this.session = session;
            this.input = input;
            this.stateView = stateView;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a == null) return;
            String code = input.getText().toString().trim();
            if (code.isEmpty()) {
                Toast.makeText(a, "请先粘贴 code", Toast.LENGTH_SHORT).show();
                return;
            }
            stateView.setText("正在校验…");
            new Thread(new DirectPollOnceTask(a, session, code, stateView, dialog), "direct-poll-once").start();
        }
    }

    private void startDirectPolling(ChannelLogin.LoginSession session, TextView stateView,
                                    AlertDialog dialog, String code) {
        new Thread(new DirectPollTask(this, session, code, stateView, dialog), "direct-poll").start();
    }

    // -----------------------------------------------------------------------
    // 粘贴 token
    // -----------------------------------------------------------------------

    private void showImportTokenDialog(String loginType) {
        LoginProviders.Spec spec = LoginProviders.specOf(loginType);
        if (spec == null) return;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        box.setPadding(pad, pad, pad, pad);

        box.addView(Ui.caption(this,
                "在电脑上登录后，从该应用的本地凭据复制 access_token 粘贴到这里；\n"
                        + "也支持直接粘贴包含 access_token 的 JSON。"));
        EditText input = new EditText(this);
        input.setHint("access_token 或 {json}");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(input);

        AlertDialog dialog = buildInputDialog("登录 " + spec.displayName, box, "保存");
        dialog.setOnShowListener(new ImportDialogListener(this, dialog, loginType, input));
        dialog.show();
    }

    private static final class ImportDialogListener implements android.content.DialogInterface.OnShowListener {
        private final WeakReference<MainActivity> ref;
        private final AlertDialog dialog;
        private final String loginType;
        private final EditText input;

        ImportDialogListener(MainActivity a, AlertDialog dialog, String loginType, EditText input) {
            this.ref = new WeakReference<>(a);
            this.dialog = dialog;
            this.loginType = loginType;
            this.input = input;
        }

        @Override
        public void onShow(android.content.DialogInterface d) {
            MainActivity a = ref.get();
            if (a == null) return;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(new ImportSaveClickListener(a, dialog, loginType, input));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setOnClickListener(new DismissClickListener(dialog));
        }
    }

    private static final class ImportSaveClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;
        private final AlertDialog dialog;
        private final String loginType;
        private final EditText input;

        ImportSaveClickListener(MainActivity a, AlertDialog dialog, String loginType, EditText input) {
            this.ref = new WeakReference<>(a);
            this.dialog = dialog;
            this.loginType = loginType;
            this.input = input;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a == null) return;
            Channel probe = new Channel();
            probe.id = loginType;
            probe.kind = Channel.KIND_LOGIN;
            probe.loginType = loginType;
            try {
                Channel saved = LoginProviders.importCredential(probe, input.getText().toString());
                saved.id = loginType;
                LoginProviders.Spec spec = LoginProviders.specOf(loginType);
                saved.name = spec == null ? loginType : spec.displayName;
                saved.kind = Channel.KIND_LOGIN;
                saved.enabled = true;
                a.config.upsertChannel(saved);
                dialog.dismiss();
                a.refreshAll();
                Toast.makeText(a, "已保存：" + saved.nickname, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(a, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    // -----------------------------------------------------------------------
    // 管理已登录渠道
    // -----------------------------------------------------------------------

    private void showManageLoginDialog(Channel channel) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        box.setPadding(pad, pad, pad, pad);

        box.addView(Ui.body(this, "账号：" + (channel.nickname.isEmpty() ? "—" : channel.nickname)));
        box.addView(Ui.caption(this, "站点：" + (channel.domain.isEmpty() ? "默认" : channel.domain)));
        String exp;
        if (channel.expiresAt > 0) {
            exp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(new java.util.Date(channel.expiresAt));
        } else {
            exp = "未知";
        }
        box.addView(Ui.caption(this, "token 过期：" + exp));
        box.addView(Ui.caption(this, "模型：" + channel.effectiveModels().size() + " 个"));

        AlertDialog dialog = buildInputDialog(channel.name, box, "刷新模型");
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "退出登录", new SilentClickListener());
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "关闭", new SilentClickListener());
        dialog.setOnShowListener(new ManageDialogListener(this, dialog, channel));
        dialog.show();
    }

    private static final class ManageDialogListener implements android.content.DialogInterface.OnShowListener {
        private final WeakReference<MainActivity> ref;
        private final AlertDialog dialog;
        private final Channel channel;

        ManageDialogListener(MainActivity a, AlertDialog dialog, Channel channel) {
            this.ref = new WeakReference<>(a);
            this.dialog = dialog;
            this.channel = channel;
        }

        @Override
        public void onShow(android.content.DialogInterface d) {
            MainActivity a = ref.get();
            if (a == null) return;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(new RefreshModelsClickListener(a, dialog, channel));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(new LogoutClickListener(a, dialog, channel));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setOnClickListener(new DismissClickListener(dialog));
        }
    }

    private static final class RefreshModelsClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;
        private final AlertDialog dialog;
        private final Channel channel;

        RefreshModelsClickListener(MainActivity a, AlertDialog dialog, Channel channel) {
            this.ref = new WeakReference<>(a);
            this.dialog = dialog;
            this.channel = channel;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a == null) return;
            new Thread(new RefreshModelsTask(a, dialog, channel), "refresh-models").start();
        }
    }

    private static final class LogoutClickListener implements View.OnClickListener {
        private final WeakReference<MainActivity> ref;
        private final AlertDialog dialog;
        private final Channel channel;

        LogoutClickListener(MainActivity a, AlertDialog dialog, Channel channel) {
            this.ref = new WeakReference<>(a);
            this.dialog = dialog;
            this.channel = channel;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = ref.get();
            if (a == null) return;
            a.config.removeChannel(channel.id);
            dialog.dismiss();
            a.refreshAll();
        }
    }

    // -----------------------------------------------------------------------
    // API Key 渠道编辑
    // -----------------------------------------------------------------------

    private void showProxyDialog(Channel existing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        box.setPadding(pad, pad, pad, pad);

        EditText idInput = new EditText(this);
        idInput.setHint("前缀 id（如 ds）");
        idInput.setInputType(InputType.TYPE_CLASS_TEXT);
        EditText nameInput = new EditText(this);
        nameInput.setHint("名称（如 DeepSeek 官方）");
        EditText urlInput = new EditText(this);
        urlInput.setHint("Base URL（https://api.deepseek.com/v1）");
        urlInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        EditText keyInput = new EditText(this);
        keyInput.setHint("API Key（多个用逗号分隔）");
        EditText modelInput = new EditText(this);
        modelInput.setHint("可选：模型清单（逗号分隔）");

        if (existing != null) {
            idInput.setText(existing.id);
            idInput.setEnabled(false);
            nameInput.setText(existing.name);
            urlInput.setText(existing.baseUrl);
            keyInput.setText(join(existing.apiKeys));
            modelInput.setText(join(existing.manualModels));
        }

        box.addView(idInput);
        box.addView(nameInput);
        box.addView(urlInput);
        box.addView(keyInput);
        box.addView(modelInput);

        box.addView(Ui.caption(this, "常用：deepseek.com/v1 · siliconflow.cn/v1 · openrouter.ai/api/v1"));
        box.addView(Ui.caption(this, "模型 id 写成 <前缀>:<模型>，例如 ds:deepseek-chat"));

        AlertDialog dialog = buildInputDialog(existing == null ? "添加 API Key 渠道" : "编辑渠道", box, "保存");
        if (existing != null) {
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "删除", new SilentClickListener());
        }
        dialog.setOnShowListener(new ProxyDialogListener(this, dialog, existing,
                idInput, nameInput, urlInput, keyInput, modelInput));
        dialog.show();
    }

    private static final class ProxyDialogListener implements android.content.DialogInterface.OnShowListener {
        private final WeakReference<MainActivity> ref;
        private final AlertDialog dialog;
        private final Channel existing;
        private final EditText idInput;
        private final EditText nameInput;
        private final EditText urlInput;
        private final EditText keyInput;
        private final EditText modelInput;

        ProxyDialogListener(MainActivity a, AlertDialog dialog, Channel existing,
                            EditText idInput, EditText nameInput, EditText urlInput,
                            EditText keyInput, EditText modelInput) {
            this.ref = new WeakReference<>(a);
            this.dialog = dialog;
            this.existing = existing;
            this.idInput = idInput;
            this.nameInput = nameInput;
            this.urlInput = urlInput;
            this.keyInput = keyInput;
            this.modelInput = modelInput;
        }

        @Override
        public void onShow(android.content.DialogInterface d) {
            MainActivity a = ref.get();
            if (a == null) return;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(new SaveProxyClickListener(this));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setOnClickListener(new DismissClickListener(dialog));
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                        .setOnClickListener(new DeleteProxyClickListener(this));
            }
        }
    }

    private static final class SaveProxyClickListener implements View.OnClickListener {
        private final ProxyDialogListener owner;

        SaveProxyClickListener(ProxyDialogListener owner) {
            this.owner = owner;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = owner.ref.get();
            if (a == null) return;
            if (a.saveProxy(owner.existing, owner.idInput, owner.nameInput,
                    owner.urlInput, owner.keyInput, owner.modelInput)) {
                owner.dialog.dismiss();
            }
        }
    }

    private static final class DeleteProxyClickListener implements View.OnClickListener {
        private final ProxyDialogListener owner;

        DeleteProxyClickListener(ProxyDialogListener owner) {
            this.owner = owner;
        }

        @Override
        public void onClick(View v) {
            MainActivity a = owner.ref.get();
            if (a == null) return;
            a.config.removeChannel(owner.existing.id);
            a.refreshAll();
            owner.dialog.dismiss();
        }
    }

    private boolean saveProxy(Channel existing, EditText idInput, EditText nameInput,
                              EditText urlInput, EditText keyInput, EditText modelInput) {
        String id = idInput.getText().toString().trim().toLowerCase();
        if (!Channel.isValidId(id)) {
            Toast.makeText(this, "前缀 id 不合法：小写字母开头，只能含小写字母/数字/短横线，≤16 字符", Toast.LENGTH_LONG).show();
            return false;
        }
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "Base URL 必填", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);

        Channel c = existing == null ? new Channel() : existing;
        c.id = id;
        c.kind = Channel.KIND_PROXY;
        c.name = nameInput.getText().toString().trim();
        if (c.name.isEmpty()) c.name = id;
        c.baseUrl = url;
        c.apiKeys = split(keyInput.getText().toString());
        c.manualModels = split(modelInput.getText().toString());
        c.enabled = true;
        config.upsertChannel(c);
        refreshAll();
        Toast.makeText(this, "已保存渠道 " + id, Toast.LENGTH_SHORT).show();
        return true;
    }

    // -----------------------------------------------------------------------
    // 后台任务
    // -----------------------------------------------------------------------

    private static final class DeviceLoginStarter implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final String loginType;

        DeviceLoginStarter(MainActivity a, String loginType) {
            this.ref = new WeakReference<>(a);
            this.loginType = loginType;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            try {
                LoginProviders.Spec spec = LoginProviders.specOf(loginType);
                LoginProviders.DeviceState ds = LoginProviders.requestDeviceState(loginType);
                a.ui.post(new ShowLoginProgressTask(a, loginType, spec, ds));
            } catch (Exception e) {
                a.ui.post(new ShowToastTask(a, "无法发起登录：" + e.getMessage(), true));
            }
        }
    }

    private static final class ShowLoginProgressTask implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final String loginType;
        private final LoginProviders.Spec spec;
        private final LoginProviders.DeviceState ds;

        ShowLoginProgressTask(MainActivity a, String loginType, LoginProviders.Spec spec,
                              LoginProviders.DeviceState ds) {
            this.ref = new WeakReference<>(a);
            this.loginType = loginType;
            this.spec = spec;
            this.ds = ds;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a != null) a.showLoginProgress(loginType, spec, ds);
        }
    }

    private static final class ShowToastTask implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final String message;
        private final boolean longDuration;

        ShowToastTask(MainActivity a, String message, boolean longDuration) {
            this.ref = new WeakReference<>(a);
            this.message = message;
            this.longDuration = longDuration;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a != null) {
                Toast.makeText(a, message, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static final class PollLoginTask implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final String loginType;
        private final LoginProviders.DeviceState ds;
        private final TextView stateView;
        private final AlertDialog dialog;

        PollLoginTask(MainActivity a, String loginType, LoginProviders.DeviceState ds,
                      TextView stateView, AlertDialog dialog) {
            this.ref = new WeakReference<>(a);
            this.loginType = loginType;
            this.ds = ds;
            this.stateView = stateView;
            this.dialog = dialog;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            Channel probe = new Channel();
            probe.id = loginType;
            probe.kind = Channel.KIND_LOGIN;
            probe.loginType = loginType;
            probe.loginState = ds.state;

            long deadline = System.currentTimeMillis() + 5 * 60 * 1000L;
            int tick = 0;
            while (System.currentTimeMillis() < deadline) {
                try {
                    Channel result = LoginProviders.pollToken(probe);
                    tick++;
                    if (result != null) {
                        LoginProviders.Spec spec = LoginProviders.specOf(loginType);
                        result.id = loginType;
                        result.name = spec == null ? loginType : spec.displayName;
                        result.kind = Channel.KIND_LOGIN;
                        result.enabled = true;
                        // 只有「没有真实清单」且「该渠道没有实时目录接口」时才用内置表。
                        // WorkBuddy 有目录接口：拉不到就是拉不到，绝不拿静态表凑数
                        // —— 否则界面又会列出账号根本没权限的模型（上一版就是这个毛病）。
                        if (result.cachedModels.isEmpty()
                                && result.manualModels.isEmpty()
                                && !LoginProviders.hasLiveModelCatalog(loginType)) {
                            result.manualModels = LoginProviders.defaultModelsFor(loginType);
                            // Qoder / Raccoon 的内置表带完整规格与积分倍率，登录时就写进去，
                            // 界面不必等用户点「刷新模型」才显示真实名与倍率
                            if (LoginProviders.QODER.equals(loginType)
                                    || LoginProviders.QODER_CN.equals(loginType)) {
                                result.modelSpecs.putAll(LoginProviders.qoderBuiltinSpecs());
                            } else if (LoginProviders.RACCOON.equals(loginType)) {
                                result.modelSpecs.putAll(LoginProviders.raccoonBuiltinSpecs());
                            } else if (LoginProviders.CODEARTS.equals(loginType)) {
                                result.modelSpecs.putAll(LoginProviders.codeartsBuiltinSpecs());
                            }
                        }
                        a.config.upsertChannel(result);
                        a.ui.post(new LoginSuccessTask(a, dialog, result));
                        return;
                    }
                    a.ui.post(new PollProgressTask(stateView, tick));
                } catch (Exception e) {
                    a.ui.post(new PollProgressTask(stateView, tick, e.getMessage()));
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
            a.ui.post(new PollProgressTask(stateView, tick, "超时（5 分钟），可关掉重试"));
        }
    }

    private static final class LoginSuccessTask implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final AlertDialog dialog;
        private final Channel channel;

        LoginSuccessTask(MainActivity a, AlertDialog dialog, Channel channel) {
            this.ref = new WeakReference<>(a);
            this.dialog = dialog;
            this.channel = channel;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            dialog.dismiss();
            a.refreshAll();
            // 登录成功顺手查一次积分 / 额度，省得用户再点一下。
            // 走统一入口：把新渠道加入 targets，与「自动查一轮」共用同一套状态管理
            if (Credits.supported(channel.loginType)) {
                new Thread(new CreditsTask(a, channel), "credits").start();
            }
            Toast.makeText(a, "登录成功：" + channel.nickname, Toast.LENGTH_LONG).show();
            // 新登录的渠道当天还没签到过 → 立刻补一次
            a.autoCheckinStarted = false;
            a.maybeAutoCheckin();
            // 其余渠道的积分也允许重新扫一遍（新渠道刚加进来）
            a.creditsAutoStarted = false;
            a.maybeAutoQueryCredits();
        }
    }

    private static final class PollProgressTask implements Runnable {
        private final TextView stateView;
        private final int tick;
        private final String error;

        PollProgressTask(TextView stateView, int tick) {
            this(stateView, tick, null);
        }

        PollProgressTask(TextView stateView, int tick, String error) {
            this.stateView = stateView;
            this.tick = tick;
            this.error = error;
        }

        @Override
        public void run() {
            if (stateView == null) return;
            if (error == null) {
                stateView.setText("等待登录…（已查 " + tick + " 次，完成后自动识别）");
            } else {
                stateView.setText(error);
            }
        }
    }

    private static final class RefreshModelsTask implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final AlertDialog dialog;
        private final Channel channel;

        RefreshModelsTask(MainActivity a, AlertDialog dialog, Channel channel) {
            this.ref = new WeakReference<>(a);
            this.dialog = dialog;
            this.channel = channel;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            // 走统一入口：有实时目录的（WorkBuddy）真去拉，没有的（Qoder/ZCode/Raccoon）
            // 直接用内置表并说明来源 —— 不再对没有目录接口的渠道打一个注定失败的请求
            ModelCatalog.RefreshResult result = ModelCatalog.refresh(channel);
            if (result.ok) {
                a.config.upsertChannel(channel);
            }
            a.ui.post(new RefreshModelsDoneTask(a, dialog, result.message));
        }
    }

    private static final class RefreshModelsDoneTask implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final AlertDialog dialog;
        private final String message;

        RefreshModelsDoneTask(MainActivity a, AlertDialog dialog, String message) {
            this.ref = new WeakReference<>(a);
            this.dialog = dialog;
            this.message = message;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            if (dialog != null) dialog.dismiss();
            a.refreshAll();
            Toast.makeText(a, message, Toast.LENGTH_SHORT).show();
        }
    }

    // -----------------------------------------------------------------------
    // 直接登录：后台任务
    // -----------------------------------------------------------------------

    /** 发起直接登录（建二维码 / 拼授权页），完成后弹进度框。 */
    private static final class DirectLoginStarter implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final String loginType;

        DirectLoginStarter(MainActivity a, String loginType) {
            this.ref = new WeakReference<>(a);
            this.loginType = loginType;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            try {
                ChannelLogin.LoginSession session = ChannelLogin.begin(loginType);
                a.ui.post(new ShowDirectLoginTask(a, loginType, session));
            } catch (Exception e) {
                a.ui.post(new ShowToastTask(a, "无法发起登录：" + e.getMessage(), true));
            }
        }
    }

    private static final class ShowDirectLoginTask implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final String loginType;
        private final ChannelLogin.LoginSession session;

        ShowDirectLoginTask(MainActivity a, String loginType, ChannelLogin.LoginSession session) {
            this.ref = new WeakReference<>(a);
            this.loginType = loginType;
            this.session = session;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a != null) a.showDirectLoginDialog(loginType, session);
        }
    }

    /** 轮询直到登录成功 / 失败 / 超时。 */
    private static final class DirectPollTask implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final ChannelLogin.LoginSession session;
        private final String code;
        private final TextView stateView;
        private final AlertDialog dialog;

        DirectPollTask(MainActivity a, ChannelLogin.LoginSession session, String code,
                       TextView stateView, AlertDialog dialog) {
            this.ref = new WeakReference<>(a);
            this.session = session;
            this.code = code;
            this.stateView = stateView;
            this.dialog = dialog;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            long deadline = System.currentTimeMillis() + 5 * 60 * 1000L;
            if (session.expiresAt > 0 && session.expiresAt < deadline) deadline = session.expiresAt;
            long interval = Math.max(1000L, session.pollIntervalMs);
            int tick = 0;

            while (System.currentTimeMillis() < deadline) {
                try {
                    ChannelLogin.PollResult result = ChannelLogin.poll(session, code);
                    tick += 1;
                    if (result.channel != null) {
                        saveAndFinish(a, result.channel, dialog);
                        return;
                    }
                    if (result.terminal) {
                        a.ui.post(new PollProgressTask(stateView, tick, result.message));
                        return;
                    }
                    a.ui.post(new PollProgressTask(stateView, tick, result.message));
                } catch (Exception e) {
                    a.ui.post(new PollProgressTask(stateView, tick, e.getMessage()));
                }
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException ie) {
                    return;
                }
            }
            a.ui.post(new PollProgressTask(stateView, tick, "已超时，可关掉重试"));
        }
    }

    /** ZCode：手动提交一次 code（不循环，避免把无效 code 反复送给上游）。 */
    private static final class DirectPollOnceTask implements Runnable {
        private final WeakReference<MainActivity> ref;
        private final ChannelLogin.LoginSession session;
        private final String code;
        private final TextView stateView;
        private final AlertDialog dialog;

        DirectPollOnceTask(MainActivity a, ChannelLogin.LoginSession session, String code,
                           TextView stateView, AlertDialog dialog) {
            this.ref = new WeakReference<>(a);
            this.session = session;
            this.code = code;
            this.stateView = stateView;
            this.dialog = dialog;
        }

        @Override
        public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            try {
                ChannelLogin.PollResult result = ChannelLogin.poll(session, code);
                if (result.channel != null) {
                    saveAndFinish(a, result.channel, dialog);
                    return;
                }
                a.ui.post(new PollProgressTask(stateView, 1, result.message));
            } catch (Exception e) {
                a.ui.post(new PollProgressTask(stateView, 1, "失败：" + e.getMessage()));
            }
        }
    }

    /** 登录成功后落库并刷新界面。 */
    private static void saveAndFinish(MainActivity a, Channel channel, AlertDialog dialog) {
        a.config.upsertChannel(channel);
        a.ui.post(new LoginSuccessTask(a, dialog, channel));
    }

    // -----------------------------------------------------------------------
    // 小工具
    // -----------------------------------------------------------------------

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String part : s.split("[,\\n]")) {
            String v = part.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
