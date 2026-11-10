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

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * ZCode（智谱）登录页 —— 内置 WebView 走官方 OAuth，**回调自动捕获**。
 *
 * <h3>为什么能做到「无须其他操作」</h3>
 *
 * ZCode 的 OAuth 回调是自定义 scheme `zcode://oauth/callback?code=…&state=…`。
 * 之前说「手机上没有应用接它」，于是退化成「让用户把 code 粘回来」——那只对了一半：
 * **我们自己的 App 可以接**。把登录放进内置 WebView 后，回调会先落到
 * `zcode.z.ai/app/oauth/login`（一个中转页），再由它跳到 `zcode://oauth/callback`；
 * 这一步在 WebView 里表现为**一次导航**，用 `shouldOverrideUrlLoading` 就能截住，
 * 从 URL 里取出 `code` 与 `state`，直接换 token。
 *
 * 所以既不用注册系统 scheme（本地实测手机上确实没有应用占用它，
 * 但注册会污染系统意图，且用户从外部浏览器登录时未必回到本 App），
 * 也不用用户复制粘贴 —— 全程在这一个页面里闭环。
 *
 * <p>⚠ 关键点：`code` **必须在几十秒内**拿去换 token（一次性且短时效），
 * 所以换成 token 的动作要立刻在后台线程发起，不能等用户再点一次。
 */
public class ZcodeLoginActivity extends Activity {

    /** 用来拿 state 与 redirectUri（换 token 要原样带回）。 */
    private ChannelLogin.LoginSession session;

    private WebView webView;
    private ProgressBar progress;
    private TextView hint;
    private LinearLayout errorBar;

    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean finished = false;
    /** 已经交出去的 code（避免 WebView 重复导航导致换两次 token）。 */
    private String handledCode = "";

    /** 回传渠道的 key（JSON 字符串）。 */
    public static final String EXTRA_CHANNEL_JSON = "channelJson";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
        setupWebView();
        startLogin();
    }

    // -----------------------------------------------------------------------
    // 界面（与 RaccoonLoginActivity 保持同一套结构）
    // -----------------------------------------------------------------------

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 14);
        bar.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 10));
        bar.setBackgroundColor(Ui.CARD);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(Ui.weight1());
        titles.addView(Ui.body(this, "登录 ZCode（智谱）"));
        hint = Ui.caption(this, "在下方页面完成登录，会自动回到 App");
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        titles.addView(hint);
        bar.addView(titles);

        Button cancel = Ui.linkButton(this, "取消", Ui.MUTED);
        cancel.setOnClickListener(new CancelClickListener(this));
        bar.addView(cancel);
        root.addView(bar);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 2)));

        errorBar = new LinearLayout(this);
        errorBar.setOrientation(LinearLayout.HORIZONTAL);
        errorBar.setGravity(Gravity.CENTER_VERTICAL);
        errorBar.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 8));
        errorBar.setBackgroundColor(0xFF2D1B1B);
        TextView errText = Ui.caption(this, "页面加载失败 —— 检查网络后重试");
        errText.setTextColor(Ui.RED);
        errText.setLayoutParams(Ui.weight1());
        errorBar.addView(errText);
        Button retry = Ui.linkButton(this, "重试", Ui.BLUE);
        retry.setOnClickListener(new RetryClickListener(this));
        errorBar.addView(retry);
        errorBar.setVisibility(View.GONE);
        root.addView(errorBar);

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

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " MobileModelHub/1.0");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new LoginWebViewClient(this));
        webView.setWebChromeClient(new LoginChromeClient(this));
    }

    // -----------------------------------------------------------------------
    // 登录流程
    // -----------------------------------------------------------------------

    /** 在后台线程拼授权 URL，然后加载。 */
    private void startLogin() {
        setHint("正在准备登录…");
        new Thread(new PrepareTask(this), "zcode-prepare").start();
    }

    /** 后台拼 URL（ChannelLogin.begin 会走网络）。 */
    private static final class PrepareTask implements Runnable {
        private final WeakReference<ZcodeLoginActivity> ref;

        PrepareTask(ZcodeLoginActivity a) {
            this.ref = new WeakReference<>(a);
        }

        @Override
        public void run() {
            ZcodeLoginActivity a = ref.get();
            if (a == null) return;
            try {
                ChannelLogin.LoginSession s = ChannelLogin.begin(LoginProviders.ZCODE);
                a.ui.post(new LoadUrlTask(a, s));
            } catch (Exception e) {
                a.ui.post(new ShowErrorTask(a, "无法发起登录：" + e.getMessage()));
            }
        }
    }

    private static final class LoadUrlTask implements Runnable {
        private final WeakReference<ZcodeLoginActivity> ref;
        private final ChannelLogin.LoginSession session;

        LoadUrlTask(ZcodeLoginActivity a, ChannelLogin.LoginSession session) {
            this.ref = new WeakReference<>(a);
            this.session = session;
        }

        @Override
        public void run() {
            ZcodeLoginActivity a = ref.get();
            if (a == null || a.finished) return;
            a.session = session;
            a.setHint("在下方页面登录，完成后自动识别");
            a.webView.loadUrl(session.loginUrl);
        }
    }

    /**
     * 捕获回调 URL 里的 code。
     *
     * <p>两条路：
     * <ol>
     *   <li>自定义 scheme `zcode://oauth/callback?code=…`（正式回调）；</li>
     *   <li>中转页 `zcode.z.ai/app/oauth/login?…&code=…`（有些情况下 code 直接挂在 https 上）。</li>
     * </ol>
     * 命中后立刻返回 true 阻止继续导航，并在后台换 token。
     */
    void handlePossibleCallback(String url) {
        if (finished || url == null) return;
        String code = extractCodeFromUrl(url);
        if (code.isEmpty()) return;
        if (code.equals(handledCode)) return;   // 同一次登录只换一次
        handledCode = code;
        setHint("已拿到授权码，正在换取凭据…");
        new Thread(new ExchangeTask(this, code), "zcode-exchange").start();
    }

    /**
     * 从 URL 里取 `code`（**纯函数**，可离线验证）。
     *
     * <p>只认这几种形态，避免把 `state` 之类误当 code：
     * <ul>
     *   <li>`zcode://oauth/callback?code=xxx`</li>
     *   <li>`https://zcode.z.ai/app/oauth/login?...&code=xxx`</li>
     *   <li>`authCode=xxx`（同义参数）</li>
     * </ul>
     */
    static String extractCodeFromUrl(String url) {
        return OAuthCallback.zcodeCode(url);
    }

    /** 取查询参数（自己解析，避免依赖 Uri 的行为差异）。 */
    static String queryParam(String url, String key) {
        return OAuthCallback.queryParam(url, key);
    }

    /** 后台用 code 换 token。 */
    private static final class ExchangeTask implements Runnable {
        private final WeakReference<ZcodeLoginActivity> ref;
        private final String code;

        ExchangeTask(ZcodeLoginActivity a, String code) {
            this.ref = new WeakReference<>(a);
            this.code = code;
        }

        @Override
        public void run() {
            ZcodeLoginActivity a = ref.get();
            if (a == null) return;
            try {
                ChannelLogin.PollResult r = ChannelLogin.poll(a.session, code);
                if (r.channel != null) {
                    a.ui.post(new DeliverTask(a, r.channel));
                } else {
                    a.ui.post(new ShowErrorTask(a, r.message.isEmpty() ? "换取凭据失败" : r.message));
                }
            } catch (Exception e) {
                a.ui.post(new ShowErrorTask(a, "换取凭据失败：" + e.getMessage()));
            }
        }
    }

    /** 回到 UI 线程回传结果并关闭。 */
    private static final class DeliverTask implements Runnable {
        private final WeakReference<ZcodeLoginActivity> ref;
        private final Channel channel;

        DeliverTask(ZcodeLoginActivity a, Channel channel) {
            this.ref = new WeakReference<>(a);
            this.channel = channel;
        }

        @Override
        public void run() {
            ZcodeLoginActivity a = ref.get();
            if (a == null || a.finished) return;
            a.finished = true;
            a.ui.removeCallbacksAndMessages(null);
            Intent out = new Intent();
            out.putExtra(EXTRA_CHANNEL_JSON, channel.toJson().toString());
            a.setResult(RESULT_OK, out);
            a.finish();
        }
    }

    private static final class ShowErrorTask implements Runnable {
        private final WeakReference<ZcodeLoginActivity> ref;
        private final String message;

        ShowErrorTask(ZcodeLoginActivity a, String message) {
            this.ref = new WeakReference<>(a);
            this.message = message;
        }

        @Override
        public void run() {
            ZcodeLoginActivity a = ref.get();
            if (a == null) return;
            a.setHint(message);
        }
    }

    // -----------------------------------------------------------------------
    // 状态回调
    // -----------------------------------------------------------------------

    void setHint(String text) {
        if (hint != null) hint.setText(text);
    }

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
        handledCode = "";
        startLogin();
    }

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

    // -----------------------------------------------------------------------
    // 内部类（全部具名静态类：d8 对匿名类会崩）
    // -----------------------------------------------------------------------

    private static final class LoginWebViewClient extends WebViewClient {
        private final WeakReference<ZcodeLoginActivity> ref;

        LoginWebViewClient(ZcodeLoginActivity a) {
            this.ref = new WeakReference<>(a);
        }

        /** 拦截回调：命中就吃掉这次导航。 */
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            ZcodeLoginActivity a = ref.get();
            if (a == null) return false;
            if (!extractCodeFromUrl(url).isEmpty()) {
                a.handlePossibleCallback(url);
                return true;   // 不再导航（zcode:// 本来也没人接）
            }
            // 自定义 scheme 但不是回调 → 直接拦掉，否则 WebView 会报 ERR_UNKNOWN_URL_SCHEME
            if (url != null && url.startsWith("zcode://")) return true;
            return false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            ZcodeLoginActivity a = ref.get();
            if (a == null) return;
            a.updateLoadProgress(-1);
            a.showError(false);
            // 有些流程 code 会直接挂在 https 中转页上 → 每次加载完也检查一次
            a.handlePossibleCallback(url);
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            ZcodeLoginActivity a = ref.get();
            if (a == null) return;
            if (failingUrl != null && failingUrl.startsWith("http")) {
                a.showError(true);
                a.setHint("页面加载失败：" + description);
            }
        }
    }

    private static final class LoginChromeClient extends WebChromeClient {
        private final WeakReference<ZcodeLoginActivity> ref;

        LoginChromeClient(ZcodeLoginActivity a) {
            this.ref = new WeakReference<>(a);
        }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            ZcodeLoginActivity a = ref.get();
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

    private static final class CancelClickListener implements View.OnClickListener {
        private final WeakReference<ZcodeLoginActivity> ref;

        CancelClickListener(ZcodeLoginActivity a) {
            this.ref = new WeakReference<>(a);
        }

        @Override
        public void onClick(View v) {
            ZcodeLoginActivity a = ref.get();
            if (a != null) a.cancelAndClose();
        }
    }

    private static final class RetryClickListener implements View.OnClickListener {
        private final WeakReference<ZcodeLoginActivity> ref;

        RetryClickListener(ZcodeLoginActivity a) {
            this.ref = new WeakReference<>(a);
        }

        @Override
        public void onClick(View v) {
            ZcodeLoginActivity a = ref.get();
            if (a != null) a.reload();
        }
    }
}
