package com.animeshelf.app;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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

    @Test public void leftSwipeChangesSpeedAndRightSwipeChangesTheSystemMusicVolume() {
        AudioManager audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        assertTrue(max > 1);
        int initialVolume = max / 2;
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, initialVolume, 0);
        overlay.hideControls();
        down(160, 200);
        touch(MotionEvent.ACTION_MOVE, 162, 100);
        touch(MotionEvent.ACTION_UP, 162, 100);
        assertTrue(host.speed > 1f);
        assertEquals(initialVolume, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        float changedSpeed = host.speed;

        down(480, 200);
        touch(MotionEvent.ACTION_MOVE, 478, 100);
        touch(MotionEvent.ACTION_UP, 478, 100);
        assertTrue(audio.getStreamVolume(AudioManager.STREAM_MUSIC) > initialVolume);
        assertEquals(changedSpeed, host.speed, .001f);
        assertEquals(0, host.seekCalls);
    }

    @Test public void compactAndLandscapeControlsKeepSeparateFortyEightDpTargets() {
        for (int[] size : new int[][]{{360, 203}, {640, 360}}) {
            layout(size[0], size[1]);
            overlay.showControls();
            layout(size[0], size[1]);
            String[] buttons = {"返回", "回到首页", "暂停", "播放速度", "选集", "显示方式", "全屏"};
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
        float speed = 1f;
        int playbackToggles, fullscreenToggles, seekCalls, observations;
        @Override public boolean hasPlayer() { observations++; return true; }
        @Override public boolean isPlaying() { observations++; return playing; }
        @Override public boolean canSeek() { observations++; return seekable; }
        @Override public long positionMs() { observations++; return position; }
        @Override public long durationMs() { observations++; return duration; }
        @Override public float speed() { observations++; return speed; }
        @Override public void togglePlayback() { playbackToggles++; playing = !playing; }
        @Override public void seekTo(long value) { seekCalls++; position = value; }
        @Override public void setSpeed(float value) { speed = value; }
        @Override public void chooseSpeed() { }
        @Override public void chooseEpisode() { }
        @Override public void chooseDisplay() { }
        @Override public void back() { }
        @Override public void home() { }
        @Override public void toggleFullScreen() { fullscreenToggles++; }
    }
}
