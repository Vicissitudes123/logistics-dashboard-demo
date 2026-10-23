package com.animeshelf.app;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.TextView;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

/** UI regressions for escaping a failed player without losing viewing history. */
@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainNavigationTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private Store store;
    private Models.Series series;
    private Models.Episode episode;
    private FakePlayback playback;

    @Before public void setUp() throws Exception {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        store = new Store(activity);
        series = new Models.Series();
        series.id = "navigation-series";
        series.title = "导航测试番剧";
        episode = new Models.Episode();
        episode.id = "navigation-episode";
        episode.seriesId = series.id;
        episode.seriesTitle = series.title;
        episode.title = "第一集";
        episode.index = 1;
        series.episodes.add(episode);
        store.saveSeries(series);
    }

    @After public void tearDown() throws Exception {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        if (controller != null) controller.pause().stop().destroy();
        Field singleton = MediaRepository.class.getDeclaredField("instance"); singleton.setAccessible(true);
        MediaRepository active = (MediaRepository) singleton.get(null);
        if (active != null) active.releaseForTests();
    }

    @Test public void homeButtonClosesFailedPlayerAndSavesItsLastPosition() throws Exception {
        attachPlayback(Player.STATE_IDLE, 73500, true);
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        click("首页");

        assertHome();
        assertTrue(playback.released);
        assertNull(get("player"));
        assertEquals(73500, store.getProgress(episode.id).positionMs);
        assertEquals(episode.id, store.getLastEpisode(series.id));
        assertEquals(0, activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Test public void fullScreenHasHomeAndItRestoresTheCompleteHomeLayout() throws Exception {
        attachPlayback(Player.STATE_READY, 46000, true);
        invoke("setFullScreen", new Class<?>[]{boolean.class}, true);
        View home = findDescription(activity.getWindow().getDecorView(), "回到首页");
        assertNotNull(home);
        assertTrue(home.isShown());
        assertEquals(View.GONE, ((View) get("header")).getVisibility());

        assertTrue(home.performClick());

        assertHome();
        assertFalse((Boolean) get("fullScreen"));
        assertEquals(View.VISIBLE, ((View) get("nav")).getVisibility());
        assertEquals(View.VISIBLE, ((View) get("body")).getVisibility());
        assertEquals(View.VISIBLE, ((View) get("loginButton")).getVisibility());
        assertEquals(46000, store.getProgress(episode.id).positionMs);
        assertTrue(playback.released);
    }

    @Test public void landscapeFullscreenGivesTheEntireAvailableAreaToVideo() throws Exception {
        attachPlayback(Player.STATE_READY, 46000, true);
        invoke("setFullScreen", new Class<?>[]{boolean.class}, true);
        View root = (View) get("root");
        float density = activity.getResources().getDisplayMetrics().density;
        int width = Math.round(640 * density), height = Math.round(360 * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);

        assertEquals(View.GONE, ((View) get("header")).getVisibility());
        assertEquals(View.GONE, ((View) get("nav")).getVisibility());
        assertEquals(View.GONE, ((View) get("body")).getVisibility());
        View frame = (View) get("videoFrame");
        assertEquals(width - root.getPaddingLeft() - root.getPaddingRight(), frame.getWidth());
        assertEquals(height - root.getPaddingTop() - root.getPaddingBottom(), frame.getHeight());
    }

    @Test public void systemBackLeavesFullscreenThenReturnsHomeThenExits() throws Exception {
        attachPlayback(Player.STATE_READY, 32000, true);
        invoke("setFullScreen", new Class<?>[]{boolean.class}, true);

        activity.onBackPressed();
        assertFalse((Boolean) get("fullScreen"));
        assertSame(playback.player, get("player"));
        assertFalse(playback.released);
        assertFalse(activity.isFinishing());

        activity.onBackPressed();
        assertHome();
        assertTrue(playback.released);
        assertFalse(activity.isFinishing());
        assertEquals(32000, store.getProgress(episode.id).positionMs);

        activity.onBackPressed();
        assertTrue(activity.isFinishing());
    }

    @Test public void downloadsPageClosesPlayerAndBackReturnsToHome() throws Exception {
        attachPlayback(Player.STATE_READY, 184000, true);

        click("离线下载");

        assertTrue((Boolean) get("downloadsPage"));
        assertEquals(View.GONE, ((View) get("playerPanel")).getVisibility());
        assertNull(get("player"));
        assertTrue(playback.released);
        assertTrue(findButton("首页").isShown());
        assertEquals(184000, store.getProgress(episode.id).positionMs);
        activity.onBackPressed();
        assertHome();
    }

    @Test public void openingAnotherSeriesClosesPreviousPlayerWithoutChangingItsProgress() throws Exception {
        attachPlayback(Player.STATE_READY, 51000, true);
        Models.Series other = new Models.Series();
        other.id = "other-series";
        other.title = "另一部番剧";

        invoke("renderSeries", new Class<?>[]{Models.Series.class}, other);

        assertEquals(View.GONE, ((View) get("playerPanel")).getVisibility());
        assertNull(get("player"));
        assertTrue(playback.released);
        assertTrue((Boolean) get("seriesPage"));
        assertEquals(51000, store.getProgress(episode.id).positionMs);
        activity.onBackPressed();
        assertHome();
    }

    @Test public void errorDialogCanReturnHomeWithoutErasingUnloadedResumePosition() throws Exception {
        store.saveProgress(episode, 98000, 300000);
        attachPlayback(Player.STATE_IDLE, 0, false);
        set("requestedResumeMs", 98000L);

        invoke("showPlaybackFailure", new Class<?>[]{Throwable.class}, new IOException("test failure"));
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals("回到首页", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        // AlertDialog dispatches button listeners through its Handler. With a
        // paused main looper, drain that message before observing navigation.
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertHome();
        assertTrue(playback.released);
        assertEquals(98000, store.getProgress(episode.id).positionMs);
    }

    @Test public void downloadListRefreshDoesNotCancelAnInFlightSelection() throws Exception {
        click("离线下载");
        int before = (Integer) get("selectionToken");

        invoke("renderDownloads", new Class<?>[]{});

        assertEquals(before, ((Integer) get("selectionToken")).intValue());
        assertTrue((Boolean) get("downloadsPage"));
        assertTrue(findButton("首页").isShown());
    }

    @Test public void returningHomeDiscardsALatePlaybackSelectionResult() throws Exception {
        attachPlayback(Player.STATE_READY, 52000, true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        AtomicBoolean selectionApplied = new AtomicBoolean();
        Class<?> receiverType = Class.forName("com.animeshelf.app.MainActivity$Receiver");
        Object receiver = Proxy.newProxyInstance(receiverType.getClassLoader(),
            new Class<?>[]{receiverType}, (proxy, method, arguments) -> {
                if (method.getName().equals("accept")) selectionApplied.set(true);
                return null;
            });
        Callable<String> pendingSelection = () -> {
            started.countDown();
            if (!complete.await(5, TimeUnit.SECONDS)) throw new IOException("selection test timed out");
            return "ready to open another episode";
        };
        invoke("runTask", new Class<?>[]{String.class, int.class, Callable.class, receiverType},
            "读取下一集…", (Integer) get("selectionToken"), pendingSelection, receiver);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        click("首页");
        complete.countDown();
        // Drain the real worker before the paused UI looper, avoiding timing-based sleeps.
        ExecutorService worker = (ExecutorService) get("io");
        worker.shutdown();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertHome();
        assertFalse(selectionApplied.get());
        assertNull(get("player"));
        assertTrue(playback.released);
        assertEquals(52000, store.getProgress(episode.id).positionMs);
        assertEquals(View.GONE, ((View) get("status")).getVisibility());
    }

    @Test public void endedEpisodeCanReplayFromTheBeginningWithTheVisiblePlayControl() throws Exception {
        attachPlayback(Player.STATE_ENDED, 300000, true);
        PlayerOverlay overlay = (PlayerOverlay) get("playerOverlay");
        overlay.refresh();
        View replay = findDescription(overlay, "播放");
        assertNotNull(replay);

        assertTrue(replay.performClick());

        assertEquals(1, playback.seekCalls);
        assertEquals(0, playback.position);
        assertEquals(1, playback.playCalls);
        assertTrue(playback.playWhenReady);
    }

    @Test public void pausingTheActivityDiscardsAnUnreleasedSeekGesture() throws Exception {
        attachPlayback(Player.STATE_READY, 76000, true);
        invoke("setFullScreen", new Class<?>[]{boolean.class}, true);
        View root = (View) get("root");
        root.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 640, 360);
        PlayerOverlay overlay = (PlayerOverlay) get("playerOverlay");
        overlay.hideControls();
        long now = android.os.SystemClock.uptimeMillis();
        dispatch(overlay, now, MotionEvent.ACTION_DOWN, 160, 160);
        dispatch(overlay, now, MotionEvent.ACTION_MOVE, 280, 160);
        assertEquals(0, playback.seekCalls);

        controller.pause();
        controller.resume();
        dispatch(overlay, now, MotionEvent.ACTION_UP, 280, 160);

        assertEquals(0, playback.seekCalls);
        assertEquals(76000, store.getProgress(episode.id).positionMs);
        assertFalse(playback.playWhenReady);
        assertFalse(playback.released);
    }

    @Test @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    public void pausingDuringLongPressRestoresPlaybackSpeedWithoutChangingItsSavedPreference() throws Exception {
        attachPlayback(Player.STATE_READY, 76000, true);
        playback.speed = .75f;
        store.setSpeed(.75f);
        invoke("setFullScreen", new Class<?>[]{boolean.class}, true);
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
        View root = (View) get("root");
        root.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 640, 360);
        PlayerOverlay overlay = (PlayerOverlay) get("playerOverlay");
        overlay.hideControls();
        long downTime = android.os.SystemClock.uptimeMillis();
        dispatch(overlay, downTime, MotionEvent.ACTION_DOWN, 200, 160);
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(
            Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1));
        assertEquals(2f, playback.speed, .001f);
        assertEquals(.75f, store.getSpeed(), .001f);

        controller.pause();

        assertEquals(.75f, playback.speed, .001f);
        assertEquals(.75f, store.getSpeed(), .001f);
        assertFalse(playback.playWhenReady);
        assertEquals(76000, store.getProgress(episode.id).positionMs);
        controller.resume();
        dispatch(overlay, downTime, MotionEvent.ACTION_UP, 200, 160);
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(
            Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 50));
        assertEquals(.75f, playback.speed, .001f);
        assertEquals(.75f, store.getSpeed(), .001f);
        assertEquals(0, playback.playCalls);
        assertEquals(0, playback.seekCalls);
    }

    @Test public void playbackBrightnessStaysInThisWindowAndRestoresAcrossPauseResumeAndHome() throws Exception {
        attachPlayback(Player.STATE_READY, 52000, true);
        float initialBrightness = activity.getWindow().getAttributes().screenBrightness;
        int systemBrightness = android.provider.Settings.System.getInt(activity.getContentResolver(),
            android.provider.Settings.System.SCREEN_BRIGHTNESS, -1);
        store.setSpeed(1.5f);

        invoke("setPlaybackBrightness", new Class<?>[]{float.class}, -.2f);
        assertEquals(.05f, activity.getWindow().getAttributes().screenBrightness, .001f);
        invoke("setPlaybackBrightness", new Class<?>[]{float.class}, 2f);
        assertEquals(1f, activity.getWindow().getAttributes().screenBrightness, .001f);
        invoke("setPlaybackBrightness", new Class<?>[]{float.class}, .8f);
        assertEquals(.8f, activity.getWindow().getAttributes().screenBrightness, .001f);

        controller.pause();
        assertEquals(initialBrightness, activity.getWindow().getAttributes().screenBrightness, .001f);
        controller.resume();
        assertEquals(.8f, activity.getWindow().getAttributes().screenBrightness, .001f);
        click("首页");
        assertEquals(initialBrightness, activity.getWindow().getAttributes().screenBrightness, .001f);
        assertEquals(systemBrightness, android.provider.Settings.System.getInt(activity.getContentResolver(),
            android.provider.Settings.System.SCREEN_BRIGHTNESS, -1));
        assertEquals(1.5f, store.getSpeed(), .001f);
        assertTrue(playback.released);
        assertNull(get("player"));
    }

    @Test public void offlineSeriesKeepsItsGroupDuringRefreshAndBackReturnsThroughTheCatalogue() throws Exception {
        Models.Quality quality = DownloadCatalogueTest.quality(80, "7", "30280");
        store.saveDownload(MediaRepository.key(episode, quality), episode, quality);
        click("离线下载");
        MediaRepository repository = (MediaRepository) get("media");
        DownloadCatalogue.Group group = DownloadCatalogue.groups(repository.downloads()).get(0);
        invoke("openDownloadGroup", new Class<?>[]{DownloadCatalogue.Group.class}, group);
        invoke("renderDownloads", new Class<?>[]{});
        assertEquals(group.id, get("downloadGroupId"));

        activity.onBackPressed();

        assertNull(get("downloadGroupId"));
        assertTrue((Boolean) get("downloadsPage"));
        assertFalse(activity.isFinishing());
        assertNotNull(findText((View) get("content"), "离线下载"));
        activity.onBackPressed();
        assertHome();
    }

    @Test public void confirmedDeletionUsesItsVersionSnapshotAndPreservesOtherVersionsAndHistory() throws Exception {
        Models.Quality selectedQuality = DownloadCatalogueTest.quality(80, "7", "30280");
        Models.Quality otherQuality = DownloadCatalogueTest.quality(112, "7", "30280");
        Models.Episode otherEpisode = DownloadCatalogueTest.episode(series.id, series.title, "another-episode", 2);
        String selectedKey = MediaRepository.key(episode, selectedQuality);
        String otherQualityKey = MediaRepository.key(episode, otherQuality);
        String otherEpisodeKey = MediaRepository.key(otherEpisode, selectedQuality);
        store.saveDownload(selectedKey, episode, selectedQuality);
        store.saveDownload(otherQualityKey, episode, otherQuality);
        store.saveDownload(otherEpisodeKey, otherEpisode, selectedQuality);
        store.saveProgress(episode, 98000, 300000);
        click("离线下载");
        MediaRepository repository = (MediaRepository) get("media");
        MediaRepository.OfflineItem selected = null;
        for (MediaRepository.OfflineItem item : repository.downloads()) if (selectedKey.equals(item.key)) selected = item;
        assertNotNull(selected);
        int before = (Integer) get("downloadMutationGeneration");
        invoke("confirmRemoveDownloads", new Class<?>[]{java.util.List.class}, java.util.Collections.singletonList(selected));
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();

        // A new version appears after confirmation is opened; it was not selected for deletion.
        Models.Quality newlyAdded = DownloadCatalogueTest.quality(64, "7", "30280");
        String newlyAddedKey = MediaRepository.key(episode, newlyAdded);
        store.saveDownload(newlyAddedKey, episode, newlyAdded);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();

        java.util.Set<String> remaining = new java.util.HashSet<>();
        for (MediaRepository.OfflineItem item : repository.downloads()) remaining.add(item.key);
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList(otherQualityKey, otherEpisodeKey, newlyAddedKey)), remaining);
        assertEquals(98000, store.getProgress(episode.id).positionMs);
        assertEquals(episode.id, store.getLastEpisode(series.id));
        assertTrue((Integer) get("downloadMutationGeneration") > before);
    }

    @Test public void activityPauseCancelsBatchPreparationAndItsLateSuccessCannotEnqueue() throws Exception {
        Models.Quality profile = DownloadCatalogueTest.quality(80, "7", "30280");
        java.util.ArrayDeque<Runnable> scheduled = new java.util.ArrayDeque<>();
        BatchDownloadSession.Result[] pending = new BatchDownloadSession.Result[1];
        java.util.concurrent.atomic.AtomicInteger enqueued = new java.util.concurrent.atomic.AtomicInteger();
        BatchDownloadSession session = new BatchDownloadSession(java.util.Collections.singletonList(episode), profile,
            new BatchDownloadSession.Gateway() {
                @Override public MediaRepository.OfflineItem existing(Models.Episode e, Models.Quality q) { return null; }
                @Override public void resolve(Models.Episode e, BatchDownloadSession.Result callback) { pending[0] = callback; }
                @Override public void enqueue(Models.Episode e, Models.Quality q) { enqueued.incrementAndGet(); }
            }, scheduled::addLast, new BatchDownloadSession.Listener() {
                @Override public void changed(BatchDownloadSession job) { }
                @Override public void finished(BatchDownloadSession job) { }
            });
        set("batchSession", session);
        session.start();
        scheduled.removeFirst().run();
        assertNotNull(pending[0]);

        controller.pause();
        pending[0].success(java.util.Collections.singletonList(profile));
        controller.resume();

        assertTrue(session.isCancelled());
        assertNull(get("batchSession"));
        assertEquals(0, enqueued.get());
    }

    private void attachPlayback(int state, long position, boolean wasReady) throws Exception {
        playback = new FakePlayback(state, position, 300000);
        set("player", playback.player);
        set("currentEpisode", episode);
        set("currentSeries", series);
        set("sourceReady", wasReady);
        set("seriesPage", true);
        ((View) get("playerPanel")).setVisibility(View.VISIBLE);
        ((PlayerOverlay) get("playerOverlay")).setActive(true);
    }

    private void assertHome() throws Exception {
        assertFalse((Boolean) get("downloadsPage"));
        assertFalse((Boolean) get("seriesPage"));
        assertEquals(View.GONE, ((View) get("playerPanel")).getVisibility());
        assertNotNull(findText((View) get("content"), "我的番剧"));
        assertTrue(findButton("首页").isShown());
    }

    private void click(String label) { assertTrue(findButton(label).performClick()); }
    private Button findButton(String label) {
        TextView found = findText(activity.getWindow().getDecorView(), label);
        assertNotNull("Missing button: " + label, found);
        assertTrue(found instanceof Button);
        return (Button) found;
    }
    private static TextView findText(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return (TextView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
    private static View findDescription(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }
    private static void dispatch(View view, long downTime, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action, x, y, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }
    private Object get(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }
    private void set(String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(activity, value);
    }
    private void invoke(String name, Class<?>[] parameters, Object... arguments) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        method.invoke(activity, arguments);
    }

    /** Only playback state is simulated; UI navigation and persisted history are real. */
    private static final class FakePlayback {
        final ExoPlayer player;
        boolean released, playWhenReady = true;
        long position;
        int seekCalls, playCalls;
        float speed = 1f;
        FakePlayback(int state, long position, long duration) {
            this.position = position;
            player = (ExoPlayer) Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(), new Class<?>[]{ExoPlayer.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getPlaybackState": return state;
                    case "getCurrentPosition": return this.position;
                    case "getDuration": return duration;
                    case "getApplicationLooper": return Looper.getMainLooper();
                    case "getPlaybackParameters": return new PlaybackParameters(speed);
                    case "getPlayWhenReady": return playWhenReady;
                    case "isCurrentMediaItemSeekable": return true;
                    case "pause": playWhenReady = false; return null;
                    case "play": playWhenReady = true; playCalls++; return null;
                    case "seekTo": this.position = ((Number) args[args.length - 1]).longValue(); seekCalls++; return null;
                    case "setPlaybackSpeed": speed = ((Number) args[0]).floatValue(); return null;
                    case "release": released = true; return null;
                    case "equals": return proxy == args[0];
                    case "hashCode": return System.identityHashCode(proxy);
                    case "toString": return "FakePlayback";
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
