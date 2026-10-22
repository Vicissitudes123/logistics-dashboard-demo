package com.animeshelf.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Official website login, verified against Bilibili's account API on this device. */
@androidx.media3.common.util.UnstableApi
public final class LoginActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private WebView web;
    private TextView accountStatus;
    private Button check;
    private boolean dead, checking;
    private int checkGeneration;
    private long lastCheckAt;
    private String lastCheckedCookie;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 17, 27));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        int space = (int) (16 * getResources().getDisplayMetrics().density);
        TextView explanation = new TextView(this);
        explanation.setText("B站官方网页登录\n完成网页登录后点「检查登录」。账号状态由本机直接向 B站核验。");
        explanation.setTextColor(Color.WHITE);
        explanation.setPadding(space, space, space, space / 2);
        root.addView(explanation);
        accountStatus = new TextView(this);
        accountStatus.setTextColor(Color.rgb(119, 224, 210));
        accountStatus.setText("尚未核验登录状态");
        accountStatus.setPadding(space, 0, space, space / 2);
        root.addView(accountStatus);

        LinearLayout actions = new LinearLayout(this);
        check = new Button(this);
        check.setText("检查登录");
        check.setOnClickListener(v -> verifyLogin(true));
        actions.addView(check, new LinearLayout.LayoutParams(0, -2, 1));
        Button done = new Button(this);
        done.setText("返回番匣");
        done.setOnClickListener(v -> { CookieManager.getInstance().flush(); finish(); });
        actions.addView(done, new LinearLayout.LayoutParams(0, -2, 1));
        Button logout = new Button(this);
        logout.setText("退出账号");
        logout.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("退出此工具中的 B站账号？")
            .setMessage("只清除此工具保存的登录状态，不影响 B站 App。")
            .setNegativeButton("取消", null).setPositiveButton("退出", (d, w) -> logout()).show());
        actions.addView(logout, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(actions);

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // The site may try bilibili:// or intent:// even after a successful login.
                // Blocking an app handoff says nothing about the account's login state.
                return !allowedPage(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !allowedPage(Uri.parse(url));
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (dead || !allowedPage(Uri.parse(url))) return;
                CookieManager.getInstance().flush();
                handler.removeCallbacks(autoCheck);
                handler.postDelayed(autoCheck, 700);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        root.requestApplyInsets();
        web.loadUrl("https://passport.bilibili.com/login");
    }

    private static boolean allowedPage(Uri uri) {
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme()) && host != null &&
            ("bilibili.com".equalsIgnoreCase(host) || host.toLowerCase(java.util.Locale.ROOT).endsWith(".bilibili.com"));
    }

    private final Runnable autoCheck = () -> verifyLogin(false);

    private void verifyLogin(boolean manual) {
        if (dead || checking) return;
        String cookie = CookieManager.getInstance().getCookie("https://api.bilibili.com");
        long now = android.os.SystemClock.elapsedRealtime();
        if (!manual && lastCheckAt > 0 && java.util.Objects.equals(cookie, lastCheckedCookie) && now - lastCheckAt < 30000) return;
        lastCheckedCookie = cookie;
        lastCheckAt = now;
        checking = true;
        final int generation = ++checkGeneration;
        check.setEnabled(false);
        accountStatus.setText("正在核验本机登录状态…");
        io.execute(() -> {
            try {
                String name = new BiliClient(cookie).verifyLogin();
                handler.post(() -> {
                    if (dead || generation != checkGeneration) return;
                    checking = false; check.setEnabled(true);
                    if (!java.util.Objects.equals(cookie, CookieManager.getInstance().getCookie("https://api.bilibili.com"))) {
                        verifyLogin(false); return;
                    }
                    if (name.isEmpty()) accountStatus.setText("尚未登录：请先在下方官方网页完成登录，再检查一次。");
                    else accountStatus.setText("已核验登录：" + name + "\n登录状态保存在本机，可返回番匣。");
                });
            } catch (Exception exception) {
                String detail = MediaErrors.describe(exception);
                handler.post(() -> {
                    if (dead || generation != checkGeneration) return;
                    checking = false; check.setEnabled(true);
                    if (!java.util.Objects.equals(cookie, CookieManager.getInstance().getCookie("https://api.bilibili.com"))) {
                        verifyLogin(false); return;
                    }
                    accountStatus.setText("暂时无法核验登录状态，请重试检查。\n" + detail);
                });
            }
        });
    }

    private void logout() {
        ++checkGeneration;
        checking = false;
        check.setEnabled(true);
        lastCheckedCookie = null; lastCheckAt = 0;
        handler.removeCallbacks(autoCheck);
        accountStatus.setText("正在清除此工具中的登录状态…");
        CookieManager.getInstance().removeAllCookies(removed -> {
            if (dead) return;
            CookieManager.getInstance().flush();
            accountStatus.setText("已退出此工具中的账号");
            web.clearHistory();
            web.loadUrl("https://passport.bilibili.com/login");
        });
    }

    @Override protected void onDestroy() {
        dead = true; ++checkGeneration;
        handler.removeCallbacksAndMessages(null); io.shutdownNow();
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}
