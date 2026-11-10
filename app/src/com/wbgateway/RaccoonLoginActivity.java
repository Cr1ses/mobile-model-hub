package com.wbgateway;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Raccoon（商汤）登录页 —— 用 WebView 走官方网页登录。
 *
 * <h3>为什么必须用 WebView，而不是原生表单</h3>
 *
 * Raccoon 的手机号登录有**阿里云无感验证**（`initAliyunCaptcha`，
 * SceneId `1pkmy0x3`），服务端对 `captcha_token` 做了**来源绑定**。实测结论：
 *
 * <pre>
 * 无 token                                    → captcha_token required
 * 随手编的 token                              → captcha_token invalid
 * 阿里云组件在本机拿到的真 token（跨上下文发）  → captcha_token invalid
 * 网页自己发出去的真 token（抓包后重放）       → captcha_token invalid
 * </pre>
 *
 * 也就是「同一次页面会话里由页面自己发」才被认。所以短信登录**只能在页面环境里完成**，
 * 原生界面重放一定失败 —— 这不是实现偷懒，是服务端校验决定的。
 *
 * <h3>登完之后怎么拿 token</h3>
 *
 * 网页的 token 存在 **cookie** 里（`getToken: () => readCookie(document.cookie,
 * config.cookieName)`），不是 localStorage。`cookieName` 来自运行时配置、
 * 并非固定字符串，因此这里按站点自己的命名规则识别（见 {@link #READ_TOKEN_JS}），
 * 而不是硬编码某个键名 —— 硬编码在被混淆/改前缀后会静默失效。
 *
 * <p>取 token 用**轮询页面**而不是监听 URL：登录是在同一个页面里完成的，
 * 页面不会跳转，只靠 URL 变化永远等不到。
 */
public class RaccoonLoginActivity extends Activity {

    /** 登录页地址。 */
    private static final String LOGIN_URL = "https://xiaohuanxiong.com/login";

    /** 轮询间隔：先密后疏，登录成功后 1 秒内就能取到。 */
    private static final long[] POLL_DELAYS_MS = {800L, 800L, 1200L, 2000L, 3000L, 5000L};

    /** 整体等待上限（用户可能要看短信、等验证码）。 */
    private static final long TIMEOUT_MS = 5 * 60 * 1000L;

    /**
     * 读登录 token 的 JS。
     *
     * <h3>为什么不硬编码一个 cookie 名</h3>
     *
     * 网页真实的取 token 是 `readCookie(document.cookie, config.cookieName)`，
     * 而 `cookieName` 在**运行时配置**里（`self.__next_f` 下发的 config），
     * 不是固定字符串；同一个站点的不同产品还会换前缀。
     *
     * 站点自己的派生规则（从 bundle 里读出来的）是：
     * <pre>
     * cookieName 形如 `xxxxxhx_token`
     *   访问令牌   = cookieName 去掉尾部 "xhx_token" + "raccoon_token"
     *   刷新令牌   = 去尾后 + "raccoon_refresh_token"
     * </pre>
     * 所以这里**不猜键名**，而是：先从 cookie 里认出符合 `*xhx_token` 的那一项，
     * 按上式推导出访问令牌名；再兜底扫一遍所有名字里带 `raccoon_token`
     * （排除 `refresh`）的 cookie。两条都拿不到才算未登录。
     *
     * <p>这样即使站点把前缀换掉、或把 cookieName 改了，只要命名规则还在就能取到；
     * 真取不到时宁可报「没登录成功」也不塞一个错值进去。
     */
    private static final String READ_TOKEN_JS =
            "(function(){"
                    + "function all(){"
                    + "  var out={};"
                    + "  try{"
                    + "    var cs=document.cookie?document.cookie.split(';'):[];"
                    + "    for(var i=0;i<cs.length;i++){"
                    + "      var p=cs[i].indexOf('=');"
                    + "      if(p<=0) continue;"
                    + "      var k=cs[i].slice(0,p).trim();"
                    + "      var v=cs[i].slice(p+1).trim();"
                    + "      try{ v=decodeURIComponent(v); }catch(e){}"
                    + "      out[k]=v;"
                    + "    }"
                    + "  }catch(e){}"
                    + "  return out;"
                    + "}"
                    + "var c=all(),k,prefix=null;"
                    // ① 先按站点规则从 *xhx_token 推导前缀（不 break：找第一个即可）
                    + "for(k in c){"
                    + "  if(prefix===null && /^[A-Za-z0-9_-]*xhx_token$/.test(k)){"
                    + "    prefix=k.slice(0,-9);"
                    + "  }"
                    + "}"
                    + "if(prefix!==null){"
                    + "  var t=c[prefix+'raccoon_token'];"
                    + "  if(t) return t;"
                    + "}"
                    // ② 兜底：所有名字里带 raccoon_token 的（排除 refresh）
                    + "for(k in c){"
                    + "  if(/raccoon_token$/i.test(k) && !/refresh/i.test(k) && c[k]) return c[k];"
                    + "}"
                    + "return '';"
                    + "})()";

    private WebView webView;
    private ProgressBar progress;
    private TextView hint;
    private Button cancel;
    private LinearLayout errorBar;

    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private long startedAt = 0L;
    private int pollIndex = 0;
    private boolean finished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startedAt = System.currentTimeMillis();
        setContentView(buildRoot());
        setupWebView();
        webView.loadUrl(LOGIN_URL);
        schedulePoll();
    }

    // -----------------------------------------------------------------------
    // 界面
    // -----------------------------------------------------------------------

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // 顶部条：标题 + 取消
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 14);
        bar.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 10));
        bar.setBackgroundColor(Ui.CARD);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(Ui.weight1());
        titles.addView(Ui.body(this, "登录 Raccoon（商汤）"));
        hint = Ui.caption(this, "用手机号 + 短信验证码登录，完成后自动回到 App");
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        titles.addView(hint);
        bar.addView(titles);

        cancel = Ui.linkButton(this, "取消", Ui.MUTED);
        cancel.setOnClickListener(new CancelClickListener(this));
        bar.addView(cancel);
        root.addView(bar);

        // 进度条
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 2)));

        // 出错提示条（默认隐藏）
        errorBar = new LinearLayout(this);
        errorBar.setOrientation(LinearLayout.HORIZONTAL);
        errorBar.setGravity(Gravity.CENTER_VERTICAL);
        errorBar.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 8));
        errorBar.setBackgroundColor(0xFF2D1B1B);
        TextView errText = Ui.caption(this, "网页加载失败 —— 检查网络后重试");
        errText.setTextColor(Ui.RED);
        errText.setLayoutParams(Ui.weight1());
        errorBar.addView(errText);
        Button retry = Ui.linkButton(this, "重试", Ui.BLUE);
        retry.setOnClickListener(new RetryClickListener(this));
        errorBar.addView(retry);
        errorBar.setVisibility(View.GONE);
        root.addView(errorBar);

        // WebView 占满剩余
        FrameLayout container = new FrameLayout(this);
        container.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(container);
        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setBackgroundColor(Color.WHITE);
        container.addView(webView);

        return root;
    }

    /** 配置 WebView：要能跑 JS、能存 cookie/localStorage、能弹窗。 */
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);            // 站点用了 localStorage
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // 与常见手机浏览器一致，避免站点给非移动端页面
        s.setUserAgentString(s.getUserAgentString() + " MobileModelHub/1.0");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new LoginWebViewClient(this));
        webView.setWebChromeClient(new LoginChromeClient(this));
    }

    // -----------------------------------------------------------------------
    // 轮询取 token
    // -----------------------------------------------------------------------

    private void schedulePoll() {
        if (finished) return;
        long delay = POLL_DELAYS_MS[Math.min(pollIndex, POLL_DELAYS_MS.length - 1)];
        pollIndex += 1;
        ui.postDelayed(new PollTokenTask(this), delay);
    }

    /**
     * 读一次登录态，取到就结束。
     *
     * <p>策略是「**先收集候选，再用真实请求验证**」，而不是单靠 cookie 命名判断：
     * 站点真实的 token cookie 名由运行时配置决定、且该站点会用 HttpOnly cookie，
     * 只靠猜名字迟早出错（错了的症状是「登录成功但一用就 401」）。
     * 现在把候选逐个拿去调 `user_info`，能调通的那个就是真令牌。
     *
     * <p>候选来自三条路：
     * <ol>
     *   <li>`CookieManager.getCookie()` —— 原生侧读，HttpOnly 也能拿到；</li>
     *   <li>注入 JS 读 `document.cookie` —— 覆盖 JS 可读的存储；</li>
     *   <li>再补一个「按规则推导」的结果（已在 ① 里）。</li>
     * </ol>
     */
    void pollOnce() {
        if (finished || webView == null) return;
        if (System.currentTimeMillis() - startedAt > TIMEOUT_MS) {
            setHint("登录超时，请重试");
            return;
        }
        // ① 原生 cookie 罐（同步、无时序问题）
        java.util.List<String> candidates = new java.util.ArrayList<>();
        String raw = null;
        try {
            raw = CookieManager.getInstance().getCookie("https://xiaohuanxiong.com");
        } catch (Exception ignored) {
        }
        if (raw != null && !raw.isEmpty()) {
            candidates.addAll(RaccoonToken.candidateTokens(RaccoonToken.parseCookieHeader(raw)));
        }
        if (!candidates.isEmpty()) {
            setHint("已检测到登录状态，正在校验…");
            new Thread(new VerifyTask(this, candidates), "raccoon-login-verify").start();
            return;
        }
        // ② 让页面自报（JS 可读的 cookie）
        try {
            webView.evaluateJavascript(READ_TOKEN_JS, new TokenCallback(this));
        } catch (Exception e) {
            schedulePoll();
        }
    }

    /**
     * 校验候选令牌：能调通 `user_info` 的那个才是真的。
     *
     * <p>后台线程执行（不能在主线程做网络请求）。
     */
    private static final class VerifyTask implements Runnable {
        private final java.lang.ref.WeakReference<RaccoonLoginActivity> ref;
        private final java.util.List<String> candidates;

        VerifyTask(RaccoonLoginActivity a, java.util.List<String> candidates) {
            this.ref = new java.lang.ref.WeakReference<>(a);
            this.candidates = candidates;
        }

        @Override
        public void run() {
            RaccoonLoginActivity a = ref.get();
            if (a == null) return;
            String valid = a.findValidToken(candidates);
            if (valid.isEmpty()) {
                a.ui.post(new RetryPollTask(a));
                return;
            }
            a.ui.post(new TokenAcceptedTask(a, valid));
        }
    }

    /** 候选里找出真正可用的令牌；都不可用返回空串。 */
    String findValidToken(java.util.List<String> candidates) {
        LoginProviders.Spec spec = LoginProviders.specOf(LoginProviders.RACCOON);
        if (spec == null) return "";
        for (String token : candidates) {
            Channel probe = new Channel();
            probe.accessToken = token;
            probe.officeIdentity = "";
            try {
                org.json.JSONObject body = ChannelLogin.requestJsonPublic(
                        "GET", spec.endpoint + "/api/web/auth/v1/user_info", spec, probe, null, 10_000);
                // 拿到 data 即说明令牌有效
                if (body.optJSONObject("data") != null) return token;
            } catch (Exception ignored) {
                // 这个候选不行，试下一个
            }
        }
        return "";
    }

    /** 令牌校验通过的后续：切后台补信息再回传。 */
    private static final class TokenAcceptedTask implements Runnable {
        private final java.lang.ref.WeakReference<RaccoonLoginActivity> ref;
        private final String token;

        TokenAcceptedTask(RaccoonLoginActivity a, String token) {
            this.ref = new java.lang.ref.WeakReference<>(a);
            this.token = token;
        }

        @Override
        public void run() {
            RaccoonLoginActivity a = ref.get();
            if (a != null) a.finish(this.token);
        }
    }

    /** 校验没通过：继续下一轮（可能用户还没登录完）。 */
    private static final class RetryPollTask implements Runnable {
        private final java.lang.ref.WeakReference<RaccoonLoginActivity> ref;

        RetryPollTask(RaccoonLoginActivity a) {
            this.ref = new java.lang.ref.WeakReference<>(a);
        }

        @Override
        public void run() {
            RaccoonLoginActivity a = ref.get();
            if (a != null) a.schedulePoll();
        }
    }

    /**
     * `evaluateJavascript` 的回调：页面自报的候选也要**实际校验**后再用。
     *
     * <p>不能直接把页面给的字符串当令牌 —— JS 读到的可能是派生值或残留值，
     * 只有能调通 `user_info` 的才算数。
     */
    void onTokenResult(String raw) {
        if (finished) return;
        String candidate = unquoteJson(raw);
        if (candidate.isEmpty()) {
            schedulePoll();
            return;
        }
        setHint("已检测到登录状态，正在校验…");
        java.util.List<String> list = new java.util.ArrayList<>();
        list.add(candidate);
        new Thread(new VerifyTask(this, list), "raccoon-login-verify").start();
    }

    /**
     * 拿到 token 后收尾：补账号信息、拉模型、回传结果。
     *
     * <p>⚠ **必须开后台线程**：这里要发 HTTP 请求（`user_info`），而 Android 从
     * 3.0 起在主线程上做网络操作会直接抛 `NetworkOnMainThreadException` ——
     * 表现是「登录成功的瞬间 App 崩掉」。而这条路径由 UI 线程的 Handler 驱动
     * （`pollOnce` → `finish`），不显式切线程就必然踩到。
     *
     * <p>桌面验证抓不到这个问题（桌面没有这条限制），只能靠人读代码或真机跑。
     */
    private void finish(String token) {
        if (finished) return;
        finished = true;
        ui.removeCallbacksAndMessages(null);
        setHint("正在保存登录信息…");
        new Thread(new FinishTask(this, token), "raccoon-login-finish").start();
    }

    /** 后台线程：补账号信息 + 拉模型 + 回传。 */
    private static final class FinishTask implements Runnable {
        private final java.lang.ref.WeakReference<RaccoonLoginActivity> ref;
        private final String token;

        FinishTask(RaccoonLoginActivity a, String token) {
            this.ref = new java.lang.ref.WeakReference<>(a);
            this.token = token;
        }

        @Override
        public void run() {
            RaccoonLoginActivity a = ref.get();
            if (a == null) return;
            Channel c = a.buildChannel(token);
            a.ui.post(new DeliverTask(a, c));
        }
    }

    /** 在后台线程里构造渠道对象（含网络请求）。 */
    Channel buildChannel(String token) {
        LoginProviders.Spec spec = LoginProviders.specOf(LoginProviders.RACCOON);
        Channel c = new Channel();
        c.id = LoginProviders.RACCOON;
        c.loginType = LoginProviders.RACCOON;
        c.kind = Channel.KIND_LOGIN;
        c.name = spec == null ? "Raccoon（商汤）" : spec.displayName;
        c.baseUrl = ChannelLogin.inferBaseUrl(LoginProviders.RACCOON);
        c.accessToken = token;
        c.domain = spec == null ? "xiaohuanxiong.com" : spec.apiDomain;
        c.expiresAt = LoginProviders.jwtExpiresAt(token);
        c.nickname = LoginProviders.jwtNickname(token);
        c.enabled = true;

        // 补昵称与组织标识（失败不影响登录）
        try {
            if (spec != null) {
                org.json.JSONObject body = ChannelLogin.requestJsonPublic(
                        "GET", spec.endpoint + "/api/web/auth/v1/user_info", spec, c, null, 12_000);
                org.json.JSONObject data = body.optJSONObject("data");
                if (data != null) {
                    String name = data.optString("name", "");
                    if (!name.isEmpty()) c.nickname = name;
                    String office = data.optString("office_identity", "");
                    if (!office.isEmpty()) c.officeIdentity = office;
                }
            }
        } catch (Exception ignored) {
            // 账号信息是锦上添花
        }
        if (c.nickname.isEmpty()) {
            // 取不到真实昵称时留空（同上，避免「名字 · 名字」）
        }

        // 拉一次模型清单（端点 `/api/web/llm/v2/model_catalog` 存在，但可能因权限返回空）
        try {
            java.util.List<String> models = ChannelLogin.fetchModels(c);
            if (!models.isEmpty()) c.cachedModels = models;
        } catch (Exception ignored) {
        }
        if (c.cachedModels.isEmpty() && c.manualModels.isEmpty()) {
            c.manualModels = LoginProviders.defaultModelsFor(LoginProviders.RACCOON);
            // 内置表带积分倍率（x0 / x0.25 / x1 …），不写进去界面上就只剩模型 id
            c.modelSpecs.putAll(LoginProviders.raccoonBuiltinSpecs());
        }
        c.modelsUpdatedAt = System.currentTimeMillis();
        return c;
    }

    /** 回到 UI 线程回传结果并关闭。 */
    private static final class DeliverTask implements Runnable {
        private final java.lang.ref.WeakReference<RaccoonLoginActivity> ref;
        private final Channel channel;

        DeliverTask(RaccoonLoginActivity a, Channel channel) {
            this.ref = new java.lang.ref.WeakReference<>(a);
            this.channel = channel;
        }

        @Override
        public void run() {
            RaccoonLoginActivity a = ref.get();
            if (a == null) return;
            Intent out = new Intent();
            out.putExtra(EXTRA_CHANNEL_JSON, channel.toJson().toString());
            a.setResult(RESULT_OK, out);
            a.finish();
        }
    }

    /** 把 `evaluateJavascript` 回传的 JSON 字面量还原成原值（实现在 {@link RaccoonToken}）。 */
    static String unquoteJson(String raw) {
        return RaccoonToken.unquoteJson(raw);
    }
    // -----------------------------------------------------------------------
    // 状态回调（供内部类使用）
    // -----------------------------------------------------------------------

    void setHint(String text) {
        if (hint != null) hint.setText(text);
    }

    /**
     * 更新顶部进度条。
     *
     * <p>⚠ 名字不能叫 `setProgress` —— `Activity.setProgress(int)` 是 final，
     * 同名会被判为「试图覆盖 final 方法」，编译直接失败。
     */
    void updateLoadProgress(int percent) {
        if (progress == null) return;
        if (percent < 0 || percent >= 100) {
            progress.setVisibility(View.GONE);
        } else {
            progress.setVisibility(View.VISIBLE);
            progress.setProgress(percent);
        }
    }

    void showError(boolean show) {
        if (errorBar != null) errorBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    void reload() {
        showError(false);
        setHint("用手机号 + 短信验证码登录，完成后自动回到 App");
        startedAt = System.currentTimeMillis();
        finished = false;
        pollIndex = 0;
        if (webView != null) webView.loadUrl(LOGIN_URL);
        schedulePoll();
    }

    /** 取消：把当前 URL / 提示留在界面上没有意义，直接回传取消。 */
    void cancelAndClose() {
        finished = true;
        ui.removeCallbacksAndMessages(null);
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        cancelAndClose();
    }

    @Override
    protected void onDestroy() {
        finished = true;
        ui.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.setWebChromeClient(null);
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    /** 回传渠道的 key（JSON 字符串）。 */
    public static final String EXTRA_CHANNEL_JSON = "channelJson";

    // -----------------------------------------------------------------------
    // 内部类（⚠ 全部具名静态类：build-tools 34 的 d8 处理匿名类会崩）
    // -----------------------------------------------------------------------

    /** 页面加载进度与错误。 */
    private static final class LoginWebViewClient extends WebViewClient {
        private final java.lang.ref.WeakReference<RaccoonLoginActivity> ref;

        LoginWebViewClient(RaccoonLoginActivity a) {
            this.ref = new java.lang.ref.WeakReference<>(a);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            // 登录相关的跳转都留在 WebView 内（站点会在 /login 与 /agreement 间跳）
            return false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            RaccoonLoginActivity a = ref.get();
            if (a == null) return;
            a.updateLoadProgress(-1);
            a.showError(false);
            // 页面刚加载完可能已经带着登录态（用户上次登录过）
            a.pollOnce();
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            RaccoonLoginActivity a = ref.get();
            if (a == null) return;
            // 只在意主文档失败；子资源失败不该挡住登录
            if (failingUrl != null && failingUrl.startsWith("http")) {
                a.showError(true);
                a.setHint("页面加载失败：" + description);
            }
        }
    }

    /** 进度 / JS 弹窗（网页用了 alert/confirm 时必须接管，否则点不动）。 */
    private static final class LoginChromeClient extends WebChromeClient {
        private final java.lang.ref.WeakReference<RaccoonLoginActivity> ref;

        LoginChromeClient(RaccoonLoginActivity a) {
            this.ref = new java.lang.ref.WeakReference<>(a);
        }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            RaccoonLoginActivity a = ref.get();
            if (a != null) a.updateLoadProgress(newProgress);
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            result.confirm();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            result.confirm();
            return true;
        }
    }

    /**
     * 读 token 的回调。
     *
     * <p>⚠ **不能**写成 `implements android.webkit.ValueCallback<String>`：
     * 那样 javac 会给这个类写一条泛型 `Signature` 属性
     * （`Landroid/webkit/ValueCallback&lt;Ljava/lang/String;&gt;;`），
     * 而 build-tools 34 的 d8 解析这条签名时会崩
     * （`NullPointerException: String.length()`），报错和「源码里有匿名类」一模一样。
     *
     * <p>做法：实现**原始类型** `ValueCallback`，参数按 `Object` 收再转 String。
     * 语义完全一样，但编译产物里没有那条泛型签名。
     */
    private static final class TokenCallback implements android.webkit.ValueCallback {
        private final java.lang.ref.WeakReference<RaccoonLoginActivity> ref;

        TokenCallback(RaccoonLoginActivity a) {
            this.ref = new java.lang.ref.WeakReference<>(a);
        }

        @Override
        public void onReceiveValue(Object value) {
            RaccoonLoginActivity a = ref.get();
            if (a != null) a.onTokenResult(value == null ? "" : String.valueOf(value));
        }
    }

    /** 定时读一次 token。 */
    private static final class PollTokenTask implements Runnable {
        private final java.lang.ref.WeakReference<RaccoonLoginActivity> ref;

        PollTokenTask(RaccoonLoginActivity a) {
            this.ref = new java.lang.ref.WeakReference<>(a);
        }

        @Override
        public void run() {
            RaccoonLoginActivity a = ref.get();
            if (a != null) a.pollOnce();
        }
    }

    private static final class CancelClickListener implements View.OnClickListener {
        private final java.lang.ref.WeakReference<RaccoonLoginActivity> ref;

        CancelClickListener(RaccoonLoginActivity a) {
            this.ref = new java.lang.ref.WeakReference<>(a);
        }

        @Override
        public void onClick(View v) {
            RaccoonLoginActivity a = ref.get();
            if (a != null) a.cancelAndClose();
        }
    }

    private static final class RetryClickListener implements View.OnClickListener {
        private final java.lang.ref.WeakReference<RaccoonLoginActivity> ref;

        RetryClickListener(RaccoonLoginActivity a) {
            this.ref = new java.lang.ref.WeakReference<>(a);
        }

        @Override
        public void onClick(View v) {
            RaccoonLoginActivity a = ref.get();
            if (a != null) a.reload();
        }
    }
}
