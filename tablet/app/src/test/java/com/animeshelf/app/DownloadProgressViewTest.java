package com.animeshelf.app;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.util.UnstableApi;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DownloadProgressViewTest {
    private MediaRepository.OfflineItem item(long downloaded, long total, boolean complete, boolean active) {
        MediaRepository.OfflineItem result = new MediaRepository.OfflineItem();
        result.bytesDownloaded = downloaded; result.totalBytes = total; result.complete = complete; result.downloading = active;
        result.status = complete ? "已完成" : active ? "下载中" : "已暂停"; return result;
    }
    private DownloadProgressView view() { return new DownloadProgressView(RuntimeEnvironment.getApplication(), 0xFF8DB5FF); }

    @Test public void aggregateWeightsActualBytesAndWaitsForEveryUnknownTrack() {
        DownloadProgressView view = view();
        MediaRepository.OfflineItem large = item(900, 1000, false, true), small = item(10, 100, false, false);
        view.bind(Arrays.asList(large, small), "总进度");
        assertEquals(827, view.bar.getProgress()); assertEquals("83%", view.percentage.getText().toString());
        assertFalse(view.bar.isIndeterminate());
        small.totalBytes = -1; view.bind(Arrays.asList(large, small), "总进度");
        assertTrue(view.bar.isIndeterminate()); assertFalse(view.percentage.getText().toString().contains("%"));
        large.complete = true; large.downloading = false; small.complete = true;
        view.bind(Arrays.asList(large, small), "总进度");
        assertFalse(view.bar.isIndeterminate()); assertEquals(1000, view.bar.getProgress()); assertEquals("100%", view.percentage.getText().toString());
    }

    @Test public void pauseFailureAndWaitingNeverAnimateOrClaimCompletionEarly() {
        DownloadProgressView view = view(); MediaRepository.OfflineItem task = item(51, -1, false, true);
        view.bind(task); assertTrue(view.bar.isIndeterminate());
        task.downloading = false; task.paused = true; view.bind(task);
        assertFalse(view.bar.isIndeterminate()); assertEquals(0, view.bar.getProgress()); assertEquals("大小未知", view.percentage.getText().toString());
        task.totalBytes = 100; view.bind(task); assertEquals(510, view.bar.getProgress());
        task.paused = false; task.failed = true; view.bind(task); assertEquals(510, view.bar.getProgress()); assertFalse(view.bar.isIndeterminate());
        task.failed = false; task.waitingForNetwork = true; view.bind(task); assertEquals(510, view.bar.getProgress());
        task.bytesDownloaded = 100; view.bind(task); assertEquals("99%", view.percentage.getText().toString());
        task.complete = true; view.bind(task); assertEquals("100%", view.percentage.getText().toString());
    }

    @Test public void narrowPhoneRendersReadableBarsForActivePausedFailedAndComplete() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(20, 20, 20, 20); root.setBackgroundColor(0xFF0D111C);
        String[] names = {"第 1 集 · 下载中", "第 2 集 · 已暂停", "第 3 集 · 下载失败", "第 4 集 · 已离线保存", "第 5 集 · 正在获取大小"};
        for (int i = 0; i < names.length; i++) {
            LinearLayout card = new LinearLayout(context); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(16, 16, 16, 4);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(); bg.setColor(0xFF1B1D2D); bg.setCornerRadius(16); card.setBackground(bg);
            TextView name = new TextView(context); name.setText(names[i]); name.setTextSize(16); name.setTextColor(0xFFF4F5FA); card.addView(name);
            DownloadProgressView progress = view(); MediaRepository.OfflineItem task = item((long)(i + 1) * 115 * 1024 * 1024, 600L * 1024 * 1024, i == 3, i == 0 || i == 4);
            if (i == 1) task.paused = true; if (i == 2) task.failed = true; if (i == 4) task.totalBytes = -1;
            progress.bind(task); card.addView(progress);
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(-1, -2); cell.bottomMargin = 14; root.addView(card, cell);
        }
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 360, 800);
        for (int i = 0; i < root.getChildCount(); i++) {
            LinearLayout card = (LinearLayout) root.getChildAt(i); DownloadProgressView progress = (DownloadProgressView) card.getChildAt(1);
            assertTrue(progress.bar.getWidth() >= 280); assertEquals(8, progress.bar.getHeight());
            assertTrue(progress.size.getWidth() > 100); assertTrue(progress.percentage.getRight() <= progress.getWidth());
            assertTrue(progress.bar.getTop() >= progress.getChildAt(0).getBottom());
        }
        String directory = System.getProperty("anime.visuals.dir");
        if (directory != null) {
            File file = new File(directory, "download-progress-360.png"); file.getParentFile().mkdirs();
            Bitmap image = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888); root.draw(new Canvas(image));
            try (FileOutputStream output = new FileOutputStream(file)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)); }
            image.recycle();
        }
    }
}
