package com.animeshelf.app;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
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

/** Real navigation and persistence with public update requests injected and no media network. */
@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainEnhancementTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private Store store;
    private MediaRepository repository;
    private Models.Series recent, watched, offline;

    @Before public void setUp() throws Exception {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get(); store = new Store(activity);
        repository = (MediaRepository) get("media");
        recent = addSeries("enhancement-recent", "共同 最近");
        watched = addSeries("enhancement-watched", "共同 完结");
        offline = addSeries("enhancement-offline", "共同 离线");
        store.saveProgress(recent.episodes.get(0), 42000, 300000);
        store.setPinned(recent.id, true); store.setPinned(offline.id, true);
        store.setSeriesWatched(watched.id, true);
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

    @Test public void filtersCombineWithSearchAndKeepRecentThenPinnedWithoutDuplicates() throws Exception {
        EditText search = (EditText) get("librarySearch");
        search.setText("共同");
        assertEquals(Arrays.asList(recent.id, offline.id, watched.id), cardIds());

        clickDescription("首页筛选已看完");
        assertEquals(Arrays.asList(watched.id), cardIds());
        assertEquals("watched", new Store(activity).getLibraryFilter());
        assertEquals("共同", ((EditText) get("librarySearch")).getText().toString());

        clickDescription("首页筛选未看完");
        assertEquals(Arrays.asList(recent.id, offline.id), cardIds());
        assertSame("Changing only the filter keeps the search input", search, get("librarySearch"));
        search.setText("离线");
        assertEquals(Arrays.asList(offline.id), cardIds());
        clickDescription("首页筛选已看完");
        assertTrue(cardIds().isEmpty());
        assertEquals("离线", search.getText().toString());
        clickDescription("首页筛选全部");
        assertEquals(Arrays.asList(offline.id), cardIds());
        assertEquals(42000, store.getProgress(recent.episodes.get(0).id).positionMs);
    }

    @Test public void offlineFilterRequiresBothTracksAndStillExcludesHiddenSeries() throws Exception {
        saveDownload(offline, true);
        saveDownload(recent, false);
        clickDescription("首页筛选有离线");
        assertEquals(Arrays.asList(offline.id), cardIds());
        assertEquals("offline", new Store(activity).getLibraryFilter());
        store.hideSeries(offline.id);
        invoke("renderLibraryResults");
        assertTrue(cardIds().isEmpty());
        assertEquals("Hiding never deletes download metadata", 2, store.savedDownloads().size());
        assertEquals(42000, store.getProgress(recent.episodes.get(0).id).positionMs);
    }

    @Test public void allowingMobileDownloadsRequiresConfirmationAndEnablingWifiAgainDoesNot() throws Exception {
        invoke("renderSettings");
        assertTrue(store.isWifiOnlyDownloads());
        clickDescription("仅Wi-Fi下载设置");
        assertTrue(dialogText().contains("流量"));
        assertTrue("The warning is shown before changing the preference", store.isWifiOnlyDownloads());
        clickDialog(AlertDialog.BUTTON_NEGATIVE);
        assertTrue(new Store(activity).isWifiOnlyDownloads());

        clickDescription("仅Wi-Fi下载设置");
        clickDialog(AlertDialog.BUTTON_POSITIVE);
        assertFalse(new Store(activity).isWifiOnlyDownloads());
        clickDescription("仅Wi-Fi下载设置");
        assertTrue(new Store(activity).isWifiOnlyDownloads());
        assertFalse(showingDialogOrNull());
    }

    @Test public void updateIsCheckedOnlyOnRequestAndOpensTheBrowserOnlyAfterConfirmation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        UpdateChecker.Release candidate = release(26);
        set("updateResolver", (MainActivity.UpdateResolver) (code, sdk) -> {
            assertTrue(code > 0); assertEquals(28, sdk);
            calls.incrementAndGet(); return candidate;
        });
        invoke("renderSettings"); idle();
        assertEquals(0, calls.get());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        clickDescription("检查更新"); drainWorker();
        assertEquals(1, calls.get());
        assertTrue(dialogText().contains("0.9.9"));
        assertTrue(dialogText().contains("测试更新说明"));
        assertNull("Checking metadata does not start a download", Shadows.shadowOf(activity).getNextStartedActivity());
        clickDialog(AlertDialog.BUTTON_POSITIVE);
        Intent opened = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(opened);
        assertEquals(Intent.ACTION_VIEW, opened.getAction());
        assertEquals(candidate.apkUrl, opened.getDataString());
    }

    @Test public void incompatibleUpdateShowsTheRequirementWithoutOfferingAnInstaller() throws Exception {
        invoke("showUpdateRelease", new Class<?>[]{UpdateChecker.Release.class}, release(35));
        assertTrue(dialogText().contains("Android API 35"));
        assertEquals("知道了", showingDialog().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        clickDialog(AlertDialog.BUTTON_POSITIVE);
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void anUpdateResultArrivingAfterHomeNavigationCannotOpenADialog() throws Exception {
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        UpdateChecker.Release candidate = release(26);
        set("updateResolver", (MainActivity.UpdateResolver) (code, sdk) -> {
            started.countDown();
            if (!finish.await(5, TimeUnit.SECONDS)) throw new IOException("test update timeout");
            return candidate;
        });
        invoke("renderSettings");
        try {
            clickDescription("检查更新");
            assertTrue(started.await(5, TimeUnit.SECONDS));
            invoke("renderLibrary");
        } finally { finish.countDown(); }
        drainWorker();
        assertFalse((Boolean) get("updateChecking"));
        assertFalse(showingDialogOrNull());
        assertEquals(View.GONE, ((View) get("status")).getVisibility());
        assertFalse((Boolean) get("settingsPage"));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void skipEditorStartsDisabledRejectsOutOfRangeAndSavesOnlyThisSeries() throws Exception {
        invoke("renderSeriesContent", new Class<?>[]{Models.Series.class}, recent);
        clickDescription("剧集片头片尾设置");
        View form = showingDialog().getWindow().getDecorView();
        Switch enabled = (Switch) description(form, "启用本番自动跳过");
        EditText intro = (EditText) description(form, "片头秒数");
        EditText outro = (EditText) description(form, "片尾秒数");
        assertFalse(enabled.isChecked());
        enabled.setChecked(true); intro.setText("601"); outro.setText("60");
        clickDialog(AlertDialog.BUTTON_POSITIVE);
        assertTrue(showingDialog().isShowing());
        assertNotNull(intro.getError());
        assertFalse("An invalid form cannot partly persist", store.getSkipSettings(recent.id).enabled);
        intro.setText("90");
        clickDialog(AlertDialog.BUTTON_POSITIVE);
        Store.SkipSettings saved = new Store(activity).getSkipSettings(recent.id);
        assertTrue(saved.enabled); assertEquals(90, saved.introSeconds); assertEquals(60, saved.outroSeconds);
        assertFalse(store.getSkipSettings(watched.id).enabled);
        assertFalse((Boolean) get("skipDialogOpen"));
    }

    @Test public void introWaitsForReadyAndCurrentNavigationAndUndoDisablesOnlyThisSession() throws Exception {
        store.setSkipSettings(recent.id, true, 90, 60);
        FakePlayback playback = attachPlayback(0);
        startSkip(recent.id, 0);
        set("sourceReady", false); invoke("updateSkipPlayback");
        assertEquals(0, playback.seeks);
        set("sourceReady", true);
        set("playbackNavigationToken", (Integer) get("selectionToken") - 1);
        invoke("updateSkipPlayback");
        assertEquals("A replaced navigation cannot seek", 0, playback.seeks);
        set("playbackNavigationToken", get("selectionToken"));
        invoke("updateSkipPlayback");
        assertEquals(90000, playback.position);
        assertEquals(View.VISIBLE, ((View) get("skipNotice")).getVisibility());
        assertEquals("撤销", ((TextView) get("skipNoticeAction")).getText().toString());
        clickDescription("取消本集自动跳过");
        assertEquals(0, playback.position);
        assertEquals(View.GONE, ((View) get("skipNotice")).getVisibility());
        invoke("updateSkipPlayback");
        assertEquals("Undo must not immediately skip again", 2, playback.seeks);
        assertTrue(((SkipPlaybackPolicy) get("skipPolicy")).isDisabledForSession());
        assertTrue("A temporary cancellation preserves the saved series choice", store.getSkipSettings(recent.id).enabled);
        startSkip(recent.id, 0); invoke("updateSkipPlayback");
        assertEquals("A fresh session uses the saved choice", 3, playback.seeks);
    }

    @Test public void resumeAndManualSeekNeverRestartTheIntroOrOverwriteTheSavedSkipChoice() throws Exception {
        store.setSkipSettings(recent.id, true, 90, 60);
        FakePlayback playback = attachPlayback(42000);
        startSkip(recent.id, 42000); invoke("updateSkipPlayback");
        assertEquals(0, playback.seeks);
        assertEquals(42000, playback.position);
        PlayerOverlay overlay = (PlayerOverlay) get("playerOverlay");
        Field hostField = PlayerOverlay.class.getDeclaredField("host"); hostField.setAccessible(true);
        ((PlayerOverlay.Host) hostField.get(overlay)).seekTo(10000);
        invoke("updateSkipPlayback");
        assertEquals(1, playback.seeks);
        assertEquals(10000, playback.position);
        assertTrue(((SkipPlaybackPolicy) get("skipPolicy")).isDisabledForSession());
        assertEquals(10000, store.getProgress(recent.episodes.get(0).id).positionMs);
        assertEquals(90, store.getSkipSettings(recent.id).introSeconds);
        assertTrue(store.getSkipSettings(recent.id).enabled);
    }

    @Test public void backgroundTimeNeverConsumesTheOutroCancellationWindow() throws Exception {
        store.setSkipSettings(recent.id, true, 0, 90);
        FakePlayback playback = attachPlayback(240000);
        startSkip(recent.id, playback.position); invoke("updateSkipPlayback");
        assertTrue(((TextView) get("skipNoticeText")).getText().toString().contains("5 秒"));
        controller.pause();
        assertEquals(View.GONE, ((View) get("skipNotice")).getVisibility());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS);
        assertEquals(0, playback.seeks);
        controller.resume(); idle();
        assertFalse("Returning to the app does not start paused playback", playback.playWhenReady);
        playback.playWhenReady = true;
        invoke("updateSkipPlayback");
        assertTrue(((TextView) get("skipNoticeText")).getText().toString().contains("5 秒"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS);
        assertEquals(0, playback.seeks);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        assertEquals(1, playback.seeks);
        assertEquals(300000, playback.position);
        assertTrue(store.getProgress(recent.episodes.get(0).id).completed);
        invoke("updateSkipPlayback");
        assertEquals("A completed outro must not repeatedly seek", 1, playback.seeks);
    }

    private FakePlayback attachPlayback(long position) throws Exception {
        FakePlayback result = new FakePlayback(position);
        set("player", result.player); set("currentEpisode", recent.episodes.get(0));
        set("currentSeries", recent); set("currentQuality", DownloadCatalogueTest.quality(80, "7", "30280"));
        set("currentOffline", true); set("sourceReady", true); set("seriesPage", true);
        set("playbackNavigationToken", get("selectionToken"));
        ((View) get("playerPanel")).setVisibility(View.VISIBLE);
        ((PlayerOverlay) get("playerOverlay")).setActive(true);
        return result;
    }

    private void startSkip(String seriesId, long position) throws Exception {
        invoke("startSkipSession", new Class<?>[]{String.class, long.class}, seriesId, position);
    }

    private Models.Series addSeries(String id, String title) throws Exception {
        Models.Series result = new Models.Series(); result.id = id; result.title = title;
        for (int i = 1; i <= 2; i++) {
            Models.Episode episode = new Models.Episode();
            episode.id = id + ":" + i; episode.seriesId = id; episode.seriesTitle = title;
            episode.title = "第 " + i + " 集"; episode.index = i; result.episodes.add(episode);
        }
        store.saveSeries(result);
        ((Set<String>) get("coverLookups")).add(id);
        return result;
    }

    private void saveDownload(Models.Series series, boolean withAudio) throws Exception {
        Models.Episode episode = series.episodes.get(0);
        Models.Quality quality = DownloadCatalogueTest.quality(80, "7", "30280");
        quality.videoUrl = "https://media.example.test/video.m4s";
        quality.audioUrl = "https://media.example.test/audio.m4s";
        String key = MediaRepository.key(episode, quality);
        store.saveDownload(key, episode, quality);
        putCompletedTrack(key + ":v", quality.videoUrl);
        if (withAudio) putCompletedTrack(key + ":a", quality.audioUrl);
    }

    private void putCompletedTrack(String key, String url) throws Exception {
        DownloadRequest request = new DownloadRequest.Builder(key, Uri.parse(url)).setCustomCacheKey(key).build();
        DownloadProgress progress = new DownloadProgress(); progress.bytesDownloaded = 100; progress.percentDownloaded = 100;
        Download completed = new Download(request, Download.STATE_COMPLETED, 1, 1, 100,
            0, Download.FAILURE_REASON_NONE, progress);
        ((DefaultDownloadIndex) repository.manager.getDownloadIndex()).putDownload(completed);
    }

    private static UpdateChecker.Release release(int minSdk) throws Exception {
        String root = "https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/anime-shelf-apk/downloads/";
        JSONObject document = new JSONObject().put("schemaVersion", 1).put("versionCode", 999).put("versionName", "0.9.9")
            .put("packageName", "com.animeshelf.app").put("minSdk", minSdk)
            .put("apkUrl", root + "anime-shelf-0.9.9.apk").put("zipUrl", root + "anime-shelf-0.9.9-install.zip")
            .put("apkSha256", "a".repeat(64)).put("zipSha256", "b".repeat(64))
            .put("apkBytes", 5000000).put("zipBytes", 5000100)
            .put("notes", new JSONArray().put("测试更新说明")).put("sourceCommit", "c".repeat(40));
        return UpdateChecker.parse(document.toString(), 9, 28);
    }

    private List<String> cardIds() throws Exception {
        List<String> result = new ArrayList<>(); collectCards((View) get("libraryResults"), result); return result;
    }
    private static void collectCards(View view, List<String> output) {
        String description = String.valueOf(view.getContentDescription());
        if (description.startsWith("番剧：")) output.add(description.substring(3));
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            collectCards(((ViewGroup) view).getChildAt(i), output);
    }
    private void clickDescription(String value) throws Exception {
        View target = description((View) get("root"), value);
        assertNotNull("Missing action: " + value, target); assertTrue(target.performClick());
    }
    private static View description(View view, String value) {
        if (value.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = description(((ViewGroup) view).getChildAt(i), value); if (found != null) return found;
        }
        return null;
    }
    private static String text(View view) {
        StringBuilder result = new StringBuilder();
        if (view instanceof TextView) result.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            result.append(text(((ViewGroup) view).getChildAt(i)));
        return result.toString();
    }
    private static boolean showingDialogOrNull() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); return dialog != null && dialog.isShowing();
    }
    private static AlertDialog showingDialog() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(dialog); assertTrue(dialog.isShowing()); return dialog;
    }
    private static String dialogText() { return text(showingDialog().getWindow().getDecorView()); }
    private static void clickDialog(int which) { assertTrue(showingDialog().getButton(which).performClick()); idle(); }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private void drainWorker() throws Exception {
        ExecutorService worker = (ExecutorService) get("io"); worker.shutdown();
        assertTrue("Background task completed", worker.awaitTermination(5, TimeUnit.SECONDS)); idle();
    }
    private Object get(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity);
    }
    private void set(String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(activity, value);
    }
    private void invoke(String name) throws Exception { invoke(name, new Class<?>[]{}); }
    private void invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(activity, arguments);
    }

    private static final class FakePlayback {
        final ExoPlayer player;
        long position;
        int seeks;
        boolean playWhenReady = true;
        FakePlayback(long initialPosition) {
            position = initialPosition;
            player = (ExoPlayer) Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(), new Class<?>[]{ExoPlayer.class},
                (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getPlaybackState": return Player.STATE_READY;
                        case "getCurrentPosition": return position;
                        case "getDuration": return 300000L;
                        case "getApplicationLooper": return Looper.getMainLooper();
                        case "getPlaybackParameters": return new PlaybackParameters(1f);
                        case "getPlayWhenReady": case "isPlaying": return playWhenReady;
                        case "isCurrentMediaItemSeekable": return true;
                        case "pause": playWhenReady = false; return null;
                        case "play": playWhenReady = true; return null;
                        case "seekTo": position = ((Number) arguments[arguments.length - 1]).longValue(); seeks++; return null;
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == arguments[0];
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == float.class) return 0f;
                    return null;
                });
        }
    }
}
