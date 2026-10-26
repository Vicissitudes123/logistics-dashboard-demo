package com.animeshelf.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class LibraryUpgradeTest {
    private Context context;
    private Store store;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication(); store = new Store(context);
        context.getSharedPreferences("library", 0).edit().clear().commit(); PendingDownloads.clear(context);
    }
    private Models.Series series(int... indices) {
        Models.Series series = new Models.Series(); series.id = "ss1"; series.title = "番剧";
        for (int index : indices) { Models.Episode ep = new Models.Episode(); ep.id = "ep" + index; ep.index = index;
            ep.seriesId = series.id; ep.seriesTitle = series.title; ep.title = "第 " + index + " 集"; series.episodes.add(ep); }
        return series;
    }
    private Models.Quality quality(int qn) {
        Models.Quality q = new Models.Quality(); q.qn = qn; q.id = qn + ":7:30280"; q.label = "测试画质";
        q.videoUrl = "https://media.example/" + qn + ".m4s"; q.audioUrl = "https://media.example/audio.m4s"; return q;
    }
    private MediaRepository.OfflineItem item(int index, int qn, long bytes) {
        Models.Episode ep = series(index).episodes.get(0); MediaRepository.OfflineItem item = new MediaRepository.OfflineItem();
        item.episode = ep; item.episodeId = ep.id; item.quality = quality(qn); item.key = ep.id + ":" + qn;
        item.complete = true; item.bytesDownloaded = bytes; return item;
    }
    @Test public void refreshMergesByIdWithoutDeletingMissingEpisodesOrProgress() {
        Models.Series original = series(1, 2); store.saveSeries(original);
        store.saveProgress(original.episodes.get(1), 42000, 60000); store.setPinned(original.id, true); store.hideSeries(original.id);
        assertEquals(1, store.mergeRefreshedSeries(series(2, 3)));
        assertEquals(3, store.listSeries().get(0).episodes.size()); assertEquals("ep2", store.getLastEpisode(original.id));
        assertEquals(42000, store.getProgress("ep2").positionMs); assertTrue(store.isPinned(original.id)); assertTrue(store.listVisibleSeries().isEmpty());
        assertEquals(1, store.newEpisodeCount(original.id));
        assertEquals(0, store.mergeRefreshedSeries(series(2, 3))); assertEquals(1, store.newEpisodeCount(original.id));
        store.markEpisodesSeen(original.id); assertEquals(0, store.newEpisodeCount(original.id));
    }
    @Test public void invalidRefreshCannotPartiallyWrite() {
        store.saveSeries(series(1)); Models.Series incoming = series(2, 3); incoming.episodes.get(1).seriesId = "wrong";
        assertThrows(IllegalArgumentException.class, () -> store.mergeRefreshedSeries(incoming));
        assertEquals(1, store.listSeries().get(0).episodes.size()); assertEquals(0, store.newEpisodeCount("ss1"));
    }
    @Test public void refreshDoesNotResurrectDeletedSeries() {
        store.saveSeries(series(1)); store.deleteSeries("ss1");
        assertThrows(IllegalArgumentException.class, () -> store.mergeRefreshedSeries(series(1, 2)));
        assertTrue(store.listSeries().isEmpty());
    }
    @Test public void journalSurvivesStoreRecreationAndDeduplicatesSameProfile() {
        Models.Quality q = quality(80); List<Models.Episode> episodes = series(1, 2).episodes;
        assertEquals(2, PendingDownloads.add(context, episodes, q, Collections.singletonMap("ep1", q)));
        assertEquals(0, PendingDownloads.add(context, episodes, q, Collections.emptyMap()));
        List<PendingDownloads.Job> restored = PendingDownloads.list(context.getApplicationContext());
        assertEquals(2, restored.size()); assertEquals(q.videoUrl, restored.get(0).prepared.videoUrl); assertNull(restored.get(1).prepared);
        assertEquals(1, PendingDownloads.add(context, episodes.subList(0, 1), quality(64), Collections.emptyMap()));
        assertEquals(3, PendingDownloads.list(context).size());
    }
    @Test public void cancellationTombstonesLateWorkAndPreservesOtherQualities() {
        Models.Episode ep = series(1).episodes.get(0); Models.Quality q = quality(80);
        PendingDownloads.add(context, Collections.singletonList(ep), q, Collections.emptyMap());
        String late = PendingDownloads.list(context).get(0).id;
        PendingDownloads.add(context, Collections.singletonList(ep), quality(64), Collections.emptyMap());
        PendingDownloads.cancel(context, ep, q); assertFalse(PendingDownloads.contains(context, late));
        PendingDownloads.finish(context, late, "late error"); assertEquals(1, PendingDownloads.list(context).size());
        assertEquals(64, PendingDownloads.list(context).get(0).profile.qn);
    }
    @Test public void failedJournalIsRetainedAndExplicitRetryRefreshesAddresses() {
        Models.Quality q = quality(80);
        PendingDownloads.add(context, series(1).episodes, q, Collections.singletonMap("ep1", q));
        String id = PendingDownloads.list(context).get(0).id;
        PendingDownloads.finish(context, id, "解析失败"); assertFalse(PendingDownloads.hasReady(context));
        PendingDownloads.retry(context); assertTrue(PendingDownloads.hasReady(context)); assertNull(PendingDownloads.list(context).get(0).prepared);
        PendingDownloads.finish(context, id, ""); assertTrue(PendingDownloads.list(context).isEmpty());
    }
    @Test public void controlIntentSurvivesNetworkChangesAndObjectRecreation() {
        SharedPreferences prefs = context.getSharedPreferences("testControls", 0); prefs.edit().clear().commit();
        DownloadControls controls = new DownloadControls(prefs); controls.pause("a", true);
        controls = new DownloadControls(prefs);
        assertEquals(DownloadNetworkPolicy.STOP_USER_PAUSED, controls.reason("a", 0));
        assertEquals(DownloadNetworkPolicy.STOP_WIFI_REQUIRED | DownloadNetworkPolicy.STOP_USER_PAUSED,
            controls.reason("a", DownloadNetworkPolicy.STOP_WIFI_REQUIRED));
        assertEquals(0, controls.reason("b", 0)); controls.prioritize("b");
        assertEquals(0, controls.reason("b", 0)); assertEquals(DownloadControls.STOP_PRIORITY, controls.reason("c", 0));
        controls.forget("b"); assertEquals(0, controls.reason("c", 0)); assertTrue(controls.paused("a"));
    }
    @Test public void pausingPriorityLetsOtherJobsContinueWithoutUndoingPauses() {
        DownloadControls controls = new DownloadControls(context.getSharedPreferences("testPriority", 0));
        controls.prioritize("a"); controls.pause("a", true);
        assertEquals("", controls.priority()); assertEquals(0, controls.reason("b", 0));
        assertTrue(controls.paused("a")); controls.resumeAll(); assertEquals(0, controls.reason("a", 0));
    }
    @Test public void storageFiltersOnlyCompletedWatchedCopiesAndRealDuplicates() {
        MediaRepository.OfflineItem low = item(1, 64, 50), high = item(1, 80, 100), other = item(2, 80, 200);
        MediaRepository.OfflineItem incomplete = item(2, 64, 10); incomplete.complete = false;
        List<MediaRepository.OfflineItem> all = Arrays.asList(low, high, other, incomplete);
        store.saveProgress(low.episode, 60000, 60000);
        assertEquals(Arrays.asList(low, high), StorageCleanup.filter(all, store, 1));
        assertEquals(Arrays.asList(low, high), StorageCleanup.filter(all, store, 2));
        assertEquals(150, StorageCleanup.bytes(Arrays.asList(low, high, low)));
        assertEquals(4, StorageCleanup.filter(all, store, 0).size());
    }
    @Test public void sleepTimerIsOneShotAndChangingModeClearsOldDeadline() {
        SleepTimer timer = new SleepTimer(); timer.minutes(15, 1000);
        assertFalse(timer.consumeTime(900999)); assertTrue(timer.consumeTime(901000)); assertFalse(timer.consumeTime(9999999));
        timer.minutes(30, 1000); timer.afterEpisode(); assertFalse(timer.consumeTime(Long.MAX_VALUE));
        assertTrue(timer.consumeEpisodeEnd()); assertFalse(timer.consumeEpisodeEnd());
        timer.afterEpisode(); timer.minutes(60, 0); assertFalse(timer.consumeEpisodeEnd());
        timer.cancel(); assertFalse(timer.consumeTime(Long.MAX_VALUE));
    }
    @Test public void backupRetentionMustStayInsideGrantedTree() {
        Uri root = Uri.parse("content://files/tree/backup");
        assertTrue(AutoBackup.sameTree(root, Uri.parse("content://files/tree/backup/document/backup%2Fa.json")));
        assertFalse(AutoBackup.sameTree(root, Uri.parse("content://other/tree/backup/document/a.json")));
        assertFalse(AutoBackup.sameTree(root, Uri.parse("content://files/tree/private/document/a.json")));
        assertFalse(AutoBackup.sameTree(root, Uri.parse("file:///private")));
    }
}
