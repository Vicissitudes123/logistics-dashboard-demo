package com.animeshelf.app;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.media3.common.util.UnstableApi;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
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

/** Native render artifacts and real measured geometry for the revised library. No remote artwork. */
@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomeDesignTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private Store store;

    @Before public void setUp() throws Exception {
        controller = Robolectric.buildActivity(MainActivity.class).setup(); activity = controller.get(); store = new Store(activity);
        add("other-b", "夏日里的故事", 12);
        add("other-a", "JOJO 的奇妙冒险 不灭钻石", 39);
        add("pin-b", "跃动青春", 12); store.setPinned("pin-b", true);
        add("pin-a", "葬送的芙莉莲", 28); store.setPinned("pin-a", true);
        Models.Series recent = add("recent", "擅长捉弄的高木同学", 12);
        store.saveProgress(recent.episodes.get(1), 136000, 1395000);
        invoke("renderLibrary");
    }

    @After public void tearDown() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        Field singleton = MediaRepository.class.getDeclaredField("instance"); singleton.setAccessible(true);
        MediaRepository active = (MediaRepository) singleton.get(null);
        if (active != null) active.releaseForTests();
    }

    @Test @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    public void phoneUsesLargeContinuePosterAndTwoColumnArtwork() throws Exception {
        View root = layout(360, 800);
        assertLargePostersAndColumns(root, 2);
        assertTrue(find(root, "导航首页") instanceof Button);
        assertTrue(find(root, "导航首页").isSelected());
        assertNotNull(find(root, "清空搜索"));
        assertNotNull(find(root, "继续观看：recent"));
        assertTrue(((View) get("header")).getHeight() < dp(84));
        savePreview(root, "home-360.png");
    }

    @Test @Config(qualifiers = "w412dp-h915dp-port-mdpi")
    public void largerPhoneKeepsTwoColumnsAndUsesTheAdditionalPosterSpace() throws Exception {
        View root = layout(412, 915);
        assertLargePostersAndColumns(root, 2);
        ImageView recent = cover("recent"), grid = cover("pin-a");
        assertTrue(recent.getWidth() >= dp(118));
        assertTrue(grid.getWidth() >= dp(160));
        savePreview(root, "home-412.png");
    }

    @Test @Config(qualifiers = "w320dp-h640dp-port-mdpi")
    public void narrowPhoneUsesOneReadableColumnAndNoHorizontalOverflow() throws Exception {
        View root = layout(320, 640);
        assertLargePostersAndColumns(root, 1);
        View search = (View) get("librarySearch");
        assertTrue(search.getWidth() >= dp(160));
        View filters = (View) get("libraryFilters");
        for (int i = 0; i < ((ViewGroup) filters).getChildCount(); i++) {
            View choice = ((ViewGroup) filters).getChildAt(i);
            assertTrue(choice.getWidth() >= dp(60));
            assertTrue(choice.getRight() <= filters.getWidth());
        }
    }

    @Test @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    public void themeAndNavigationSelectionRemainConsistentAcrossPages() throws Exception {
        View root = layout(360, 800);
        store.setThemeId("purple"); invoke("applyTheme"); invoke("renderLibrary"); layout(360, 800);
        TextView home = (TextView) find(root, "导航首页");
        assertEquals(ThemePalette.get("purple").accent, home.getCurrentTextColor());
        assertTrue(find(root, "导航离线下载").performClick());
        assertTrue(find(root, "导航离线下载").isSelected()); assertFalse(home.isSelected());
        assertTrue(find(root, "导航设置").performClick());
        assertTrue(find(root, "导航设置").isSelected());
        assertTrue(find(root, "导航首页").performClick());
        assertTrue(home.isSelected()); assertEquals(136000, store.getProgress("recent:2").positionMs);
    }

    private void assertLargePostersAndColumns(View root, int columns) throws Exception {
        ImageView recent = cover("recent"), first = cover("pin-a"), second = cover("pin-b");
        assertTrue("Continue artwork is visibly larger than the old 64dp thumbnail", recent.getWidth() >= dp(104));
        assertTrue(recent.getHeight() >= dp(156));
        assertTrue(first.getWidth() >= dp(136)); assertTrue(first.getHeight() > first.getWidth());
        View firstCard = find(root, "番剧：pin-a"), secondCard = find(root, "番剧：pin-b");
        assertNotNull(firstCard); assertNotNull(secondCard);
        if (columns == 2) {
            assertSame(firstCard.getParent(), secondCard.getParent());
            assertEquals(firstCard.getWidth(), secondCard.getWidth());
            assertTrue(secondCard.getLeft() - firstCard.getRight() >= dp(12));
        } else assertNotSame(firstCard.getParent(), secondCard.getParent());
        assertNoOverflow((View) get("libraryResults"));
        assertNotNull(find(root, "管理番剧：recent"));
        assertNotNull(find(root, "管理番剧：pin-a"));
    }

    private void assertNoOverflow(View view) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) view;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            assertTrue("Child fits horizontally: " + child.getContentDescription(), child.getLeft() >= 0 && child.getRight() <= parent.getWidth());
            assertNoOverflow(child);
        }
    }

    private View layout(int widthDp, int heightDp) throws Exception {
        View root = (View) get("root");
        for (int i = 0; i < 3; i++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(heightDp), View.MeasureSpec.EXACTLY));
            root.layout(0, 0, dp(widthDp), dp(heightDp)); Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
        Map<String, ImageView> posters = (Map<String, ImageView>) get("libraryCovers");
        int index = 0; for (ImageView image : posters.values()) image.setImageBitmap(posterFixture(index++));
        return root;
    }

    private Bitmap posterFixture(int index) {
        Bitmap bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(bitmap);
        int[] light = {0xFF96D7DC, 0xFFC6B2ED, 0xFFFFC284, 0xFFB7C79C, 0xFF8EB4DB};
        int[] dark = {0xFF355E7D, 0xFF4D416F, 0xFF703B56, 0xFF36545A, 0xFF313B66};
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, 320, 480, light[index % light.length], dark[index % dark.length], Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 320, 480, paint); paint.setShader(null);
        paint.setColor(0x77FFFFFF); canvas.drawCircle(242, 76, 54, paint);
        paint.setColor(0x44303D63); canvas.drawRect(0, 310, 320, 480, paint);
        paint.setColor(0xFF1F3147); canvas.drawRoundRect(70, 178, 136, 411, 30, 30, paint);
        paint.setColor(0xFFF8DAB7); canvas.drawCircle(103, 155, 41, paint);
        paint.setColor(0xFFEAE4CE); canvas.drawRoundRect(174, 214, 244, 416, 28, 28, paint);
        paint.setColor(0xFFF8DAB7); canvas.drawCircle(208, 190, 37, paint);
        paint.setColor(Color.WHITE); paint.setTextSize(20); canvas.drawText("LOCAL ARTWORK FIXTURE", 23, 455, paint);
        return bitmap;
    }

    private void savePreview(View view, String name) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        File directory = new File(System.getProperty("anime.visuals.dir", "build/reports/visuals"));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
    }

    private Models.Series add(String id, String title, int count) {
        Models.Series series = new Models.Series(); series.id = id; series.title = title;
        for (int i = 1; i <= count; i++) {
            Models.Episode episode = new Models.Episode(); episode.id = id + ":" + i;
            episode.seriesId = id; episode.seriesTitle = title; episode.title = "第 " + i + " 集"; episode.index = i;
            episode.localUri = "content://home-design/" + id + "/" + i; series.episodes.add(episode);
        }
        store.saveSeries(series); return series;
    }

    private ImageView cover(String id) throws Exception { return ((Map<String, ImageView>) get("libraryCovers")).get(id); }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private Object get(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity);
    }
    private void invoke(String name) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(activity);
    }
    private View find(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = find(((ViewGroup) root).getChildAt(i), description); if (found != null) return found;
        }
        return null;
    }
}
