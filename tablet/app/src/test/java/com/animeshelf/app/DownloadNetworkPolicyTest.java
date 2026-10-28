package com.animeshelf.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.net.NetworkCapabilities;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNetworkCapabilities;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class DownloadNetworkPolicyTest {
    private static NetworkCapabilities connected(int... transports) {
        NetworkCapabilities result = ShadowNetworkCapabilities.newInstance();
        ShadowNetworkCapabilities shadow = shadowOf(result);
        shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        for (int transport : transports) shadow.addTransportType(transport);
        return result;
    }

    @Test public void wifiIsAllowedEvenWhenMetered() {
        NetworkCapabilities caps = connected(NetworkCapabilities.TRANSPORT_WIFI);
        shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        DownloadNetworkPolicy.Info info = DownloadNetworkPolicy.evaluate(true, caps);
        assertTrue(info.connected);
        assertTrue(info.wifi);
        assertTrue(info.allowed);
        assertEquals(0, DownloadNetworkPolicy.stopReason(info, false));
    }

    @Test public void unmeteredMobileAndEthernetAreNotWifi() {
        NetworkCapabilities cellular = connected(NetworkCapabilities.TRANSPORT_CELLULAR);
        shadowOf(cellular).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        assertFalse(DownloadNetworkPolicy.evaluate(true, cellular).allowed);
        assertFalse(DownloadNetworkPolicy.evaluate(true,
            connected(NetworkCapabilities.TRANSPORT_ETHERNET)).allowed);
    }

    @Test public void vpnUsesOnlyItsActiveUnderlyingTransport() {
        DownloadNetworkPolicy.Info wifi = DownloadNetworkPolicy.evaluate(true,
            connected(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI));
        assertTrue(wifi.vpn);
        assertTrue(wifi.allowed);
        assertFalse(DownloadNetworkPolicy.evaluate(true,
            connected(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_CELLULAR)).allowed);
        assertFalse(DownloadNetworkPolicy.evaluate(true,
            connected(NetworkCapabilities.TRANSPORT_VPN)).allowed);
        // Ambiguous/multi-network VPNs must not silently consume the cellular route.
        assertFalse(DownloadNetworkPolicy.evaluate(true, connected(NetworkCapabilities.TRANSPORT_VPN,
            NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR)).allowed);
    }

    @Test public void disconnectedAndUnvalidatedNetworksRemainBlocked() {
        assertFalse(DownloadNetworkPolicy.evaluate(true, null).allowed);
        assertFalse(DownloadNetworkPolicy.evaluate(false, null).allowed);
        NetworkCapabilities captiveWifi = connected(NetworkCapabilities.TRANSPORT_WIFI);
        shadowOf(captiveWifi).removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        assertFalse(DownloadNetworkPolicy.evaluate(true, captiveWifi).allowed);
        assertEquals(DownloadNetworkPolicy.STOP_WIFI_REQUIRED,
            DownloadNetworkPolicy.stopReason(DownloadNetworkPolicy.evaluate(true, captiveWifi), false));
    }

    @Test public void mobileWaitsAtStartupThenWifiClearsOnlyTheNetworkReason() {
        DownloadNetworkPolicy.Info mobile = DownloadNetworkPolicy.evaluate(true,
            connected(NetworkCapabilities.TRANSPORT_CELLULAR));
        DownloadNetworkPolicy.Info wifi = DownloadNetworkPolicy.evaluate(true,
            connected(NetworkCapabilities.TRANSPORT_WIFI));
        assertEquals(DownloadNetworkPolicy.STOP_WIFI_REQUIRED, DownloadNetworkPolicy.stopReason(mobile, false));
        assertEquals(0, DownloadNetworkPolicy.stopReason(wifi, false));
        assertEquals(DownloadNetworkPolicy.STOP_WIFI_REQUIRED, DownloadNetworkPolicy.stopReason(mobile, false));
        assertEquals(DownloadNetworkPolicy.STOP_WIFI_REQUIRED | DownloadNetworkPolicy.STOP_USER_PAUSED,
            DownloadNetworkPolicy.stopReason(mobile, true));
        assertEquals(DownloadNetworkPolicy.STOP_USER_PAUSED, DownloadNetworkPolicy.stopReason(wifi, true));
        assertEquals(0, DownloadNetworkPolicy.stopReason(wifi, false));
    }

    @Test public void allowingMobileDoesNotCancelManualPause() {
        DownloadNetworkPolicy.Info mobile = DownloadNetworkPolicy.evaluate(false,
            connected(NetworkCapabilities.TRANSPORT_CELLULAR));
        assertTrue(mobile.allowed);
        assertEquals(0, DownloadNetworkPolicy.stopReason(mobile, false));
        assertEquals(DownloadNetworkPolicy.STOP_USER_PAUSED, DownloadNetworkPolicy.stopReason(mobile, true));
    }
}
