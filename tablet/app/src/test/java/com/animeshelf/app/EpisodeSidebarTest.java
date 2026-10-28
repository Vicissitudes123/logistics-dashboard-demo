package com.animeshelf.app;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.media3.common.util.UnstableApi;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w800dp-h360dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class EpisodeSidebarTest {
    @Test public void localEpisodePanelIsSortedReadableAndSelectsTheExactEpisode() throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        Dialog dialog = null;
        try {
            Activity activity = controller.get(); Store store = new Store(activity);
            Models.Series series = new Models.Series(); series.id = "sidebar"; series.title = "擅长捉弄的高木同学";
            for (int i : new int[]{3, 1, 2}) {
                Models.Episode e = new Models.Episode(); e.id = "sidebar-" + i; e.seriesId = series.id; e.index = i;
                e.title = "第 " + i + " 集 · 同桌与放学路上的故事"; series.episodes.add(e);
            }
            store.saveSeries(series); store.saveProgress(series.episodes.get(2), 136000, 1395000);
            AtomicReference<Models.Episode> picked = new AtomicReference<>();
            dialog = EpisodeSidebar.show(activity, series, "sidebar-2", store, Collections.singleton("sidebar-2"), 0xFF8DB5FF, picked::set, () -> {});
            assertEquals(360, dialog.getWindow().getAttributes().width);
            View root = ((ViewGroup) dialog.findViewById(android.R.id.content)).getChildAt(0);
            root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 360, 360);
            String text = text(root); assertTrue(text.indexOf("第 1 集") < text.indexOf("第 2 集")); assertTrue(text.indexOf("第 2 集") < text.indexOf("第 3 集"));
            assertTrue(text.contains("正在观看 · 已下载 · 看到 02:16"));
            View row = find(root, "侧栏第 2 集，正在观看 · 已下载 · 看到 02:16"); assertNotNull(row); assertTrue(row.getHeight() >= 72); assertTrue(row.getWidth() >= 300);
            String directory = System.getProperty("anime.visuals.dir");
            if (directory != null) {
                File file = new File(directory, "episode-sidebar-360.png"); file.getParentFile().mkdirs();
                Bitmap image = Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888); root.draw(new Canvas(image));
                try (FileOutputStream output = new FileOutputStream(file)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)); } image.recycle();
            }
            assertTrue(row.performClick()); assertEquals("sidebar-2", picked.get().id); assertFalse(dialog.isShowing());
        } finally { if (dialog != null) dialog.dismiss(); controller.pause().stop().destroy(); }
    }
    private static View find(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { View found = find(((ViewGroup) root).getChildAt(i), description); if (found != null) return found; } return null;
    }
    private static String text(View root) {
        StringBuilder text = new StringBuilder(); if (root instanceof TextView) text.append(((TextView) root).getText()).append('\n');
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) text.append(text(((ViewGroup) root).getChildAt(i))); return text.toString();
    }
}
