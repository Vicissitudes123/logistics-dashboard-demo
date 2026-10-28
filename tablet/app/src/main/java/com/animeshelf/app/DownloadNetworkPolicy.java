package com.animeshelf.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import java.util.function.BooleanSupplier;

/** Wi-Fi transport policy, independent of whether a mobile or Wi-Fi network is metered. */
public final class DownloadNetworkPolicy {
    public static final int STOP_WIFI_REQUIRED = 1 << 16;
    public static final int STOP_USER_PAUSED = 1 << 17;

    public static final class Info {
        public final boolean wifiOnly, wifi, vpn, connected, allowed;
        public final String summary;
        private Info(boolean wifiOnly, boolean wifi, boolean vpn, boolean connected, String summary) {
            this.wifiOnly = wifiOnly; this.wifi = wifi; this.vpn = vpn; this.connected = connected;
            this.allowed = connected && (!wifiOnly || wifi); this.summary = summary;
        }
    }

    private final ConnectivityManager connectivity;
    private final BooleanSupplier wifiOnly;
    private final Handler handler;
    private final Runnable changed;
    private boolean registered;
    private boolean closed;
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { notifyChanged(); }
        @Override public void onLost(Network network) { notifyChanged(); }
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { notifyChanged(); }
        @Override public void onUnavailable() { notifyChanged(); }
    };

    public DownloadNetworkPolicy(Context context, BooleanSupplier wifiOnly, Handler handler, Runnable changed) {
        connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.wifiOnly = wifiOnly; this.handler = handler; this.changed = changed;
    }

    public void start() {
        if (closed || registered || connectivity == null) return;
        try {
            connectivity.registerDefaultNetworkCallback(callback, handler);
            registered = true;
        } catch (SecurityException | IllegalArgumentException unavailable) {
            // open/read still recheck the active transport; no extra runtime permission is needed.
        }
    }

    private void notifyChanged() {
        if (closed) return;
        // Query after the callback returns, rather than synchronously querying capabilities
        // inside onAvailable (where Android documents that they may not yet be populated).
        handler.removeCallbacks(changed);
        handler.post(changed);
    }

    /** Release the observer when its owner is disposed (the production repository is a singleton). */
    public void close() {
        closed = true;
        handler.removeCallbacks(changed);
        if (!registered || connectivity == null) return;
        try { connectivity.unregisterNetworkCallback(callback); }
        catch (IllegalArgumentException ignored) { }
        registered = false;
    }

    public Info info() {
        boolean restricted = wifiOnly.getAsBoolean();
        try {
            Network active = connectivity == null ? null : connectivity.getActiveNetwork();
            return evaluate(restricted, active == null ? null : connectivity.getNetworkCapabilities(active));
        } catch (SecurityException unavailable) {
            return new Info(restricted, false, false, false, "无法确认当前网络；下载等待可用网络");
        }
    }

    static Info evaluate(boolean wifiOnly, NetworkCapabilities capabilities) {
        boolean connected = capabilities != null
            && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        boolean vpn = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        boolean cellular = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        // Android reports the VPN's underlying transports together with TRANSPORT_VPN.
        // A separate connected Wi-Fi interface is not proof that the default VPN uses it.
        boolean wifi = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !cellular;
        String description;
        if (!connected) description = "等待可用网络";
        else if (wifi) description = vpn ? "当前为 Wi-Fi（VPN）" : "当前为 Wi-Fi";
        else if (vpn && !cellular) description = "VPN 底层网络无法确认";
        else if (cellular) description = vpn ? "当前为移动网络（VPN）" : "当前为移动网络";
        else description = "当前为非 Wi-Fi 网络";
        if (wifiOnly && !wifi) description += " · 仅 Wi-Fi 下载，正在等待 Wi-Fi";
        return new Info(wifiOnly, wifi, vpn, connected, description);
    }

    public static int stopReason(Info info, boolean userPaused) {
        return (userPaused ? STOP_USER_PAUSED : 0)
            | (info.wifiOnly && (!info.connected || !info.wifi) ? STOP_WIFI_REQUIRED : 0);
    }
}
