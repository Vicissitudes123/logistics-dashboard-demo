package com.animeshelf.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.media3.common.util.UnstableApi;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Actual downloaded bytes, including audio. Unknown lengths never become a fabricated percentage. */
@UnstableApi
final class DownloadProgressView extends LinearLayout {
    final ProgressBar bar;
    final TextView size, percentage;
    private final int accent;
    private static final int MUTED = 0xFFA0A6B8, TRACK = 0xFF30364B, COMPLETE = 0xFF8DDACB, FAILED = 0xFFFF929A;

    DownloadProgressView(Context context, int accent) {
        super(context); this.accent = accent; setOrientation(VERTICAL); setPadding(0, dp(8), 0, dp(13));
        LinearLayout line = new LinearLayout(context); line.setGravity(Gravity.CENTER_VERTICAL);
        size = new TextView(context); size.setTextSize(12); size.setTextColor(MUTED); size.setPadding(0, 0, dp(8), 0);
        line.addView(size, new LinearLayout.LayoutParams(0, -2, 1));
        percentage = new TextView(context); percentage.setTextSize(14); percentage.setTypeface(null, Typeface.BOLD);
        line.addView(percentage, new LinearLayout.LayoutParams(-2, -2)); addView(line);
        bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal); bar.setMax(1000);
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams track = new LinearLayout.LayoutParams(-1, dp(8)); track.topMargin = dp(8); addView(bar, track);
    }

    void bind(MediaRepository.OfflineItem item) { bind(Collections.singletonList(item), item.status == null ? "下载进度" : item.status); }

    void bind(List<MediaRepository.OfflineItem> items, String label) {
        long bytes = 0, total = 0; boolean known = !items.isEmpty(), complete = !items.isEmpty(), active = false, failed = false;
        for (MediaRepository.OfflineItem item : items) {
            bytes = DownloadStatus.add(bytes, Math.max(0, item.bytesDownloaded));
            // Completed older records may not have a reported length; their saved byte count is final.
            long length = item.complete ? Math.max(0, item.bytesDownloaded) : item.totalBytes;
            if (length <= 0 && !item.complete) known = false;
            if (length >= 0) total = DownloadStatus.add(total, length);
            complete &= item.complete; active |= item.downloading; failed |= item.failed;
        }
        boolean measurable = complete || (known && total > 0);
        int value = complete ? 1000 : measurable ? (int) Math.min(990, bytes * 1000d / total) : 0;
        int color = complete ? COMPLETE : active ? accent : failed ? FAILED : MUTED;
        String fraction = complete ? "100%" : measurable ? String.format(Locale.CHINA, "%.0f%%", value / 10f)
            : active ? "获取大小中" : "大小未知";
        size.setText(android.text.format.Formatter.formatShortFileSize(getContext(), bytes)
            + (measurable ? " / " + android.text.format.Formatter.formatShortFileSize(getContext(), total) : " / 总大小待获取"));
        percentage.setText(fraction); percentage.setTextColor(color);
        GradientDrawable background = shape(TRACK), foreground = shape(color);
        LayerDrawable layers = new LayerDrawable(new android.graphics.drawable.Drawable[]{background,
            new ClipDrawable(foreground, Gravity.LEFT, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background); layers.setId(1, android.R.id.progress); bar.setProgressDrawable(layers);
        bar.setIndeterminateTintList(ColorStateList.valueOf(color));
        bar.setIndeterminate(!measurable && active); bar.setProgress(value);
        // A paused/queued/failed unknown-length task is a stationary empty track, never a running animation.
        setContentDescription(label + " · " + (measurable ? fraction : "总大小暂未知") + " · " + size.getText());
    }

    private GradientDrawable shape(int color) {
        GradientDrawable result = new GradientDrawable(); result.setColor(color); result.setCornerRadius(dp(4)); return result;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
