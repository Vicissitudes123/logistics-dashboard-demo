package com.animeshelf.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Set;
import java.util.function.Consumer;

/** Non-pausing right-hand episode panel; its catalogue and progress are entirely local. */
@androidx.media3.common.util.UnstableApi
final class EpisodeSidebar {
    static Dialog show(Activity activity, Models.Series series, String currentId, Store store,
                       Set<String> downloaded, int accent, Consumer<Models.Episode> choose, Runnable dismissed) {
        Dialog dialog = new Dialog(activity); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(0xF5181C2A);
        int pad = dp(activity, 16); root.setPadding(pad, dp(activity, 8), pad, pad);
        LinearLayout header = new LinearLayout(activity); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(activity, "选集 · " + series.episodes.size() + " 集", 19, Color.WHITE);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = new Button(activity); close.setText("×"); close.setTextColor(Color.WHITE); close.setTextSize(24); close.setBackgroundColor(Color.TRANSPARENT);
        close.setContentDescription("关闭选集侧栏"); close.setOnClickListener(v -> dialog.dismiss()); header.addView(close, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
        root.addView(header);
        TextView name = label(activity, series.title, 13, 0xFFB0B6C7); name.setMaxLines(2); name.setPadding(0, 0, 0, dp(activity, 10)); root.addView(name);
        ScrollView scroll = new ScrollView(activity); LinearLayout list = new LinearLayout(activity); list.setOrientation(LinearLayout.VERTICAL); scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); View selected = null;
        for (Models.Episode episode : DownloadCatalogue.episodes(series.episodes)) {
            LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(pad, dp(activity, 12), pad, dp(activity, 12));
            card.setMinimumHeight(dp(activity, 72)); boolean current = episode.id.equals(currentId);
            GradientDrawable background = new GradientDrawable(); background.setColor(current ? 0xFF29354C : 0xFF212636); background.setCornerRadius(dp(activity, 12));
            if (current) background.setStroke(dp(activity, 1), accent); card.setBackground(background);
            String heading = episode.title.matches("^第\\s*" + episode.index + "\\s*集.*") ? episode.title : "第 " + episode.index + " 集 · " + episode.title;
            TextView episodeTitle = label(activity, heading, 15, current ? accent : Color.WHITE); episodeTitle.setTypeface(null, Typeface.BOLD); episodeTitle.setMaxLines(2); card.addView(episodeTitle);
            Store.Progress progress = store.getProgress(episode.id);
            String detail = current ? "正在观看 · " : "";
            detail += downloaded.contains(episode.id) ? "已下载" : episode.localUri.isEmpty() ? "在线" : "本地文件";
            detail += progress.completed ? " · 已看完" : progress.positionMs > 0 ? " · 看到 " + time(progress.positionMs) : " · 尚未观看";
            TextView subtitle = label(activity, detail, 12, 0xFFB0B6C7); subtitle.setPadding(0, dp(activity, 7), 0, 0); card.addView(subtitle);
            card.setContentDescription("侧栏第 " + episode.index + " 集，" + detail); card.setOnClickListener(v -> { dialog.dismiss(); choose.accept(episode); });
            LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, -2); row.bottomMargin = dp(activity, 8); list.addView(card, row);
            if (current) selected = card;
        }
        dialog.setContentView(root); dialog.setCanceledOnTouchOutside(true); dialog.setOnDismissListener(d -> dismissed.run()); dialog.show();
        Window window = dialog.getWindow(); window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); window.setGravity(Gravity.RIGHT | Gravity.TOP);
        int width = activity.getWindow().getDecorView().getWidth(); if (width <= 0) width = activity.getResources().getDisplayMetrics().widthPixels;
        window.setLayout(Math.min(dp(activity, 360), Math.round(width * .48f)), -1); window.setDimAmount(.18f);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        window.getDecorView().setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        View target = selected; if (target != null) scroll.post(() -> scroll.scrollTo(0, Math.max(0, target.getTop() - dp(activity, 20))));
        return dialog;
    }
    private static TextView label(Activity activity, String text, int size, int color) { TextView view = new TextView(activity); view.setText(text); view.setTextSize(size); view.setTextColor(color); return view; }
    private static String time(long milliseconds) { long seconds = milliseconds / 1000; return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60); }
    private static int dp(Activity activity, int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
