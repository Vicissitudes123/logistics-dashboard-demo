package com.animeshelf.app;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

/** Measured native controls and visual artifacts. Frames are generated locally, without media requests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, qualifiers = "w800dp-h360dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PlayerControlsLayoutTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout parent;
    private PlayerOverlay overlay;
    private FakeHost host;

    @Before public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get(); parent = new FrameLayout(activity);
        host = new FakeHost(); host.frame = frameFixture();
        ImageView picture = new ImageView(activity); picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.setImageBitmap(host.frame); parent.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        overlay = new PlayerOverlay(activity, host); parent.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        activity.setContentView(parent);
        overlay.setTitle("擅长捉弄的高木同学 · 第 2 集", "第 2 集 · HDR 真彩 · HEVC · 在线");
        overlay.setActive(true); idle();
    }

    @After public void tearDown() {
        if (overlay != null) overlay.dispose();
        if (controller != null) controller.pause().stop().destroy();
    }

    @Test public void landscapeQuickControlsHaveDistinctReachableTargets() throws Exception {
        overlay.setFullScreen(true); layout(800, 360);
        assertControlsFit("上一集", "暂停", "下一集", "播放速度", "选集", "显示方式", "清晰度", "退出全屏",
            "返回", "回到首页", "锁定播放器", "播放进度");
        Rect previous = bounds(find("上一集")), play = bounds(find("暂停")), next = bounds(find("下一集"));
        assertTrue("Previous, play and next retain familiar ordering", previous.right <= play.left && play.right <= next.left);
        assertEquals(host.qualityLabel(), ((TextView) find("清晰度")).getText().toString());
        savePreview("player-landscape.png");
    }

    @Test @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    public void portraitPlayerKeepsAllControlsInsideItsShortViewport() throws Exception {
        overlay.setFullScreen(false); layout(360, 220);
        assertControlsFit("上一集", "暂停", "下一集", "播放速度", "选集", "显示方式", "清晰度", "全屏",
            "返回", "回到首页", "播放进度");
        Rect play = bounds(find("暂停")), speed = bounds(find("播放速度"));
        assertFalse("Compact controls wrap to separate rows", verticalOverlap(play, speed));
        assertEquals(View.GONE, find("锁定播放器").getVisibility());
        savePreview("player-portrait.png");
    }

    @Test public void lockingAndUnlockingUseTheSameLeftSidePosition() throws Exception {
        overlay.setFullScreen(true); layout(800, 360);
        Rect unlockedPosition = bounds(find("锁定播放器"));
        assertTrue("Lock remains on the left edge", unlockedPosition.left >= 0 && unlockedPosition.right <= dp(80));
        assertTrue("Lock is below the title row", unlockedPosition.top > bounds(find("返回")).bottom);
        assertTrue(find("锁定播放器").performClick()); layout(800, 360);
        assertTrue(overlay.isLocked());
        assertEquals("Changing state must not move the control", unlockedPosition, bounds(find("解锁播放器")));
        assertFalse(visibleInOverlay(find("上一集")));
        assertFalse(visibleInOverlay(find("下一集")));
        assertFalse(visibleInOverlay(find("清晰度")));
        assertControlsFit("解锁播放器");
        savePreview("player-locked.png");
        assertTrue(find("解锁播放器").performClick()); layout(800, 360);
        assertFalse(overlay.isLocked());
        assertEquals(unlockedPosition, bounds(find("锁定播放器")));
    }

    @Test public void horizontalSeekingShowsTheRequestedFrameAndOnlyCompactTimeText() throws Exception {
        overlay.setFullScreen(true); layout(800, 360); overlay.hideControls();
        long downTime = SystemClock.uptimeMillis();
        touch(downTime, MotionEvent.ACTION_DOWN, 260, 145);
        touch(downTime, MotionEvent.ACTION_MOVE, 420, 146);
        idle(); layout(800, 360);
        View preview = (View) field("seekPreview");
        ImageView image = (ImageView) field("seekPreviewImage");
        TextView time = (TextView) field("seekPreviewTime");
        assertTrue(host.previewRequests > 0);
        assertEquals("Preview extraction must not move the main player", 0, host.seeks);
        assertEquals(View.VISIBLE, preview.getVisibility());
        assertEquals(View.VISIBLE, image.getVisibility());
        assertTrue(image.getDrawable() instanceof BitmapDrawable);
        assertSame("The thumbnail displays the delivered target frame", host.frame, ((BitmapDrawable) image.getDrawable()).getBitmap());
        assertEquals(formatTime(host.lastPreviewMs) + " / " + formatTime(host.durationMs()), time.getText().toString());
        assertFalse(time.getText().toString().contains("松手"));
        assertFalse(time.getText().toString().contains("进度"));
        Rect picture = bounds(image), clock = bounds(time), hud = bounds(preview);
        assertTrue(picture.width() >= dp(160) && picture.height() >= dp(90));
        assertTrue("Time belongs below the frame", clock.top >= picture.bottom);
        assertInside("Seek preview", hud);
        assertEquals(View.GONE, ((View) field("feedback")).getVisibility());
        savePreview("player-seek-preview.png");
        touch(downTime, MotionEvent.ACTION_CANCEL, 420, 146);
    }

    private void assertControlsFit(String... descriptions) {
        List<Rect> rectangles = new ArrayList<>();
        for (String description : descriptions) {
            View control = find(description); assertNotNull("Missing control: " + description, control);
            assertTrue("Visible control: " + description, visibleInOverlay(control));
            Rect rectangle = bounds(control);
            assertTrue(description + " keeps a 48dp touch width: " + rectangle, rectangle.width() >= dp(48));
            assertTrue(description + " keeps a 48dp touch height: " + rectangle, rectangle.height() >= dp(48));
            assertInside(description, rectangle);
            for (int i = 0; i < rectangles.size(); i++) {
                assertFalse(description + " overlaps " + descriptions[i] + ": " + rectangle + " / " + rectangles.get(i),
                    Rect.intersects(rectangle, rectangles.get(i)));
            }
            rectangles.add(rectangle);
        }
    }

    private void assertInside(String description, Rect rectangle) {
        assertTrue(description + " leaves the player viewport: " + rectangle,
            rectangle.left >= 0 && rectangle.top >= 0 && rectangle.right <= overlay.getWidth() && rectangle.bottom <= overlay.getHeight());
    }

    private static boolean verticalOverlap(Rect first, Rect second) {
        return first.top < second.bottom && second.top < first.bottom;
    }

    private boolean visibleInOverlay(View view) {
        for (View current = view; current != null;) {
            if (current.getVisibility() != View.VISIBLE) return false;
            if (current == overlay) return true;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    private Rect bounds(View view) {
        Rect result = new Rect(0, 0, view.getWidth(), view.getHeight());
        overlay.offsetDescendantRectToMyCoords(view, result); return result;
    }

    private void layout(int widthDp, int heightDp) {
        for (int pass = 0; pass < 3; pass++) {
            parent.measure(View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(heightDp), View.MeasureSpec.EXACTLY));
            parent.layout(0, 0, dp(widthDp), dp(heightDp)); idle();
        }
    }

    private void touch(long downTime, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, dp(x), dp(y), 0);
        assertTrue(overlay.onTouchEvent(event)); event.recycle();
    }

    private View find(String description) { return find(overlay, description); }
    private View find(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = find(((ViewGroup) root).getChildAt(i), description); if (found != null) return found;
        }
        return null;
    }

    private Object field(String name) throws Exception {
        Field field = PlayerOverlay.class.getDeclaredField(name); field.setAccessible(true); return field.get(overlay);
    }

    private void savePreview(String name) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(parent.getWidth(), parent.getHeight(), Bitmap.Config.ARGB_8888);
        parent.draw(new Canvas(bitmap));
        File directory = new File(System.getProperty("anime.visuals.dir", "build/reports/visuals"));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
    }

    private Bitmap frameFixture() {
        Bitmap bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, 640, 360, 0xFF48768C, 0xFF223048, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 640, 360, paint); paint.setShader(null);
        paint.setColor(0x99DCE7D0); canvas.drawCircle(458, 90, 38, paint);
        paint.setColor(0xFF33555B); Path hills = new Path();
        hills.moveTo(0, 270); hills.lineTo(170, 125); hills.lineTo(330, 250); hills.lineTo(480, 165); hills.lineTo(640, 270);
        hills.lineTo(640, 360); hills.lineTo(0, 360); hills.close(); canvas.drawPath(hills, paint);
        paint.setColor(0xFF1A3038); canvas.drawRect(0, 300, 640, 360, paint);
        paint.setColor(Color.WHITE); paint.setTextSize(15); canvas.drawText("LOCAL FRAME FIXTURE", 228, 292, paint);
        return bitmap;
    }

    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private static String formatTime(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private static final class FakeHost implements PlayerOverlay.Host {
        Bitmap frame;
        int previewRequests, seeks;
        long lastPreviewMs;
        @Override public boolean hasPlayer() { return true; }
        @Override public boolean isPlaying() { return true; }
        @Override public boolean canSeek() { return true; }
        @Override public long positionMs() { return 206000; }
        @Override public long durationMs() { return 1395000; }
        @Override public float speed() { return 1f; }
        @Override public int controlsTimeoutMs() { return 0; }
        @Override public float brightness() { return .5f; }
        @Override public void setBrightness(float brightness) { }
        @Override public void setTemporarySpeed(float speed) { }
        @Override public void togglePlayback() { }
        @Override public void seekTo(long position) { seeks++; }
        @Override public void setSpeed(float speed) { }
        @Override public void chooseSpeed() { }
        @Override public void chooseEpisode() { }
        @Override public void chooseDisplay() { }
        @Override public void back() { }
        @Override public void home() { }
        @Override public void toggleFullScreen() { }
        @Override public boolean canPrevious() { return true; }
        @Override public boolean canNext() { return true; }
        @Override public String qualityLabel() { return "HDR 真彩"; }
        @Override public void requestSeekPreview(long positionMs, Consumer<Bitmap> callback) {
            previewRequests++; lastPreviewMs = positionMs; callback.accept(frame);
        }
    }
}
