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

import org.json.JSONObject;

import java.lang.ref.WeakReference;

/**
 * 华为 CodeArts 登录页（内置 WebView，自动捕获 OAuth 回调）。
 *
 * <h3>回调怎么截</h3>
 *
 * CodeArts 的回调地址是 `http://127.0.0.1:PORT/oauth/callback?code=…&state=…`。
 * 手机上不需要真的起 HTTP 服务 —— 在 WebView 里这是一次导航，
 * 用 `shouldOverrideUrlLoading` 拦下来、从 URL 取 `code` 即可
 * （`127.0.0.1` 能不能连上无关紧要，因为我们根本不发这个请求）。
 *
 * <h3>为什么必须保存 code_verifier 与 DPoP 私钥</h3>
 *
 * 华为云的临时凭据只有约 2 小时。之后续期要同时出示：
 * `refresh_token` + **登录时用的** `code_verifier` + **同一把** DPoP 私钥
 * （华为校验 jwk 指纹与首次一致）。所以这三样都写进渠道配置，
 * 否则用户每天都要重新登录一次。
 */
public class CodeArtsLoginActivity extends Activity {

    public static final String EXTRA_CHANNEL_JSON = "channelJson";

    private WebView webView;
    private ProgressBar progress;
    private TextView hint;
    private LinearLayout errorBar;

    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean finished = false;
    private String handledCode = "";
    private String handledSecret = "";

    /** 本次登录用的 PKCE 与 DPoP。 */
    private String codeVerifier = "";
    private String callbackUrl = "";
    private String ticketId = "";
    private CodeArtsLogin.DpopKey dpopKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
        setupWebView();
        startLogin();
    }

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
        titles.addView(Ui.body(this, "登录 华为 CodeArts"));
        hint = Ui.caption(this, "在下方页面登录，会自动回到 App");
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
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new LoginWebViewClient(this));
        webView.setWebChromeClient(new LoginChromeClient(this));
    }

    /** 生成 PKCE / DPoP 后加载授权页。 */
    private void startLogin() {
        setHint("正在准备登录…");
        new Thread(new PrepareTask(this), "codearts-prepare").start();
    }

    private static final class PrepareTask implements Runnable {
        private final WeakReference<CodeArtsLoginActivity> ref;

        PrepareTask(CodeArtsLoginActivity a) {
            this.ref = new WeakReference<>(a);
        }

        @Override
        public void run() {
            CodeArtsLoginActivity a = ref.get();
            if (a == null) return;
            try {
                String[] pkce = CodeArtsLogin.pkce();
                a.codeVerifier = pkce[0];
                a.dpopKey = CodeArtsLogin.generateDpopKey();
                if (a.dpopKey == null || a.dpopKey.pair == null) {
                    a.ui.post(new ShowErrorTask(a, "无法生成登录密钥"));
                    return;
                }
                byte[] rnd = new byte[32];
                new java.security.SecureRandom().nextBytes(rnd);
                StringBuilder tid = new StringBuilder();
                for (byte b : rnd) tid.append(String.format("%02x", b));
                a.ticketId = tid.toString();
                // 回调地址：WebView 里只用于「认出来」，不需要真的有人监听
                a.callbackUrl = CodeArtsLogin.callbackUrl(CodeArtsLogin.CALLBACK_PORT);
                String url = CodeArtsLogin.buildAuthUrl(
                        CodeArtsLogin.CALLBACK_PORT, pkce[1], a.ticketId);
                a.ui.post(new LoadUrlTask(a, url));
            } catch (Exception e) {
                a.ui.post(new ShowErrorTask(a, "无法发起登录：" + e.getMessage()));
            }
        }
    }

    private static final class LoadUrlTask implements Runnable {
        private final WeakReference<CodeArtsLoginActivity> ref;
        private final String url;

        LoadUrlTask(CodeArtsLoginActivity a, String url) {
            this.ref = new WeakReference<>(a);
            this.url = url;
        }

        @Override
        public void run() {
            CodeArtsLoginActivity a = ref.get();
            if (a == null || a.finished) return;
            a.setHint("在下方页面登录华为账号，完成后自动识别");
            a.webView.loadUrl(url);
        }
    }

    /**
     * 从回调 URL 里取授权码。
     *
     * <p>逻辑在 {@link OAuthCallback#codeArtsCode(String)} —— 抽成纯函数
     * 才能离线验证（这里是 Activity 子类，桌面上加载不了）。
     */
    static String extractCode(String url) {
        return OAuthCallback.codeArtsCode(url);
    }

    /** 从回调 URL 里取 `secret`（旧 ticket 流程）。 */
    static String extractSecret(String url) {
        return OAuthCallback.codeArtsSecret(url);
    }

    void handleCallback(String url) {
        if (finished) return;

        String code = extractCode(url);
        if (!code.isEmpty() && !code.equals(handledCode)) {
            handledCode = code;
            setHint("已拿到授权码，正在换取凭据…");
            new Thread(new ExchangeTask(this, code), "codearts-exchange").start();
            return;
        }

        // 旧流程：门户回调只给 secret，凭据要从 ticket 端点轮询
        String secret = extractSecret(url);
        if (!secret.isEmpty() && !secret.equals(handledSecret)) {
            handledSecret = secret;
            setHint("已拿到登录凭据，正在兑换…");
            new Thread(new PollTicketTask(this, secret), "codearts-ticket").start();
        }
    }

    /**
     * 把一次成功的换令牌结果落成渠道（**后台线程**调用）。
     *
     * <p>新流程（code 换凭据）与旧流程（ticket 轮询）最终都走这里，
     * 避免两条路径各写一份渠道组装逻辑而慢慢长歪。
     */
    void applyToken(JSONObject token) {
        Channel c = new Channel();
        c.id = LoginProviders.CODEARTS;
        c.loginType = LoginProviders.CODEARTS;
        c.kind = Channel.KIND_LOGIN;
        c.enabled = true;
        LoginProviders.Spec spec = LoginProviders.specOf(LoginProviders.CODEARTS);
        c.name = spec == null ? "华为 CodeArts" : spec.displayName;
        c.domain = spec == null ? "" : spec.apiDomain;
        c.baseUrl = CodeArtsLogin.API_BASE;
        if (!CodeArtsLogin.applyCredentials(c, token)) {
            ui.post(new ShowErrorTask(this, "换令牌响应缺少凭据"));
            return;
        }
        // 续期必需的三样，一起存
        c.codeVerifier = codeVerifier;
        c.dpopJwk = CodeArtsLogin.jwkToStore(dpopKey);
        // 内置模型表（清单端点需签名，登录时先给一份可用的）
        c.manualModels = new java.util.ArrayList<>(
                LoginProviders.codeartsBuiltinSpecs().keySet());
        c.modelSpecs.putAll(LoginProviders.codeartsBuiltinSpecs());
        ui.post(new DeliverTask(this, c));
    }

    private static final class ExchangeTask implements Runnable {
        private final WeakReference<CodeArtsLoginActivity> ref;
        private final String code;

        ExchangeTask(CodeArtsLoginActivity a, String code) {
            this.ref = new WeakReference<>(a);
            this.code = code;
        }

        @Override
        public void run() {
            CodeArtsLoginActivity a = ref.get();
            if (a == null) return;
            try {
                JSONObject token = CodeArtsLogin.exchangeCode(
                        this.code, a.codeVerifier, a.callbackUrl, a.dpopKey, 30_000);
                a.applyToken(token);
            } catch (Exception e) {
                a.ui.post(new ShowErrorTask(a, "换取凭据失败：" + e.getMessage()));
            }
        }
    }

    /** 旧流程：用回调里的 secret 轮询凭据（最多 60 秒）。 */
    private static final class PollTicketTask implements Runnable {
        private final WeakReference<CodeArtsLoginActivity> ref;
        private final String secret;

        PollTicketTask(CodeArtsLoginActivity a, String secret) {
            this.ref = new WeakReference<>(a);
            this.secret = secret;
        }

        @Override
        public void run() {
            CodeArtsLoginActivity a = ref.get();
            if (a == null) return;
            try {
                JSONObject token = CodeArtsLogin.fetchTicketCredentials(a.ticketId, secret, 60_000);
                a.applyToken(token);
            } catch (Exception e) {
                a.ui.post(new ShowErrorTask(a, "换取凭据失败：" + e.getMessage()));
            }
        }
    }

    private static final class DeliverTask implements Runnable {
        private final WeakReference<CodeArtsLoginActivity> ref;
        private final Channel channel;

        DeliverTask(CodeArtsLoginActivity a, Channel channel) {
            this.ref = new WeakReference<>(a);
            this.channel = channel;
        }

        @Override
        public void run() {
            CodeArtsLoginActivity a = ref.get();
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
        private final WeakReference<CodeArtsLoginActivity> ref;
        private final String message;

        ShowErrorTask(CodeArtsLoginActivity a, String message) {
            this.ref = new WeakReference<>(a);
            this.message = message;
        }

        @Override
        public void run() {
            CodeArtsLoginActivity a = ref.get();
            if (a != null) a.setHint(message);
        }
    }

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
        handledSecret = "";
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

    private static final class LoginWebViewClient extends WebViewClient {
        private final WeakReference<CodeArtsLoginActivity> ref;

        LoginWebViewClient(CodeArtsLoginActivity a) {
            this.ref = new WeakReference<>(a);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            CodeArtsLoginActivity a = ref.get();
            if (a == null) return false;
            // 新流程回调带 code，旧流程带 secret —— 两种都要截住
            if (!extractCode(url).isEmpty() || !extractSecret(url).isEmpty()) {
                a.handleCallback(url);
                return true;
            }
            return false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            CodeArtsLoginActivity a = ref.get();
            if (a == null) return;
            a.updateLoadProgress(-1);
            a.showError(false);
            a.handleCallback(url);
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            CodeArtsLoginActivity a = ref.get();
            if (a == null) return;
            if (failingUrl != null && failingUrl.startsWith("http")) {
                a.showError(true);
                a.setHint("页面加载失败：" + description);
            }
        }
    }

    private static final class LoginChromeClient extends WebChromeClient {
        private final WeakReference<CodeArtsLoginActivity> ref;

        LoginChromeClient(CodeArtsLoginActivity a) {
            this.ref = new WeakReference<>(a);
        }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            CodeArtsLoginActivity a = ref.get();
            if (a != null) a.updateLoadProgress(newProgress);
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            result.confirm();
            return true;
        }
    }

    private static final class CancelClickListener implements View.OnClickListener {
        private final WeakReference<CodeArtsLoginActivity> ref;

        CancelClickListener(CodeArtsLoginActivity a) {
            this.ref = new WeakReference<>(a);
        }

        @Override
        public void onClick(View v) {
            CodeArtsLoginActivity a = ref.get();
            if (a != null) a.cancelAndClose();
        }
    }

    private static final class RetryClickListener implements View.OnClickListener {
        private final WeakReference<CodeArtsLoginActivity> ref;

        RetryClickListener(CodeArtsLoginActivity a) {
            this.ref = new WeakReference<>(a);
        }

        @Override
        public void onClick(View v) {
            CodeArtsLoginActivity a = ref.get();
            if (a != null) a.reload();
        }
    }
}
