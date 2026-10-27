package com.animeshelf.app;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.offline.DefaultDownloadIndex;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadProgress;
import androidx.media3.exoplayer.offline.DownloadRequest;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

/** Real navigation, sources and READY listeners with fake playback, never external media IO. */
@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainPlaybackContinuityTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private Store store;
    private MediaRepository repository;
    private Models.Series series;
    private Models.Quality profile;
    private final List<FakePlayer> created = new ArrayList<>();
    private final AtomicInteger resolveCalls = new AtomicInteger();

    @Before public void setUp() throws Exception {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get(); store = new Store(activity);
        repository = (MediaRepository) get("media");
        series = new Models.Series(); series.id = "continuity-series"; series.title = "续播测试番剧";
        for (int i = 1; i <= 3; i++) {
            Models.Episode episode = new Models.Episode();
            episode.id = "continuity-episode-" + i; episode.seriesId = series.id;
            episode.seriesTitle = series.title; episode.index = i; episode.title = "第 " + i + " 集";
            series.episodes.add(episode);
        }
        store.saveSeries(series);
        ((Set<String>) get("coverLookups")).add(series.id);
        profile = quality(80, "7");
        set("playerFactory", (MainActivity.PlayerFactory) () -> {
            FakePlayer result = new FakePlayer(); created.add(result); return result.player;
        });
        resolver((episode, cookie) -> { resolveCalls.incrementAndGet(); return Collections.emptyList(); });
        invoke("renderLibrary"); idle();
    }

    @After public void tearDown() throws Exception {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        if (controller != null) controller.pause().stop().destroy();
        if (repository != null) repository.releaseForTests();
    }

    @Test public void homeContinueUsesSavedOfflineProfileAndItsResumeWithoutAskingAgain() throws Exception {
        Models.Episode first = series.episodes.get(0);
        saveDownloaded(first, profile, true);
        saveDownloaded(first, quality(64, "7"), true);
        store.setSeriesPlaybackPreference(series.id, true, profile);
        store.saveProgress(first, 42000, 300000);
        invoke("renderLibrary");
        clickDescription("继续观看：" + series.id);

        assertEquals(1, created.size());
        assertEquals(0, resolveCalls.get());
        assertEquals(first.id, ((Models.Episode) get("currentEpisode")).id);
        assertTrue((Boolean) get("currentOffline"));
        assertProfile(profile, (Models.Quality) get("currentQuality"));
        assertEquals(42000, currentFake().position);
        assertFalse(dialogShowing());
    }

    @Test public void episodeListKeepsCurrentSuccessfulProfileAheadOfGlobalPreference() throws Exception {
        start(0, profile, false).ready();
        Models.Quality global = quality(64, "12"); store.setPreferredQuality(global);
        Models.Quality actual = quality(80, "7"); actual.id = "80:7:30232";
        resolver((episode, cookie) -> {
            resolveCalls.incrementAndGet(); assertEquals(series.episodes.get(1).id, episode.id);
            return Arrays.asList(global, actual);
        });
        clickDescription("第 2 集，长按选择"); drainWorker();

        assertEquals(1, resolveCalls.get());
        assertEquals(2, created.size());
        assertEquals(series.episodes.get(1).id, ((Models.Episode) get("currentEpisode")).id);
        assertFalse((Boolean) get("currentOffline"));
        assertSame(actual, get("currentQuality"));
        assertFalse(dialogShowing());
    }

    @Test public void episodePickerAlsoContinuesTheCurrentOfflineModeWithoutResolvingOnline() throws Exception {
        saveDownloaded(series.episodes.get(0), profile, true);
        saveDownloaded(series.episodes.get(1), profile, true);
        FakePlayer first = start(0, profile, true); first.ready();
        invoke("chooseEpisodeMenu"); chooseItem(1);

        assertEquals(2, created.size());
        assertTrue(first.released);
        assertEquals(series.episodes.get(1).id, ((Models.Episode) get("currentEpisode")).id);
        assertTrue((Boolean) get("currentOffline"));
        assertEquals(0, resolveCalls.get());
        assertFalse(dialogShowing());
    }

    @Test public void completedDownloadTakesPriorityOverSavedOnlineModeWithoutARequest() throws Exception {
        Models.Episode episode = series.episodes.get(0);
        saveDownloaded(episode, profile, true);
        store.setSeriesPlaybackPreference(series.id, false, profile);
        resolver((selected, cookie) -> { resolveCalls.incrementAndGet(); return Collections.singletonList(profile); });
        invoke("selectEpisode", new Class<?>[]{Models.Episode.class}, episode); drainWorker();

        assertEquals(0, resolveCalls.get());
        assertEquals(1, created.size());
        assertTrue((Boolean) get("currentOffline"));
        assertFalse(dialogShowing());
    }

    @Test public void missingOfflineProfileUsesAnAvailableDownloadedVersionWithoutNetwork() throws Exception {
        Models.Episode episode = series.episodes.get(1);
        Models.Quality lower = quality(64, "7");
        saveDownloaded(episode, lower, true);
        store.setSeriesPlaybackPreference(series.id, true, profile);
        invoke("selectEpisode", new Class<?>[]{Models.Episode.class}, episode);

        assertFalse(dialogShowing());
        assertEquals(0, resolveCalls.get());
        assertEquals(1, created.size());
        assertProfile(lower, (Models.Quality) get("currentQuality"));
        assertTrue((Boolean) get("currentOffline"));
        assertEquals(0, resolveCalls.get());
    }

    @Test public void onlyReadyPlaybackCanReplaceTheLastSuccessfulSeriesPreference() throws Exception {
        store.setSeriesPlaybackPreference(series.id, true, profile);
        Models.Quality attempted = quality(112, "12");
        FakePlayer failed = start(0, attempted, false);
        assertProfile(profile, store.getSeriesPlaybackPreference(series.id).quality);
        failed.fail();
        assertTrue(store.getSeriesPlaybackPreference(series.id).offline);
        assertProfile(profile, store.getSeriesPlaybackPreference(series.id).quality);
        showingDialog().dismiss(); idle();

        FakePlayer succeeding = start(0, attempted, false);
        assertProfile(profile, store.getSeriesPlaybackPreference(series.id).quality);
        succeeding.ready();
        assertFalse(new Store(activity).getSeriesPlaybackPreference(series.id).offline);
        assertProfile(attempted, store.getSeriesPlaybackPreference(series.id).quality);
    }

    @Test public void quickQualitySwitchUsesLatestPositionAndPreservesPauseAndTemporarySkipCancellation() throws Exception {
        FakePlayer previous = start(0, profile, false); previous.ready();
        previous.position = 30000; previous.playWhenReady = false;
        ((SkipPlaybackPolicy) get("skipPolicy")).disableForSession();
        Models.Quality replacement = quality(80, "12");
        resolver((episode, cookie) -> { resolveCalls.incrementAndGet(); return Arrays.asList(profile, replacement); });
        invoke("choosePlaybackQuality"); drainWorker();
        assertSame(previous.player, get("player")); assertFalse(previous.released);
        previous.position = 67000;
        chooseQuality(replacement);

        assertEquals(2, created.size());
        assertTrue(previous.released);
        assertEquals(67000, currentFake().position);
        assertFalse(currentFake().playWhenReady);
        assertEquals(series.episodes.get(0).id, ((Models.Episode) get("currentEpisode")).id);
        assertTrue(((SkipPlaybackPolicy) get("skipPolicy")).isDisabledForSession());
        assertEquals(67000, store.getProgress(series.episodes.get(0).id).positionMs);
        assertProfile(profile, store.getSeriesPlaybackPreference(series.id).quality);
        currentFake().ready();
        assertProfile(replacement, store.getSeriesPlaybackPreference(series.id).quality);
        assertEquals(1, resolveCalls.get());
    }

    @Test public void dismissingTheQuickQualityMenuDoesNotReplacePlaybackOrPreferences() throws Exception {
        FakePlayer current = start(0, profile, false); current.ready(); current.position = 88000;
        resolver((episode, cookie) -> { resolveCalls.incrementAndGet(); return Arrays.asList(profile, quality(112, "12")); });
        invoke("choosePlaybackQuality"); drainWorker();
        clickDialog(AlertDialog.BUTTON_NEGATIVE);

        assertSame(current.player, get("player")); assertFalse(current.released);
        assertEquals(1, created.size()); assertEquals(88000, current.position);
        assertProfile(profile, store.getSeriesPlaybackPreference(series.id).quality);
        assertEquals("Canceling the picker restores the existing playback navigation", get("selectionToken"), get("playbackNavigationToken"));
        assertFalse((Boolean) get("playbackChoicePending"));
    }

    @Test public void delayedQualityResultCannotReopenPlaybackAfterReturningHome() throws Exception {
        FakePlayer previous = start(0, profile, false); previous.ready(); previous.position = 52000;
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        resolver((episode, cookie) -> {
            resolveCalls.incrementAndGet(); started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test resolver timeout");
            return Arrays.asList(profile, quality(112, "12"));
        });
        try {
            invoke("choosePlaybackQuality");
            assertTrue(started.await(5, TimeUnit.SECONDS));
            invoke("renderLibrary");
        } finally { release.countDown(); }
        drainWorker();

        assertNull(get("player")); assertTrue(previous.released);
        assertEquals(1, created.size()); assertFalse(dialogShowing());
        assertEquals(52000, store.getProgress(series.episodes.get(0).id).positionMs);
    }

    @Test public void offlineQuickQualityListsOnlyCompleteVersionsAndNeverResolvesOnline() throws Exception {
        Models.Episode episode = series.episodes.get(0);
        Models.Quality lower = quality(64, "7"), incomplete = quality(112, "12");
        saveDownloaded(episode, profile, true); saveDownloaded(episode, lower, true);
        saveDownloaded(episode, incomplete, false);
        FakePlayer previous = start(0, profile, true); previous.ready(); previous.position = 77000;
        invoke("choosePlaybackQuality"); idle();

        assertEquals(2, showingDialog().getListView().getAdapter().getCount());
        assertEquals(0, resolveCalls.get());
        chooseQuality(lower);
        assertEquals(2, created.size());
        assertTrue((Boolean) get("currentOffline"));
        assertEquals(77000, currentFake().position);
        assertProfile(lower, (Models.Quality) get("currentQuality"));
        assertEquals(0, resolveCalls.get());
    }

    @Test public void adjacentControlsRespectSeriesBoundariesAndKeepOfflineMode() throws Exception {
        for (Models.Episode episode : series.episodes) saveDownloaded(episode, profile, true);
        start(0, profile, true).ready();
        PlayerOverlay.Host host = playerHost();
        assertFalse(host.canPrevious()); assertTrue(host.canNext());
        host.previousEpisode();
        assertEquals(1, created.size());
        host.nextEpisode(); currentFake().ready();
        assertEquals(series.episodes.get(1).id, ((Models.Episode) get("currentEpisode")).id);
        assertTrue(host.canPrevious()); assertTrue(host.canNext());
        host.nextEpisode(); currentFake().ready();
        assertEquals(series.episodes.get(2).id, ((Models.Episode) get("currentEpisode")).id);
        assertTrue(host.canPrevious()); assertFalse(host.canNext());
        host.nextEpisode();
        assertEquals(3, created.size());
        assertTrue((Boolean) get("currentOffline"));
        assertEquals(0, resolveCalls.get());
    }

    @Test public void oldQualityChoiceCannotReplaceANewerEpisode() throws Exception {
        FakePlayer previous = start(0, profile, false); previous.ready();
        Models.Quality replacement = quality(112, "12");
        int token = (Integer) get("selectionToken");
        invoke("showPlaybackQualityChoices", new Class<?>[]{Models.Episode.class, List.class, boolean.class, int.class, ExoPlayer.class},
            series.episodes.get(0), Arrays.asList(profile, replacement), false, token, previous.player);
        AlertDialog stale = showingDialog();
        FakePlayer current = start(1, profile, false); current.ready();
        stale.getListView().performItemClick(null, 1, 1); idle();

        assertSame(current.player, get("player"));
        assertEquals(series.episodes.get(1).id, ((Models.Episode) get("currentEpisode")).id);
        assertEquals(2, created.size());
        assertProfile(profile, store.getSeriesPlaybackPreference(series.id).quality);
    }

    @Test public void explicitReplayStartsAFreshSkipSessionButResumingPausedPlaybackKeepsCancellation() throws Exception {
        store.setSkipSettings(series.id, true, 90, 60);
        FakePlayer current = start(0, profile, false); current.ready();
        SkipPlaybackPolicy skip = (SkipPlaybackPolicy) get("skipPolicy");
        skip.disableForSession(); current.position = 120000; current.playWhenReady = false;
        invoke("togglePlayback");
        assertTrue(current.playWhenReady);
        assertTrue("Ordinary pause/resume must keep the user's cancellation", skip.isDisabledForSession());

        current.state = Player.STATE_ENDED; current.position = 300000;
        invoke("selectEpisode", new Class<?>[]{Models.Episode.class}, series.episodes.get(0));
        assertSame(current.player, get("player")); assertEquals(1, created.size());
        assertEquals(0, current.position); assertFalse(skip.isDisabledForSession());
        current.ready(); invoke("updateSkipPlayback");
        assertEquals(90000, current.position);

        skip.disableForSession(); current.state = Player.STATE_ENDED; current.position = 300000;
        invoke("togglePlayback");
        assertEquals(0, current.position); assertFalse(skip.isDisabledForSession());
        current.ready(); invoke("updateSkipPlayback");
        assertEquals(90000, current.position);
        assertEquals(1, created.size()); assertEquals(0, resolveCalls.get());
    }

    @Test public void offlinePagesShowPersistentRecentEpisodeAndIndividualProgressWithoutResolving() throws Exception {
        Models.Episode first = series.episodes.get(0), second = series.episodes.get(1);
        saveDownloaded(first, profile, true); saveDownloaded(second, profile, true);
        store.saveProgress(first, 917000, 1439000); store.saveProgress(second, 492000, 1439000);
        resolver((ep, cookie) -> { throw new AssertionError("Offline history must not resolve network media"); });
        invoke("openDownloads");
        assertTrue(text((View) get("content")).contains("最近观看：第 2 集 · 08:12"));
        set("downloadGroupId", series.id); invoke("renderDownloads");
        assertTrue(text((View) get("content")).contains("看到 15:17 / 23:59"));
        assertTrue(text((View) get("content")).contains("看到 08:12 / 23:59"));
        clickDescription("离线继续观看：" + series.id);
        assertTrue((Boolean) get("currentOffline")); assertEquals(second.id, ((Models.Episode) get("currentEpisode")).id);
        assertEquals(492000, currentFake().position); assertEquals(0, resolveCalls.get());
    }

    @Test public void newestOnlineHistoryIsStillVisibleWhenOnlyEarlierEpisodesAreDownloaded() throws Exception {
        Models.Episode first = series.episodes.get(0), second = series.episodes.get(1);
        saveDownloaded(first, profile, true); store.saveProgress(first, 13000, 300000); store.saveProgress(second, 25000, 300000);
        invoke("openDownloads"); set("downloadGroupId", series.id); invoke("renderDownloads");
        assertTrue(text((View) get("content")).contains("最近观看：第 2 集 · 00:25（本集未下载）"));
        clickDescription("离线继续观看：" + series.id);
        assertEquals(first.id, ((Models.Episode) get("currentEpisode")).id);
        assertEquals(13000, currentFake().position); assertTrue((Boolean) get("currentOffline"));
        assertEquals(0, resolveCalls.get());
    }

    @Test public void downloadedNextEpisodeAlsoOverridesOnlineAutoContinuation() throws Exception {
        Models.Episode second = series.episodes.get(1); saveDownloaded(second, profile, true);
        FakePlayer first = start(0, profile, false); first.ready(); store.setAutoNext(true);
        first.state = Player.STATE_ENDED;
        invoke("finishEpisode", new Class<?>[]{ExoPlayer.class}, first.player); idle();
        assertEquals(second.id, ((Models.Episode) get("currentEpisode")).id);
        assertTrue((Boolean) get("currentOffline")); assertEquals(0, resolveCalls.get());
    }

    @Test public void compactMenuKeepsSpeedEpisodeAndDisplayReachable() throws Exception {
        start(0, profile, false).ready();
        invoke("showCompactPlaybackMenu");
        assertEquals(4, showingDialog().getListView().getAdapter().getCount());
        chooseItem(1);
        assertEquals(series.episodes.size(), showingDialog().getListView().getAdapter().getCount());
    }

    @Test public void singleDownloadShowsStorageBeforeEnqueueAndCancelLeavesNoTask() throws Exception {
        profile.videoBytes = 120000000; profile.audioBytes = 4000000;
        invoke("prepareDownloads", new Class<?>[]{java.util.List.class, Models.Quality.class}, Collections.singletonList(series.episodes.get(0)), profile);
        drainWorker();
        assertTrue(dialogText().contains("设备可用")); assertTrue(dialogText().contains("第 1 集"));
        assertTrue(dialogText().contains("124")); assertTrue(repository.downloads().isEmpty());
        clickDialog(AlertDialog.BUTTON_NEGATIVE); assertTrue(repository.downloads().isEmpty());
    }

    @Test public void preparationCanceledInBackgroundCannotReopenConfirmationOrQueueDownloads() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        resolver((ep, cookie) -> { started.countDown(); release.await(5, TimeUnit.SECONDS); return Collections.singletonList(profile); });
        invoke("prepareDownloads", new Class<?>[]{java.util.List.class, Models.Quality.class}, series.episodes.subList(0, 2), profile);
        assertTrue(started.await(5, TimeUnit.SECONDS)); controller.pause(); release.countDown(); drainWorker();
        assertFalse(dialogShowing()); assertTrue(repository.downloads().isEmpty());
    }

    @Test public void episodeEndSleepTimerOverridesAutoNextWithoutChangingPreference() throws Exception {
        store.setAutoNext(true); FakePlayer first = start(0, profile, false); first.ready();
        ((SleepTimer) get("sleepTimer")).afterEpisode();
        invoke("finishEpisode", new Class<?>[]{ExoPlayer.class}, first.player); idle();
        assertEquals(1, created.size()); assertFalse(first.playWhenReady); assertTrue(store.isAutoNext());
        assertEquals(0, resolveCalls.get());
    }

    @Test public void confirmedBatchSurvivesActivityPauseAndHasNoModalOwner() throws Exception {
        java.util.Map<String, Models.Quality> prepared = new java.util.HashMap<>();
        for (Models.Episode episode : series.episodes) prepared.put(episode.id, profile);
        invoke("startBatchDownloads", new Class<?>[]{Models.Series.class, java.util.List.class, Models.Quality.class, java.util.Map.class},
            series, series.episodes, profile, prepared);
        assertEquals(3, PendingDownloads.list(activity).size());
        controller.pause();
        assertEquals(3, PendingDownloads.list(activity).size()); assertNull(get("batchDialog"));
        PendingDownloads.clear(activity);
    }

    @Test @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    public void fullscreenSidebarKeepsPlayingAndUsesOfflineResumeWithoutNetwork() throws Exception {
        Models.Episode next = series.episodes.get(1); saveDownloaded(next, profile, true);
        store.saveProgress(next, 42000, 300000);
        FakePlayer first = start(0, profile, false); first.ready(); first.position = 18000;
        invoke("setFullScreen", new Class<?>[]{boolean.class}, true);
        invoke("chooseEpisodeMenu");
        android.app.Dialog sidebar = (android.app.Dialog) get("episodeSidebar");
        assertNotNull(sidebar); assertTrue(sidebar.isShowing()); assertTrue(first.playWhenReady);
        assertEquals(18000, first.position); assertFalse(first.released);
        assertTrue(text(sidebar.getWindow().getDecorView()).contains("已下载 · 看到 00:42"));
        View nextRow = description(sidebar.getWindow().getDecorView(), "侧栏第 2 集，已下载 · 看到 00:42");
        assertNotNull(nextRow); assertTrue(nextRow.performClick()); idle();
        assertFalse(sidebar.isShowing()); assertTrue((Boolean) get("fullScreen"));
        assertEquals(next.id, ((Models.Episode) get("currentEpisode")).id);
        assertTrue((Boolean) get("currentOffline")); assertEquals(42000, currentFake().position);
        assertEquals(0, resolveCalls.get());
    }

    private FakePlayer start(int index, Models.Quality quality, boolean offline) throws Exception {
        invoke("play", new Class<?>[]{Models.Episode.class, Models.Quality.class, boolean.class}, series.episodes.get(index), quality, offline);
        FakePlayer configured = currentFake();
        assertEquals("The real source setup must reach fake prepare without an error", Player.STATE_BUFFERING, configured.state);
        return configured;
    }

    private FakePlayer currentFake() { assertFalse(created.isEmpty()); return created.get(created.size() - 1); }

    private void saveDownloaded(Models.Episode episode, Models.Quality quality, boolean audioComplete) throws Exception {
        String key = MediaRepository.key(episode, quality); store.saveDownload(key, episode, quality);
        completeTrack(key + ":v", quality.videoUrl);
        if (audioComplete) completeTrack(key + ":a", quality.audioUrl);
    }

    private void completeTrack(String key, String url) throws Exception {
        DownloadRequest request = new DownloadRequest.Builder(key, Uri.parse(url)).setCustomCacheKey(key).build();
        DownloadProgress progress = new DownloadProgress(); progress.bytesDownloaded = 100; progress.percentDownloaded = 100;
        Download download = new Download(request, Download.STATE_COMPLETED, 1, 1, 100, 0, Download.FAILURE_REASON_NONE, progress);
        ((DefaultDownloadIndex) repository.manager.getDownloadIndex()).putDownload(download);
    }

    private static Models.Quality quality(int qn, String codec) {
        Models.Quality quality = new Models.Quality(); quality.qn = qn; quality.id = qn + ":" + codec + ":30280";
        quality.label = qn + " / " + codec;
        quality.videoUrl = "https://media.example.test/" + qn + "-" + codec + "/video.m4s";
        quality.audioUrl = "https://media.example.test/" + qn + "-" + codec + "/audio.m4s";
        return quality;
    }

    private static void assertProfile(Models.Quality expected, Models.Quality actual) {
        assertNotNull(actual); assertTrue(DownloadCatalogue.sameProfile(expected, actual));
    }
    private void resolver(MainActivity.QualityResolver resolver) throws Exception { set("qualityResolver", resolver); }
    private PlayerOverlay.Host playerHost() throws Exception {
        PlayerOverlay overlay = (PlayerOverlay) get("playerOverlay");
        Field field = PlayerOverlay.class.getDeclaredField("host"); field.setAccessible(true);
        return (PlayerOverlay.Host) field.get(overlay);
    }
    private void clickDescription(String value) throws Exception {
        View target = description((View) get("root"), value); assertNotNull("Missing " + value, target);
        assertTrue(target.performClick()); idle();
    }
    private static View description(View view, String value) {
        if (value.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View result = description(((ViewGroup) view).getChildAt(i), value); if (result != null) return result;
        }
        return null;
    }
    private static String text(View view) {
        StringBuilder result = new StringBuilder();
        if (view instanceof TextView) result.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) result.append(text(((ViewGroup) view).getChildAt(i)));
        return result.toString();
    }
    private static AlertDialog showingDialog() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(dialog); assertTrue(dialog.isShowing()); return dialog;
    }
    private static boolean dialogShowing() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); return dialog != null && dialog.isShowing();
    }
    private static String dialogText() { return text(showingDialog().getWindow().getDecorView()); }
    private static void chooseItem(int index) { showingDialog().getListView().performItemClick(null, index, index); idle(); }
    private static void chooseQuality(Models.Quality quality) {
        AlertDialog dialog = showingDialog();
        for (int i = 0; i < dialog.getListView().getAdapter().getCount(); i++) {
            if (String.valueOf(dialog.getListView().getAdapter().getItem(i)).contains(quality.label)) { chooseItem(i); return; }
        }
        fail("Missing quality " + quality.label);
    }
    private static void clickDialog(int which) { idle(); assertTrue(showingDialog().getButton(which).performClick()); idle(); }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private void drainWorker() throws Exception {
        ExecutorService worker = (ExecutorService) get("io"); worker.shutdown();
        assertTrue("Resolver completed", worker.awaitTermination(5, TimeUnit.SECONDS)); idle();
    }
    private Object get(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity);
    }
    private void set(String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(activity, value);
    }
    private void invoke(String name) throws Exception { invoke(name, new Class<?>[]{}); }
    private void invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(activity, args);
    }

    private static final class FakePlayer {
        final ExoPlayer player;
        final List<Player.Listener> listeners = new ArrayList<>();
        long position;
        int state = Player.STATE_IDLE;
        float speed = 1f;
        boolean playWhenReady, released;
        PlaybackException error;
        FakePlayer() {
            player = (ExoPlayer) Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(), new Class<?>[]{ExoPlayer.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getApplicationLooper": return Looper.getMainLooper();
                    case "getAvailableCommands": return Player.Commands.EMPTY;
                    case "getCurrentTimeline": return Timeline.EMPTY;
                    case "getCurrentTracks": return Tracks.EMPTY;
                    case "getVideoSize": return VideoSize.UNKNOWN;
                    case "getPlaybackState": return state;
                    case "getCurrentPosition": case "getContentPosition": return position;
                    case "getDuration": case "getContentDuration": return 300000L;
                    case "getPlaybackParameters": return new PlaybackParameters(speed);
                    case "getPlayWhenReady": return playWhenReady;
                    case "isPlaying": return playWhenReady && state == Player.STATE_READY;
                    case "isCurrentMediaItemSeekable": return true;
                    case "getPlayerError": return error;
                    case "seekTo": position = ((Number) args[args.length - 1]).longValue(); return null;
                    case "setPlaybackSpeed": speed = ((Number) args[0]).floatValue(); return null;
                    case "setPlayWhenReady": playWhenReady = (Boolean) args[0]; return null;
                    case "prepare": state = Player.STATE_BUFFERING; return null;
                    case "pause": playWhenReady = false; return null;
                    case "play": playWhenReady = true; return null;
                    case "release": released = true; return null;
                    case "addListener": listeners.add((Player.Listener) args[0]); return null;
                    case "removeListener": listeners.remove(args[0]); return null;
                    case "equals": return proxy == args[0];
                    case "hashCode": return System.identityHashCode(proxy);
                    case "toString": return "ContinuityFakePlayer";
                }
                Class<?> type = method.getReturnType();
                if (type == boolean.class) return false;
                if (type == int.class) return 0;
                if (type == long.class) return 0L;
                if (type == float.class) return 0f;
                return null;
            });
        }
        void ready() {
            state = Player.STATE_READY; error = null;
            for (Player.Listener listener : new ArrayList<>(listeners)) listener.onPlaybackStateChanged(state);
        }
        void fail() {
            state = Player.STATE_IDLE;
            error = new PlaybackException("Synthetic unavailable source", new IOException("fixture"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
            for (Player.Listener listener : new ArrayList<>(listeners)) listener.onPlayerError(error);
        }
    }
}
