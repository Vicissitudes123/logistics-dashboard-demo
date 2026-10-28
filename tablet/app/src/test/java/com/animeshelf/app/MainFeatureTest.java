package com.animeshelf.app;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
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
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;

/** Feature boundaries use real Activity/UI/storage and an injected, network-free resolver. */
@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainFeatureTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private Store store;
    private Models.Series series;
    private Models.Quality profile;
    private FakePlayback playback;
    private AtomicInteger resolveCalls;

    @Before public void setUp() throws Exception {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        store = new Store(activity);
        series = new Models.Series(); series.id = "feature-series"; series.title = "功能回归番剧";
        for (int i = 1; i <= 3; i++) {
            Models.Episode episode = new Models.Episode();
            episode.id = "feature-episode-" + i; episode.index = i; episode.title = "第 " + i + " 集";
            episode.seriesId = series.id; episode.seriesTitle = series.title;
            series.episodes.add(episode);
        }
        store.saveSeries(series);
        // Keep poster discovery out of tests; an absent poster uses the real fallback UI.
        ((Set<String>) get("coverLookups")).add(series.id);
        profile = quality(80, "7", "30280");
        resolveCalls = new AtomicInteger();
        resolver((episode, cookie) -> { resolveCalls.incrementAndGet(); return Collections.emptyList(); });
        invoke("renderLibrary");
    }

    @After public void tearDown() throws Exception {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        if (controller != null) controller.pause().stop().destroy();
        Field singleton = MediaRepository.class.getDeclaredField("instance"); singleton.setAccessible(true);
        MediaRepository active = (MediaRepository) singleton.get(null);
        if (active != null) active.releaseForTests();
    }

    @Test public void backFirstUnlocksThenLeavesFullscreenThenSavesAndReturnsHome() throws Exception {
        attach(0, Player.STATE_READY, false);
        playback.position = 73000;
        invoke("setFullScreen", new Class<?>[]{boolean.class}, true);
        PlayerOverlay overlay = (PlayerOverlay) get("playerOverlay");
        description(overlay, "锁定播放器").performClick();
        assertTrue(overlay.isLocked());

        activity.onBackPressed();
        assertFalse(overlay.isLocked());
        assertTrue((Boolean) get("fullScreen"));
        assertSame(playback.player, get("player"));
        activity.onBackPressed();
        assertFalse((Boolean) get("fullScreen"));
        assertSame(playback.player, get("player"));
        activity.onBackPressed();
        assertNull(get("player"));
        assertTrue(playback.released);
        assertEquals(73000, store.getProgress(series.episodes.get(0).id).positionMs);
        assertTrue(text((View) get("content")).contains("我的番剧"));
        assertFalse(activity.isFinishing());
    }

    @Test public void watchedManagementChangesTheBadgeWithoutErasingResumeAndHasAPosterFallback() throws Exception {
        Models.Episode first = series.episodes.get(0);
        store.saveProgress(first, 65000, 300000);
        invoke("renderLibrary");
        View card = description((View) get("root"), "番剧：" + series.id);
        assertNotNull(description(card, "封面：" + series.title));
        assertTrue(text(card).contains("观看中"));
        card.performLongClick(); chooseItem(2);
        assertTrue(store.isSeriesWatched(series));
        assertTrue(text(description((View) get("root"), "番剧：" + series.id)).contains("已看完"));
        assertEquals(65000, store.getProgress(first.id).positionMs);
        description((View) get("root"), "番剧：" + series.id).performLongClick(); chooseItem(2);
        assertFalse(store.isSeriesWatched(series));
        assertFalse(text(description((View) get("root"), "番剧：" + series.id)).contains("已看完"));
        assertEquals(first.id, store.getLastEpisode(series.id));
        assertEquals(65000, store.getProgress(first.id).positionMs);
    }

    @Test public void settingsPersistAutoNextAndAllowDisablingAndClearingQualityMemory() throws Exception {
        store.setPreferredQuality(profile);
        invoke("renderSettings");
        boolean before = store.isAutoNext();
        description((View) get("root"), "自动连播设置").performClick();
        assertEquals(!before, new Store(activity).isAutoNext());
        assertTrue(text(description((View) get("root"), "自动连播设置")).contains(before ? "已关闭" : "已开启"));
        description((View) get("root"), "画质偏好设置").performClick(); chooseItem(0);
        assertFalse(new Store(activity).isRememberQuality());
        assertNotNull("Disabling memory does not silently discard the stored preference", store.getPreferredQuality());
        description((View) get("root"), "画质偏好设置").performClick(); chooseItem(1);
        assertNull(new Store(activity).getPreferredQuality());
    }

    @Test public void qualityChoicesPromoteTheActualMatchingProfileAndExplainMissingPreference() throws Exception {
        store.setPreferredQuality(profile);
        Models.Quality lower = quality(64, "7", "30280");
        Models.Quality otherCodec = quality(80, "12", "30280");
        Models.Quality matching = quality(80, "7", "30232");
        int token = (Integer) get("selectionToken");
        invoke("showQualityChoices", new Class<?>[]{Models.Episode.class, java.util.List.class, int.class},
            series.episodes.get(0), Arrays.asList(lower, otherCodec, matching), token);
        AlertDialog dialog = showingDialog();
        assertEquals("★ 已记住 · " + matching.label, dialog.getListView().getAdapter().getItem(0));
        assertEquals(3, dialog.getListView().getAdapter().getCount());
        dialog.dismiss();
        invoke("showQualityChoices", new Class<?>[]{Models.Episode.class, java.util.List.class, int.class},
            series.episodes.get(0), Arrays.asList(lower, otherCodec), token);
        assertTrue(dialogText().contains("偏好画质不可用，请选择"));
        assertNull(get("player"));
        assertEquals(0, resolveCalls.get());
    }

    @Test public void duplicateEndedEventsResolveOnceAndMissingSameQualityNeverDowngrades() throws Exception {
        attach(0, Player.STATE_ENDED, false);
        store.setAutoNext(true);
        resolver((episode, cookie) -> {
            assertEquals(series.episodes.get(1).id, episode.id);
            resolveCalls.incrementAndGet();
            return Collections.singletonList(quality(64, "7", "30280"));
        });
        ended(); ended();
        idle(); drainWorker();
        assertEquals(1, resolveCalls.get());
        assertSame(playback.player, get("player"));
        assertEquals(series.episodes.get(0).id, ((Models.Episode) get("currentEpisode")).id);
        assertTrue(store.getProgress(series.episodes.get(0).id).completed);
        assertTrue(dialogText().contains("相同清晰度与编码"));
        assertEquals("选择画质", showingDialog().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        clickDialog(AlertDialog.BUTTON_POSITIVE);
        assertEquals(1, showingDialog().getListView().getAdapter().getCount());
        assertSame("Only explicit user choice may replace the ended player", playback.player, get("player"));
    }

    @Test public void offlineContinuationWithoutTheNextFileNeverRequestsOnlineQualities() throws Exception {
        attach(0, Player.STATE_ENDED, true); store.setAutoNext(true);
        ended(); idle();
        assertEquals(0, resolveCalls.get());
        assertSame(playback.player, get("player"));
        assertTrue(dialogText().contains("离线连播已暂停"));
        assertTrue(dialogText().contains("没有完整下载"));
        assertTrue(store.getProgress(series.episodes.get(0).id).completed);
    }

    @Test public void disabledUnpreparedLastOrReplacedPlayerCannotScheduleAutoNext() throws Exception {
        attach(0, Player.STATE_ENDED, false);
        store.setAutoNext(false); ended(); idle();
        store.setAutoNext(true); set("sourceReady", false); ended(); idle();
        set("sourceReady", true);
        invoke("finishEpisode", new Class<?>[]{ExoPlayer.class}, new FakePlayback(Player.STATE_ENDED).player); idle();
        set("currentEpisode", series.episodes.get(2)); ended(); idle();
        assertEquals(0, resolveCalls.get());
        assertSame(playback.player, get("player"));
        assertFalse(playback.released);
    }

    @Test public void navigatingHomeBeforePostedContinuationCancelsItAndKeepsCompletion() throws Exception {
        attach(0, Player.STATE_ENDED, false); store.setAutoNext(true);
        ended();
        invoke("renderLibrary");
        idle();
        assertEquals(0, resolveCalls.get());
        assertNull(get("player"));
        assertTrue(playback.released);
        assertTrue(store.getProgress(series.episodes.get(0).id).completed);
        assertTrue(text((View) get("content")).contains("我的番剧"));
    }

    @Test public void lateContinuationResultCannotReopenPlaybackAfterHomeNavigation() throws Exception {
        attach(0, Player.STATE_ENDED, false); store.setAutoNext(true);
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        resolver((episode, cookie) -> {
            resolveCalls.incrementAndGet(); started.countDown();
            if (!finish.await(5, TimeUnit.SECONDS)) throw new IOException("resolver timed out");
            return Collections.singletonList(profile);
        });
        try {
            ended(); idle();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            invoke("renderLibrary");
        } finally { finish.countDown(); }
        drainWorker();
        assertEquals(1, resolveCalls.get());
        assertNull(get("player"));
        assertNull(get("currentEpisode"));
        assertTrue(playback.released);
        assertTrue(text((View) get("content")).contains("我的番剧"));
    }

    @Test public void backgroundEndDoesNotAdvanceButReturningBeforeEndKeepsAutoNextEnabled() throws Exception {
        attach(0, Player.STATE_READY, true); store.setAutoNext(true);
        controller.pause();
        playback.state = Player.STATE_ENDED;
        ended(); idle();
        assertEquals(0, resolveCalls.get());
        assertFalse((Boolean) get("autoNextQueued"));
        playback.state = Player.STATE_READY;
        controller.resume();
        assertEquals(get("selectionToken"), get("playbackNavigationToken"));
        playback.state = Player.STATE_ENDED;
        ended(); idle();
        assertTrue("Foreground completion can still reach the offline continuation guard", dialogText().contains("离线连播已暂停"));
        assertEquals(0, resolveCalls.get());
    }

    @Test public void backupPickersUseSafAndExportWritesOnlyMetadataAfterSavingPlayback() throws Exception {
        invoke("importBackupMenu");
        ShadowActivity.IntentForResult pick = Shadows.shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, pick.intent.getAction());
        assertTrue(pick.intent.hasCategory(Intent.CATEGORY_OPENABLE));
        activity.onActivityResult(pick.requestCode, Activity.RESULT_CANCELED, null);
        assertFalse((Boolean) get("backupBusy"));

        attach(0, Player.STATE_READY, false); playback.position = 47000;
        profile.videoUrl = "https://example.invalid/video?secret=must-not-export";
        profile.headers.put("Cookie", "must-not-export-cookie"); store.setPreferredQuality(profile);
        invoke("exportBackupMenu");
        ShadowActivity.IntentForResult export = Shadows.shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, export.intent.getAction());
        assertEquals("application/json", export.intent.getType());
        assertTrue(export.intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertTrue(export.intent.getStringExtra(Intent.EXTRA_TITLE).endsWith(".json"));
        assertNull(get("player")); assertTrue(playback.released);
        File output = new File(activity.getCacheDir(), "feature-export.json");
        activity.onActivityResult(export.requestCode, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(output)));
        drainWorker();
        String json = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
        LibraryBackup.Snapshot snapshot = store.inspectBackup(json);
        assertEquals(1, snapshot.seriesCount); assertEquals(3, snapshot.episodeCount);
        assertFalse(json.contains("must-not-export"));
        assertFalse(json.contains("Cookie"));
        assertEquals(47000, store.getProgress(series.episodes.get(0).id).positionMs);
        assertFalse((Boolean) get("backupBusy"));
    }

    @Test public void safImportPreviewsBeforeChangingSettingsAndCancelLeavesTheLibraryIntact() throws Exception {
        store.setThemeId("purple");
        String backup = store.exportBackup();
        store.setThemeId("blue");
        File source = new File(activity.getCacheDir(), "feature-import.json");
        Files.write(source.toPath(), backup.getBytes(StandardCharsets.UTF_8));
        invoke("importBackupMenu");
        ShadowActivity.IntentForResult pick = Shadows.shadowOf(activity).getNextStartedActivityForResult();
        activity.onActivityResult(pick.requestCode, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(source)));
        drainWorker();
        assertTrue(dialogText().contains("恢复这份片库备份"));
        assertTrue(dialogText().contains("1 部番剧"));
        assertEquals("blue", store.getThemeId());
        clickDialog(AlertDialog.BUTTON_NEGATIVE);
        assertEquals("blue", store.getThemeId());
        assertEquals(1, store.listSeries().size());
        assertFalse((Boolean) get("backupBusy"));
    }

    @Test public void restoringConfirmedBackupAppliesSettingsWithoutRemovingExistingDownloadMetadata() throws Exception {
        store.setThemeId("purple"); store.setSeriesWatched(series.id, true);
        LibraryBackup.Snapshot snapshot = store.inspectBackup(store.exportBackup());
        store.setThemeId("blue"); store.setSeriesWatched(series.id, false);
        String key = MediaRepository.key(series.episodes.get(0), profile);
        store.saveDownload(key, series.episodes.get(0), profile);
        invoke("previewBackup", new Class<?>[]{LibraryBackup.Snapshot.class}, snapshot);
        clickDialog(AlertDialog.BUTTON_POSITIVE); drainWorker();
        assertEquals("purple", new Store(activity).getThemeId());
        assertTrue(store.isSeriesWatched(series));
        assertEquals(1, ((MediaRepository) get("media")).downloads().size());
        assertEquals(key, ((MediaRepository) get("media")).downloads().get(0).key);
        assertTrue((Boolean) get("settingsPage"));
        assertTrue(dialogText().contains("恢复完成"));
        assertFalse((Boolean) get("backupBusy"));
    }

    @Test public void malformedSafBackupDoesNotPartiallyApplyAndAlwaysReleasesBusyState() throws Exception {
        String before = store.exportBackup();
        File source = new File(activity.getCacheDir(), "broken-import.json");
        Files.write(source.toPath(), "{\"format\":\"anime-shelf-library\",\"series\":[".getBytes(StandardCharsets.UTF_8));
        invoke("importBackupMenu");
        ShadowActivity.IntentForResult pick = Shadows.shadowOf(activity).getNextStartedActivityForResult();
        activity.onActivityResult(pick.requestCode, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(source)));
        drainWorker();
        assertTrue(dialogText().contains("未能完成"));
        assertFalse((Boolean) get("backupBusy"));
        assertEquals(1, store.listSeries().size());
        assertEquals("blue", store.getThemeId());
        assertEquals(store.inspectBackup(before).episodeCount, store.inspectBackup(store.exportBackup()).episodeCount);
    }

    @Test public void downloadCardsOnlyShowTransferSpeedWhileActiveAndKeepFailuresActionable() throws Exception {
        MediaRepository.OfflineItem entry = new MediaRepository.OfflineItem();
        entry.episode = series.episodes.get(0); entry.episodeId = entry.episode.id;
        entry.quality = profile; entry.key = MediaRepository.key(entry.episode, profile);
        entry.status = "下载中"; entry.downloading = true; entry.percent = 25;
        entry.bytesDownloaded = 1024 * 1024; entry.totalBytes = 4 * 1024 * 1024;
        entry.bytesPerSecond = 1024 * 512; entry.estimatedRemainingSeconds = 6;
        ViewGroup content = (ViewGroup) get("content"); content.removeAllViews();
        invoke("renderDownloadItem", new Class<?>[]{MediaRepository.OfflineItem.class}, entry);
        assertTrue(text(content).contains("/s"));
        assertTrue(text(content).contains("预计剩余 6 秒"));
        assertFalse(text(content).contains("离线播放"));

        entry.downloading = false; entry.failed = true; entry.status = "下载失败";
        entry.errorDetail = "HTTP 403 · 视频轨 · cdn.example.invalid";
        content.removeAllViews(); invoke("renderDownloadItem", new Class<?>[]{MediaRepository.OfflineItem.class}, entry);
        assertFalse(text(content).contains("/s"));
        assertFalse(text(content).contains("预计剩余"));
        assertTrue(text(content).contains("重新解析重试"));
        findText(content, "失败详情").performClick();
        assertTrue(dialogText().contains(entry.errorDetail));
        assertFalse(text(content).contains("离线播放"));
    }

    private void attach(int index, int state, boolean offline) throws Exception {
        playback = new FakePlayback(state);
        set("player", playback.player); set("currentEpisode", series.episodes.get(index));
        set("currentSeries", series); set("currentQuality", profile); set("currentOffline", offline);
        set("sourceReady", true); set("seriesPage", true);
        set("playbackNavigationToken", get("selectionToken"));
        ((View) get("playerPanel")).setVisibility(View.VISIBLE);
        ((PlayerOverlay) get("playerOverlay")).setActive(true);
    }
    private void ended() throws Exception {
        invoke("savePosition");
        invoke("finishEpisode", new Class<?>[]{ExoPlayer.class}, playback.player);
    }
    private void resolver(MainActivity.QualityResolver resolver) throws Exception { set("qualityResolver", resolver); }
    private void drainWorker() throws Exception {
        ExecutorService worker = (ExecutorService) get("io");
        worker.shutdown(); assertTrue("Background operation completed", worker.awaitTermination(5, TimeUnit.SECONDS));
        idle();
    }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private static Models.Quality quality(int qn, String codec, String audio) {
        Models.Quality result = new Models.Quality(); result.qn = qn;
        result.id = qn + ":" + codec + ":" + audio; result.label = qn + " / " + codec + " / " + audio;
        return result;
    }
    private static AlertDialog showingDialog() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog); assertTrue(dialog.isShowing()); return dialog;
    }
    private static String dialogText() { return text(showingDialog().getWindow().getDecorView()); }
    private static void chooseItem(int position) { showingDialog().getListView().performItemClick(null, position, position); idle(); }
    private static void clickDialog(int which) { showingDialog().getButton(which).performClick(); idle(); }
    private static View description(View view, String value) {
        if (value.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = description(((ViewGroup) view).getChildAt(i), value); if (found != null) return found;
        }
        return null;
    }
    private static TextView findText(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = findText(((ViewGroup) view).getChildAt(i), value); if (found != null) return found;
        }
        return null;
    }
    private static String text(View view) {
        StringBuilder result = new StringBuilder();
        if (view instanceof TextView) result.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) result.append(text(((ViewGroup) view).getChildAt(i)));
        return result.toString();
    }
    private Object get(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity);
    }
    private void set(String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(activity, value);
    }
    private void invoke(String name) throws Exception { invoke(name, new Class<?>[]{}); }
    private void invoke(String name, Class<?>[] types, Object... values) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(activity, values);
    }
    private static final class FakePlayback {
        final ExoPlayer player;
        int state;
        long position = 300000;
        boolean released, playWhenReady = true;
        FakePlayback(int state) {
            this.state = state;
            player = (ExoPlayer) Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(), new Class<?>[]{ExoPlayer.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getPlaybackState": return this.state;
                    case "getCurrentPosition": return position;
                    case "getDuration": return 300000L;
                    case "getApplicationLooper": return Looper.getMainLooper();
                    case "getPlaybackParameters": return new PlaybackParameters(1f);
                    case "getPlayWhenReady": return playWhenReady;
                    case "isCurrentMediaItemSeekable": return true;
                    case "pause": playWhenReady = false; return null;
                    case "release": released = true; return null;
                    case "equals": return proxy == args[0];
                    case "hashCode": return System.identityHashCode(proxy);
                    case "toString": return "FeatureFakePlayback";
                    default:
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class) return false;
                        if (type == int.class) return 0;
                        if (type == long.class) return 0L;
                        if (type == float.class) return 0f;
                        return null;
                }
            });
        }
    }
}
