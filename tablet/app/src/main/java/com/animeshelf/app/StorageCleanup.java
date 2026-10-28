package com.animeshelf.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@androidx.media3.common.util.UnstableApi
final class StorageCleanup {
    static List<MediaRepository.OfflineItem> filter(List<MediaRepository.OfflineItem> all, Store store, int mode) {
        Map<String, Integer> counts = new HashMap<>();
        for (MediaRepository.OfflineItem item : all) if (item.complete) counts.merge(item.episode.id, 1, Integer::sum);
        List<MediaRepository.OfflineItem> result = new ArrayList<>();
        for (MediaRepository.OfflineItem item : all) {
            Store.Progress p = store.getProgress(item.episode.id);
            boolean watched = p.completed && p.durationMs > 0 && p.positionMs >= p.durationMs - 1000;
            if (mode == 0 || (mode == 1 && item.complete && watched)
                || (mode == 2 && item.complete && counts.getOrDefault(item.episode.id, 0) > 1)) result.add(item);
        }
        return result;
    }
    static long bytes(List<MediaRepository.OfflineItem> selected) {
        long bytes = 0; java.util.Set<String> counted = new java.util.HashSet<>();
        for (MediaRepository.OfflineItem item : selected) if (counted.add(item.key)) bytes = DownloadStatus.add(bytes, Math.max(0, item.bytesDownloaded));
        return bytes;
    }
    static void show(Activity activity, Store store, MediaRepository repository, Consumer<List<MediaRepository.OfflineItem>> remove) {
        LinearLayout root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density); root.setPadding(pad, pad / 2, pad, 0);
        TextView summary = new TextView(activity); summary.setTextSize(14);
        summary.setText("按番剧占用从大到小排列；删除视频后保留片库和观看进度。\n多版本筛选会展示同一集的所有版本，请自行保留需要的一份。"); root.addView(summary);
        Spinner filter = new Spinner(activity);
        filter.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, new String[]{"全部离线视频", "已看完的下载", "同一集的多个版本"})); root.addView(filter);
        ScrollView scroll = new ScrollView(activity); LinearLayout list = new LinearLayout(activity); list.setOrientation(LinearLayout.VERTICAL); scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Map<String, MediaRepository.OfflineItem> chosen = new LinkedHashMap<>();
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("存储清理").setView(root)
            .setNegativeButton("取消", null).setPositiveButton("删除所选", null).create();
        Runnable update = () -> {
            if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) == null) return;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!chosen.isEmpty());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("删除 " + chosen.size() + " 份 · 约 "
                + android.text.format.Formatter.formatShortFileSize(activity, bytes(new ArrayList<>(chosen.values()))));
        };
        Runnable render = () -> {
            chosen.clear(); list.removeAllViews();
            List<DownloadCatalogue.Group> groups = DownloadCatalogue.groups(filter(repository.downloads(), store, filter.getSelectedItemPosition()));
            groups.sort(Comparator.comparingLong((DownloadCatalogue.Group group) -> group.bytes).reversed().thenComparing(group -> group.id));
            for (DownloadCatalogue.Group group : groups) {
                TextView title = new TextView(activity); title.setTextSize(17); title.setTextColor(Color.WHITE);
                title.setPadding(0, pad, 0, pad / 2); title.setText(group.title + " · " + android.text.format.Formatter.formatShortFileSize(activity, group.bytes)); list.addView(title);
                for (MediaRepository.OfflineItem item : group.items) {
                    CheckBox row = new CheckBox(activity); row.setMinHeight(pad * 3); row.setTextSize(14);
                    row.setText("第 " + item.episode.index + " 集 · " + item.quality.label + "\n"
                        + android.text.format.Formatter.formatShortFileSize(activity, Math.max(0, item.bytesDownloaded)) + " · " + item.status);
                    row.setContentDescription("清理选择：" + item.key);
                    row.setOnCheckedChangeListener((view, checked) -> { if (checked) chosen.put(item.key, item); else chosen.remove(item.key); update.run(); });
                    list.addView(row);
                }
            }
            if (groups.isEmpty()) { TextView empty = new TextView(activity); empty.setPadding(0, pad, 0, pad); empty.setText("没有符合条件的离线视频"); list.addView(empty); }
            update.run();
        };
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { render.run(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getWindow().setLayout(-1, Math.round(activity.getResources().getDisplayMetrics().heightPixels * .85f));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (chosen.isEmpty()) return;
                List<MediaRepository.OfflineItem> selected = new ArrayList<>(chosen.values()); dialog.dismiss(); remove.accept(selected);
            }); render.run();
        }); dialog.show();
    }
}
