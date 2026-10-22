package com.animeshelf.app;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Bilibili's own login page; no JavaScript bridge, password collection or remote sync. */
public final class LoginActivity extends Activity {
    private WebView web;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 17, 27));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView explanation = new TextView(this);
        explanation.setText("B 站官方登录页\n登录状态仅保存在这台手机，完成后点下方返回。");
        explanation.setTextColor(Color.WHITE);
        int space = (int) (16 * getResources().getDisplayMetrics().density);
        explanation.setPadding(space, space, space, space);
        root.addView(explanation);
        LinearLayout actions = new LinearLayout(this);
        Button done = new Button(this);
        done.setText("完成登录，返回番匣");
        done.setOnClickListener(v -> { CookieManager.getInstance().flush(); finish(); });
        actions.addView(done, new LinearLayout.LayoutParams(0, -2, 1));
        Button logout = new Button(this);
        logout.setText("退出账号");
        logout.setOnClickListener(v -> new android.app.AlertDialog.Builder(this).setTitle("退出此工具中的 B 站账号？")
            .setMessage("只清除此工具保存的登录状态，不影响 B 站 App。")
            .setNegativeButton("取消", null).setPositiveButton("退出", (d, w) -> {
                CookieManager.getInstance().removeAllCookies(value -> { CookieManager.getInstance().flush(); web.clearHistory(); web.loadUrl("https://passport.bilibili.com/login"); });
            }).show());
        actions.addView(logout);
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
                Uri uri = request.getUrl();
                String host = uri.getHost();
                boolean allowed = "https".equalsIgnoreCase(uri.getScheme()) && host != null &&
                    (host.equals("bilibili.com") || host.endsWith(".bilibili.com"));
                if (!allowed) android.widget.Toast.makeText(LoginActivity.this, "请在当前官方页面完成登录", android.widget.Toast.LENGTH_SHORT).show();
                return !allowed;
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        web.loadUrl("https://passport.bilibili.com/login");
    }
    @Override protected void onDestroy() {
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}
