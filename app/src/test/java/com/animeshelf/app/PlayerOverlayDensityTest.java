package com.animeshelf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Check the drawable's displayed rectangle, rather than only its 48 dp touch target. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PlayerOverlayDensityTest {
    @Test @Config(qualifiers = "w360dp-h640dp-port-mdpi")
    public void iconsKeepTheirVisualSizeAtMdpi() { assertDisplayedIconSizes(1f); }

    @Test @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    public void iconsKeepTheirVisualSizeAtXhdpi() { assertDisplayedIconSizes(2f); }

    @Test @Config(qualifiers = "w360dp-h640dp-port-xxhdpi")
    public void iconsKeepTheirVisualSizeAtXxhdpi() { assertDisplayedIconSizes(3f); }

    @Test @Config(qualifiers = "w360dp-h640dp-port-xxxhdpi")
    public void iconsKeepTheirVisualSizeAtXxxhdpi() { assertDisplayedIconSizes(4f); }

    private static void assertDisplayedIconSizes(float expectedDensity) {
        Context context = RuntimeEnvironment.getApplication();
        float density = context.getResources().getDisplayMetrics().density;
        assertEquals(expectedDensity, density, .001f);
        FakeHost host = new FakeHost();
        PlayerOverlay overlay = new PlayerOverlay(context, host);
        FrameLayout parent = new FrameLayout(context);
        parent.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        try {
            measure(parent, density);
            for (String description : new String[]{"播放", "返回", "回到首页", "全屏"}) {
                assertIcon(overlay, description, density);
            }
            // Replacing drawables during playback or fullscreen must retain the density fix.
            host.playing = true;
            overlay.refresh();
            overlay.setFullScreen(true);
            measure(parent, density);
            assertIcon(overlay, "暂停", density);
            assertIcon(overlay, "退出全屏", density);
        } finally {
            overlay.dispose();
        }
    }

    private static void measure(View view, float density) {
        int width = Math.round(360 * density), height = Math.round(203 * density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static void assertIcon(View root, String description, float density) {
        View found = find(root, description);
        assertNotNull("Missing icon: " + description, found);
        assertTrue(description, found instanceof ImageView);
        ImageView button = (ImageView) found;
        assertTrue(description + " keeps only transient touch feedback", button.getBackground() instanceof RippleDrawable);
        RippleDrawable background = (RippleDrawable) button.getBackground();
        assertEquals(description + " has no persistent background content", 1, background.getNumberOfLayers());
        assertEquals(description + " has only the non-visible ripple mask", android.R.id.mask, background.getId(0));
        assertTrue(description + " touch width", button.getWidth() / density >= 48f);
        assertTrue(description + " touch height", button.getHeight() / density >= 48f);
        Drawable drawable = button.getDrawable();
        assertNotNull(drawable);
        RectF displayed = new RectF(drawable.getBounds());
        button.getImageMatrix().mapRect(displayed);
        displayed.offset(button.getPaddingLeft(), button.getPaddingTop());
        String details = description + " density=" + density + " drawable=" + drawable.getBounds()
            + " matrix=" + button.getImageMatrix() + " displayed=" + displayed
            + " button=" + button.getWidth() + "x" + button.getHeight()
            + " padding=" + button.getPaddingLeft() + "," + button.getPaddingTop()
            + "," + button.getPaddingRight() + "," + button.getPaddingBottom()
            + " overlay=" + root.getWidth() + "x" + root.getHeight();
        // A raw 24 px drawable centered inside a large button was only 8 dp at xxhdpi.
        // Mapping the real bounds through ImageView's matrix catches that regression.
        assertEquals("Displayed width in dp: " + details, 32f, displayed.width() / density, .1f);
        assertEquals("Displayed height in dp: " + details, 32f, displayed.height() / density, .1f);
        assertTrue("Icon must fit inside its touch target: " + details, displayed.left >= 0
            && displayed.top >= 0 && displayed.right <= button.getWidth() && displayed.bottom <= button.getHeight());
    }

    private static View find(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final class FakeHost implements PlayerOverlay.Host {
        boolean playing;
        @Override public boolean hasPlayer() { return true; }
        @Override public boolean isPlaying() { return playing; }
        @Override public boolean canSeek() { return true; }
        @Override public long positionMs() { return 60000; }
        @Override public long durationMs() { return 300000; }
        @Override public float speed() { return 1f; }
        @Override public void togglePlayback() { playing = !playing; }
        @Override public void seekTo(long position) { }
        @Override public void setSpeed(float speed) { }
        @Override public float brightness() { return .5f; }
        @Override public void setBrightness(float brightness) { }
        @Override public void setTemporarySpeed(float speed) { }
        @Override public void chooseSpeed() { }
        @Override public void chooseEpisode() { }
        @Override public void chooseDisplay() { }
        @Override public void back() { }
        @Override public void home() { }
        @Override public void toggleFullScreen() { }
    }
}
