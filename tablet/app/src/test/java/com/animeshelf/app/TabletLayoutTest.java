package com.animeshelf.app;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.offline.DefaultDownloadIndex;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadProgress;
import androidx.media3.exoplayer.offline.DownloadRequest;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

/** Measures the actual adaptive hierarchy; no tablet hardware or external media is simulated as verified. */
@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w1280dp-h800dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TabletLayoutTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private Store store;
    private MediaRepository repository;
    @Before public void setUp() throws Exception {
        controller = Robolectric.buildActivity(MainActivity.class).setup(); activity = controller.get(); store = new Store(activity);
        repository = (MediaRepository) field("media");
        for (int i = 0; i < 9; i++) {
            Models.Series s = new Models.Series(); s.id = "tablet-" + i; s.title = i == 0 ? "擅长捉弄的高木同学" : "平板番剧 " + i;
            Models.Episode e = new Models.Episode(); e.id = s.id + "-ep1"; e.seriesId = s.id; e.seriesTitle = s.title; e.index = 1; e.title = "第 1 集 · 放学路上";
            e.localUri = "content://fixture/" + e.id; s.episodes.add(e); store.saveSeries(s);
            if (i == 0) store.saveProgress(e, 136000, 1395000);
        }
        invoke("renderLibrary");
    }
    @After public void tearDown() {
        if (controller != null) controller.pause().stop().destroy();
        if (repository != null) repository.releaseForTests();
    }
    @Test public void windowWidthChangesNavigationWithoutClippingCardsOrLosingSearch() throws Exception {
        ((EditText) field("librarySearch")).setText("平板");
        for (int width : new int[]{360, 599, 600, 800, 839, 840, 1024, 1280, 360}) {
            View root = layout(width, 800); LinearLayout navigation = (LinearLayout) field("nav");
            assertEquals("Window " + width, width >= 600 ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL, navigation.getOrientation());
            assertSame(width >= 600 ? field("mainArea") : root, navigation.getParent());
            for (int i = 0; i < navigation.getChildCount(); i++) {
                View target = navigation.getChildAt(i); assertTrue(target.getWidth() >= 48); assertTrue(target.getHeight() >= 48);
            }
            assertEquals("平板", ((EditText) field("librarySearch")).getText().toString());
            assertNoHorizontalOverflow((View) field("libraryResults"));
        }
    }
    @Test public void expandedHomeUsesLargerContinueArtworkAndSixReadableGridColumns() throws Exception {
        View root = layout(1280, 800);
        Map<String, ImageView> covers = (Map<String, ImageView>) field("libraryCovers");
        assertEquals(168, covers.get("tablet-0").getWidth());
        // Store sorts the catalogue; row geometry must not assume insertion order or series IDs.
        ViewGroup results = (ViewGroup) field("libraryResults"), firstRow = null;
        for (int i = 0; i < results.getChildCount() && firstRow == null; i++) {
            View candidate = results.getChildAt(i);
            if (!(candidate instanceof ViewGroup)) continue;
            ViewGroup row = (ViewGroup) candidate;
            if (row.getChildCount() > 0 && String.valueOf(row.getChildAt(0).getContentDescription()).startsWith("番剧：")) firstRow = row;
        }
        assertNotNull(firstRow); assertEquals(6, firstRow.getChildCount());
        for (int i = 0; i < firstRow.getChildCount(); i++) {
            View card = firstRow.getChildAt(i);
            assertTrue(String.valueOf(card.getContentDescription()).startsWith("番剧："));
            assertTrue(card.getWidth() >= 170); assertTrue(card.getWidth() <= 230);
        }
        save(root, "tablet-home-1280.png");
    }
    @Test public void portraitAndNarrowSplitWindowKeepAnAccessibleSingleContentColumn() throws Exception {
        View root = layout(800, 1280); assertEquals(LinearLayout.VERTICAL, ((LinearLayout) field("workspace")).getOrientation());
        save(root, "tablet-home-portrait-800.png");
        root = layout(400, 800); assertSame(root, ((View) field("nav")).getParent());
        assertTrue(((View) field("content")).getWidth() <= 400); assertNoHorizontalOverflow((View) field("libraryResults"));
        save(root, "tablet-split-window-400.png");
    }
    @Test public void downloadedCatalogueUsesTwoPanesAndNarrowingKeepsTheChosenGroup() throws Exception {
        saveDownload("offline-a", "离线番剧甲"); saveDownload("offline-b", "离线番剧乙");
        layout(1280, 800); invoke("openDownloads"); View root = layout(1280, 800);
        View master = (View) field("downloadMasterScroll"), detail = (View) field("body");
        assertEquals(View.VISIBLE, master.getVisibility()); assertEquals(280, master.getWidth());
        assertTrue(detail.getLeft() >= master.getRight());
        View second = find(root, "离线目录：offline-b"); assertNotNull(second); second.performClick(); layout(1280, 800);
        assertEquals("offline-b", field("downloadGroupId")); save(root, "tablet-downloads-1280.png");
        layout(600, 800); assertEquals(View.GONE, master.getVisibility()); assertEquals("offline-b", field("downloadGroupId"));
        layout(1280, 800); activity.onBackPressed(); assertFalse((Boolean) field("downloadsPage"));
    }
    @Test public void actualActivityRecreationRestoresSearchAndDownloadSelection() throws Exception {
        ((EditText) field("librarySearch")).setText("高木"); controller.recreate(); activity = controller.get(); idle(); layout(1280, 800);
        assertEquals("高木", ((EditText) field("librarySearch")).getText().toString());
        saveDownload("offline-a", "离线番剧甲"); invoke("openDownloads"); layout(1280, 800);
        MediaRepository.OfflineItem item = repository.downloads().get(0);
        invoke("beginDownloadSelection", new Class<?>[]{java.util.List.class}, java.util.Collections.singletonList(item));
        controller.recreate(); activity = controller.get(); idle(); layout(1280, 800);
        assertTrue((Boolean) field("downloadsPage")); assertTrue((Boolean) field("downloadSelectionMode"));
        assertTrue(((Set<String>) field("selectedDownloadKeys")).contains(item.key));
        assertEquals(View.VISIBLE, ((View) field("selectionBar")).getVisibility());
    }
    private void saveDownload(String id, String title) throws Exception {
        Models.Series s = new Models.Series(); s.id = id; s.title = title;
        Models.Episode e = new Models.Episode(); e.id = id + "-ep1"; e.seriesId = id; e.seriesTitle = title; e.title = "第 1 集 · 已下载的故事"; e.index = 1; s.episodes.add(e); store.saveSeries(s);
        ((Set<String>) field("coverLookups")).add(id);
        Models.Quality q = new Models.Quality(); q.qn = 80; q.id = "80:7:30280"; q.label = "1080P · AVC"; q.videoUrl = "https://media.example/" + id + ".mp4";
        String key = MediaRepository.key(e, q); store.saveDownload(key, e, q); store.saveProgress(e, 42000, 300000);
        DownloadRequest request = new DownloadRequest.Builder(key + ":v", Uri.parse(q.videoUrl)).setCustomCacheKey(key + ":v").build();
        DownloadProgress progress = new DownloadProgress(); progress.bytesDownloaded = 100; progress.percentDownloaded = 100;
        ((DefaultDownloadIndex) repository.manager.getDownloadIndex()).putDownload(new Download(request, Download.STATE_COMPLETED, 1, 1, 100, 0, Download.FAILURE_REASON_NONE, progress));
    }
    private View layout(int width, int height) throws Exception {
        android.content.res.Resources resources = activity.getResources();
        android.content.res.Configuration previous = new android.content.res.Configuration(resources.getConfiguration());
        org.robolectric.RuntimeEnvironment.setQualifiers("w" + width + "dp-h" + height + "dp-" + (width >= height ? "land" : "port") + "-mdpi");
        android.content.res.Configuration resized = new android.content.res.Configuration(resources.getConfiguration());
        controller.configurationChange(resized, resources.getDisplayMetrics(), previous.diff(resized));
        assertSame("Handled window changes must retain the activity", activity, controller.get());
        View root = (View) field("root");
        for (int i = 0; i < 4; i++) { root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); root.layout(0, 0, width, height); idle(); }
        assertEquals("The real window must agree with the measured fixture", width, root.getWidth());
        assertEquals(height, root.getHeight());
        Map<String, ImageView> covers = (Map<String, ImageView>) field("libraryCovers"); int index = 0;
        for (ImageView image : covers.values()) { Bitmap b = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(b); canvas.drawColor(new int[]{0xFF47738C,0xFF6F527E,0xFF827145}[index++ % 3]); Paint p = new Paint(); p.setColor(0xFFB4C7DE); canvas.drawCircle(80, 80, 42, p); p.setColor(Color.WHITE); p.setTextSize(14); canvas.drawText("LOCAL FIXTURE", 24, 218, p); image.setImageBitmap(b); }
        return root;
    }
    private static void assertNoHorizontalOverflow(View view) {
        if (!(view instanceof ViewGroup)) return; ViewGroup parent = (ViewGroup) view;
        for (int i = 0; i < parent.getChildCount(); i++) { View child = parent.getChildAt(i); assertTrue(child.getLeft() >= 0); assertTrue("Overflow " + child.getContentDescription(), child.getRight() <= parent.getWidth()); assertNoHorizontalOverflow(child); }
    }
    private static View find(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { View found = find(((ViewGroup) root).getChildAt(i), description); if (found != null) return found; } return null;
    }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private Object field(String name) throws Exception { Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity); }
    private void invoke(String name) throws Exception { invoke(name, new Class<?>[]{}); }
    private void invoke(String name, Class<?>[] types, Object... args) throws Exception { Method m = MainActivity.class.getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(activity, args); }
    private static void save(View root, String filename) throws Exception {
        String directory = System.getProperty("anime.visuals.dir"); if (directory == null) return;
        File file = new File(directory, filename); file.getParentFile().mkdirs(); Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888); root.draw(new Canvas(bitmap));
        try (FileOutputStream stream = new FileOutputStream(file)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); } bitmap.recycle();
    }
}
