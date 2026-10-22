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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
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
    private LinearLayout root, header, body, nav, playerPanel, playerTools, content;
    private FrameLayout videoFrame;
    private ScrollView scroll;
    private TextView status, nowTitle, nowSubtitle, speedLabel, tagline;
    private Button loginButton, addButton;
    private PlayerView playerView;
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
            return insets;
        });
        root.requestApplyInsets();
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
        nowTitle = text("", 16, TEXT); nowTitle.setTypeface(null, Typeface.BOLD);
        nowTitle.setPadding(dp(16), dp(8), dp(16), dp(3));
        nowSubtitle = text("", 12, MUTED); nowSubtitle.setPadding(dp(16), 0, dp(16), dp(8));
        playerPanel.addView(nowTitle); playerPanel.addView(nowSubtitle);
        videoFrame = new FrameLayout(this); videoFrame.setBackgroundColor(Color.BLACK);
        videoFrame.setClipChildren(true);
        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        videoFrame.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        playerPanel.addView(videoFrame, new LinearLayout.LayoutParams(-1, dp(220)));
        playerTools = column(); playerTools.setPadding(dp(8), dp(4), dp(8), dp(4));
        LinearLayout firstTools = row();
        addEqual(firstTools, button("上一集", false, v -> adjacent(-1)));
        speedLabel = button("1.0×", false, v -> chooseSpeed()); addEqual(firstTools, speedLabel);
        addEqual(firstTools, button("下一集", false, v -> adjacent(1)));
        addEqual(firstTools, button("全屏", false, v -> setFullScreen(!fullScreen)));
        playerTools.addView(firstTools);
        LinearLayout secondTools = row();
        addEqual(secondTools, button("缩小 −", false, v -> setZoom(zoom - .25f)));
        addEqual(secondTools, button("适应", false, v -> { zoom = 1; applyZoom(); playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT); }));
        addEqual(secondTools, button("铺满", false, v -> { zoom = 1; applyZoom(); playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM); }));
        addEqual(secondTools, button("放大 ＋", false, v -> setZoom(zoom + .25f)));
        playerTools.addView(secondTools);
        playerPanel.addView(playerTools);
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
        closePlayback();
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
        closePlayback();
        renderSeriesContent(series);
        scroll.scrollTo(0, 0);
    }

    /** Render the episode list belonging to the active playback without closing it. */
    private void renderSeriesContent(Models.Series series) {
        ++selectionToken;
        downloadsPage = false; seriesPage = true; currentSeries = series; content.removeAllViews();
        content.addView(button("‹  回到首页", false, v -> renderLibrary()));
        title(series.title, series.episodes.size() + " 集  ·  点击剧集选择播放或下载");
        String lastId = store.getLastEpisode(series.id);
        Models.Episode resume = findEpisode(series, lastId);
        if (resume != null) {
            Store.Progress p = store.getProgress(resume.id);
            content.addView(button("继续第 " + resume.index + " 集 · " + time(p.positionMs), true, v -> selectEpisode(resume)), gapLayout());
        }
        for (Models.Episode episode : series.episodes) {
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
            TextView play = text(selected ? "已选择" : "›", 14, AQUA);
            line.addView(play); card.addView(line); card.setOnClickListener(v -> selectEpisode(episode));
            content.addView(card, gapLayout());
        }
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
        ++selectionToken; savePosition(); sourceReady = false;
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
            speedLabel.setText(speedText(next.getPlaybackParameters().speed));
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
                }
                @Override public void onPlaybackStateChanged(int state) {
                    if (player != next) return;
                    if (state == Player.STATE_READY) { sourceReady = true; applyZoom(); savePosition(); }
                    if (state == Player.STATE_ENDED) { savePosition(); notice("本集已看完，可点击下一集继续"); }
                }
            });
            next.prepare(); next.play();
            playerPanel.setVisibility(View.VISIBLE);
            nowTitle.setText(episode.seriesTitle + " · 第 " + episode.index + " 集");
            nowSubtitle.setText(episode.title + "  ·  " + (quality == null ? "本地文件" : quality.label + (offline ? " · 离线" : " · 在线")));
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
        ++selectionToken;
        ++taskStatusToken;
        status.setVisibility(View.GONE);
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

    private void chooseSpeed() {
        if (player == null) return;
        float[] rates = {.5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f};
        String[] names = new String[rates.length];
        for (int i = 0; i < rates.length; i++) names[i] = speedText(rates[i]);
        new AlertDialog.Builder(this).setTitle("播放速度").setItems(names, (d, i) -> {
            store.setSpeed(rates[i]);
            if (player != null) player.setPlaybackSpeed(rates[i]); speedLabel.setText(names[i]);
        }).show();
    }

    private void setZoom(float value) { zoom = Math.max(.5f, Math.min(3f, value)); applyZoom(); notice("画面缩放 " + Math.round(zoom * 100) + "%"); }
    private void applyZoom() {
        View surface = playerView.getVideoSurfaceView();
        if (surface != null) { surface.setScaleX(zoom); surface.setScaleY(zoom); }
    }

    private void setFullScreen(boolean value) {
        fullScreen = value;
        // Home remains reachable even when playback failed in full screen.
        header.setVisibility(View.VISIBLE);
        header.setPadding(dp(20), dp(value ? 4 : 18), dp(20), dp(value ? 4 : 12));
        tagline.setVisibility(value ? View.GONE : View.VISIBLE);
        loginButton.setVisibility(value ? View.GONE : View.VISIBLE);
        addButton.setVisibility(value ? View.GONE : View.VISIBLE);
        body.setVisibility(value ? View.GONE : View.VISIBLE);
        nav.setVisibility(value ? View.GONE : View.VISIBLE);
        nowTitle.setVisibility(value ? View.GONE : View.VISIBLE); nowSubtitle.setVisibility(value ? View.GONE : View.VISIBLE);
        if (value) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
        root.requestApplyInsets();
        resizeVideo();
    }

    private void resizeVideo() {
        if (fullScreen) {
            playerPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
            videoFrame.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            playerPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            int width = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            videoFrame.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.min(width * 9 / 16, screenHeight / 3)));
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) { super.onConfigurationChanged(configuration); resizeVideo(); }
    @Override public void onBackPressed() {
        if (fullScreen) { setFullScreen(false); return; }
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
        closePlayback();
        renderDownloads();
        scroll.scrollTo(0, 0);
    }

    private void renderDownloads() {
        downloadsPage = true; seriesPage = false; lastRender = System.currentTimeMillis(); content.removeAllViews();
        title("离线下载", "完整下载后，即可在无网络时播放");
        List<MediaRepository.OfflineItem> entries = media.downloads();
        if (entries.isEmpty()) { empty("还没有下载任务", "进入一部番剧，选择剧集和清晰度，\n即可加入下载队列。", "去番剧库", this::renderLibrary); return; }
        for (MediaRepository.OfflineItem entry : entries) {
            LinearLayout card = card();
            TextView name = text(entry.episode.seriesTitle + " · 第 " + entry.episode.index + " 集", 16, TEXT);
            name.setTypeface(null, Typeface.BOLD); card.addView(name);
            card.addView(text(entry.quality.label + "  ·  " + entry.status, 12, AQUA));
            final String diagnostic = entry.errorDetail == null || entry.errorDetail.trim().isEmpty()
                ? "此任务尚未记录具体错误。请重新解析重试，失败后可查看新的诊断。" : entry.errorDetail;
            if (entry.failed) {
                TextView detail = text(diagnostic, 12, MUTED);
                detail.setMaxLines(3);
                detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
                detail.setPadding(0, dp(5), 0, dp(5));
                card.addView(detail);
            }
            if (entry.percent >= 0 && !entry.complete) card.addView(text(String.format(Locale.CHINA, "%.0f%%", entry.percent), 12, MUTED));
            LinearLayout actions = row();
            if (entry.complete) addEqual(actions, button("离线播放", true, v -> play(entry.episode, entry.quality, true)));
            else if (entry.failed) addEqual(actions, button("重新解析重试", true, v -> {
                String cookie = CookieManager.getInstance().getCookie("https://api.bilibili.com");
                runTask("正在刷新下载地址…", () -> new BiliClient(cookie).refresh(entry.episode, entry.quality.id), quality -> {
                    media.enqueue(entry.episode, quality); media.resumeAll();
                    if (downloadsPage) renderDownloads();
                    notice("已重新加入下载队列");
                });
            }));
            else addEqual(actions, button(entry.paused ? "继续全部" : "暂停全部", false, v -> {
                try { if (entry.paused) media.resumeAll(); else media.pauseAll(); renderDownloads(); } catch (Exception e) { error("操作失败", explain(e)); }
            }));
            if (entry.failed) addEqual(actions, button("失败详情", false, v -> new AlertDialog.Builder(this)
                .setTitle("下载失败详情").setMessage(diagnostic)
                .setPositiveButton("知道了", null)
                .setNeutralButton("复制诊断", (d, w) -> copyDiagnostic(diagnostic)).show()));
            addEqual(actions, button("删除", false, v -> new AlertDialog.Builder(this).setTitle("删除这份离线视频？")
                .setMessage("观看记录会保留。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> {
                    try { media.remove(entry); renderDownloads(); } catch (Exception e) { error("删除失败", explain(e)); }
                }).show()));
            card.addView(actions); content.addView(card, gapLayout());
        }
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

    @Override protected void onPause() { savePosition(); if (player != null) player.pause(); super.onPause(); }
    @Override protected void onDestroy() {
        savePosition(); dead = true; ++selectionToken; handler.removeCallbacksAndMessages(null); io.shutdownNow();
        if (player != null) { playerView.setPlayer(null); player.release(); player = null; }
        super.onDestroy();
    }
}
