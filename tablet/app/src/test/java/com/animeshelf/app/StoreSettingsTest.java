package com.animeshelf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Settings changes must survive reopening while preserving the user's saved media and account. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class StoreSettingsTest {
    private Context context;
    private SharedPreferences prefs;
    private Store store;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        store = new Store(context);
    }

    @Test public void defaultsAndEverySupportedSettingSurviveOpeningANewStore() {
        assertDefaults(store);
        for (ThemePalette palette : ThemePalette.entries()) {
            store.setThemeId(palette.id);
            assertEquals(palette.id, new Store(context).getThemeId());
        }
        for (float speed : new float[]{1.5f, 2f, 2.5f, 3f}) {
            store.setHoldSpeed(speed);
            assertEquals(speed, new Store(context).getHoldSpeed(), .001f);
        }
        for (int timeout : new int[]{3500, 5000, 8000, 0}) {
            store.setControlsTimeoutMs(timeout);
            assertEquals(timeout, new Store(context).getControlsTimeoutMs());
        }
        store.setThemeId(" PiNk ");
        store.setSpeed(1.75f);
        assertEquals("pink", new Store(context).getThemeId());
        assertEquals(1.75f, new Store(context).getSpeed(), .001f);
    }

    @Test public void invalidSettingValuesAndWrongStoredTypesFallBackToDefaults() {
        prefs.edit().putInt("theme", 7).putString("holdSpeed", "broken")
            .putString("controlsTimeoutMs", "never").commit();
        assertDefaults(new Store(context));

        prefs.edit().putString("theme", "unknown-theme").putFloat("holdSpeed", Float.NaN)
            .putInt("controlsTimeoutMs", -500).commit();
        assertDefaults(new Store(context));

        store.setThemeId(null);
        for (float speed : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, 1f, 5f}) {
            store.setHoldSpeed(speed);
            assertEquals(2f, new Store(context).getHoldSpeed(), .001f);
        }
        store.setControlsTimeoutMs(1234);
        assertDefaults(new Store(context));
    }

    @Test public void resettingSettingsPreservesLoginDownloadsHistoryPinsAndHiddenLibraryEntries() throws Exception {
        Models.Series series = series("saved", "已保存番剧");
        Models.Episode episode = series.episodes.get(0);
        store.saveSeries(series);
        store.saveProgress(episode, 123000, 300000);
        store.setPinned(series.id, true);
        store.hideSeries(series.id);
        store.saveDownload("saved-download", episode, quality());
        store.saveDownloadError("saved-download:v", "HTTP 503 · 测试错误");
        prefs.edit().putString("unrelatedPreference", "preserve-me").commit();
        String previousHistory = prefs.getString("progress:" + episode.id, "");
        String previousDownload = prefs.getString("downloads", "");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setCookie("https://api.bilibili.com", "SESSDATA=local-test-session; Path=/; Secure");
        String previousCookie = cookies.getCookie("https://api.bilibili.com");
        assertNotNull(previousCookie);
        assertTrue(previousCookie.contains("SESSDATA=local-test-session"));
        store.setThemeId("green");
        store.setSpeed(1.75f);
        store.setHoldSpeed(3f);
        store.setControlsTimeoutMs(0);

        store.resetAppearanceAndPlaybackSettings();

        Store reopened = new Store(context);
        assertDefaults(reopened);
        assertEquals(Collections.singletonList("saved"), ids(reopened.listSeries()));
        assertTrue(reopened.listVisibleSeries().isEmpty());
        assertEquals(Collections.singletonList("saved"), ids(reopened.listHiddenSeries()));
        assertTrue(reopened.isPinned("saved"));
        assertEquals(previousHistory, prefs.getString("progress:" + episode.id, ""));
        assertEquals(123000, reopened.getProgress(episode.id).positionMs);
        assertEquals(episode.id, reopened.getLastEpisode(series.id));
        assertEquals(series.id, reopened.getLastSeries());
        assertEquals(previousDownload, prefs.getString("downloads", ""));
        assertEquals(1, reopened.savedDownloads().size());
        assertEquals("HTTP 503 · 测试错误", reopened.downloadError("saved-download:v"));
        assertEquals("preserve-me", prefs.getString("unrelatedPreference", ""));
        assertEquals(previousCookie, cookies.getCookie("https://api.bilibili.com"));
    }

    @Test public void hidingRestoringAndReimportingChangesVisibilityWithoutDiscardingSavedData() {
        Models.Series first = series("first", "同名番剧");
        Models.Series second = series("second", "同名番剧");
        store.saveSeries(first);
        store.saveSeries(second);
        store.saveProgress(first.episodes.get(0), 42000, 300000);
        store.saveDownload("first-download", first.episodes.get(0), quality());
        store.setPinned(first.id, true);
        store.hideSeries(first.id);

        Store reopened = new Store(context);
        assertEquals(Collections.singletonList("second"), ids(reopened.listVisibleSeries()));
        assertEquals(Collections.singletonList("first"), ids(reopened.listHiddenSeries()));
        assertEquals(Arrays.asList("second", "first"), ids(reopened.listSeries()));
        assertEquals(42000, reopened.getProgress(first.episodes.get(0).id).positionMs);
        assertEquals(1, reopened.savedDownloads().size());
        assertTrue(reopened.isPinned(first.id));

        reopened.restoreSeries(first.id);
        assertEquals(Arrays.asList("second", "first"), ids(reopened.listVisibleSeries()));
        reopened.hideSeries(first.id);
        first.title = "重新导入后的名称";
        reopened.saveSeries(first);
        Store imported = new Store(context);
        assertEquals(Arrays.asList("first", "second"), ids(imported.listVisibleSeries()));
        assertTrue(imported.listHiddenSeries().isEmpty());
        assertEquals(2, imported.listSeries().size());
        assertEquals("重新导入后的名称", imported.listVisibleSeries().get(0).title);
        assertEquals(42000, imported.getProgress(first.episodes.get(0).id).positionMs);
        assertEquals(1, imported.savedDownloads().size());
        assertTrue(imported.isPinned(first.id));
    }

    @Test public void anUnplayedImportOrStaleLastSeriesIsNotARecentViewingRecord() throws Exception {
        Models.Series first = series("first", "First");
        store.saveSeries(first);
        prefs.edit().putString("lastSeries", first.id).commit();
        writeProgress(first.episodes.get(0), 0);
        assertEquals("", store.getRecentVisibleSeriesId());

        // A real READY record can be at position zero; its timestamp distinguishes it.
        writeProgress(first.episodes.get(0), 100);
        assertEquals(first.id, store.getRecentVisibleSeriesId());
        store.saveSeries(series("new-unplayed", "New import"));
        assertEquals(first.id, store.getRecentVisibleSeriesId());
    }

    @Test public void recentUsesAValidLastSeriesThenFallsBackToNewestVisibleEpisode() throws Exception {
        Models.Series earlier = series("earlier", "Earlier");
        Models.Series newer = series("newer", "Newer");
        Models.Episode secondEpisode = episode("newer:2", newer.id, newer.title, 2);
        newer.episodes.add(secondEpisode);
        store.saveSeries(earlier);
        store.saveSeries(newer);
        store.saveSeries(series("unplayed", "Never played"));
        writeProgress(earlier.episodes.get(0), 100);
        writeProgress(newer.episodes.get(0), 50);
        writeProgress(secondEpisode, 300);
        prefs.edit().putString("lastSeries", earlier.id).commit();
        assertEquals("earlier", store.getRecentVisibleSeriesId());

        prefs.edit().putString("lastSeries", "missing-series").commit();
        assertEquals("newer", store.getRecentVisibleSeriesId());
        store.hideSeries("newer");
        assertEquals("earlier", store.getRecentVisibleSeriesId());
        store.hideSeries("earlier");
        assertEquals("", store.getRecentVisibleSeriesId());
        store.restoreSeries("newer");
        assertEquals("newer", store.getRecentVisibleSeriesId());
    }

    private void writeProgress(Models.Episode episode, long timestamp) throws Exception {
        prefs.edit().putString("progress:" + episode.id, new JSONObject().put("position", 0)
            .put("duration", 300000).put("updated", timestamp).toString()).commit();
    }

    private static void assertDefaults(Store value) {
        assertEquals("blue", value.getThemeId());
        assertEquals(1f, value.getSpeed(), .001f);
        assertEquals(2f, value.getHoldSpeed(), .001f);
        assertEquals(3500, value.getControlsTimeoutMs());
    }

    private static Models.Series series(String id, String title) {
        Models.Series value = new Models.Series();
        value.id = id;
        value.title = title;
        value.episodes.add(episode(id + ":1", id, title, 1));
        return value;
    }

    private static Models.Episode episode(String id, String seriesId, String title, int index) {
        Models.Episode value = new Models.Episode();
        value.id = id;
        value.seriesId = seriesId;
        value.seriesTitle = title;
        value.title = "第 " + index + " 集";
        value.index = index;
        return value;
    }

    private static Models.Quality quality() {
        Models.Quality value = new Models.Quality();
        value.id = "80:7:30280";
        value.label = "1080P AVC";
        value.qn = 80;
        value.videoUrl = "https://media.example.test/video.m4s";
        value.audioUrl = "https://media.example.test/audio.m4s";
        return value;
    }

    private static List<String> ids(List<Models.Series> values) {
        List<String> result = new ArrayList<>();
        for (Models.Series value : values) result.add(value.id);
        return result;
    }
}
