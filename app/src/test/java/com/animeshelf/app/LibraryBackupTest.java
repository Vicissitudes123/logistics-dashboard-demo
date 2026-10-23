package com.animeshelf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.webkit.CookieManager;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Backup is a metadata transfer, never an account/session or video-cache transfer. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class LibraryBackupTest {
    private Context context;
    private SharedPreferences prefs;
    private Store store;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        store = new Store(context);
    }

    @Test public void exportContainsOnlyPortableMetadataAndNeverMediaSecretsOrLocalGrants() throws Exception {
        Models.Series online = series("ss1", "线上片库", "ep1");
        online.coverUrl = "https://i0.hdslb.com/bfs/bangumi/public-cover.jpg";
        store.saveSeries(online);
        Models.Series local = series("local-library", "本地文件", "local-episode");
        local.episodes.get(0).localUri = "content://private.provider/URI_GRANT_CANARY";
        store.saveSeries(local);
        Models.Quality quality = quality();
        quality.videoUrl = "https://media.example/video.m4s?token=SIGNED_VIDEO_CANARY";
        quality.audioUrl = "https://media.example/audio.m4s?token=SIGNED_AUDIO_CANARY";
        quality.headers.put("Cookie", "COOKIE_HEADER_CANARY");
        quality.label = "QUALITY_LABEL_PRIVATE_CANARY";
        store.saveDownload("DOWNLOAD_INDEX_CANARY", online.episodes.get(0), quality);
        store.saveDownloadError("DOWNLOAD_INDEX_CANARY:v", "PRIVATE_FAILURE_CANARY");
        store.setPreferredQuality(quality);
        prefs.edit().putString("SESSDATA", "PREFERENCE_COOKIE_CANARY").commit();
        CookieManager cookies = CookieManager.getInstance();
        cookies.setCookie("https://api.bilibili.com", "SESSDATA=WEBVIEW_COOKIE_CANARY; Path=/");

        String text = store.exportBackup();

        for (String secret : Arrays.asList("URI_GRANT_CANARY", "SIGNED_VIDEO_CANARY", "SIGNED_AUDIO_CANARY",
                "COOKIE_HEADER_CANARY", "QUALITY_LABEL_PRIVATE_CANARY", "DOWNLOAD_INDEX_CANARY", "PRIVATE_FAILURE_CANARY",
                "PREFERENCE_COOKIE_CANARY", "WEBVIEW_COOKIE_CANARY", "localUri", "videoUrl", "audioUrl", "headers"))
            assertFalse("Backup leaked " + secret, text.contains(secret));
        JSONObject root = new JSONObject(text);
        assertEquals(2, root.getJSONObject("settings").getJSONObject("preferredQuality").length());
        assertTrue(text.contains("public-cover.jpg"));
        LibraryBackup.Snapshot snapshot = store.inspectBackup(text);
        assertEquals(2, snapshot.seriesCount);
        assertEquals(2, snapshot.episodeCount);
        assertEquals(1, snapshot.skippedLocalEpisodes);
        assertTrue(snapshot.summary().contains("重新导入"));
    }

    @Test public void backupRestoresCatalogueHistoryManagementAndPlaybackPreferences() throws Exception {
        Models.Series source = series("ss1", "备份番剧", "ep1");
        source.coverUrl = "https://i1.hdslb.com/bfs/bangumi/cover.png";
        store.saveSeries(source); progress("ep1", 120000, 300000, 1000, false);
        prefs.edit().putString("last:ss1", "ep1").putString("lastSeries", "ss1").commit();
        store.setPinned("ss1", true); store.hideSeries("ss1"); store.setSeriesWatched("ss1", true);
        store.setThemeId("pink"); store.setSpeed(1.75f); store.setHoldSpeed(2.5f); store.setControlsTimeoutMs(8000);
        store.setAutoNext(true); store.setRememberQuality(false); store.setPreferredQuality(quality());
        LibraryBackup.Snapshot snapshot = store.inspectBackup(store.exportBackup());
        prefs.edit().clear().commit();

        LibraryBackup.Result result = new Store(context).importBackup(snapshot);

        Store reopened = new Store(context);
        assertEquals(1, result.addedSeries); assertEquals(1, result.importedProgress);
        assertEquals("备份番剧", reopened.listSeries().get(0).title);
        assertEquals(source.coverUrl, reopened.listSeries().get(0).coverUrl);
        assertTrue(reopened.listVisibleSeries().isEmpty()); assertTrue(reopened.isPinned("ss1"));
        assertTrue(reopened.isSeriesWatched(reopened.listSeries().get(0)));
        assertEquals(120000, reopened.getProgress("ep1").positionMs);
        assertEquals("ep1", reopened.getLastEpisode("ss1")); assertEquals("ss1", reopened.getLastSeries());
        assertEquals("pink", reopened.getThemeId()); assertEquals(1.75f, reopened.getSpeed(), .001f);
        assertEquals(2.5f, reopened.getHoldSpeed(), .001f); assertEquals(8000, reopened.getControlsTimeoutMs());
        assertTrue(reopened.isAutoNext()); assertFalse(reopened.isRememberQuality());
        assertEquals("112:12:0", reopened.getPreferredQuality().id);
        assertTrue(reopened.getPreferredQuality().videoCandidates().isEmpty());
        assertTrue(reopened.savedDownloads().isEmpty());
    }

    @Test public void mergeKeepsNewerProgressExistingNamesUnrelatedSeriesOfflineFilesAndLogin() throws Exception {
        Models.Series source = series("ss1", "备份中的旧名称", "ep1", "ep2");
        store.saveSeries(source); progress("ep1", 30000, 300000, 1000, false); progress("ep2", 80000, 300000, 2000, false);
        LibraryBackup.Snapshot snapshot = store.inspectBackup(store.exportBackup());
        prefs.edit().clear().commit();
        Models.Series target = series("ss1", "本机重新命名", "ep1", "ep3");
        target.episodes.get(0).title = "本机剧集名称";
        store.saveSeries(target); store.saveSeries(series("ss-other", "无关番剧", "ep-other"));
        progress("ep1", 180000, 300000, 3000, false);
        prefs.edit().putString("last:ss1", "ep1").putString("lastSeries", "ss1")
            .putString("unrelatedPreference", "KEEP_ME").commit();
        store.saveDownload("existing-download", target.episodes.get(0), quality());
        String originalDownloads = prefs.getString("downloads", "");
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setCookie("https://api.bilibili.com", "SESSDATA=KEEP_SESSION; Path=/");
        String originalCookie = cookies.getCookie("https://api.bilibili.com");
        assertNotNull(originalCookie);

        LibraryBackup.Result result = store.importBackup(snapshot);

        assertEquals(0, result.addedSeries); assertEquals(1, result.mergedSeries);
        assertEquals(1, result.importedProgress); assertEquals(1, result.keptNewerProgress);
        Models.Series merged = find("ss1");
        assertEquals("本机重新命名", merged.title);
        assertEquals("本机剧集名称", merged.episodes.get(0).title);
        assertEquals(Arrays.asList("ep1", "ep3", "ep2"), episodeIds(merged));
        assertEquals(2, store.listSeries().size());
        assertEquals(180000, store.getProgress("ep1").positionMs);
        assertEquals(80000, store.getProgress("ep2").positionMs);
        assertEquals("ep1", store.getLastEpisode("ss1"));
        assertEquals(originalDownloads, prefs.getString("downloads", ""));
        assertEquals(originalCookie, cookies.getCookie("https://api.bilibili.com"));
        assertEquals("KEEP_ME", prefs.getString("unrelatedPreference", ""));
        Map<String, ?> beforeSecondImport = new HashMap<>(prefs.getAll());
        LibraryBackup.Result repeated = store.importBackup(snapshot);
        assertEquals(0, repeated.importedProgress); assertEquals(2, repeated.keptNewerProgress);
        assertEquals(beforeSecondImport, prefs.getAll());
    }

    @Test public void malformedOrUnsupportedBackupsCannotChangeAnyPreference() throws Exception {
        store.saveSeries(series("ss1", "原有片库", "ep1"));
        progress("ep1", 10000, 300000, 1000, false);
        String valid = store.exportBackup();
        List<String> invalid = new ArrayList<>();
        invalid.add("{}"); invalid.add(valid + " trailing-data");
        invalid.add(new JSONObject(valid).put("version", 99).toString());
        invalid.add(new JSONObject(valid).put("cookies", "NEVER_IMPORT").toString());
        invalid.add(new JSONObject(valid).put("series", "not-an-array").toString());
        JSONObject wrongBoolean = new JSONObject(valid);
        wrongBoolean.getJSONObject("settings").put("autoNext", "true"); invalid.add(wrongBoolean.toString());
        JSONObject unsupportedSpeed = new JSONObject(valid);
        unsupportedSpeed.getJSONObject("settings").put("speed", 4); invalid.add(unsupportedSpeed.toString());
        JSONObject badProfile = new JSONObject(valid);
        badProfile.getJSONObject("settings").put("preferredQuality", new JSONObject().put("qn", 112).put("codec", "12")
            .put("videoUrl", "https://media.example/private")); invalid.add(badProfile.toString());
        JSONObject badProgress = new JSONObject(valid);
        badProgress.getJSONArray("progress").getJSONObject(0).put("positionMs", 400000); invalid.add(badProgress.toString());
        JSONObject badReference = new JSONObject(valid);
        badReference.getJSONArray("progress").getJSONObject(0).put("episodeId", "missing"); invalid.add(badReference.toString());
        JSONObject duplicate = new JSONObject(valid);
        JSONArray episodes = duplicate.getJSONArray("series").getJSONObject(0).getJSONArray("episodes");
        episodes.put(new JSONObject(episodes.getJSONObject(0).toString())); invalid.add(duplicate.toString());
        JSONObject maliciousCover = new JSONObject(valid);
        maliciousCover.getJSONArray("series").getJSONObject(0).put("coverUrl", "https://i0.hdslb.com/bfs/a.jpg?token=private");
        invalid.add(maliciousCover.toString());
        JSONObject badId = new JSONObject(valid);
        badId.getJSONArray("series").getJSONObject(0).put("id", "../private"); invalid.add(badId.toString());
        invalid.add(" ".repeat(LibraryBackup.MAX_BYTES + 1));
        invalid.add("[".repeat(30) + "0" + "]".repeat(30));
        Map<String, ?> before = new HashMap<>(prefs.getAll());
        for (String input : invalid) {
            assertThrows(IllegalArgumentException.class, () -> store.importBackup(store.inspectBackup(input)));
            assertEquals(before, prefs.getAll());
        }
    }

    @Test public void ownershipConflictDetectedAfterStagingAnotherSeriesStillWritesNothing() throws Exception {
        store.saveSeries(series("ss-conflict", "旧片库", "ep-conflict"));
        store.saveSeries(series("ss-new", "会先处理的新片库", "ep-new"));
        LibraryBackup.Snapshot snapshot = store.inspectBackup(store.exportBackup());
        prefs.edit().clear().commit();
        store.saveSeries(series("ss-local-owner", "本机实际归属", "ep-conflict"));
        store.setThemeId("green");
        Map<String, ?> before = new HashMap<>(prefs.getAll());

        assertThrows(IllegalArgumentException.class, () -> store.importBackup(snapshot));

        assertEquals(before, prefs.getAll());
        assertEquals(Collections.singletonList("ss-local-owner"), seriesIds(store.listSeries()));
    }

    @Test public void localEntriesAreReportedAndSkippedWithoutErasingExistingLocalMetadata() throws Exception {
        Models.Series sourceLocal = series("local-source", "备份本地视频", "local-source-ep");
        sourceLocal.episodes.get(0).localUri = "content://provider/source";
        Models.Series sameLocal = series("local-existing", "备份里的旧本地标题", "local-existing-ep");
        sameLocal.episodes.get(0).localUri = "content://provider/old-grant";
        store.saveSeries(sourceLocal); store.saveSeries(sameLocal); store.saveSeries(series("ss-online", "在线", "ep-online"));
        progress("local-existing-ep", 10000, 300000, 1000, false);
        LibraryBackup.Snapshot snapshot = store.inspectBackup(store.exportBackup());
        prefs.edit().clear().commit();
        sameLocal.title = "本机本地视频"; sameLocal.episodes.get(0).localUri = "content://provider/current-grant";
        store.saveSeries(sameLocal); progress("local-existing-ep", 90000, 300000, 2000, false);

        LibraryBackup.Result result = store.importBackup(snapshot);

        assertEquals(2, result.skippedLocalEpisodes);
        assertEquals(1, result.addedSeries); assertEquals(0, result.importedProgress);
        assertEquals(Arrays.asList("local-existing", "ss-online"), seriesIds(store.listSeries()));
        assertEquals("本机本地视频", find("local-existing").title);
        assertEquals("content://provider/current-grant", find("local-existing").episodes.get(0).localUri);
        assertEquals(90000, store.getProgress("local-existing-ep").positionMs);
        assertEquals(0, store.getProgress("local-source-ep").updatedAt);
        assertTrue(store.savedDownloads().isEmpty());
        assertTrue(result.summary().contains("重新导入"));
    }

    @Test public void importUsesOneCommitAndNoIntermediateApply() throws Exception {
        store.saveSeries(series("ss1", "事务测试", "ep1"));
        progress("ep1", 30000, 300000, 1000, false); store.setPinned("ss1", true);
        LibraryBackup.Snapshot snapshot = store.inspectBackup(store.exportBackup());
        prefs.edit().clear().commit();
        AtomicInteger editors = new AtomicInteger(), commits = new AtomicInteger(), applies = new AtomicInteger();
        SharedPreferences instrumented = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                if (!method.getName().equals("edit")) return method.invoke(prefs, args);
                editors.incrementAndGet(); SharedPreferences.Editor real = prefs.edit();
                return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                    (editorProxy, operation, values) -> {
                        if (operation.getName().equals("commit")) commits.incrementAndGet();
                        if (operation.getName().equals("apply")) applies.incrementAndGet();
                        Object value = operation.invoke(real, values);
                        return value instanceof SharedPreferences.Editor ? editorProxy : value;
                    });
            });
        Context wrapped = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return instrumented; }
        };

        new Store(wrapped).importBackup(snapshot);

        assertEquals(1, editors.get()); assertEquals(1, commits.get()); assertEquals(0, applies.get());
        assertEquals(30000, store.getProgress("ep1").positionMs);
        assertTrue(store.isPinned("ss1"));
    }

    @Test public void theSameLocalFileInTwoCollectionsCanBeBackedUpWithoutDuplicatingItsProgress() throws Exception {
        Models.Series first = series("local-first", "第一个本地合集", "local-same-file");
        Models.Series second = series("local-second", "第二个本地合集", "local-same-file");
        first.episodes.get(0).localUri = "content://provider/same-file";
        second.episodes.get(0).localUri = "content://provider/same-file";
        store.saveSeries(first); store.saveSeries(second); progress("local-same-file", 12000, 300000, 1000, false);

        LibraryBackup.Snapshot snapshot = store.inspectBackup(store.exportBackup());

        assertEquals(2, snapshot.seriesCount); assertEquals(2, snapshot.skippedLocalEpisodes);
        assertEquals(1, snapshot.progressCount);
        prefs.edit().clear().commit();
        LibraryBackup.Result result = store.importBackup(snapshot);
        assertEquals(2, result.skippedLocalEpisodes);
        assertEquals(0, result.addedSeries); assertEquals(0, result.importedProgress);
        assertTrue(store.listSeries().isEmpty());
    }

    @Test public void newPreferencesContainNoUrlsAndResetWithoutRemovingWatchedHistory() {
        Models.Series series = series("ss1", "播放设置", "ep1"); store.saveSeries(series);
        assertFalse(store.isAutoNext()); assertTrue(store.isRememberQuality()); assertNull(store.getPreferredQuality());
        Models.Quality profile = quality(); profile.videoUrl = "https://media.example/video?secret=SECRET_PROFILE_CANARY";
        store.setPreferredQuality(profile); store.setAutoNext(true); store.setRememberQuality(false);
        store.setSeriesWatched(series.id, true); store.saveProgress(series.episodes.get(0), 12000, 300000);
        assertFalse(prefs.getString("preferredQuality", "").contains("SECRET_PROFILE_CANARY"));
        profile.id = "bad";
        assertEquals("112:12:0", new Store(context).getPreferredQuality().id);

        store.resetAppearanceAndPlaybackSettings();

        assertFalse(store.isAutoNext()); assertTrue(store.isRememberQuality()); assertNull(store.getPreferredQuality());
        assertTrue(store.isSeriesWatched(series)); assertEquals(12000, store.getProgress("ep1").positionMs);
        Models.Quality invalid = quality(); invalid.id = "80:12:30280";
        store.setPreferredQuality(invalid); assertNull(store.getPreferredQuality());
        store.setSpeed(4f); assertEquals(1f, store.getSpeed(), .001f);
        prefs.edit().putFloat("speed", 4f).commit(); assertEquals(1f, store.getSpeed(), .001f);
        store.setSpeed(3f); assertEquals(3f, store.getSpeed(), .001f);
    }

    @Test public void watchedStatusRequiresEveryCompletedEpisodeUnlessExplicitlyOverridden() {
        Models.Series series = series("ss1", "完成状态", "ep1", "ep2"); store.saveSeries(series);
        assertFalse(store.isSeriesWatched(series));
        store.saveProgress(series.episodes.get(0), 300000, 300000);
        assertFalse(store.isSeriesWatched(series));
        store.saveProgress(series.episodes.get(1), 0, 500);
        assertFalse(store.getProgress("ep2").completed); assertFalse(store.isSeriesWatched(series));
        store.saveProgress(series.episodes.get(1), 300000, 300000);
        assertTrue(store.isSeriesWatched(series));
        store.setSeriesWatched(series.id, false); assertFalse(store.isSeriesWatched(series));
        store.clearSeriesWatchedOverride(series.id); assertTrue(store.isSeriesWatched(series));
        store.saveProgress(series.episodes.get(1), 1000, 300000);
        assertFalse(store.isSeriesWatched(series));
        store.setSeriesWatched(series.id, true); assertTrue(store.isSeriesWatched(series));
        assertFalse(store.isSeriesWatched(series("empty", "没有剧集")));
    }

    @Test public void delayedCoverUpdatePreservesLibraryOrderHiddenStateAndHistory() {
        Models.Series first = series("ss1", "第一部", "ep1"), second = series("ss2", "第二部", "ep2");
        store.saveSeries(first); store.saveSeries(second); store.hideSeries(first.id); store.setPinned(first.id, true);
        store.saveProgress(first.episodes.get(0), 12000, 300000);

        store.updateSeriesCover(first.id, "https://i0.hdslb.com/bfs/bangumi/fetched.jpg");

        assertEquals(Arrays.asList("ss2", "ss1"), seriesIds(store.listSeries()));
        assertEquals(Collections.singletonList("ss1"), seriesIds(store.listHiddenSeries()));
        assertEquals("https://i0.hdslb.com/bfs/bangumi/fetched.jpg", find("ss1").coverUrl);
        assertEquals(12000, store.getProgress("ep1").positionMs); assertTrue(store.isPinned("ss1"));
        store.updateSeriesCover(first.id, "https://evil.example/private.jpg");
        assertEquals("https://i0.hdslb.com/bfs/bangumi/fetched.jpg", find("ss1").coverUrl);
    }

    private void progress(String id, long position, long duration, long timestamp, boolean complete) throws Exception {
        prefs.edit().putString("progress:" + id, new JSONObject().put("position", position).put("duration", duration)
            .put("updated", timestamp).put("completed", complete).toString()).commit();
    }
    private Models.Series find(String id) {
        for (Models.Series series : store.listSeries()) if (id.equals(series.id)) return series;
        throw new AssertionError("Missing series: " + id);
    }
    private static Models.Series series(String id, String title, String... episodes) {
        Models.Series series = new Models.Series(); series.id = id; series.title = title;
        for (String episodeId : episodes) {
            Models.Episode episode = new Models.Episode(); episode.id = episodeId; episode.title = "第 " + (series.episodes.size() + 1) + " 集";
            episode.index = series.episodes.size() + 1; episode.seriesId = id; episode.seriesTitle = title;
            series.episodes.add(episode);
        }
        return series;
    }
    private static Models.Quality quality() {
        Models.Quality quality = new Models.Quality(); quality.qn = 112; quality.id = "112:12:30280"; quality.label = "1080P 高码率 · HEVC";
        quality.videoUrl = "https://media.example/video.m4s"; quality.audioUrl = "https://media.example/audio.m4s";
        return quality;
    }
    private static List<String> seriesIds(List<Models.Series> items) {
        List<String> ids = new ArrayList<>(); for (Models.Series series : items) ids.add(series.id); return ids;
    }
    private static List<String> episodeIds(Models.Series series) {
        List<String> ids = new ArrayList<>(); for (Models.Episode episode : series.episodes) ids.add(episode.id); return ids;
    }
}
