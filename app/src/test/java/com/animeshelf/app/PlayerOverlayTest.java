package com.animeshelf.app;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.media.AudioManager;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.time.Duration;
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

/** Real touch dispatch must distinguish picture gestures from interactive controls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, qualifiers = "w640dp-h360dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class PlayerOverlayTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout parent;
    private PlayerOverlay overlay;
    private FakeHost host;
    private long downTime;

    @Before public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        parent = new FrameLayout(activity);
        host = new FakeHost();
        overlay = new PlayerOverlay(activity, host);
        parent.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        activity.setContentView(parent);
        overlay.setActive(true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        layout(640, 360);
    }

    @After public void tearDown() {
        if (overlay != null) overlay.dispose();
        if (controller != null) controller.pause().stop().destroy();
    }

    @Test public void singlePictureTapHidesAndRestoresControlsWithoutPausingPlayback() {
        assertTrue(overlay.areControlsVisible());
        tap(200, 160);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertFalse(overlay.areControlsVisible());
        assertEquals(0, host.playbackToggles);

        tap(200, 160);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertTrue(overlay.areControlsVisible());
        assertEquals(0, host.playbackToggles);
    }

    @Test public void controlsAutoHideDuringPlaybackButPausedRefreshRespectsManualHiding() {
        assertTrue(overlay.areControlsVisible());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3600));
        assertFalse(overlay.areControlsVisible());

        host.playing = false;
        overlay.refresh();
        assertTrue(overlay.areControlsVisible());
        tap(200, 160);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertFalse(overlay.areControlsVisible());
        // Multiple 500 ms updates must not undo the user's explicit hide action.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        assertFalse(overlay.areControlsVisible());
        assertEquals(0, host.playbackToggles);
    }

    @Test public void doubleTapPausesExactlyOnceWithoutAlsoHidingTheControls() {
        tap(200, 160);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60));
        tap(200, 160);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));

        assertEquals(1, host.playbackToggles);
        assertFalse(host.playing);
        assertTrue(overlay.areControlsVisible());
        assertEquals(0, host.seekCalls);
    }

    @Test public void fullscreenButtonReceivesTouchWithoutTriggeringAPictureGesture() {
        Rect bounds = bounds(find("全屏"));
        tap(bounds.centerX(), bounds.centerY());
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, host.fullscreenToggles);
        assertEquals(0, host.playbackToggles);
        assertEquals(0, host.seekCalls);
        assertTrue(overlay.areControlsVisible());
    }

    @Test public void horizontalDragSeeksOnlyAfterReleaseAndNeverWhenUnavailable() {
        overlay.hideControls();
        down(160, 160);
        touch(MotionEvent.ACTION_MOVE, 240, 162);
        touch(MotionEvent.ACTION_MOVE, 320, 164);
        assertEquals(0, host.seekCalls);
        touch(MotionEvent.ACTION_UP, 320, 164);
        assertEquals(1, host.seekCalls);
        assertTrue(host.position > 60000 && host.position <= host.duration);

        host.seekable = false;
        overlay.refresh();
        assertFalse(find("播放进度").isEnabled());
        overlay.hideControls();
        down(160, 160);
        touch(MotionEvent.ACTION_MOVE, 300, 160);
        touch(MotionEvent.ACTION_UP, 300, 160);
        assertEquals(1, host.seekCalls);
    }

    @Test public void cancelAndSecondFingerDiscardUncommittedSeeking() {
        overlay.hideControls();
        down(160, 160);
        touch(MotionEvent.ACTION_MOVE, 260, 160);
        touch(MotionEvent.ACTION_CANCEL, 260, 160);
        assertEquals(0, host.seekCalls);

        down(160, 160);
        touch(MotionEvent.ACTION_MOVE, 260, 160);
        secondFinger(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
        secondFinger(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
        touch(MotionEvent.ACTION_UP, 260, 160);
        assertEquals(0, host.seekCalls);

        down(160, 160);
        touch(MotionEvent.ACTION_MOVE, 240, 160);
        touch(MotionEvent.ACTION_UP, 240, 160);
        assertEquals(1, host.seekCalls);
    }

    @Test public void leftSwipeChangesBrightnessAndRightSwipeChangesVolumeWithoutChangingSpeed() {
        AudioManager audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        assertTrue(max > 1);
        int initialVolume = max / 2;
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, initialVolume, 0);
        overlay.hideControls();
        down(160, 200);
        touch(MotionEvent.ACTION_MOVE, 162, 100);
        touch(MotionEvent.ACTION_UP, 162, 100);
        assertTrue(host.brightness > .5f);
        assertEquals(1f, host.speed, .001f);
        assertEquals(initialVolume, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        float changedBrightness = host.brightness;

        down(480, 200);
        touch(MotionEvent.ACTION_MOVE, 478, 100);
        touch(MotionEvent.ACTION_UP, 478, 100);
        assertTrue(audio.getStreamVolume(AudioManager.STREAM_MUSIC) > initialVolume);
        assertEquals(changedBrightness, host.brightness, .001f);
        assertEquals(1f, host.speed, .001f);
        assertEquals(0, host.persistedSpeedChanges);
        assertEquals(0, host.temporarySpeedChanges);
        assertEquals(0, host.seekCalls);
    }

    @Test public void longPressStartsAtTheThresholdAndRestoresEachOriginalSpeedOnRelease() {
        for (float original : new float[]{.75f, 1.5f}) {
            host.speed = original;
            overlay.hideControls();
            int changes = host.temporarySpeedChanges;
            down(200, 160);
            idle(ViewConfiguration.getLongPressTimeout() - 1);
            assertEquals("No boost before the long-press threshold", original, host.speed, .001f);
            assertEquals(changes, host.temporarySpeedChanges);

            idle(2);
            assertEquals(2f, host.speed, .001f);
            assertEquals(changes + 1, host.temporarySpeedChanges);
            boolean visibleDuringHold = overlay.areControlsVisible();
            touch(MotionEvent.ACTION_UP, 200, 160);
            assertEquals(original, host.speed, .001f);
            assertEquals(changes + 2, host.temporarySpeedChanges);
            idle(ViewConfiguration.getDoubleTapTimeout() + 50);
            assertEquals("Releasing a long press must not also toggle the controls",
                visibleDuringHold, overlay.areControlsVisible());
            assertEquals(0, host.persistedSpeedChanges);
            assertEquals(0, host.playbackToggles);
            assertEquals(0, host.seekCalls);
        }
    }

    @Test public void configuredHoldSpeedIsTemporaryAndNeverSlowsFasterPlayback() {
        for (float[] scenario : new float[][]{{.75f, 1.5f, 1.5f}, {1.5f, 3f, 3f}, {3f, 1.5f, 3f}}) {
            host.speed = scenario[0];
            host.holdSpeed = scenario[1];
            overlay.hideControls();
            down(200, 160);
            idle(ViewConfiguration.getLongPressTimeout() + 1);
            assertEquals("Configured hold speed or the faster original speed", scenario[2], host.speed, .001f);
            touch(MotionEvent.ACTION_UP, 200, 160);
            idle(ViewConfiguration.getDoubleTapTimeout() + 50);
            assertEquals("Release restores the precise original speed", scenario[0], host.speed, .001f);
            assertEquals("Hold configuration itself is unchanged", scenario[1], host.holdSpeed, .001f);
            assertEquals(0, host.persistedSpeedChanges);
            assertEquals(0, host.playbackToggles);
            assertEquals(0, host.seekCalls);
        }
    }

    @Test public void invalidHoldSpeedsFallBackWithoutPersistingOrStrandingTheTemporaryRate() {
        for (float configured : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1f, 1f, 4f}) {
            host.speed = .75f;
            host.holdSpeed = configured;
            overlay.hideControls();
            down(200, 160);
            idle(ViewConfiguration.getLongPressTimeout() + 1);
            assertEquals("Invalid hold setting " + configured, 2f, host.speed, .001f);
            touch(MotionEvent.ACTION_CANCEL, 200, 160);
            idle(ViewConfiguration.getDoubleTapTimeout() + 50);
            assertEquals(.75f, host.speed, .001f);
            assertEquals(0, host.persistedSpeedChanges);
        }
    }

    @Test public void disablingAutoHideCancelsTheOldTimerAndFiveSecondsIsAppliedExactly() {
        host.controlsTimeoutMs = 0;
        overlay.showControls();
        idle(10000);
        assertTrue("A zero timeout keeps controls visible even after the old 3.5 s timer", overlay.areControlsVisible());

        host.controlsTimeoutMs = 5000;
        overlay.showControls();
        idle(4999);
        assertTrue("The selected five-second timeout has not elapsed", overlay.areControlsVisible());
        idle(2);
        assertFalse(overlay.areControlsVisible());

        host.controlsTimeoutMs = -1;
        overlay.showControls();
        idle(3499);
        assertTrue("An invalid timeout uses the default 3.5 seconds", overlay.areControlsVisible());
        idle(2);
        assertFalse(overlay.areControlsVisible());
    }

    @Test public void everyInterruptedLongPressRestoresSpeedAndDiscardsTheTouchStream() {
        for (String interruption : new String[]{"cancel", "second finger", "drag", "pause", "navigation", "dispose"}) {
            overlay.setActive(true);
            overlay.hideControls();
            host.speed = .75f;
            down(200, 160);
            idle(ViewConfiguration.getLongPressTimeout() + 1);
            assertEquals(interruption + " starts with boost", 2f, host.speed, .001f);
            switch (interruption) {
                case "cancel": touch(MotionEvent.ACTION_CANCEL, 200, 160); break;
                case "second finger": secondFinger(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT)); break;
                case "drag": touch(MotionEvent.ACTION_MOVE, 300, 160); break;
                case "pause": overlay.setActive(false); break;
                case "navigation": overlay.cancelInteraction(); break;
                case "dispose": overlay.dispose(); break;
                default: fail(interruption);
            }
            assertEquals(interruption + " restores the original speed", .75f, host.speed, .001f);
            int changesAfterCancellation = host.temporarySpeedChanges;
            touch(MotionEvent.ACTION_UP, 300, 160);
            idle(ViewConfiguration.getLongPressTimeout() + 50);
            assertEquals(interruption + " has no late speed change", changesAfterCancellation, host.temporarySpeedChanges);
            assertEquals(0, host.persistedSpeedChanges);
            assertEquals(0, host.seekCalls);
            assertEquals(0, host.playbackToggles);
            assertEquals(.5f, host.brightness, .001f);
        }
    }

    @Test public void draggingBeforeTheLongPressThresholdSeeksWithoutStartingABoost() {
        host.speed = 1.5f;
        overlay.hideControls();
        down(200, 160);
        idle(ViewConfiguration.getLongPressTimeout() / 2);
        touch(MotionEvent.ACTION_MOVE, 300, 160);
        idle(ViewConfiguration.getLongPressTimeout() + 1);
        assertEquals(1.5f, host.speed, .001f);
        assertEquals(0, host.temporarySpeedChanges);
        assertEquals(0, host.seekCalls);
        touch(MotionEvent.ACTION_UP, 300, 160);
        assertEquals(1, host.seekCalls);
        assertEquals(0, host.persistedSpeedChanges);
        assertEquals(0, host.playbackToggles);
    }

    @Test public void doubleTapThatResumesPlaybackCannotTurnIntoALongPressBoost() {
        host.playing = false;
        host.speed = 1.5f;
        overlay.refresh();
        tap(200, 160);
        idle(60);
        down(200, 160);
        assertEquals(1, host.playbackToggles);
        assertTrue(host.playing);
        idle(ViewConfiguration.getLongPressTimeout() + 1);
        assertEquals(1.5f, host.speed, .001f);
        assertEquals(0, host.temporarySpeedChanges);
        touch(MotionEvent.ACTION_UP, 200, 160);
        idle(ViewConfiguration.getDoubleTapTimeout() + 50);
        assertEquals(1, host.playbackToggles);
        assertEquals(0, host.persistedSpeedChanges);
    }

    @Test public void pausedPlaybackAndCancelledPendingTouchesNeverStartALateBoost() {
        host.playing = false;
        overlay.refresh();
        overlay.hideControls();
        down(200, 160);
        idle(ViewConfiguration.getLongPressTimeout() + 1);
        touch(MotionEvent.ACTION_UP, 200, 160);
        assertEquals(0, host.temporarySpeedChanges);
        idle(ViewConfiguration.getDoubleTapTimeout() + 50);

        host.playing = true;
        down(200, 160);
        idle(ViewConfiguration.getLongPressTimeout() / 2);
        overlay.setActive(false);
        idle(ViewConfiguration.getLongPressTimeout() + 1);
        assertEquals(0, host.temporarySpeedChanges);
        assertEquals(0, host.persistedSpeedChanges);
        assertEquals(0, host.playbackToggles);
    }

    @Test public void compactAndLandscapeControlsKeepSeparateFortyEightDpTargets() {
        for (int[] size : new int[][]{{360, 203}, {640, 360}}) {
            layout(size[0], size[1]);
            overlay.showControls();
            layout(size[0], size[1]);
            String[] buttons = size[0] < 500
                ? new String[]{"返回", "回到首页", "暂停", "上一集", "下一集", "清晰度", "更多播放设置", "全屏"}
                : new String[]{"返回", "回到首页", "暂停", "播放速度", "选集", "显示方式", "全屏"};
            for (String description : buttons) {
                View button = find(description);
                Rect rectangle = bounds(button);
                String details = layoutDetails(button, size);
                assertTrue("Touch width must be at least 48 px: " + details, rectangle.width() >= 48);
                assertTrue("Touch height must be at least 48 px: " + details, rectangle.height() >= 48);
                assertTrue("Control must fit inside the requested viewport: " + details, rectangle.left >= 0 && rectangle.top >= 0
                    && rectangle.right <= size[0] && rectangle.bottom <= size[1]);
            }
            for (int i = 0; i < buttons.length; i++) {
                for (int j = i + 1; j < buttons.length; j++) {
                    assertFalse(layoutDetails(find(buttons[i]), size) + " overlaps "
                            + layoutDetails(find(buttons[j]), size),
                        Rect.intersects(bounds(find(buttons[i])), bounds(find(buttons[j]))));
                }
            }
            Rect seek = bounds(find("播放进度")), full = bounds(find("全屏"));
            assertTrue("Seek target: " + layoutDetails(find("播放进度"), size), seek.width() >= 48 && seek.height() >= 48);
            assertFalse("Seek " + seek + " overlaps fullscreen " + full, Rect.intersects(seek, full));
            assertTrue("Fullscreen must be at bottom right: " + layoutDetails(find("全屏"), size),
                full.centerX() > size[0] / 2 && full.centerY() > size[1] / 2);
        }
    }

    @Test public void cancelInteractionAndDisposalCannotCommitAStaleGesture() {
        overlay.hideControls();
        down(160, 160);
        touch(MotionEvent.ACTION_MOVE, 260, 160);
        overlay.cancelInteraction();
        touch(MotionEvent.ACTION_UP, 260, 160);
        assertEquals(0, host.seekCalls);

        down(160, 160);
        touch(MotionEvent.ACTION_MOVE, 260, 160);
        overlay.dispose();
        touch(MotionEvent.ACTION_UP, 260, 160);
        int observations = host.observations;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        assertEquals(0, host.seekCalls);
        assertEquals(observations, host.observations);
    }

    @Test public void fullscreenLockLeavesAPersistentTouchableUnlockAndRestoresControls() {
        assertFalse(find("锁定播放器").isShown());
        assertFalse(find("解锁播放器").isShown());
        lockFullscreen();
        View unlock = find("解锁播放器");
        assertTrue(unlock.isShown());
        assertTrue(bounds(unlock).width() >= 48 && bounds(unlock).height() >= 48);
        assertFalse(overlay.areControlsVisible());
        assertFalse(find("返回").isShown());
        assertFalse(find("播放进度").isShown());

        idle(20000);
        host.playing = false;
        overlay.refresh();
        overlay.showControls();
        assertTrue("Neither timeout nor paused refresh hides the only way to unlock", unlock.isShown());
        assertTrue(overlay.isLocked());
        assertFalse(overlay.areControlsVisible());

        tapControl("解锁播放器");
        assertFalse(overlay.isLocked());
        assertFalse(unlock.isShown());
        assertTrue(overlay.areControlsVisible());
        assertTrue(find("播放进度").isEnabled());
        assertTrue(find("播放").isEnabled());
        tapControl("播放");
        assertEquals(1, host.playbackToggles);
        assertTrue(host.playing);

        overlay.hideControls();
        down(160, 160);
        touch(MotionEvent.ACTION_MOVE, 300, 160);
        touch(MotionEvent.ACTION_UP, 300, 160);
        assertEquals("Unlock restores ordinary picture gestures", 1, host.seekCalls);
    }

    @Test public void lockedTouchStreamsCannotSeekAdjustVolumeBrightnessOrActivateHiddenControls() {
        overlay.setFullScreen(true);
        layout(640, 360);
        String[] normalButtons = {"返回", "回到首页", "暂停", "播放速度", "选集", "显示方式", "退出全屏"};
        Rect[] targets = new Rect[normalButtons.length];
        for (int i = 0; i < normalButtons.length; i++) targets[i] = bounds(find(normalButtons[i]));
        Rect timeline = bounds(find("播放进度"));
        lockFullscreen();
        AudioManager audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        int initialVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2;
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, initialVolume, 0);
        int[] actions = host.actions();

        int observations = host.observations;
        down(160, 160);
        touch(MotionEvent.ACTION_MOVE, 320, 164);
        touch(MotionEvent.ACTION_UP, 320, 164);
        assertEquals("A locked touch stream does not consult the playback host", observations, host.observations);

        down(160, 200);
        touch(MotionEvent.ACTION_MOVE, 162, 100);
        touch(MotionEvent.ACTION_UP, 162, 100);
        down(480, 200);
        touch(MotionEvent.ACTION_MOVE, 478, 100);
        touch(MotionEvent.ACTION_UP, 478, 100);
        tap(200, 160);
        idle(60);
        tap(200, 160);
        idle(ViewConfiguration.getDoubleTapTimeout() + 50);
        down(200, 160);
        idle(ViewConfiguration.getLongPressTimeout() + 1);
        touch(MotionEvent.ACTION_UP, 200, 160);
        for (Rect target : targets) tap(target.centerX(), target.centerY());
        down(timeline.left + 20, timeline.centerY());
        touch(MotionEvent.ACTION_MOVE, timeline.right - 20, timeline.centerY());
        touch(MotionEvent.ACTION_UP, timeline.right - 20, timeline.centerY());
        // A queued accessibility click must obey the same lock as real touch dispatch.
        for (String description : normalButtons) find(description).performClick();
        overlay.performClick();
        idle(ViewConfiguration.getLongPressTimeout() + 50);

        assertArrayEquals("All host actions remain untouched while locked", actions, host.actions());
        assertEquals(initialVolume, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        assertEquals(.5f, host.brightness, .001f);
        assertEquals(1f, host.speed, .001f);
        assertEquals(60000, host.position);
        assertTrue(overlay.isLocked());
        assertFalse(overlay.areControlsVisible());
        assertTrue(find("解锁播放器").isShown());
    }

    @Test public void lockingDuringTemporaryBoostRestoresTheExactSpeedAndDiscardsRelease() {
        for (float original : new float[]{.75f, 1.5f}) {
            overlay.unlock();
            overlay.setFullScreen(true);
            layout(640, 360);
            host.speed = original;
            down(200, 160);
            idle(ViewConfiguration.getLongPressTimeout() + 1);
            assertEquals(2f, host.speed, .001f);
            int changes = host.temporarySpeedChanges;
            // Simulate locking while the finger is still down and the bars are hidden.
            find("锁定播放器").performClick();
            assertTrue(overlay.isLocked());
            assertEquals(original, host.speed, .001f);
            assertEquals(changes + 1, host.temporarySpeedChanges);
            touch(MotionEvent.ACTION_UP, 200, 160);
            idle(ViewConfiguration.getLongPressTimeout() + 50);
            assertEquals(changes + 1, host.temporarySpeedChanges);
            assertEquals(0, host.persistedSpeedChanges);
            assertEquals(0, host.playbackToggles);
            assertEquals(0, host.seekCalls);
        }
    }

    @Test public void lockingCancelsPendingLongPressPictureSeekAndTimelineScrub() {
        for (String gesture : new String[]{"pending hold", "picture seek", "timeline scrub"}) {
            overlay.unlock();
            overlay.setFullScreen(true);
            layout(640, 360);
            float x = 200, y = 160;
            if (gesture.equals("timeline scrub")) {
                Rect timeline = bounds(find("播放进度"));
                x = timeline.right - 30; y = timeline.centerY();
                down(timeline.left + 30, y);
                touch(MotionEvent.ACTION_MOVE, x, y);
            } else {
                down(x, y);
                if (gesture.equals("picture seek")) {
                    x = 300;
                    touch(MotionEvent.ACTION_MOVE, x, y);
                }
            }
            assertEquals(gesture + " has not committed", 0, host.seekCalls);
            find("锁定播放器").performClick();
            assertTrue(overlay.isLocked());
            touch(MotionEvent.ACTION_UP, x, y);
            idle(ViewConfiguration.getLongPressTimeout() + 50);
            assertEquals(gesture + " cannot commit after locking", 0, host.seekCalls);
            assertEquals(0, host.temporarySpeedChanges);
            assertEquals(0, host.playbackToggles);
        }
    }

    @Test public void leavingFullscreenDeactivatingAndDisposingCannotStrandTheLock() {
        for (String reset : new String[]{"exit fullscreen", "inactive", "dispose"}) {
            lockFullscreen();
            if (reset.equals("exit fullscreen")) overlay.setFullScreen(false);
            else if (reset.equals("inactive")) overlay.setActive(false);
            else overlay.dispose();
            assertFalse(reset + " clears the lock", overlay.isLocked());
            assertFalse(find("解锁播放器").isShown());
            assertFalse(find("锁定播放器").isShown());
            if (!reset.equals("exit fullscreen")) {
                int observations = host.observations;
                idle(5000);
                assertEquals(reset + " stops the refresh timer", observations, host.observations);
                overlay.setActive(true);
            }
            layout(640, 360);
            assertTrue(overlay.areControlsVisible());
            int toggles = host.playbackToggles;
            tap(200, 160);
            idle(60);
            tap(200, 160);
            idle(ViewConfiguration.getDoubleTapTimeout() + 50);
            assertEquals(reset + " leaves playback controls usable", toggles + 1, host.playbackToggles);
        }
    }

    @Test public void quickEpisodeButtonsRespectBoundariesAndQualityUsesTheHostLabel() {
        host.previousAvailable = false; host.nextAvailable = true; host.quality = "1080P";
        overlay.refresh(); layout(640, 360);
        assertFalse(find("上一集").isEnabled()); assertTrue(find("下一集").isEnabled());
        assertEquals("1080P", ((TextView) find("清晰度")).getText().toString());
        tapControl("上一集"); assertEquals(0, host.previousCalls);
        tapControl("下一集"); assertEquals(1, host.nextCalls);
        tapControl("清晰度"); assertEquals(1, host.qualityChoices);
        host.previousAvailable = true; host.nextAvailable = false; overlay.refresh();
        tapControl("上一集"); assertEquals(1, host.previousCalls);
        tapControl("下一集"); assertEquals(1, host.nextCalls);
        assertEquals("Shortcuts must not also become picture taps", 0, host.playbackToggles);
        assertEquals(0, host.seekCalls);
    }

    @Test public void horizontalPreviewUsesOnlyTheNewestFrameAndReleaseRejectsLateResults() throws Exception {
        overlay.hideControls(); down(160, 160);
        touch(MotionEvent.ACTION_MOVE, 260, 160);
        Consumer<Bitmap> old = host.previewCallbacks.get(host.previewCallbacks.size() - 1);
        touch(MotionEvent.ACTION_MOVE, 320, 160);
        Consumer<Bitmap> latest = host.previewCallbacks.get(host.previewCallbacks.size() - 1);
        assertNotSame(old, latest);
        ImageView picture = (ImageView) overlayField("seekPreviewImage");
        TextView caption = (TextView) overlayField("seekPreviewTime");
        Bitmap first = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
        Bitmap second = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
        latest.accept(second); idle(0);
        assertEquals(View.VISIBLE, picture.getVisibility());
        assertSame(second, ((BitmapDrawable) picture.getDrawable()).getBitmap());
        old.accept(first); idle(0);
        assertSame("An older target cannot replace the current frame", second, ((BitmapDrawable) picture.getDrawable()).getBitmap());
        assertTrue(caption.getText().toString().contains(" / "));
        assertFalse(caption.getText().toString().contains("进度"));
        assertEquals(View.GONE, ((View) overlayField("feedback")).getVisibility());
        touch(MotionEvent.ACTION_UP, 320, 160);
        assertEquals(1, host.seekCalls); assertEquals(View.GONE, ((View) overlayField("seekPreview")).getVisibility());
        latest.accept(second); old.accept(first); idle(0);
        assertNull(picture.getDrawable()); assertTrue(host.previewCancels > 0);
        assertFalse("The loader owns cached bitmap lifetime", first.isRecycled()); assertFalse(second.isRecycled());
    }

    @Test public void previewCancellationAndBackgroundNeverReopenTheThumbnailOrCommitASeek() throws Exception {
        for (String reason : new String[]{"cancel", "background", "dispose"}) {
            overlay.setActive(true); overlay.hideControls(); down(160, 160);
            touch(MotionEvent.ACTION_MOVE, 260, 160);
            Consumer<Bitmap> pending = host.previewCallbacks.get(host.previewCallbacks.size() - 1);
            if (reason.equals("cancel")) overlay.cancelInteraction();
            else if (reason.equals("background")) overlay.setActive(false);
            else overlay.dispose();
            Bitmap frame = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
            pending.accept(frame); idle(0);
            assertEquals(reason, View.GONE, ((View) overlayField("seekPreview")).getVisibility());
            assertNull(((ImageView) overlayField("seekPreviewImage")).getDrawable());
            assertFalse(frame.isRecycled()); assertEquals(0, host.seekCalls);
            touch(MotionEvent.ACTION_UP, 260, 160); assertEquals(0, host.seekCalls);
        }
    }

    private Object overlayField(String name) throws Exception {
        Field field = PlayerOverlay.class.getDeclaredField(name); field.setAccessible(true); return field.get(overlay);
    }

    private void lockFullscreen() {
        overlay.setActive(true);
        overlay.setFullScreen(true);
        layout(640, 360);
        tapControl("锁定播放器");
        assertTrue("Fullscreen lock receives a real dispatched tap", overlay.isLocked());
        layout(640, 360);
    }
    private void tapControl(String description) {
        Rect target = bounds(find(description));
        tap(target.centerX(), target.centerY());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        layout(640, 360);
    }

    private void layout(int width, int height) {
        parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        parent.layout(0, 0, width, height);
    }
    private View find(String description) {
        View result = find(overlay, description);
        assertNotNull("Missing control: " + description, result);
        return result;
    }
    private static View find(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }
    private Rect bounds(View view) {
        Rect rect = new Rect(0, 0, view.getWidth(), view.getHeight());
        overlay.offsetDescendantRectToMyCoords(view, rect);
        return rect;
    }
    private String layoutDetails(View view, int[] viewport) {
        StringBuilder details = new StringBuilder(String.valueOf(view.getContentDescription()))
            .append(" bounds=").append(bounds(view))
            .append(" viewport=").append(viewport[0]).append('x').append(viewport[1])
            .append(" overlay=").append(overlay.getWidth()).append('x').append(overlay.getHeight());
        View current = view;
        while (current != null) {
            details.append(" <- ").append(current.getClass().getSimpleName())
                .append("[").append(current.getLeft()).append(',').append(current.getTop())
                .append(',').append(current.getRight()).append(',').append(current.getBottom())
                .append("; measured=").append(current.getMeasuredWidth()).append('x').append(current.getMeasuredHeight())
                .append("; padding=").append(current.getPaddingLeft()).append(',').append(current.getPaddingTop())
                .append(',').append(current.getPaddingRight()).append(',').append(current.getPaddingBottom()).append(']');
            if (current == overlay || !(current.getParent() instanceof View)) break;
            current = (View) current.getParent();
        }
        return details.toString();
    }
    private void tap(float x, float y) { down(x, y); touch(MotionEvent.ACTION_UP, x, y); }
    private static void idle(long milliseconds) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds));
    }
    private void down(float x, float y) { downTime = SystemClock.uptimeMillis(); touch(MotionEvent.ACTION_DOWN, x, y); }
    private void touch(int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        overlay.dispatchTouchEvent(event);
        event.recycle();
    }
    private void secondFinger(int action) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = 260 + i * 80; coords[i].y = 160;
            coords[i].pressure = 1; coords[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 2,
            properties, coords, 0, 0, 1, 1, 0, 0, 0, 0);
        overlay.dispatchTouchEvent(event);
        event.recycle();
    }

    private static final class FakeHost implements PlayerOverlay.Host {
        boolean playing = true, seekable = true;
        long position = 60000, duration = 300000;
        float speed = 1f, brightness = .5f, holdSpeed = 2f;
        int controlsTimeoutMs = 3500;
        int playbackToggles, fullscreenToggles, seekCalls, observations, persistedSpeedChanges, temporarySpeedChanges;
        int brightnessChanges, speedChoices, episodeChoices, displayChoices, backCalls, homeCalls;
        boolean previousAvailable, nextAvailable;
        String quality = "清晰度";
        int previousCalls, nextCalls, qualityChoices, previewCancels;
        final List<Consumer<Bitmap>> previewCallbacks = new ArrayList<>();
        int[] actions() {
            return new int[]{playbackToggles, fullscreenToggles, seekCalls, persistedSpeedChanges, temporarySpeedChanges,
                brightnessChanges, speedChoices, episodeChoices, displayChoices, backCalls, homeCalls};
        }
        @Override public boolean hasPlayer() { observations++; return true; }
        @Override public boolean isPlaying() { observations++; return playing; }
        @Override public boolean canSeek() { observations++; return seekable; }
        @Override public long positionMs() { observations++; return position; }
        @Override public long durationMs() { observations++; return duration; }
        @Override public float speed() { observations++; return speed; }
        @Override public float holdSpeed() { observations++; return holdSpeed; }
        @Override public int controlsTimeoutMs() { observations++; return controlsTimeoutMs; }
        @Override public void togglePlayback() { playbackToggles++; playing = !playing; }
        @Override public void seekTo(long value) { seekCalls++; position = value; }
        @Override public void setSpeed(float value) { persistedSpeedChanges++; speed = value; }
        @Override public float brightness() { observations++; return brightness; }
        @Override public void setBrightness(float value) { brightnessChanges++; brightness = value; }
        @Override public void setTemporarySpeed(float value) { temporarySpeedChanges++; speed = value; }
        @Override public void chooseSpeed() { speedChoices++; }
        @Override public void chooseEpisode() { episodeChoices++; }
        @Override public void chooseDisplay() { displayChoices++; }
        @Override public void back() { backCalls++; }
        @Override public void home() { homeCalls++; }
        @Override public void toggleFullScreen() { fullscreenToggles++; }
        @Override public boolean canPrevious() { return previousAvailable; }
        @Override public boolean canNext() { return nextAvailable; }
        @Override public void previousEpisode() { previousCalls++; }
        @Override public void nextEpisode() { nextCalls++; }
        @Override public String qualityLabel() { return quality; }
        @Override public void chooseQuality() { qualityChoices++; }
        @Override public void requestSeekPreview(long position, Consumer<Bitmap> callback) { previewCallbacks.add(callback); }
        @Override public void cancelSeekPreview() { previewCancels++; }
    }
}
