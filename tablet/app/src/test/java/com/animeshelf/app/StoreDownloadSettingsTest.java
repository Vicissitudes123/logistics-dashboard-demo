package com.animeshelf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class StoreDownloadSettingsTest {
    private Context context;
    private SharedPreferences prefs;
    private Store store;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        store = new Store(context);
    }

    @Test public void wifiOnlyDefaultsToEnabledAndPersistsAnExplicitMobileDataChoice() {
        assertTrue(store.isWifiOnlyDownloads());
        store.setWifiOnlyDownloads(false);
        assertFalse(new Store(context).isWifiOnlyDownloads());
        store.setWifiOnlyDownloads(true);
        assertTrue(new Store(context).isWifiOnlyDownloads());
        prefs.edit().putString("wifiOnlyDownloads", "false").commit();
        assertTrue(new Store(context).isWifiOnlyDownloads());
    }

    @Test public void everyLibraryFilterPersistsAndInvalidOrOldValuesUseAll() {
        assertEquals("all", store.getLibraryFilter());
        for (String filter : Arrays.asList("all", "unfinished", "watched", "offline")) {
            store.setLibraryFilter(filter);
            assertEquals(filter, new Store(context).getLibraryFilter());
        }
        store.setLibraryFilter(" OFFLINE "); assertEquals("offline", store.getLibraryFilter());
        store.setLibraryFilter("unknown"); assertEquals("all", store.getLibraryFilter());
        store.setLibraryFilter(null); assertEquals("all", store.getLibraryFilter());
        prefs.edit().putInt("libraryFilter", 9).commit();
        assertEquals("all", store.getLibraryFilter());
    }

    @Test public void skipSettingsArePerSeriesClampedAndCanBeDisabledWithoutLosingDurations() {
        assertSkip(store.getSkipSettings("ss1"), false, 0, 0);
        store.setSkipSettings("ss1", true, 90, 120);
        store.setSkipSettings("ss2", true, Integer.MIN_VALUE, Integer.MAX_VALUE);
        Store reopened = new Store(context);
        assertSkip(reopened.getSkipSettings("ss1"), true, 90, 120);
        assertSkip(reopened.getSkipSettings("ss2"), true, 0, 600);
        reopened.setSkipSettings("ss1", false, 90, 120);
        assertSkip(new Store(context).getSkipSettings("ss1"), false, 90, 120);
        assertSkip(new Store.SkipSettings(true, 601, -1), true, 600, 0);
        assertSkip(reopened.getSkipSettings("missing"), false, 0, 0);
    }

    @Test public void corruptedSkipSettingsSafelyDisableSkippingAndBlankIdsDoNotWrite() {
        for (String value : Arrays.asList("broken", "{}", "{\"enabled\":\"true\",\"introSeconds\":90,\"outroSeconds\":90}",
                "{\"enabled\":true,\"introSeconds\":601,\"outroSeconds\":90}",
                "{\"enabled\":true,\"introSeconds\":90,\"outroSeconds\":90,\"url\":\"private\"}")) {
            prefs.edit().putString("skip:ss1", value).commit();
            assertSkip(store.getSkipSettings("ss1"), false, 0, 0);
        }
        prefs.edit().putInt("skip:ss1", 90).commit();
        assertSkip(store.getSkipSettings("ss1"), false, 0, 0);
        Map<String, ?> before = new HashMap<>(prefs.getAll());
        store.setSkipSettings(null, true, 90, 90);
        store.setSkipSettings("  ", true, 90, 90);
        assertEquals(before, prefs.getAll());
        assertSkip(store.getSkipSettings(null), false, 0, 0);
    }

    @Test public void globalSettingsResetRestoresWifiAndFilterButRetainsPerSeriesSkipData() {
        store.setWifiOnlyDownloads(false); store.setLibraryFilter("offline");
        store.setThemeId("green"); store.setAutoNext(true);
        store.setSkipSettings("ss1", true, 95, 80);
        store.setPinned("ss1", true); store.hideSeries("ss1"); store.setSeriesWatched("ss1", false);
        prefs.edit().putString("downloads", "[]").putString("progress:ep1", "saved-history").commit();

        store.resetAppearanceAndPlaybackSettings();

        assertTrue(store.isWifiOnlyDownloads()); assertEquals("all", store.getLibraryFilter());
        assertEquals("blue", store.getThemeId()); assertFalse(store.isAutoNext());
        assertSkip(new Store(context).getSkipSettings("ss1"), true, 95, 80);
        assertTrue(store.isPinned("ss1")); assertTrue(prefs.getBoolean("hidden:ss1", false));
        assertTrue(prefs.contains("watched:ss1"));
        assertEquals("[]", prefs.getString("downloads", ""));
        assertEquals("saved-history", prefs.getString("progress:ep1", ""));
    }

    private static void assertSkip(Store.SkipSettings settings, boolean enabled, int intro, int outro) {
        assertEquals(enabled, settings.enabled);
        assertEquals(intro, settings.introSeconds);
        assertEquals(outro, settings.outroSeconds);
    }
}
