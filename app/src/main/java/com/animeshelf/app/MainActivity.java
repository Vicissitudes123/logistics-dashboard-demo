package com.animeshelf.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local-first episode library and player. Network requests never run on the UI thread. */
@androidx.media3.common.util.UnstableApi
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(14, 17, 27);
    private static final int PANEL = Color.rgb(25, 29, 44);
    private static final int PANEL_LIGHT = Color.rgb(36, 41, 59);
    private static final int TEXT = Color.rgb(242, 243, 251);
    private static final int MUTED = Color.rgb(158, 168, 190);
    private static final int PURPLE = Color.rgb(175, 153, 255);
    private static final int AQUA = Color.rgb(119, 224, 210);
    private static final int PICK_VIDEO = 101;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private LinearLayout root, header, body, nav, playerPanel, content;
    private FrameLayout videoFrame;
    private ScrollView scroll;
    private TextView status, tagline;
    private Button loginButton, addButton;
    private PlayerView playerView;
    private PlayerOverlay playerOverlay;
    private ExoPlayer player;
    private Store store;
    private MediaRepository media;
    private Models.Series currentSeries;
    private Models.Episode currentEpisode;
    private Models.Quality currentQuality;
    private boolean dead, fullScreen, downloadsPage, seriesPage, sourceReady, currentOffline;
    private int selectionToken, taskStatusToken;
    private float zoom = 1f;
    private long lastRender, requestedResumeMs;
    private String downloadGroupId, downloadGroupTitle = "";
    private int downloadMutationGeneration;
    private BatchDownloadSession batchSession;
    private AlertDialog batchDialog;
    private boolean episodeSelectionMode, downloadSelectionMode;
    private String selectionSeriesId;
    private final Set<String> selectedEpisodeIds = new LinkedHashSet<>();
    private final Set<String> selectedDownloadKeys = new LinkedHashSet<>();
    private float originalWindowBrightness = -1f, playbackBrightness = -1f;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (dead) return;
            savePosition();
            if (downloadsPage && !fullScreen && System.currentTimeMillis() - lastRender > 1500) renderDownloads();
            handler.postDelayed(this, 3000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        originalWindowBrightness = getWindow().getAttributes().screenBrightness;
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        store = new Store(this);
        media = MediaRepository.get(this);
        buildUi();
        handler.post(ticker);
        renderLibrary();
    }

    private void buildUi() {
        root = column(); root.setBackgroundColor(BG);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left = fullScreen ? 0 : insets.getSystemWindowInsetLeft();
            int top = fullScreen ? 0 : insets.getSystemWindowInsetTop();
            int right = fullScreen ? 0 : insets.getSystemWindowInsetRight();
            int bottom = fullScreen ? 0 : insets.getSystemWindowInsetBottom();
            if (fullScreen && Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                android.view.DisplayCutout cutout = insets.getDisplayCutout();
                left = cutout.getSafeInsetLeft(); top = cutout.getSafeInsetTop();
                right = cutout.getSafeInsetRight(); bottom = cutout.getSafeInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            if (playerOverlay != null && Build.VERSION.SDK_INT >= 29) {
                android.graphics.Insets gestures = insets.getSystemGestureInsets();
                playerOverlay.setSystemGestureInset(gestures.left, gestures.top, gestures.right, gestures.bottom);
            }
            return insets;
        });
        root.requestApplyInsets();
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (playerPanel != null && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) resizeVideo();
        });
        header = column(); header.setPadding(dp(20), dp(18), dp(20), dp(12));
        LinearLayout brand = row();
        brand.addView(button("首页", false, v -> renderLibrary()));
        TextView name = text("番匣", 29, TEXT); name.setTypeface(null, Typeface.BOLD);
        brand.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        loginButton = button("B站登录", false, v -> {
            ++selectionToken;
            startActivity(new Intent(this, LoginActivity.class));
        });
        brand.addView(loginButton);
        addButton = button("＋ 添加", true, v -> addMenu());
        brand.addView(addButton);
        header.addView(brand);
        tagline = text("把喜欢的故事，带在身边", 12, MUTED);
        header.addView(tagline);
        root.addView(header);

        playerPanel = column(); playerPanel.setVisibility(View.GONE);
        videoFrame = new FrameLayout(this); videoFrame.setBackgroundColor(Color.BLACK);
        videoFrame.setClipChildren(true);
        playerView = (PlayerView) getLayoutInflater().inflate(R.layout.player_surface, videoFrame, false);
        playerView.setUseController(false);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        videoFrame.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        playerOverlay = new PlayerOverlay(this, new PlayerOverlay.Host() {
            @Override public boolean hasPlayer() { return player != null; }
            @Override public boolean isPlaying() {
                return player != null && player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED
                    && player.getPlaybackState() != Player.STATE_IDLE;
            }
            @Override public boolean canSeek() {
                return player != null && sourceReady && player.getPlaybackState() != Player.STATE_IDLE
                    && player.getDuration() > 0 && player.isCurrentMediaItemSeekable();
            }
            @Override public long positionMs() { return player == null ? 0 : player.getCurrentPosition(); }
            @Override public long durationMs() { return player == null ? -1 : player.getDuration(); }
            @Override public float speed() { return player == null ? store.getSpeed() : player.getPlaybackParameters().speed; }
            @Override public float brightness() { return currentPlaybackBrightness(); }
            @Override public void setBrightness(float value) { setPlaybackBrightness(value); }
            @Override public void setTemporarySpeed(float rate) { setTemporaryPlaybackSpeed(rate); }
            @Override public void togglePlayback() { MainActivity.this.togglePlayback(); }
            @Override public void seekTo(long position) {
                if (!canSeek()) return;
                player.seekTo(Math.max(0, Math.min(player.getDuration(), position)));
                savePosition();
            }
            @Override public void setSpeed(float rate) { setPlaybackSpeed(rate); }
            @Override public void chooseSpeed() { MainActivity.this.chooseSpeed(); }
            @Override public void chooseEpisode() { chooseEpisodeMenu(); }
            @Override public void chooseDisplay() { chooseDisplayMenu(); }
            @Override public void back() { onBackPressed(); }
            @Override public void home() { renderLibrary(); }
            @Override public void toggleFullScreen() { setFullScreen(!fullScreen); }
        });
        videoFrame.addView(playerOverlay, new FrameLayout.LayoutParams(-1, -1));
        playerPanel.addView(videoFrame, new LinearLayout.LayoutParams(-1, dp(220)));
        root.addView(playerPanel);

        body = column(); root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        status = text("", 12, AQUA); status.setPadding(dp(20), dp(5), dp(20), dp(5));
        status.setVisibility(View.GONE); body.addView(status);
        scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = column(); content.setPadding(dp(20), dp(10), dp(20), dp(20));
        scroll.addView(content); body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        nav = row(); nav.setPadding(dp(12), dp(6), dp(12), dp(10)); nav.setBackgroundColor(PANEL);
        addEqual(nav, button("首页 · 番剧库", false, v -> renderLibrary()));
        addEqual(nav, button("离线下载", false, v -> openDownloads()));
        // Keep navigation outside the shrinking list area below the player.
        root.addView(nav);
    }

    private void addMenu() {
        new AlertDialog.Builder(this).setTitle("添加到番匣")
            .setItems(new String[]{"粘贴 B站番剧 / 视频链接", "导入本地视频"}, (dialog, index) -> {
                if (index == 0) inputLink(); else pickLocal();
            }).show();
    }

    private void inputLink() {
        EditText input = new EditText(this); input.setSingleLine(false);
        input.setHint("粘贴番剧分享链接、ep / ss 链接或 BV 视频链接");
        input.setTextColor(TEXT); input.setHintTextColor(MUTED); input.setPadding(dp(20), dp(16), dp(20), dp(16));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("添加 B站内容").setView(input)
            .setMessage("读取实际可用的剧集和清晰度。账号权限、地区及加密情况可能影响结果。")
            .setNegativeButton("取消", null).setPositiveButton("解析", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) { input.setError("请先粘贴链接"); return; }
            dialog.dismiss();
            final int token = ++selectionToken;
            String cookie = CookieManager.getInstance().getCookie("https://api.bilibili.com");
            runTask("正在读取剧集…", token, () -> new BiliClient(cookie).resolve(value), series -> {
                store.saveSeries(series);
                currentSeries = series; downloadsPage = false;
                renderSeries(series);
                notice("已添加「" + series.title + "」");
            });
        }));
        dialog.show();
    }

    private void renderLibrary() {
        clearListSelection();
        closePlayback();
        downloadGroupId = null;
        currentSeries = null;
        downloadsPage = false; seriesPage = false; content.removeAllViews();
        scroll.scrollTo(0, 0);
        title("我的番剧", "剧集、离线文件和观看记录，都保存在这台设备");
        List<Models.Series> series = store.listSeries();
        if (series.isEmpty()) {
            empty("你的故事，从这里开始", "添加 B站链接，或导入已经保存的视频。\n番匣会记住你每一集看到的位置。", "添加第一部番剧", this::addMenu);
            return;
        }
        for (Models.Series entry : series) {
            LinearLayout card = card();
            TextView title = text(entry.title, 18, TEXT); title.setTypeface(null, Typeface.BOLD); card.addView(title);
            String lastId = store.getLastEpisode(entry.id);
            Models.Episode episode = findEpisode(entry, lastId);
            if (episode == null && !entry.episodes.isEmpty()) episode = entry.episodes.get(0);
            String detail = entry.episodes.size() + " 集";
            if (episode != null && lastId != null && !lastId.isEmpty()) {
                Store.Progress progress = store.getProgress(episode.id);
                detail += "  ·  上次看到第 " + episode.index + " 集  " + time(progress.positionMs);
            }
            TextView subtitle = text(detail, 12, MUTED); subtitle.setPadding(0, dp(7), 0, dp(12)); card.addView(subtitle);
            LinearLayout actions = row();
            Models.Episode resume = episode;
            addEqual(actions, button(lastId == null || lastId.isEmpty() ? "开始观看" : "继续观看", true, v -> {
                currentSeries = entry; renderSeries(entry); if (resume != null) selectEpisode(resume);
            }));
            addEqual(actions, button("选集", false, v -> { currentSeries = entry; renderSeries(entry); }));
            card.addView(actions); content.addView(card, gapLayout());
        }
    }

    private void renderSeries(Models.Series series) {
        clearListSelection();
        closePlayback();
        renderSeriesContent(series);
        scroll.scrollTo(0, 0);
    }

    /** Render the episode list belonging to the active playback without closing it. */
    private void renderSeriesContent(Models.Series series) {
        if (downloadSelectionMode || (episodeSelectionMode && !series.id.equals(selectionSeriesId))) clearListSelection();
        ++selectionToken;
        downloadsPage = false; seriesPage = true; currentSeries = series; content.removeAllViews();
        content.addView(button("‹  回到首页", false, v -> renderLibrary()));
        title(series.title, series.episodes.size() + " 集  ·  点击播放，长按多选下载");
        boolean hasOnlineEpisodes = false;
        for (Models.Episode item : series.episodes) if (item.localUri == null || item.localUri.isEmpty()) hasOnlineEpisodes = true;
        if (episodeSelectionMode) addEpisodeSelectionActions(series);
        else if (hasOnlineEpisodes) content.addView(button("批量下载", true, v -> chooseBatchEpisodes(series)), gapLayout());
        String lastId = store.getLastEpisode(series.id);
        Models.Episode resume = findEpisode(series, lastId);
        if (resume != null && !episodeSelectionMode) {
            Store.Progress p = store.getProgress(resume.id);
            content.addView(button("继续第 " + resume.index + " 集 · " + time(p.positionMs), true, v -> selectEpisode(resume)), gapLayout());
        }
        for (Models.Episode episode : DownloadCatalogue.episodes(series.episodes)) {
            LinearLayout card = card(); card.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout line = row();
            TextView num = text(String.format(Locale.ROOT, "%02d", episode.index), 22, PURPLE);
            num.setTypeface(null, Typeface.BOLD); num.setGravity(Gravity.CENTER); line.addView(num, new LinearLayout.LayoutParams(dp(44), -2));
            LinearLayout labels = column();
            TextView name = text(episode.title, 15, TEXT); name.setMaxLines(2); labels.addView(name);
            Store.Progress progress = store.getProgress(episode.id);
            String detail = episode.localUri != null && !episode.localUri.isEmpty() ? "本地视频" : "B站";
            if (hasDownload(episode.id)) detail += " · 已离线保存";
            if (progress.positionMs > 0) detail += " · " + time(progress.positionMs) + (progress.durationMs > 0 ? " / " + time(progress.durationMs) : "");
            labels.addView(text(detail, 11, MUTED));
            line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            boolean selected = currentEpisode != null && episode.id.equals(currentEpisode.id);
            boolean checked = selectedEpisodeIds.contains(episode.id);
            TextView play = text(episodeSelectionMode ? (checked ? "✓" : "○") : selected ? "已选择" : "›", episodeSelectionMode ? 24 : 14, AQUA);
            line.addView(play); card.addView(line);
            card.setContentDescription("第 " + episode.index + " 集，长按选择");
            markSelection(card, episodeSelectionMode && checked);
            card.setOnClickListener(v -> {
                if (episodeSelectionMode) toggleEpisodeSelection(series, episode);
                else selectEpisode(episode);
            });
            card.setOnLongClickListener(v -> { beginEpisodeSelection(series, episode); return true; });
            content.addView(card, gapLayout());
        }
    }

    private void clearListSelection() {
        episodeSelectionMode = false; downloadSelectionMode = false; selectionSeriesId = null;
        selectedEpisodeIds.clear(); selectedDownloadKeys.clear();
    }

    private void refreshSelectedList() {
        int y = scroll.getScrollY();
        if (downloadsPage) renderDownloads();
        else if (currentSeries != null) renderSeriesContent(currentSeries);
        scroll.scrollTo(0, y);
    }

    private void exitListSelection() { clearListSelection(); refreshSelectedList(); }

    private void markSelection(View card, boolean selected) {
        card.setSelected(selected);
        GradientDrawable shape = background(selected ? PANEL_LIGHT : PANEL, 18);
        if (selected) shape.setStroke(dp(2), PURPLE);
        card.setBackground(shape);
    }

    private static boolean downloadable(Models.Episode episode) {
        return episode.localUri == null || episode.localUri.isEmpty();
    }

    private void beginEpisodeSelection(Models.Series series, Models.Episode episode) {
        if (!downloadable(episode)) { notice("本地视频已经在手机上，无需下载"); return; }
        if (!episodeSelectionMode || !series.id.equals(selectionSeriesId)) clearListSelection();
        episodeSelectionMode = true; selectionSeriesId = series.id;
        selectedEpisodeIds.add(episode.id);
        refreshSelectedList();
    }

    private void toggleEpisodeSelection(Models.Series series, Models.Episode episode) {
        if (!downloadable(episode)) { notice("本地视频无需下载"); return; }
        if (!selectedEpisodeIds.remove(episode.id)) selectedEpisodeIds.add(episode.id);
        refreshSelectedList();
    }

    private void addEpisodeSelectionActions(Models.Series series) {
        List<Models.Episode> eligible = new ArrayList<>(), chosen = new ArrayList<>();
        Set<String> availableIds = new LinkedHashSet<>();
        for (Models.Episode episode : DownloadCatalogue.episodes(series.episodes)) if (downloadable(episode)) {
            eligible.add(episode); availableIds.add(episode.id);
        }
        selectedEpisodeIds.retainAll(availableIds);
        for (Models.Episode episode : eligible) if (selectedEpisodeIds.contains(episode.id)) chosen.add(episode);
        content.addView(text("已选 " + chosen.size() + " 集 · 点击条目勾选，返回键退出多选", 12, AQUA), gapLayout());
        LinearLayout actions = row();
        boolean all = !eligible.isEmpty() && chosen.size() == eligible.size();
        addEqual(actions, button(all ? "清空选择" : "全选", false, v -> {
            selectedEpisodeIds.clear();
            if (!all) selectedEpisodeIds.addAll(availableIds);
            refreshSelectedList();
        }));
        addEqual(actions, button("取消", false, v -> exitListSelection()));
        Button download = button("下载 " + chosen.size() + " 集", true, v -> chooseBatchQuality(series, new ArrayList<>(chosen)));
        download.setEnabled(!chosen.isEmpty()); addEqual(actions, download);
        content.addView(actions, gapLayout());
    }

    private void beginDownloadSelection(List<MediaRepository.OfflineItem> items) {
        if (!downloadSelectionMode) clearListSelection();
        downloadSelectionMode = true;
        for (MediaRepository.OfflineItem item : items) selectedDownloadKeys.add(item.key);
        refreshSelectedList();
    }

    private void toggleDownloadSelection(List<MediaRepository.OfflineItem> items) {
        boolean all = !items.isEmpty();
        for (MediaRepository.OfflineItem item : items) if (!selectedDownloadKeys.contains(item.key)) all = false;
        for (MediaRepository.OfflineItem item : items) {
            if (all) selectedDownloadKeys.remove(item.key); else selectedDownloadKeys.add(item.key);
        }
        refreshSelectedList();
    }

    private void addDownloadSelectionActions(List<MediaRepository.OfflineItem> visible) {
        List<MediaRepository.OfflineItem> chosen = new ArrayList<>();
        for (MediaRepository.OfflineItem item : visible) if (selectedDownloadKeys.contains(item.key)) chosen.add(item);
        content.addView(text("已选 " + chosen.size() + " 份视频 · 点击条目勾选，返回键退出多选", 12, AQUA), gapLayout());
        LinearLayout actions = row();
        boolean all = !visible.isEmpty() && chosen.size() == visible.size();
        addEqual(actions, button(all ? "清空选择" : "全选", false, v -> {
            for (MediaRepository.OfflineItem item : visible) {
                if (all) selectedDownloadKeys.remove(item.key); else selectedDownloadKeys.add(item.key);
            }
            refreshSelectedList();
        }));
        addEqual(actions, button("取消", false, v -> exitListSelection()));
        Button remove = button("删除 " + chosen.size() + " 份", true, v -> confirmRemoveDownloads(new ArrayList<>(chosen)));
        remove.setEnabled(!chosen.isEmpty()); addEqual(actions, remove);
        content.addView(actions, gapLayout());
    }

    private void selectEpisode(Models.Episode episode) {
        final int token = ++selectionToken;
        if (episode.localUri != null && !episode.localUri.isEmpty()) { play(episode, null, false); return; }
        if (hasDownload(episode.id)) {
            new AlertDialog.Builder(this).setTitle(episode.title)
                .setItems(new String[]{"播放已下载版本", "选择在线清晰度 / 下载其他版本"}, (d, choice) -> {
                    if (token != selectionToken) return;
                    if (choice == 0) chooseOffline(episode); else loadQualities(episode);
                }).show();
        } else loadQualities(episode);
    }

    private void loadQualities(Models.Episode episode) {
        int token = ++selectionToken;
        String cookie = CookieManager.getInstance().getCookie("https://api.bilibili.com");
        runTask("正在获取实际可用清晰度…", token, () -> new BiliClient(cookie).qualities(episode), qualities -> {
            if (token != selectionToken) return;
            if (qualities.isEmpty()) { error("没有可播放格式", "当前账号或这集内容未返回可用视频源。请先检查 B站登录及播放权限。"); return; }
            String[] names = new String[qualities.size()];
            for (int i = 0; i < qualities.size(); i++) names[i] = qualities.get(i).label;
            new AlertDialog.Builder(this).setTitle("第 " + episode.index + " 集 · 选择清晰度")
                .setItems(names, (dialog, selected) -> { if (token == selectionToken) qualityAction(episode, qualities.get(selected), token); })
                .setNegativeButton("取消", null).show();
        });
    }

    private void qualityAction(Models.Episode episode, Models.Quality quality, int token) {
        new AlertDialog.Builder(this).setTitle(quality.label)
            .setItems(new String[]{"在线播放", "下载到本机"}, (dialog, action) -> {
                if (token != selectionToken) return;
                if (action == 0) play(episode, quality, false);
                else {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 102);
                    try { media.enqueue(episode, quality); notice("已加入下载队列，可在「离线下载」查看"); }
                    catch (Exception e) { error("无法添加下载", explain(e)); }
                }
            }).setNegativeButton("取消", null).show();
    }

    private void chooseOffline(Models.Episode episode) {
        final int token = selectionToken;
        List<Models.Quality> choices = downloadedQualities(episode.id);
        if (choices.isEmpty()) { error("离线文件未就绪", "这集尚未完整下载。请到离线下载页查看进度。"); return; }
        if (choices.size() == 1) { play(episode, choices.get(0), true); return; }
        String[] names = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) names[i] = choices.get(i).label;
        new AlertDialog.Builder(this).setTitle("选择已下载版本").setItems(names, (d, i) -> {
            if (token == selectionToken) play(episode, choices.get(i), true);
        }).show();
    }

    private void play(Models.Episode episode, Models.Quality quality, boolean offline) {
        play(episode, quality, offline, -1);
    }

    private void play(Models.Episode episode, Models.Quality quality, boolean offline, long resumeOverrideMs) {
        clearListSelection();
        ++selectionToken; playerOverlay.setActive(false); savePosition(); sourceReady = false;
        if (player != null) { playerView.setPlayer(null); player.release(); player = null; }
        currentEpisode = episode; currentQuality = quality; currentOffline = offline;
        Store.Progress progress = store.getProgress(episode.id);
        long resume = resumeOverrideMs >= 0 ? resumeOverrideMs : progress.positionMs;
        if (resumeOverrideMs < 0 && progress.durationMs > 0 && resume >= progress.durationMs - 2000) resume = 0;
        requestedResumeMs = Math.max(0, resume);
        Models.Series found = findSeries(episode.seriesId);
        currentSeries = found;
        try {
            ExoPlayer next = new ExoPlayer.Builder(this).build(); player = next;
            playerView.setPlayer(next);
            if (episode.localUri != null && !episode.localUri.isEmpty()) next.setMediaItem(MediaItem.fromUri(episode.localUri));
            else next.setMediaSource(media.createMediaSource(episode, quality, offline));
            next.seekTo(requestedResumeMs);
            next.setPlaybackSpeed(store.getSpeed());
            next.addListener(new Player.Listener() {
                @Override public void onPlayerError(PlaybackException e) {
                    if (dead || player != next) return;
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    showPlaybackFailure(e);
                }
                @Override public void onIsPlayingChanged(boolean playing) {
                    if (player != next) return;
                    if (playing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else { getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); savePosition(); }
                    playerOverlay.refresh();
                }
                @Override public void onPlaybackStateChanged(int state) {
                    if (player != next) return;
                    if (state == Player.STATE_READY) { sourceReady = true; applyZoom(); savePosition(); }
                    if (state == Player.STATE_ENDED) { savePosition(); playerOverlay.showControls(); notice("本集已看完，可通过选集继续"); }
                    playerOverlay.refresh();
                }
            });
            next.prepare(); next.play();
            applyPlaybackBrightness();
            playerPanel.setVisibility(View.VISIBLE);
            playerOverlay.setTitle(episode.seriesTitle + " · 第 " + episode.index + " 集",
                episode.title + "  ·  " + (quality == null ? "本地文件" : quality.label + (offline ? " · 离线" : " · 在线")));
            playerOverlay.setActive(true);
            resizeVideo();
            downloadsPage = false;
            if (currentSeries != null) renderSeriesContent(currentSeries);
            else {
                seriesPage = true;
                content.removeAllViews();
                title("正在播放", "点击上方「首页」可保存进度并返回番剧库");
            }
        } catch (Exception e) { showPlaybackFailure(e); }
    }

    private void showPlaybackFailure(Throwable cause) {
        if (dead || isFinishing()) return;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        playerOverlay.cancelInteraction(); playerOverlay.refresh(); playerOverlay.showControls();
        String diagnostic = MediaErrors.describe(cause);
        Models.Episode failedEpisode = currentEpisode;
        Models.Quality failedQuality = currentQuality;
        ExoPlayer failedPlayer = player;
        long resume = requestedResumeMs;
        // ExoPlayer is already IDLE when a source error is delivered. Save the last
        // real position explicitly; the normal ticker intentionally ignores IDLE.
        if (sourceReady && failedPlayer != null && failedEpisode != null) {
            resume = Math.max(0, failedPlayer.getCurrentPosition());
            long duration = failedPlayer.getDuration();
            if (duration <= 0) duration = store.getProgress(failedEpisode.id).durationMs;
            store.saveProgress(failedEpisode, resume, Math.max(0, duration));
        }
        final long retryPosition = resume;
        boolean canRefresh = failedEpisode != null && failedQuality != null && !currentOffline &&
            (failedEpisode.localUri == null || failedEpisode.localUri.isEmpty());
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("播放未能继续")
            .setMessage(diagnostic + (canRefresh ? "\n\n可以刷新当前清晰度的播放地址，重试时会保留观看位置。" : ""))
            .setNegativeButton("回到首页", (d, w) -> renderLibrary())
            .setNeutralButton("复制诊断", (d, w) -> copyDiagnostic(diagnostic));
        if (canRefresh) dialog.setPositiveButton("刷新地址重试", (d, w) ->
            refreshPlayback(failedEpisode, failedQuality, failedPlayer, retryPosition));
        dialog.show();
    }

    private void refreshPlayback(Models.Episode episode, Models.Quality quality, ExoPlayer failedPlayer, long positionMs) {
        if (dead || currentEpisode != episode || player != failedPlayer) return;
        int token = ++selectionToken;
        String cookie = CookieManager.getInstance().getCookie("https://api.bilibili.com");
        runTask("正在刷新当前播放地址…", token, () -> new BiliClient(cookie).refresh(episode, quality.id), refreshed -> {
            if (token != selectionToken || currentEpisode != episode || player != failedPlayer) return;
            play(episode, refreshed, false, positionMs);
        });
    }

    private void copyDiagnostic(String diagnostic) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) { notice("暂时无法使用剪贴板"); return; }
        // The input is already sanitized by MediaErrors; never copy raw exceptions.
        clipboard.setPrimaryClip(ClipData.newPlainText("番匣诊断", diagnostic));
        notice("已复制诊断，不含播放地址参数或登录凭据");
    }

    private void savePosition() {
        if (player == null || currentEpisode == null || !sourceReady || player.getPlaybackState() == Player.STATE_IDLE) return;
        long duration = player.getDuration();
        if (duration < 0) duration = store.getProgress(currentEpisode.id).durationMs;
        store.saveProgress(currentEpisode, Math.max(0, player.getCurrentPosition()), Math.max(0, duration));
    }

    private void closePlayback() {
        restoreWindowBrightness();
        cancelBatchDownloads();
        ++selectionToken;
        ++taskStatusToken;
        status.setVisibility(View.GONE);
        playerOverlay.setActive(false);
        // A failed source can already be IDLE. Preserve its real position if it
        // reached READY; a source that never loaded must not overwrite history.
        if (player != null && currentEpisode != null && sourceReady) {
            long duration = player.getDuration();
            if (duration <= 0) duration = store.getProgress(currentEpisode.id).durationMs;
            store.saveProgress(currentEpisode, Math.max(0, player.getCurrentPosition()), Math.max(0, duration));
        }
        ExoPlayer previous = player;
        player = null;
        sourceReady = false;
        playerView.setPlayer(null);
        if (previous != null) previous.release();
        currentEpisode = null;
        currentQuality = null;
        currentSeries = null;
        currentOffline = false;
        requestedResumeMs = 0;
        if (fullScreen) setFullScreen(false);
        playerPanel.setVisibility(View.GONE);
        resizeVideo();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void adjacent(int direction) {
        if (currentEpisode == null) return;
        Models.Series playingSeries = findSeries(currentEpisode.seriesId);
        if (playingSeries == null) return;
        for (int i = 0; i < playingSeries.episodes.size(); i++) if (playingSeries.episodes.get(i).id.equals(currentEpisode.id)) {
            int target = i + direction;
            if (target < 0 || target >= playingSeries.episodes.size()) { notice(direction > 0 ? "已经是最后一集" : "已经是第一集"); return; }
            selectEpisode(playingSeries.episodes.get(target)); return;
        }
    }

    private void togglePlayback() {
        if (player == null) return;
        if (player.getPlaybackState() == Player.STATE_ENDED) {
            player.seekTo(0); player.play();
        } else if (player.getPlayerError() != null) {
            showPlaybackFailure(player.getPlayerError());
        } else if (player.getPlayWhenReady()) player.pause();
        else player.play();
        playerOverlay.refresh();
    }

    private void setPlaybackSpeed(float rate) {
        playerOverlay.cancelInteraction();
        float speed = Math.max(.25f, Math.min(3f, Math.round(rate * 4f) / 4f));
        store.setSpeed(speed);
        if (player != null) player.setPlaybackSpeed(speed);
        playerOverlay.refresh();
    }

    private void setTemporaryPlaybackSpeed(float rate) {
        if (player != null && Float.isFinite(rate)) player.setPlaybackSpeed(Math.max(.25f, Math.min(3f, rate)));
    }

    private float currentPlaybackBrightness() {
        if (playbackBrightness >= 0) return playbackBrightness;
        float current = getWindow().getAttributes().screenBrightness;
        if (current < 0) {
            try { current = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128) / 255f; }
            catch (RuntimeException ignored) { current = .5f; }
        }
        return Math.max(.05f, Math.min(1f, current));
    }

    private void setPlaybackBrightness(float value) {
        if (!Float.isFinite(value)) return;
        playbackBrightness = Math.max(.05f, Math.min(1f, value));
        applyPlaybackBrightness();
    }

    private void applyPlaybackBrightness() {
        if (player == null || playbackBrightness < 0) return;
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = playbackBrightness;
        getWindow().setAttributes(params);
    }

    private void restoreWindowBrightness() {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = originalWindowBrightness;
        getWindow().setAttributes(params);
    }

    private void chooseSpeed() {
        if (player == null) return;
        float[] rates = {.25f, .5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f};
        String[] names = new String[rates.length];
        int selected = -1;
        for (int i = 0; i < rates.length; i++) {
            names[i] = speedText(rates[i]);
            if (Math.abs(player.getPlaybackParameters().speed - rates[i]) < .01f) selected = i;
        }
        new AlertDialog.Builder(this).setTitle("播放速度 · 长按画面可临时加速")
            .setSingleChoiceItems(names, selected, (dialog, index) -> {
                setPlaybackSpeed(rates[index]); dialog.dismiss();
            }).setNegativeButton("关闭", null).show();
    }

    private void chooseEpisodeMenu() {
        if (currentEpisode == null) return;
        Models.Series series = findSeries(currentEpisode.seriesId);
        if (series == null || series.episodes.isEmpty()) { notice("当前视频没有其他剧集"); return; }
        int selected = -1;
        String[] names = new String[series.episodes.size()];
        for (int i = 0; i < names.length; i++) {
            Models.Episode episode = series.episodes.get(i);
            names[i] = "第 " + episode.index + " 集  " + episode.title;
            if (episode.id.equals(currentEpisode.id)) selected = i;
        }
        new AlertDialog.Builder(this).setTitle("选集").setSingleChoiceItems(names, selected, (dialog, index) -> {
            dialog.dismiss(); selectEpisode(series.episodes.get(index));
        }).setNegativeButton("关闭", null).show();
    }

    private void chooseDisplayMenu() {
        new AlertDialog.Builder(this).setTitle("画面显示")
            .setItems(new String[]{"适应画面", "铺满画面（裁切边缘）", "缩小 −", "放大 ＋", "恢复 100%", "视频信息 / HDR"}, (dialog, index) -> {
                if (index == 0 || index == 1) {
                    zoom = 1; applyZoom();
                    playerView.setResizeMode(index == 0 ? AspectRatioFrameLayout.RESIZE_MODE_FIT : AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                } else if (index == 2) setZoom(zoom - .25f);
                else if (index == 3) setZoom(zoom + .25f);
                else if (index == 4) setZoom(1);
                else showVideoInformation();
            }).setNegativeButton("关闭", null).show();
    }

    private void showVideoInformation() {
        String detail = VideoDiagnostics.describe(player == null ? null : player.getVideoFormat(),
            playerView.getDisplay(), playerView.getVideoSurfaceView(), currentQuality == null ? null : currentQuality.label);
        if (currentQuality != null && currentQuality.bandwidth > 0)
            detail += "\n平台标称视频码率：" + VideoDiagnostics.formatBitrate(currentQuality.bandwidth) + "（非实时测量）";
        detail += "\n\n离线下载保留平台媒体数据，番匣不额外转码压缩。";
        final String diagnostic = detail;
        ScrollView box = new ScrollView(this);
        TextView text = text(detail, 14, TEXT); text.setTextIsSelectable(true); text.setPadding(dp(20), dp(12), dp(20), dp(16));
        box.addView(text);
        new AlertDialog.Builder(this).setTitle("视频信息").setView(box).setPositiveButton("关闭", null)
            .setNeutralButton("复制信息", (d, w) -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) { clipboard.setPrimaryClip(ClipData.newPlainText("视频信息", diagnostic)); notice("已复制视频信息"); }
            }).show();
    }

    private void setZoom(float value) { zoom = Math.max(.5f, Math.min(3f, value)); applyZoom(); notice("画面缩放 " + Math.round(zoom * 100) + "%"); }
    private void applyZoom() {
        View surface = playerView.getVideoSurfaceView();
        if (surface != null) { surface.setScaleX(zoom); surface.setScaleY(zoom); }
    }

    private void setFullScreen(boolean value) {
        fullScreen = value;
        // The video owns its navigation in fullscreen; no outer bars consume space.
        header.setVisibility(value ? View.GONE : View.VISIBLE);
        body.setVisibility(value ? View.GONE : View.VISIBLE);
        nav.setVisibility(value ? View.GONE : View.VISIBLE);
        playerOverlay.setFullScreen(value);
        applySystemBars();
        setRequestedOrientation(value ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        root.requestApplyInsets();
        resizeVideo();
    }

    private void applySystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(fullScreen
            ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            : View.SYSTEM_UI_FLAG_VISIBLE);
    }

    private void resizeVideo() {
        if (fullScreen) {
            playerPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
            videoFrame.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            int width = root.getWidth() > 0 ? root.getWidth() - root.getPaddingLeft() - root.getPaddingRight()
                : getResources().getDisplayMetrics().widthPixels;
            int height = root.getHeight() > 0 ? root.getHeight() - root.getPaddingTop() - root.getPaddingBottom()
                : getResources().getDisplayMetrics().heightPixels;
            boolean compact = playerPanel.getVisibility() == View.VISIBLE && width > height;
            header.setVisibility(compact ? View.GONE : View.VISIBLE);
            nav.setPadding(dp(12), compact ? 0 : dp(6), dp(12), compact ? 0 : dp(10));
            int reserved = compact ? dp(48) : Math.max(dp(92), header.getMeasuredHeight()) + dp(64);
            int desired = Math.max(dp(180), Math.min(width * 9 / 16, height / 2));
            int videoHeight = Math.min(desired, Math.max(dp(164), height - reserved));
            playerPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            videoFrame.setLayoutParams(new LinearLayout.LayoutParams(-1, videoHeight));
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration); playerOverlay.cancelInteraction(); resizeVideo(); root.requestApplyInsets();
    }
    @Override public void onBackPressed() {
        if (fullScreen) { setFullScreen(false); return; }
        if (episodeSelectionMode || downloadSelectionMode) { exitListSelection(); return; }
        if (downloadsPage && downloadGroupId != null) { downloadGroupId = null; renderDownloads(); scroll.scrollTo(0, 0); return; }
        if (player != null || playerPanel.getVisibility() == View.VISIBLE || seriesPage || downloadsPage) { renderLibrary(); return; }
        super.onBackPressed();
    }

    private void pickLocal() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, PICK_VIDEO); } catch (Exception e) { error("无法打开文件选择器", explain(e)); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_VIDEO || result != RESULT_OK || data == null) return;
        List<Uri> files = new ArrayList<>();
        ClipData clips = data.getClipData();
        if (clips != null) for (int i = 0; i < clips.getItemCount(); i++) files.add(clips.getItemAt(i).getUri());
        else if (data.getData() != null) files.add(data.getData());
        if (files.isEmpty()) return;
        Models.Series series = new Models.Series(); series.id = "local-" + UUID.randomUUID();
        series.title = files.size() == 1 ? displayName(files.get(0)) : "本地番剧 " + new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date());
        for (Uri uri : files) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { notice("此文件来源未提供长期访问权限，重启后可能需要重新导入"); }
            Models.Episode episode = new Models.Episode(); episode.id = "local-" + stableId(uri.toString());
            episode.localUri = uri.toString(); episode.title = displayName(uri); episode.index = series.episodes.size() + 1;
            episode.seriesId = series.id; episode.seriesTitle = series.title; series.episodes.add(episode);
        }
        EditText input = new EditText(this); input.setText(series.title); input.setTextColor(TEXT); input.setPadding(dp(20), dp(12), dp(20), dp(12));
        new AlertDialog.Builder(this).setTitle("为这组视频命名").setView(input).setPositiveButton("导入", (d, w) -> {
            String name = input.getText().toString().trim(); if (!name.isEmpty()) series.title = name;
            for (Models.Episode e : series.episodes) e.seriesTitle = series.title;
            store.saveSeries(series); currentSeries = series; renderSeries(series); notice("已导入 " + series.episodes.size() + " 个视频");
        }).setNegativeButton("取消", null).show();
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) { String name = cursor.getString(0); if (name != null && !name.trim().isEmpty()) return name; }
        } catch (Exception ignored) { }
        return "本地视频";
    }

    private void openDownloads() {
        clearListSelection();
        closePlayback();
        downloadGroupId = null;
        renderDownloads();
        scroll.scrollTo(0, 0);
    }

    private void openDownloadGroup(DownloadCatalogue.Group group) {
        clearListSelection();
        downloadGroupId = group.id;
        downloadGroupTitle = group.title;
        renderDownloads();
        scroll.scrollTo(0, 0);
    }

    private void renderDownloads() {
        if (episodeSelectionMode) clearListSelection();
        downloadsPage = true; seriesPage = false; lastRender = System.currentTimeMillis();
        int previousScroll = scroll.getScrollY();
        content.removeAllViews();
        List<MediaRepository.OfflineItem> entries = media.downloads();
        Set<String> availableKeys = new LinkedHashSet<>();
        for (MediaRepository.OfflineItem item : entries) availableKeys.add(item.key);
        selectedDownloadKeys.retainAll(availableKeys);
        List<DownloadCatalogue.Group> groups = DownloadCatalogue.groups(entries);
        DownloadCatalogue.Group selected = null;
        for (DownloadCatalogue.Group group : groups) if (group.id.equals(downloadGroupId)) selected = group;
        if (downloadGroupId == null) {
            title("离线下载", groups.size() + " 部番剧 · 完整下载后可断网播放");
            if (entries.isEmpty()) {
                empty("还没有下载任务", "进入一部番剧，点击「批量下载」，\n或为单集选择清晰度下载。", "去番剧库", this::renderLibrary);
                return;
            }
            if (downloadSelectionMode) addDownloadSelectionActions(entries); else addDownloadActions(entries);
            for (DownloadCatalogue.Group group : groups) {
                LinearLayout card = card();
                card.setContentDescription("离线番剧 " + group.title + "，长按选择");
                TextView name = text(group.title, 18, TEXT); name.setTypeface(null, Typeface.BOLD); card.addView(name);
                TextView detail = text(group.episodeCount + " 集 · " + group.items.size() + " 份视频 · 已完成 "
                    + group.completeCount + " 份 · " + android.text.format.Formatter.formatShortFileSize(this, group.bytes), 12, MUTED);
                detail.setPadding(0, dp(7), 0, dp(12)); card.addView(detail);
                if (downloadSelectionMode) {
                    int checked = 0;
                    for (MediaRepository.OfflineItem item : group.items) if (selectedDownloadKeys.contains(item.key)) checked++;
                    card.addView(text(checked == group.items.size() ? "✓ 已全选" : checked > 0 ? "已选 " + checked + " 份" : "○ 未选择", 16, AQUA));
                    markSelection(card, checked > 0);
                } else {
                    LinearLayout actions = row();
                    addEqual(actions, button("查看已下载剧集", true, v -> openDownloadGroup(group)));
                    addEqual(actions, button("删除整部", false, v -> confirmRemoveDownloads(group.items)));
                    card.addView(actions);
                }
                card.setOnClickListener(v -> { if (downloadSelectionMode) toggleDownloadSelection(group.items); else openDownloadGroup(group); });
                card.setOnLongClickListener(v -> { beginDownloadSelection(group.items); return true; });
                content.addView(card, gapLayout());
            }
        } else {
            content.addView(button("‹  全部离线番剧", false, v -> {
                clearListSelection(); downloadGroupId = null; renderDownloads(); scroll.scrollTo(0, 0);
            }));
            title(selected == null ? downloadGroupTitle : selected.title, "按集数从小到大排列 · 同集可保留多个清晰度");
            if (selected == null) {
                empty("这部番剧暂时没有下载", "已删除的离线文件不会删除观看记录。", "查看全部下载",
                    () -> { downloadGroupId = null; renderDownloads(); scroll.scrollTo(0, 0); });
                return;
            }
            if (downloadSelectionMode) addDownloadSelectionActions(selected.items); else addDownloadActions(selected.items);
            for (MediaRepository.OfflineItem entry : selected.items) renderDownloadItem(entry);
        }
        scroll.scrollTo(0, previousScroll);
    }

    private void addDownloadActions(List<MediaRepository.OfflineItem> visible) {
        LinearLayout actions = row();
        addEqual(actions, button("暂停全部", false, v -> {
            try { media.pauseAll(); renderDownloads(); } catch (Exception e) { error("操作失败", explain(e)); }
        }));
        addEqual(actions, button("继续全部", false, v -> {
            try { media.resumeAll(); renderDownloads(); } catch (Exception e) { error("操作失败", explain(e)); }
        }));
        List<MediaRepository.OfflineItem> snapshot = new ArrayList<>(visible);
        addEqual(actions, button("批量删除", false, v -> chooseDownloadsToRemove(snapshot)));
        content.addView(actions, gapLayout());
    }

    private void renderDownloadItem(MediaRepository.OfflineItem entry) {
        LinearLayout card = card();
        card.setContentDescription("离线第 " + entry.episode.index + " 集，" + entry.quality.label + "，长按选择");
        List<MediaRepository.OfflineItem> item = java.util.Collections.singletonList(entry);
        card.setOnLongClickListener(v -> { beginDownloadSelection(item); return true; });
        card.setOnClickListener(v -> { if (downloadSelectionMode) toggleDownloadSelection(item); });
        TextView name = text("第 " + entry.episode.index + " 集 · " + entry.episode.title, 16, TEXT);
        name.setTypeface(null, Typeface.BOLD); card.addView(name);
        card.addView(text(entry.quality.label + "  ·  " + entry.status, 12, AQUA));
        String size = android.text.format.Formatter.formatShortFileSize(this, entry.bytesDownloaded);
        if (entry.percent >= 0 && !entry.complete) size += String.format(Locale.CHINA, " · %.0f%%", entry.percent);
        card.addView(text(size, 12, MUTED));
        if (downloadSelectionMode) {
            boolean checked = selectedDownloadKeys.contains(entry.key);
            markSelection(card, checked);
            card.addView(text(checked ? "✓ 已选择" : "○ 未选择", 18, AQUA));
            content.addView(card, gapLayout()); return;
        }
        final String diagnostic = entry.errorDetail == null || entry.errorDetail.trim().isEmpty()
            ? "此任务尚未记录具体错误。请重新解析重试，失败后可查看新的诊断。" : entry.errorDetail;
        if (entry.failed) {
            TextView detail = text(diagnostic, 12, MUTED); detail.setMaxLines(3);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END); detail.setPadding(0, dp(5), 0, dp(5)); card.addView(detail);
        }
        LinearLayout actions = row();
        if (entry.complete) addEqual(actions, button("离线播放", true, v -> play(entry.episode, entry.quality, true)));
        else if (entry.failed) addEqual(actions, button("重新解析重试", true, v -> retryDownload(entry)));
        else addEqual(actions, button(entry.paused ? "继续全部" : "暂停全部", false, v -> {
            try { if (entry.paused) media.resumeAll(); else media.pauseAll(); renderDownloads(); }
            catch (Exception e) { error("操作失败", explain(e)); }
        }));
        if (entry.failed) addEqual(actions, button("失败详情", false, v -> new AlertDialog.Builder(this)
            .setTitle("下载失败详情").setMessage(diagnostic).setPositiveButton("知道了", null)
            .setNeutralButton("复制诊断", (d, w) -> copyDiagnostic(diagnostic)).show()));
        addEqual(actions, button("删除", false, v -> confirmRemoveDownloads(java.util.Collections.singletonList(entry))));
        card.addView(actions); content.addView(card, gapLayout());
    }

    private boolean downloadExists(String key) {
        for (MediaRepository.OfflineItem item : media.downloads()) if (key.equals(item.key)) return true;
        return false;
    }

    private void retryDownload(MediaRepository.OfflineItem entry) {
        final int generation = downloadMutationGeneration;
        String cookie = CookieManager.getInstance().getCookie("https://api.bilibili.com");
        runTask("正在刷新下载地址…", () -> new BiliClient(cookie).refresh(entry.episode, entry.quality.id), quality -> {
            // A deleted task must not be resurrected by a late request.
            if (generation != downloadMutationGeneration || !downloadExists(entry.key)) return;
            media.enqueue(entry.episode, quality); media.resumeAll();
            if (downloadsPage) renderDownloads();
            notice("已重新加入下载队列");
        });
    }

    private void chooseDownloadsToRemove(List<MediaRepository.OfflineItem> source) {
        List<MediaRepository.OfflineItem> items = new ArrayList<>();
        for (DownloadCatalogue.Group group : DownloadCatalogue.groups(source)) items.addAll(group.items);
        if (items.isEmpty()) return;
        boolean[] selected = new boolean[items.size()];
        String[] labels = new String[items.size()];
        for (int i = 0; i < labels.length; i++) {
            MediaRepository.OfflineItem item = items.get(i);
            labels[i] = (downloadGroupId == null ? item.episode.seriesTitle + " · " : "")
                + "第 " + item.episode.index + " 集 · " + item.quality.label;
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("选择要删除的离线版本")
            .setMultiChoiceItems(labels, selected, (d, index, checked) -> {
                selected[index] = checked; updateMultiChoiceButtons((AlertDialog) d, selected, "删除");
            }).setNeutralButton("全选", null).setNegativeButton("取消", null).setPositiveButton("删除", null).create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> toggleAll(dialog, selected, "删除"));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                List<MediaRepository.OfflineItem> chosen = new ArrayList<>();
                for (int i = 0; i < selected.length; i++) if (selected[i]) chosen.add(items.get(i));
                if (chosen.isEmpty()) return;
                dialog.dismiss(); confirmRemoveDownloads(chosen);
            });
            updateMultiChoiceButtons(dialog, selected, "删除");
        });
        dialog.show();
    }

    private void confirmRemoveDownloads(List<MediaRepository.OfflineItem> selected) {
        LinkedHashMap<String, MediaRepository.OfflineItem> unique = new LinkedHashMap<>();
        for (MediaRepository.OfflineItem item : selected) if (item != null && item.key != null) unique.put(item.key, item);
        if (unique.isEmpty()) return;
        List<MediaRepository.OfflineItem> snapshot = new ArrayList<>(unique.values());
        long bytes = 0;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < snapshot.size(); i++) {
            MediaRepository.OfflineItem item = snapshot.get(i); bytes += Math.max(0, item.bytesDownloaded);
            if (i < 6) names.append("\n").append(item.episode.seriesTitle).append(" · 第 ").append(item.episode.index)
                .append(" 集 · ").append(item.quality.label);
        }
        if (snapshot.size() > 6) names.append("\n…另 ").append(snapshot.size() - 6).append(" 份");
        new AlertDialog.Builder(this).setTitle("删除 " + snapshot.size() + " 份离线视频？")
            .setMessage("约 " + android.text.format.Formatter.formatShortFileSize(this, bytes)
                + "。同时取消对应下载任务，保留番剧库和观看记录。" + names)
            .setNegativeButton("取消", null).setPositiveButton("确认删除", (d, w) -> removeDownloads(snapshot)).show();
    }

    private void removeDownloads(List<MediaRepository.OfflineItem> snapshot) {
        clearListSelection();
        ++downloadMutationGeneration;
        cancelBatchDownloads();
        Set<String> keys = new LinkedHashSet<>();
        for (MediaRepository.OfflineItem item : snapshot) keys.add(item.key);
        if (currentOffline && currentEpisode != null && currentQuality != null
                && keys.contains(MediaRepository.key(currentEpisode, currentQuality))) closePlayback();
        int removed = 0, failed = 0;
        for (MediaRepository.OfflineItem item : media.downloads()) {
            if (!keys.contains(item.key)) continue;
            try { media.remove(item); removed++; } catch (Exception exception) { failed++; }
        }
        if (downloadsPage) renderDownloads();
        notice("已移除 " + removed + " 份" + (failed > 0 ? "，" + failed + " 份删除失败，请重试" : "，观看记录已保留"));
    }

    private void chooseBatchEpisodes(Models.Series series) {
        List<Models.Episode> episodes = new ArrayList<>();
        for (Models.Episode episode : DownloadCatalogue.episodes(series.episodes))
            if (episode.localUri == null || episode.localUri.isEmpty()) episodes.add(episode);
        if (episodes.isEmpty()) { notice("本地视频无需下载"); return; }
        boolean[] selected = new boolean[episodes.size()];
        String[] names = new String[episodes.size()];
        for (int i = 0; i < names.length; i++) names[i] = "第 " + episodes.get(i).index + " 集 · " + episodes.get(i).title;
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("批量下载 · 选择集数")
            .setMultiChoiceItems(names, selected, (d, index, checked) -> {
                selected[index] = checked; updateMultiChoiceButtons((AlertDialog) d, selected, "选择清晰度");
            }).setNeutralButton("全选", null).setNegativeButton("取消", null).setPositiveButton("选择清晰度", null).create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> toggleAll(dialog, selected, "选择清晰度"));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                List<Models.Episode> chosen = new ArrayList<>();
                for (int i = 0; i < selected.length; i++) if (selected[i]) chosen.add(episodes.get(i));
                if (chosen.isEmpty()) return;
                dialog.dismiss(); chooseBatchQuality(series, chosen);
            });
            updateMultiChoiceButtons(dialog, selected, "选择清晰度");
        });
        dialog.show();
    }

    private void updateMultiChoiceButtons(AlertDialog dialog, boolean[] selected, String action) {
        int count = 0; for (boolean value : selected) if (value) count++;
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) { positive.setText(action + "（" + count + "）"); positive.setEnabled(count > 0); }
        Button all = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (all != null) all.setText(count == selected.length ? "清空选择" : "全选");
    }

    private void toggleAll(AlertDialog dialog, boolean[] selected, String action) {
        boolean all = true; for (boolean value : selected) if (!value) all = false;
        for (int i = 0; i < selected.length; i++) { selected[i] = !all; dialog.getListView().setItemChecked(i, !all); }
        updateMultiChoiceButtons(dialog, selected, action);
    }

    private void chooseBatchQuality(Models.Series series, List<Models.Episode> chosen) {
        final int token = ++selectionToken;
        String cookie = CookieManager.getInstance().getCookie("https://api.bilibili.com");
        Models.Episode first = chosen.get(0);
        runTask("正在读取第 " + first.index + " 集的清晰度…", token,
            () -> new BiliClient(cookie).qualities(first), available -> {
                List<Models.Quality> qualities = new ArrayList<>();
                for (Models.Quality quality : available) if (DownloadCatalogue.sameProfile(quality, quality)) qualities.add(quality);
                if (qualities.isEmpty()) { error("无法批量匹配", "接口未提供明确的清晰度与编码，请尝试单集下载。"); return; }
                String[] names = new String[qualities.size()];
                for (int i = 0; i < names.length; i++) names[i] = qualities.get(i).label;
                new AlertDialog.Builder(this).setTitle("统一清晰度 · 参考第 " + first.index + " 集")
                    .setItems(names, (dialog, index) -> {
                        if (token != selectionToken) return;
                        Models.Quality profile = qualities.get(index);
                        new AlertDialog.Builder(this).setTitle("批量下载 " + chosen.size() + " 集")
                            .setMessage(profile.label + "\n\n逐集核对相同清晰度和编码，缺少的会跳过并报告。\n已有同档位跳过，失败任务重新解析重试。\n加入队列时请保持应用在前台；已加入的任务会继续后台下载。")
                            .setNegativeButton("取消", null).setPositiveButton("开始加入队列", (d, w) -> {
                                if (token == selectionToken) startBatchDownloads(series, chosen, profile);
                            }).show();
                    }).setNegativeButton("取消", null).show();
            });
    }

    private void startBatchDownloads(Models.Series series, List<Models.Episode> episodes, Models.Quality profile) {
        cancelBatchDownloads();
        // Foreground downloads can start without notification permission. A permission
        // activity here could pause this activity and cancel a newly created batch.
        final String cookie = CookieManager.getInstance().getCookie("https://api.bilibili.com");
        BatchDownloadSession.Gateway gateway = new BatchDownloadSession.Gateway() {
            @Override public MediaRepository.OfflineItem existing(Models.Episode episode, Models.Quality quality) {
                return DownloadCatalogue.existing(media.downloads(), episode, quality);
            }
            @Override public void resolve(Models.Episode episode, BatchDownloadSession.Result callback) {
                io.execute(() -> {
                    try {
                        List<Models.Quality> available = new BiliClient(cookie).qualities(episode);
                        handler.post(() -> { if (!dead) callback.success(available); });
                    } catch (Exception exception) {
                        handler.post(() -> { if (!dead) callback.failure(exception); });
                    }
                });
            }
            @Override public void enqueue(Models.Episode episode, Models.Quality quality) { media.enqueue(episode, quality); }
        };
        batchSession = new BatchDownloadSession(episodes, profile, gateway, task -> handler.postDelayed(task, 350),
            new BatchDownloadSession.Listener() {
                @Override public void changed(BatchDownloadSession job) {
                    if (batchSession != job || batchDialog == null) return;
                    Models.Episode episode = job.current();
                    batchDialog.setMessage("正在逐集加入下载队列 " + job.processed() + " / " + job.total()
                        + (episode == null ? "" : "\n当前：第 " + episode.index + " 集") + "\n" + profile.label
                        + "\n\n已加入 " + job.added() + " · 已有 " + job.skipped() + " · 缺档 " + job.unavailable() + " · 失败 " + job.failed()
                        + "\n\n停止加入或切到后台后，已经加入的任务仍会继续下载。");
                }
                @Override public void finished(BatchDownloadSession job) {
                    if (batchSession != job) return;
                    batchSession = null;
                    if (batchDialog != null) { batchDialog.dismiss(); batchDialog = null; }
                    if (dead || isFinishing()) return;
                    openDownloads(); downloadGroupId = series.id; downloadGroupTitle = series.title; renderDownloads();
                    StringBuilder summary = new StringBuilder(job.summary()).append("\n\n已加入的任务可在离线下载页查看。");
                    List<String> details = job.details();
                    for (int i = 0; i < Math.min(details.size(), 20); i++) summary.append("\n").append(details.get(i));
                    if (details.size() > 20) summary.append("\n…其余 ").append(details.size() - 20).append(" 项");
                    error("批量处理结果", summary.toString());
                }
            });
        batchDialog = new AlertDialog.Builder(this).setTitle("批量下载")
            .setMessage("正在准备加入队列…")
            .setNegativeButton("停止加入", (d, w) -> {
                int added = batchSession == null ? 0 : batchSession.added();
                cancelBatchDownloads(); notice("已停止加入，已加入的 " + added + " 项会继续下载");
            }).create();
        batchDialog.setCanceledOnTouchOutside(false);
        batchDialog.setOnCancelListener(d -> cancelBatchDownloads());
        batchDialog.show();
        batchSession.start();
    }

    private void cancelBatchDownloads() {
        BatchDownloadSession active = batchSession; batchSession = null;
        if (batchDialog != null) { batchDialog.dismiss(); batchDialog = null; }
        if (active != null) active.cancel();
    }

    private Models.Series findSeries(String id) {
        for (Models.Series item : store.listSeries()) if (item.id.equals(id)) return item;
        return null;
    }
    private boolean hasDownload(String episodeId) {
        for (MediaRepository.OfflineItem item : media.downloads())
            if (item.complete && item.episodeId.equals(episodeId)) return true;
        return false;
    }
    private List<Models.Quality> downloadedQualities(String episodeId) {
        List<Models.Quality> result = new ArrayList<>();
        for (MediaRepository.OfflineItem item : media.downloads())
            if (item.complete && item.episodeId.equals(episodeId)) result.add(item.quality);
        return result;
    }
    private static String stableId(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (Exception e) { return Integer.toHexString(input.hashCode()); }
    }

    private interface Receiver<T> { void accept(T value) throws Exception; }
    private <T> void runTask(String message, Callable<T> task, Receiver<T> receiver) {
        runTask(message, -1, task, receiver);
    }
    private <T> void runTask(String message, int expectedSelection, Callable<T> task, Receiver<T> receiver) {
        final int statusToken = ++taskStatusToken;
        status.setText(message); status.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                T value = task.call();
                handler.post(() -> {
                    if (dead || isFinishing()) return;
                    if (statusToken == taskStatusToken) status.setVisibility(View.GONE);
                    if (expectedSelection >= 0 && expectedSelection != selectionToken) return;
                    try { receiver.accept(value); } catch (Exception e) { error("操作失败", explain(e)); }
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (dead || isFinishing()) return;
                    if (statusToken == taskStatusToken) status.setVisibility(View.GONE);
                    if (expectedSelection >= 0 && expectedSelection != selectionToken) return;
                    error("未能完成", explain(e));
                });
            }
        });
    }

    private void title(String name, String subtitle) {
        TextView title = text(name, 24, TEXT); title.setTypeface(null, Typeface.BOLD); title.setPadding(0, dp(8), 0, dp(5)); content.addView(title);
        TextView detail = text(subtitle, 12, MUTED); detail.setPadding(0, 0, 0, dp(20)); content.addView(detail);
    }
    private void empty(String heading, String description, String action, Runnable callback) {
        LinearLayout box = card(); box.setPadding(dp(24), dp(36), dp(24), dp(32));
        TextView symbol = text("▶", 36, PURPLE); symbol.setGravity(Gravity.CENTER); box.addView(symbol);
        TextView name = text(heading, 20, TEXT); name.setGravity(Gravity.CENTER); name.setPadding(0, dp(18), 0, dp(12)); box.addView(name);
        TextView detail = text(description, 13, MUTED); detail.setGravity(Gravity.CENTER); detail.setLineSpacing(dp(5), 1); detail.setPadding(0, 0, 0, dp(24)); box.addView(detail);
        box.addView(button(action, true, v -> callback.run())); content.addView(box, gapLayout());
    }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private LinearLayout card() { LinearLayout layout = column(); layout.setPadding(dp(16), dp(18), dp(16), dp(14)); layout.setBackground(background(PANEL, 18)); return layout; }
    private TextView text(String value, float size, int color) { TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); return text; }
    private Button button(String value, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(value); button.setTextSize(12); button.setAllCaps(false);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(dp(42)); button.setMinimumHeight(dp(42));
        button.setTextColor(primary ? BG : TEXT); button.setBackground(background(primary ? PURPLE : PANEL_LIGHT, 11));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2); params.setMargins(dp(3), dp(3), dp(3), dp(3)); button.setLayoutParams(params);
        button.setOnClickListener(listener); return button;
    }
    private GradientDrawable background(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    private LinearLayout.LayoutParams gapLayout() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(12); return p; }
    private void addEqual(LinearLayout row, View child) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.setMargins(dp(3), dp(3), dp(3), dp(3)); row.addView(child, p); }
    private int dp(float value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private void notice(String message) { if (!dead) Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private void error(String title, String message) { if (!dead && !isFinishing()) new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了", null).show(); }
    private static String explain(Exception exception) { return MediaErrors.describe(exception); }
    private static Models.Episode findEpisode(Models.Series series, String id) { if (id == null) return null; for (Models.Episode episode : series.episodes) if (id.equals(episode.id)) return episode; return null; }
    private static String speedText(float rate) { return String.format(Locale.ROOT, "%s×", rate); }
    private static String time(long milliseconds) { long total = Math.max(0, milliseconds) / 1000; return total >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60) : String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60); }

    @Override protected void onResume() {
        super.onResume();
        applyPlaybackBrightness();
        applySystemBars();
        if (playerOverlay != null && player != null) playerOverlay.setActive(true);
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applySystemBars();
        else if (playerOverlay != null) playerOverlay.cancelInteraction();
    }
    @Override protected void onPause() {
        cancelBatchDownloads();
        if (playerOverlay != null) playerOverlay.setActive(false);
        restoreWindowBrightness();
        savePosition(); if (player != null) player.pause(); super.onPause();
    }
    @Override protected void onDestroy() {
        cancelBatchDownloads();
        savePosition(); playerOverlay.dispose(); dead = true; ++selectionToken; handler.removeCallbacksAndMessages(null); io.shutdownNow();
        if (player != null) { playerView.setPlayer(null); player.release(); player = null; }
        super.onDestroy();
    }
}
