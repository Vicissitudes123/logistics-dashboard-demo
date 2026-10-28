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
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import android.widget.Switch;
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
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
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
    private int accentColor = 0xFF8DB7FF;
    private static final int AQUA = Color.rgb(119, 224, 210);
    private static final int PICK_VIDEO = 101;
    private static final int EXPORT_BACKUP = 103, IMPORT_BACKUP = 104, AUTO_BACKUP_FOLDER = 105;
    private static final int MAX_BACKUP_BYTES = 4 * 1024 * 1024;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final ExecutorService coverMetadata = Executors.newSingleThreadExecutor();
    private LinearLayout root, header, body, nav, playerPanel, content, selectionBar;
    private LinearLayout mainArea, workspace, downloadMaster;
    private ScrollView downloadMasterScroll;
    private boolean navigationRail, expandedWindow, layingOut;
    private int layoutWidth = -1, layoutHeight = -1, layoutState = -1;
    private String downloadMasterFingerprint = "";
    private Bundle pendingTabletState;
    private boolean playWhenReadyBeforePause;
    private int orientationBeforeFullScreen = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
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
    private volatile int selectionToken;
    private int taskStatusToken;
    private float zoom = 1f;
    private long lastRender, requestedResumeMs;
    private String downloadGroupId, downloadGroupTitle = "";
    private int downloadMutationGeneration;
    private BatchDownloadSession batchSession;
    private AlertDialog batchDialog;
    private AlertDialog downloadPreparationDialog;
    private java.util.concurrent.atomic.AtomicBoolean downloadPreparationCancelled;
    private boolean episodeSelectionMode, downloadSelectionMode;
    private String selectionSeriesId;
    private final Set<String> selectedEpisodeIds = new LinkedHashSet<>();
    private final Set<String> selectedDownloadKeys = new LinkedHashSet<>();
    private float originalWindowBrightness = -1f, playbackBrightness = -1f;
    private boolean settingsPage;
    private EditText librarySearch;
    private LinearLayout libraryResults;
    private String libraryQuery = "";
    private LinearLayout libraryFilters;
    private int libraryLayoutWidth;
    private final List<Button> navigationButtons = new ArrayList<>();
    private CoverLoader coverLoader;
    private SeekPreviewLoader seekPreviewLoader;
    private final Set<String> coverLookups = new LinkedHashSet<>();
    private final java.util.Map<String, ImageView> libraryCovers = new LinkedHashMap<>();
    private final java.util.Map<String, Models.Series> libraryCoverSeries = new LinkedHashMap<>();
    private final java.util.Map<String, String> loadedCovers = new LinkedHashMap<>();
    private volatile boolean backupBusy;
    private static final class BackupMessage extends Exception {
        BackupMessage(String message) { super(message); }
    }
    private boolean resumed, autoNextQueued, resumeNavigation;
    private int playbackNavigationToken;
    interface QualityResolver { List<Models.Quality> load(Models.Episode episode, String cookie) throws Exception; }
    private QualityResolver qualityResolver = (episode, cookie) -> new BiliClient(cookie).qualities(episode);
    interface PlayerFactory { ExoPlayer create(); }
    private PlayerFactory playerFactory = () -> new ExoPlayer.Builder(this).build();
    interface UpdateResolver { UpdateChecker.Release check(int currentCode, int sdk) throws Exception; }
    private UpdateResolver updateResolver = UpdateChecker::check;
    private boolean updateChecking, refreshingSeries;
    private android.app.Dialog episodeSidebar;
    private final Set<String> integrityBusy = new LinkedHashSet<>();
    private AutoBackup autoBackup;
    private final SleepTimer sleepTimer = new SleepTimer();
    private final Runnable sleepTick = new Runnable() { @Override public void run() {
        if (dead) return;
        if (sleepTimer.consumeTime(android.os.SystemClock.elapsedRealtime())) stopForSleepTimer();
        handler.postDelayed(this, 1000);
    }};
    private boolean playbackChoicePending;
    private AlertDialog playbackQualityDialog;
    private final SkipPlaybackPolicy skipPolicy = new SkipPlaybackPolicy();
    private LinearLayout skipNotice;
    private TextView skipNoticeText;
    private Button skipNoticeAction;
    private boolean skipNoticeIsIntro;
    private long skippedIntroFrom, skipNoticeUntil;
    private boolean skipDialogOpen;
    private final Runnable skipTicker = new Runnable() {
        @Override public void run() {
            if (dead || !resumed || player == null) return;
            updateSkipPlayback(); handler.postDelayed(this, 500);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (dead) return;
            savePosition();
            if (downloadsPage && !fullScreen && System.currentTimeMillis() - lastRender > 1500) renderDownloads();
            handler.postDelayed(this, 3000);
        }
    };

    @Override public void onCreate(Bundle state) {
        store = new Store(this);
        applyTheme();
        super.onCreate(state);
        originalWindowBrightness = getWindow().getAttributes().screenBrightness;
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        media = MediaRepository.get(this);
        autoBackup = AutoBackup.get(this);
        coverLoader = new CoverLoader(this);
        seekPreviewLoader = new SeekPreviewLoader();
        buildUi();
        handler.post(ticker); handler.post(sleepTick);
        renderLibrary();
        if (state != null && state.getBoolean("tabletState", false)) pendingTabletState = new Bundle(state);
        if (getIntent().getBooleanExtra("openDownloads", false)) { pendingTabletState = null; openDownloads(); }
    }

    private void buildUi() {
        root = new LinearLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                configureTabletLayout(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
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
        header = column(); header.setPadding(dp(20), dp(10), dp(20), dp(8));
        LinearLayout brand = row();
        LinearLayout identity = column();
        TextView name = text("番匣", 26, TEXT); name.setTypeface(null, Typeface.BOLD);
        identity.addView(name);
        tagline = text("喜欢的故事，接着看", 10, MUTED); identity.addView(tagline);
        brand.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        loginButton = button("B站登录", false, v -> {
            ++selectionToken;
            startActivity(new Intent(this, LoginActivity.class));
        });
        loginButton.setBackground(background(PANEL, 22));
        loginButton.setContentDescription("B站登录");
        brand.addView(loginButton);
        addButton = button("＋ 添加", true, v -> addMenu());
        addButton.setContentDescription("添加番剧");
        brand.addView(addButton);
        header.addView(brand);
        root.addView(header);
        mainArea = row(); mainArea.setGravity(Gravity.TOP);
        root.addView(mainArea, new LinearLayout.LayoutParams(-1, 0, 1));
        workspace = column(); workspace.setContentDescription("平板内容区");
        mainArea.addView(workspace, new LinearLayout.LayoutParams(0, -1, 1));

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
            @Override public float holdSpeed() { return store.getHoldSpeed(); }
            @Override public int controlsTimeoutMs() { return store.getControlsTimeoutMs(); }
            @Override public int accentColor() { return accentColor; }
            @Override public float brightness() { return currentPlaybackBrightness(); }
            @Override public void setBrightness(float value) { setPlaybackBrightness(value); }
            @Override public void setTemporarySpeed(float rate) { setTemporaryPlaybackSpeed(rate); }
            @Override public void togglePlayback() { MainActivity.this.togglePlayback(); }
            @Override public void seekTo(long position) {
                if (!canSeek()) return;
                skipPolicy.onManualSeek(); hideSkipNotice();
                player.seekTo(Math.max(0, Math.min(player.getDuration(), position)));
                savePosition();
            }
            @Override public void setSpeed(float rate) { setPlaybackSpeed(rate); }
            @Override public int gestureMask() { return store.getGestureMask(); }
            @Override public int seekSeconds() { return store.getSeekSeconds(); }
            @Override public void chooseSpeed() { MainActivity.this.chooseSpeed(); }
            @Override public void chooseEpisode() { chooseEpisodeMenu(); }
            @Override public void previousEpisode() { adjacent(-1); }
            @Override public void nextEpisode() { adjacent(1); }
            @Override public boolean canPrevious() { return adjacentEpisode(-1) != null; }
            @Override public boolean canNext() { return adjacentEpisode(1) != null; }
            @Override public String qualityLabel() { return currentQualityLabel(); }
            @Override public void chooseMore() { showCompactPlaybackMenu(); }
            @Override public void chooseQuality() { choosePlaybackQuality(); }
            @Override public void requestSeekPreview(long positionMs, java.util.function.Consumer<android.graphics.Bitmap> result) {
                if (seekPreviewLoader != null && player != null && sourceReady)
                    seekPreviewLoader.request(positionMs, result::accept);
                else result.accept(null);
            }
            @Override public void cancelSeekPreview() { if (seekPreviewLoader != null) seekPreviewLoader.cancel(); }
            @Override public void chooseDisplay() { chooseDisplayMenu(); }
            @Override public void back() { onBackPressed(); }
            @Override public void home() { renderLibrary(); }
            @Override public void toggleFullScreen() { setFullScreen(!fullScreen); }
        });
        videoFrame.addView(playerOverlay, new FrameLayout.LayoutParams(-1, -1));
        skipNotice = row(); skipNotice.setPadding(dp(12), dp(4), dp(4), dp(4));
        skipNotice.setBackground(background(0xDD191D2C, 12)); skipNotice.setVisibility(View.GONE);
        skipNoticeText = text("", 13, TEXT); skipNotice.addView(skipNoticeText);
        skipNoticeAction = button("本集不跳过", false, v -> cancelEpisodeSkip());
        skipNoticeAction.setContentDescription("取消本集自动跳过"); skipNotice.addView(skipNoticeAction);
        FrameLayout.LayoutParams skipParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        skipParams.topMargin = dp(72); videoFrame.addView(skipNotice, skipParams);
        playerPanel.addView(videoFrame, new LinearLayout.LayoutParams(-1, dp(220)));
        workspace.addView(playerPanel);
        downloadMasterScroll = new ScrollView(this); downloadMasterScroll.setFillViewport(true);
        downloadMasterScroll.setContentDescription("平板离线番剧目录"); downloadMasterScroll.setVisibility(View.GONE);
        downloadMaster = column(); downloadMaster.setPadding(dp(12), dp(12), dp(8), dp(12));
        downloadMasterScroll.addView(downloadMaster); workspace.addView(downloadMasterScroll);

        body = column(); workspace.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        status = text("", 12, AQUA); status.setPadding(dp(20), dp(5), dp(20), dp(5));
        status.setVisibility(View.GONE); body.addView(status);
        scroll = new ScrollView(this); scroll.setFillViewport(true);
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> loadVisibleLibraryCovers());
        content = column(); content.setPadding(dp(20), dp(10), dp(20), dp(20));
        scroll.addView(content); body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        selectionBar = column(); selectionBar.setPadding(dp(12), dp(6), dp(12), dp(8));
        selectionBar.setBackgroundColor(PANEL); selectionBar.setVisibility(View.GONE);
        selectionBar.setContentDescription("固定多选操作栏"); root.addView(selectionBar);
        nav = row(); nav.setPadding(dp(12), dp(4), dp(12), dp(4)); nav.setBackgroundColor(BG);
        addNavigationButton("首页", HomeArtwork.Symbol.LIBRARY, () -> renderLibrary());
        addNavigationButton("离线下载", HomeArtwork.Symbol.DOWNLOAD, () -> openDownloads());
        addNavigationButton("设置", HomeArtwork.Symbol.SETTINGS, () -> renderSettings());
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
        clearListSelection(); closePlayback();
        downloadGroupId = null; currentSeries = null;
        settingsPage = false; downloadsPage = false; seriesPage = false;
        refreshNavigation();
        content.removeAllViews(); scroll.scrollTo(0, 0);
        LinearLayout searchRow = row(); searchRow.setBackground(background(PANEL, 18));
        searchRow.setPadding(dp(12), 0, dp(4), 0);
        ImageView searchSymbol = new ImageView(this); searchSymbol.setImageDrawable(homeIcon(HomeArtwork.Symbol.SEARCH, MUTED, 21));
        searchRow.addView(searchSymbol, new LinearLayout.LayoutParams(dp(24), dp(24)));
        librarySearch = new EditText(this);
        librarySearch.setSingleLine(true); librarySearch.setTextSize(15);
        librarySearch.setTextColor(TEXT); librarySearch.setHintTextColor(MUTED);
        librarySearch.setHint("搜索片名，找到想看的番"); librarySearch.setContentDescription("搜索番剧");
        librarySearch.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        librarySearch.setBackgroundColor(Color.TRANSPARENT);
        librarySearch.setPadding(dp(10), dp(10), dp(4), dp(10));
        librarySearch.setText(libraryQuery);
        searchRow.addView(librarySearch, new LinearLayout.LayoutParams(0, dp(52), 1));
        ImageView clear = homeIconButton(HomeArtwork.Symbol.CLOSE, "清空搜索", v -> librarySearch.setText(""));
        clear.setAlpha(.65f); searchRow.addView(clear, new LinearLayout.LayoutParams(dp(44), dp(48)));
        content.addView(searchRow, gapLayout());
        libraryFilters = row(); libraryFilters.setPadding(dp(3), dp(3), dp(3), dp(3));
        libraryFilters.setBackground(background(PANEL, 16)); content.addView(libraryFilters, gapLayout());
        renderLibraryFilters();
        Button refresh = button("刷新番剧 · 检查新集", false, v -> refreshSeries(store.listVisibleSeries()));
        refresh.setContentDescription("刷新全部番剧"); content.addView(refresh, gapLayout());
        libraryResults = column(); content.addView(libraryResults);
        libraryLayoutWidth = availableLibraryWidth();
        libraryResults.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            if (width > 0 && width != libraryLayoutWidth) {
                libraryLayoutWidth = width;
                view.post(() -> { if (!dead && view == libraryResults) renderLibraryResults(); });
            }
        });
        librarySearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable value) {
                libraryQuery = value.toString(); renderLibraryResults(); scroll.scrollTo(0, 0);
            }
        });
        librarySearch.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            dismissLibraryKeyboard(); return true;
        });
        renderLibraryResults();
        root.setFocusableInTouchMode(true); root.requestFocus();
    }

    private void renderLibraryResults() {
        if (libraryResults == null || settingsPage || seriesPage || downloadsPage) return;
        for (ImageView image : libraryCovers.values()) coverLoader.clear(image);
        libraryResults.removeAllViews();
        libraryCovers.clear(); libraryCoverSeries.clear(); loadedCovers.clear();
        List<Models.Series> visible = store.listVisibleSeries();
        List<Models.Series> eligible = new ArrayList<>();
        String filter = store.getLibraryFilter();
        Set<String> offlineSeries = new LinkedHashSet<>();
        if ("offline".equals(filter)) for (MediaRepository.OfflineItem item : media.downloads())
            if (item.complete && item.episode != null) offlineSeries.add(item.episode.seriesId);
        for (Models.Series entry : visible) {
            if ("unfinished".equals(filter) && store.isSeriesWatched(entry)) continue;
            if ("watched".equals(filter) && !store.isSeriesWatched(entry)) continue;
            if ("offline".equals(filter) && !offlineSeries.contains(entry.id)) continue;
            eligible.add(entry);
        }
        LibraryCatalogue.Sections sections = LibraryCatalogue.build(eligible, libraryQuery,
            store.getRecentVisibleSeriesId(), store::isPinned);
        LinearLayout collection = row(); collection.setPadding(dp(2), dp(8), dp(2), dp(14));
        TextView heading = text("我的番剧", 23, TEXT); heading.setTypeface(null, Typeface.BOLD);
        collection.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView count = text(sections.total + " 部", 12, MUTED); collection.addView(count); libraryResults.addView(collection);
        if (sections.total == 0) {
            LinearLayout box = card();
            box.addView(text(visible.isEmpty() ? "还没有显示在首页的番剧" : "没有找到匹配的番剧", 17, TEXT));
            TextView note = text(visible.isEmpty() ? "添加新的番剧，或到设置恢复已移除的番剧。"
                : "all".equals(filter) ? "试试片名中的几个字，例如「高木」。" : "当前筛选下没有匹配内容。可切换到全部，或清空搜索。", 13, MUTED);
            note.setPadding(0, dp(10), 0, dp(12)); box.addView(note);
            box.addView(button(visible.isEmpty() ? "添加番剧" : "显示全部番剧", true,
                v -> { if (visible.isEmpty()) addMenu(); else {
                    store.setLibraryFilter("all"); renderLibraryFilters(); librarySearch.setText(""); renderLibraryResults();
                } }));
            libraryResults.addView(box, gapLayout()); return;
        }
        if (sections.recent != null) {
            librarySection("最近观看"); addLibraryCard(sections.recent);
        }
        if (!sections.pinned.isEmpty()) {
            librarySection("置顶番剧"); addLibraryGrid(sections.pinned);
        }
        if (!sections.others.isEmpty()) {
            librarySection("其他番剧"); addLibraryGrid(sections.others);
        }
        libraryResults.post(this::loadVisibleLibraryCovers);
    }

    private void renderLibraryFilters() {
        if (libraryFilters == null) return;
        libraryFilters.removeAllViews();
        String[] values = {"all", "unfinished", "watched", "offline"};
        String[] labels = {"全部", "未看完", "已看完", "有离线"};
        for (int i = 0; i < values.length; i++) {
            final String value = values[i];
            boolean active = value.equals(store.getLibraryFilter());
            Button choice = button(labels[i], active, v -> {
                store.setLibraryFilter(value); renderLibraryFilters(); renderLibraryResults();
            });
            choice.setContentDescription("首页筛选" + labels[i]); choice.setSelected(active);
            choice.setTextSize(12); choice.setPadding(dp(2), dp(8), dp(2), dp(8));
            choice.setBackground(background(active ? accentColor : Color.TRANSPARENT, 13));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
            params.setMargins(dp(1), 0, dp(1), 0); libraryFilters.addView(choice, params);
        }
    }

    private void librarySection(String label) {
        TextView heading = text(label, 14, MUTED); heading.setTypeface(null, Typeface.BOLD);
        heading.setPadding(dp(2), dp(2), 0, dp(10)); libraryResults.addView(heading);
    }

    private void addLibraryCard(Models.Series entry) {
        LinearLayout card = column(); card.setContentDescription("番剧：" + entry.id);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable backdrop = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF252F45, PANEL});
        backdrop.setCornerRadius(dp(20)); backdrop.setStroke(dp(1), 0xFF303B52); card.setBackground(backdrop);
        LinearLayout identity = row(); identity.setGravity(Gravity.TOP);
        int posterWidth = availableLibraryWidth() >= dp(600) ? dp(168) : availableLibraryWidth() < dp(320) ? dp(104) : dp(118);
        int posterHeight = Math.round(posterWidth * 1.5f);
        FrameLayout poster = libraryPoster(entry);
        LinearLayout.LayoutParams posterSize = new LinearLayout.LayoutParams(posterWidth, posterHeight); posterSize.rightMargin = dp(14);
        identity.addView(poster, posterSize); LinearLayout labels = column();
        TextView title = text(entry.title, 19, TEXT); title.setTypeface(null, Typeface.BOLD); title.setMaxLines(3);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END); labels.addView(title);
        String lastId = store.getLastEpisode(entry.id);
        Models.Episode episode = findEpisode(entry, lastId);
        if (episode == null && !entry.episodes.isEmpty()) episode = entry.episodes.get(0);
        boolean watched = store.isSeriesWatched(entry);
        String detail = entry.episodes.size() + " 集" + (watched ? " · 已看完" : lastId == null || lastId.isEmpty() ? " · 未开始" : " · 观看中")
            + (store.isPinned(entry.id) ? " · 已置顶" : "");
        TextView subtitle = text(detail + newEpisodeBadge(entry), 11, MUTED); subtitle.setPadding(0, dp(7), 0, dp(8)); labels.addView(subtitle);
        if (episode != null && lastId != null && !lastId.isEmpty()) {
            Store.Progress progress = store.getProgress(episode.id);
            TextView position = text("第 " + episode.index + " 集  ·  " + time(progress.positionMs), 12, TEXT);
            position.setPadding(0, dp(3), 0, dp(7)); labels.addView(position);
            android.widget.ProgressBar progressLine = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progressLine.setMax(1000); progressLine.setProgress(progress.durationMs <= 0 ? 0 : (int) Math.min(1000, progress.positionMs * 1000d / progress.durationMs));
            progressLine.setProgressTintList(ColorStateList.valueOf(accentColor)); progressLine.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF3C465C));
            progressLine.setContentDescription("观看进度：" + entry.title);
            labels.addView(progressLine, new LinearLayout.LayoutParams(-1, dp(4)));
        }
        LinearLayout actions = row(); actions.setPadding(0, dp(10), 0, 0); Models.Episode resume = episode;
        Button play = button(lastId == null || lastId.isEmpty() ? "开始观看" : "继续观看", true, v -> {
            renderSeries(entry); if (resume != null) selectEpisode(resume);
        });
        play.setContentDescription((lastId == null || lastId.isEmpty() ? "开始观看：" : "继续观看：") + entry.id);
        play.setTextSize(12); play.setPadding(dp(6), dp(8), dp(6), dp(8));
        actions.addView(play, new LinearLayout.LayoutParams(0, dp(44), 1));
        ImageView manage = homeIconButton(HomeArtwork.Symbol.MORE, "管理番剧：" + entry.id, v -> showLibraryActions(entry));
        actions.addView(manage, new LinearLayout.LayoutParams(dp(42), dp(44)));
        labels.addView(actions); identity.addView(labels, new LinearLayout.LayoutParams(0, -2, 1)); card.addView(identity);
        card.setOnClickListener(v -> renderSeries(entry));
        card.setOnLongClickListener(v -> { showLibraryActions(entry); return true; });
        libraryResults.addView(card, gapLayout());
    }

    private void addLibraryGrid(List<Models.Series> entries) {
        int available = availableLibraryWidth();
        int columns = Math.max(1, Math.min(available >= dp(600) ? 6 : 4, (available + dp(12)) / dp(available >= dp(600) ? 176 : 152)));
        int cellWidth = (available - (columns - 1) * dp(12)) / columns;
        LinearLayout line = null;
        for (int i = 0; i < entries.size(); i++) {
            if (i % columns == 0) { line = row(); line.setGravity(Gravity.TOP); libraryResults.addView(line, gapLayout()); }
            Models.Series entry = entries.get(i);
            LinearLayout card = column(); card.setPadding(dp(8), dp(8), dp(8), dp(10));
            card.setBackground(background(PANEL, 17)); card.setContentDescription("番剧：" + entry.id);
            FrameLayout poster = libraryPoster(entry);
            card.addView(poster, new LinearLayout.LayoutParams(-1, Math.round((cellWidth - dp(16)) * 1.4f)));
            TextView title = text(entry.title, 15, TEXT); title.setTypeface(null, Typeface.BOLD);
            title.setMaxLines(2); title.setMinLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setPadding(dp(3), dp(10), dp(3), 0); card.addView(title);
            String lastId = store.getLastEpisode(entry.id);
            boolean started = lastId != null && !lastId.isEmpty();
            Models.Episode resume = findEpisode(entry, lastId);
            if (resume == null && !entry.episodes.isEmpty()) resume = entry.episodes.get(0);
            LinearLayout detailRow = row();
            String metadata = entry.episodes.size() + " 集 · " + (store.isSeriesWatched(entry) ? "已看完" : started ? "观看中" : "未开始");
            TextView detail = text(metadata + newEpisodeBadge(entry), 11, MUTED); detail.setPadding(dp(3), 0, 0, 0); detail.setMaxLines(2);
            detailRow.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
            ImageView more = homeIconButton(HomeArtwork.Symbol.MORE, "管理番剧：" + entry.id, v -> showLibraryActions(entry));
            detailRow.addView(more, new LinearLayout.LayoutParams(dp(40), dp(42))); card.addView(detailRow);
            final Models.Episode target = resume;
            Button watch = button(started ? "继续观看" : "开始观看", false, v -> { renderSeries(entry); if (target != null) selectEpisode(target); });
            watch.setContentDescription((started ? "继续观看：" : "开始观看：") + entry.id);
            watch.setTextColor(accentColor); watch.setTextSize(12); watch.setBackground(background(PANEL_LIGHT, 10));
            watch.setCompoundDrawables(homeIcon(HomeArtwork.Symbol.PLAY, accentColor, 13), null, null, null);
            card.addView(watch, new LinearLayout.LayoutParams(-1, dp(48)));
            card.setOnClickListener(v -> renderSeries(entry)); card.setOnLongClickListener(v -> { showLibraryActions(entry); return true; });
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
            if (i % columns > 0) cell.leftMargin = dp(12); line.addView(card, cell);
        }
        if (line != null) for (int i = entries.size() % columns; i > 0 && i < columns; i++) {
            View spacer = new View(this); LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, 1, 1);
            cell.leftMargin = dp(12); line.addView(spacer, cell);
        }
    }

    private int availableLibraryWidth() {
        if (libraryResults != null && libraryResults.getWidth() > 0) return libraryResults.getWidth();
        int width = root != null && root.getWidth() > 0 ? root.getWidth() - root.getPaddingLeft() - root.getPaddingRight()
            : getResources().getDisplayMetrics().widthPixels;
        return Math.max(dp(200), width - dp(40));
    }

    private FrameLayout libraryPoster(Models.Series entry) {
        FrameLayout poster = new FrameLayout(this);
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF354560, 0xFF172132});
        background.setCornerRadius(dp(12)); poster.setBackground(background); poster.setClipToOutline(true);
        TextView fallback = text(entry.title.isEmpty() ? "番" : entry.title.substring(0, 1), 36, accentColor);
        fallback.setGravity(Gravity.CENTER); poster.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        ImageView cover = new ImageView(this); cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setContentDescription("封面：" + entry.title); poster.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        libraryCovers.put(entry.id, cover); libraryCoverSeries.put(entry.id, entry); return poster;
    }

    private android.graphics.drawable.Drawable homeIcon(HomeArtwork.Symbol symbol, int color, int sizeDp) {
        HomeArtwork icon = new HomeArtwork(symbol, color); icon.setBounds(0, 0, dp(sizeDp), dp(sizeDp)); return icon;
    }

    private ImageView homeIconButton(HomeArtwork.Symbol symbol, String description, View.OnClickListener action) {
        ImageView image = new ImageView(this); image.setImageDrawable(homeIcon(symbol, MUTED, 22));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setPadding(dp(11), dp(11), dp(11), dp(11));
        image.setContentDescription(description); image.setClickable(true); image.setFocusable(true);
        image.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x338DB7FF), null, background(Color.WHITE, 20)));
        image.setOnClickListener(action); return image;
    }

    private void addNavigationButton(String label, HomeArtwork.Symbol symbol, Runnable action) {
        Button item = button(label, false, v -> { action.run(); refreshNavigation(); });
        item.setTag(symbol); item.setContentDescription("导航" + label); item.setTextSize(10);
        item.setPadding(dp(4), dp(7), dp(4), dp(5)); item.setCompoundDrawablePadding(dp(4));
        item.setBackgroundColor(Color.TRANSPARENT); navigationButtons.add(item);
        addEqual(nav, item);
    }

    private void refreshNavigation() {
        if (root != null) root.requestLayout();
        int selected = settingsPage ? 2 : downloadsPage ? 1 : 0;
        for (int i = 0; i < navigationButtons.size(); i++) {
            Button item = navigationButtons.get(i); boolean current = i == selected;
            item.setSelected(current); item.setTextColor(current ? accentColor : MUTED);
            item.setCompoundDrawables(null, homeIcon((HomeArtwork.Symbol) item.getTag(), current ? accentColor : MUTED, 23), null, null);
            item.setBackground(background(current ? PANEL : Color.TRANSPARENT, 14));
        }
    }

    private void loadVisibleLibraryCovers() {
        if (dead || settingsPage || downloadsPage || seriesPage) return;
        for (java.util.Map.Entry<String, ImageView> entry : libraryCovers.entrySet()) {
            if (entry.getValue().getGlobalVisibleRect(new android.graphics.Rect()))
                loadLibraryCover(libraryCoverSeries.get(entry.getKey()), entry.getValue());
        }
    }

    private void loadLibraryCover(Models.Series series, ImageView cover) {
        if (series == null) return;
        if (!Models.safeCoverUrl(series.coverUrl).isEmpty()) {
            if (!series.coverUrl.equals(loadedCovers.get(series.id))) {
                loadedCovers.put(series.id, series.coverUrl); coverLoader.load(cover, series.coverUrl);
            }
            return;
        }
        boolean online = false;
        for (Models.Episode episode : series.episodes) if (downloadable(episode)) { online = true; break; }
        if (!online || !coverLookups.add(series.id)) return;
        coverMetadata.execute(() -> {
            try {
                String url = new BiliClient("").resolveCover(series);
                if (url.isEmpty()) return;
                handler.post(() -> {
                    if (dead) return;
                    store.updateSeriesCover(series.id, url);
                    Models.Series shown = libraryCoverSeries.get(series.id);
                    if (shown != null) shown.coverUrl = url;
                    ImageView target = libraryCovers.get(series.id);
                    if (target != null && !settingsPage && !downloadsPage && !seriesPage
                            && target.getGlobalVisibleRect(new android.graphics.Rect())) loadLibraryCover(shown, target);
                });
            } catch (Exception ignored) { /* A missing poster never blocks the local library. */ }
        });
    }

    private void showLibraryActions(Models.Series series) {
        dismissLibraryKeyboard();
        new AlertDialog.Builder(this).setTitle(series.title)
            .setItems(new String[]{store.isPinned(series.id) ? "取消置顶" : "置顶", "从首页移除",
                store.isSeriesWatched(series) ? "标记为未看完" : "标记为已看完", "刷新剧集"}, (dialog, index) -> {
                if (index == 0) {
                    store.setPinned(series.id, !store.isPinned(series.id)); renderLibraryResults();
                } else if (index == 1) confirmHideSeries(series);
                else if (index == 2) { store.setSeriesWatched(series.id, !store.isSeriesWatched(series)); renderLibraryResults(); }
                else refreshSeries(java.util.Collections.singletonList(series));
            }).setNegativeButton("取消", null).show();
    }

    private void confirmHideSeries(Models.Series series) {
        new AlertDialog.Builder(this).setTitle("从首页移除这部番剧？")
            .setMessage(series.title + "\n\n保留已下载视频和观看记录，可在「设置 → 已移除番剧」恢复。")
            .setNegativeButton("取消", null).setPositiveButton("移除", (dialog, which) -> {
                store.hideSeries(series.id); renderLibraryResults(); notice("已从首页移除");
            }).show();
    }

    private void dismissLibraryKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null && librarySearch != null) keyboard.hideSoftInputFromWindow(librarySearch.getWindowToken(), 0);
    }

    private void applyTheme() {
        ThemePalette palette = ThemePalette.get(store.getThemeId());
        accentColor = palette.accent;
        int style;
        switch (palette.id) {
            case "purple": style = R.style.AppTheme_Purple; break;
            case "teal": style = R.style.AppTheme_Teal; break;
            case "green": style = R.style.AppTheme_Green; break;
            case "orange": style = R.style.AppTheme_Orange; break;
            case "pink": style = R.style.AppTheme_Pink; break;
            default: style = R.style.AppTheme_Blue;
        }
        setTheme(style);
        getTheme().applyStyle(style, true);
        if (addButton != null) addButton.setBackground(background(accentColor, 11));
        if (librarySearch != null) librarySearch.setBackgroundTintList(ColorStateList.valueOf(accentColor));
        if (playerOverlay != null) playerOverlay.refreshTheme();
        refreshNavigation();
    }

    private void renderSettings() {
        dismissLibraryKeyboard(); clearListSelection(); closePlayback();
        settingsPage = true; downloadsPage = false; seriesPage = false; downloadGroupId = null;
        refreshNavigation();
        content.removeAllViews(); scroll.scrollTo(0, 0);
        title("设置", "外观、播放习惯和设备内的数据管理");
        settingRow("主题色", ThemePalette.get(store.getThemeId()).name + " · 点击更换按钮和进度条颜色", "主题色设置", this::chooseTheme);
        settingRow("常用倍速", speedText(store.getSpeed()) + " · 打开视频时使用", "常用倍速设置", this::chooseDefaultSpeed);
        settingRow("长按加速", speedText(store.getHoldSpeed()) + " · 松手恢复原速，原速更高时保持原速", "长按加速设置", this::chooseHoldSpeed);
        int timeout = store.getControlsTimeoutMs();
        settingRow("控制栏自动隐藏", timeout == 0 ? "不自动隐藏 · 仍可轻点画面收起" : (timeout / 1000f) + " 秒", "控制栏自动隐藏设置", this::chooseControlsTimeout);
        settingRow("自动播放下一集", store.isAutoNext() ? "已开启 · 当前集结束后继续" : "已关闭 · 点击开启", "自动连播设置", () -> {
            store.setAutoNext(!store.isAutoNext()); renderSettings();
        });
        settingRow("全局默认画质", (store.isRememberQuality() ? "已开启" : "已关闭") + " · " + preferredQualityText()
            + "\n首次观看新番时优先使用。每部番剧会单独沿用上次成功播放的画质和在线 / 离线模式。", "画质偏好设置", this::chooseQualityPreference);
        settingRow("仅 Wi-Fi 下载", (store.isWifiOnlyDownloads() ? "已开启 · 切到移动网络后等待 Wi-Fi" : "已关闭 · 允许使用移动数据下载")
            + "\n" + media.downloadNetworkInfo().summary, "仅Wi-Fi下载设置", this::chooseDownloadNetwork);
        settingRow("手势设置", "独立开关、快进灵敏度 · 横滑一屏最多 " + store.getSeekSeconds() + " 秒", "手势设置", this::chooseGestures);
        settingRow("下载空间保护", "不足 200 MB 自动暂停；清理至至少 300 MB 后手动继续", "下载空间保护", () -> StorageCleanup.show(this, store, media, this::confirmRemoveDownloads));
        settingRow("定时停止", sleepTimer.label(android.os.SystemClock.elapsedRealtime()), "定时停止设置", this::chooseSleepTimer);
        settingRow("自动备份", autoBackup.status(), "自动备份设置", this::automaticBackupMenu);
        settingRow("存储清理", "按占用排序，筛选已看完视频与多个版本；保留观看记录", "存储清理", () -> StorageCleanup.show(this, store, media, this::confirmRemoveDownloads));
        settingRow("刷新番剧", "检查已导入番剧的新集，保留本机观看进度", "刷新番剧设置", () -> refreshSeries(store.listVisibleSeries()));
        settingRow("检查更新", "当前版本 " + installedVersionName() + " · 查看新版说明与安装包", "检查更新", this::checkForUpdates);
        settingRow("导出片库备份", "保存番剧、观看进度、置顶和设置；不含账号与视频文件。", "导出片库备份", this::exportBackupMenu);
        settingRow("从备份恢复", "合并片库并保留本机较新进度；导入前可查看摘要。", "从备份恢复", this::importBackupMenu);
        settingRow("已移除番剧", store.listHiddenSeries().size() + " 部 · 点击恢复到首页", "已移除番剧", this::restoreHiddenSeriesMenu);
        long bytes = 0; int complete = 0;
        List<MediaRepository.OfflineItem> items = media.downloads();
        for (MediaRepository.OfflineItem item : items) {
            bytes += Math.max(0, item.bytesDownloaded);
            if (item.complete) complete++;
        }
        settingRow("离线视频", "已完成 " + complete + " 份 / 共 " + items.size() + " 个任务 · 约 "
            + android.text.format.Formatter.formatShortFileSize(this, bytes) + "\n点击查看与管理；卸载或清除应用数据会删除下载。", "管理离线视频", this::openDownloads);
        settingRow("重置外观和播放设置", "恢复默认蓝色、1.0× 常用倍速、2.0× 长按加速及 3.5 秒自动隐藏", "重置设置", this::confirmResetSettings);
        TextView note = text("番匣 " + installedVersionName() + "\n设置仅保存在本机。重置设置不会清除番剧、置顶、下载、观看记录或登录状态。", 12, MUTED);
        note.setPadding(dp(6), dp(8), dp(6), dp(16)); content.addView(note);
    }

    private void stopForSleepTimer() {
        autoNextQueued = false;
        // Invalidate any next-episode resolution already queued at the deadline.
        ++selectionToken; playbackNavigationToken = selectionToken;
        if (player != null) { player.pause(); savePosition(); playerOverlay.showControls(); }
        notice("定时停止已生效，观看进度已保存");
    }

    private void chooseSleepTimer() {
        new AlertDialog.Builder(this).setTitle("定时停止 · " + sleepTimer.label(android.os.SystemClock.elapsedRealtime()))
            .setItems(new String[]{"15 分钟后停止", "30 分钟后停止", "60 分钟后停止", "本集结束后停止", "关闭定时停止"}, (dialog, which) -> {
                if (which < 3) sleepTimer.minutes(new int[]{15, 30, 60}[which], android.os.SystemClock.elapsedRealtime());
                else if (which == 3) sleepTimer.afterEpisode(); else sleepTimer.cancel();
                notice(which == 4 ? "已关闭定时停止" : sleepTimer.label(android.os.SystemClock.elapsedRealtime()) + "，优先于自动连播");
                if (settingsPage) renderSettings();
            }).setNegativeButton("取消", null).show();
    }

    private void automaticBackupMenu() {
        new AlertDialog.Builder(this).setTitle("自动备份")
            .setItems(autoBackup.enabled() ? new String[]{"立即备份", "更换备份文件夹", "关闭自动备份"} : new String[]{"选择备份文件夹"}, (dialog, which) -> {
                if (autoBackup.enabled() && which == 0) { savePosition(); autoBackup.requestNow(); notice("正在备份，可稍后在设置查看结果"); }
                else if (autoBackup.enabled() && which == 2) { autoBackup.disable(); renderSettings(); }
                else {
                    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    try { startActivityForResult(pick, AUTO_BACKUP_FOLDER); }
                    catch (Exception failure) { error("无法选择文件夹", "请检查系统文件管理器是否可用"); }
                }
            }).setNegativeButton("取消", null).show();
    }

    private String newEpisodeBadge(Models.Series series) {
        int count = store.newEpisodeCount(series.id); return count > 0 ? " · 新增 " + count + " 集" : "";
    }

    private void refreshSeries(List<Models.Series> series) {
        if (refreshingSeries) { notice("正在检查新集，请稍候"); return; }
        if (series.isEmpty()) { notice("暂无可刷新的番剧"); return; }
        refreshingSeries = true;
        final String cookie = CookieManager.getInstance().getCookie("https://api.bilibili.com");
        final List<Models.Series> snapshot = new ArrayList<>(series);
        notice("正在检查新集，完成后会显示新增数量");
        io.execute(() -> {
            List<Models.Series> results = new ArrayList<>(); int failed = 0, skipped = 0;
            for (Models.Series entry : snapshot) {
                boolean online = false; for (Models.Episode episode : entry.episodes) online |= downloadable(episode);
                if (!online) { skipped++; continue; }
                try { results.add(new BiliClient(cookie).refreshSeries(entry)); }
                catch (Exception failure) { failed++; }
                if (Thread.currentThread().isInterrupted()) return;
            }
            int errors = failed, local = skipped;
            handler.post(() -> {
                refreshingSeries = false; if (dead) return;
                int added = 0, failedMerge = 0;
                for (Models.Series fresh : results) try { added += store.mergeRefreshedSeries(fresh); } catch (RuntimeException failure) { failedMerge++; }
                if (settingsPage) renderSettings();
                else if (seriesPage && currentSeries != null) {
                    Models.Series latest = findSeries(currentSeries.id);
                    if (latest != null) { int y = scroll.getScrollY(); renderSeriesContent(latest); scroll.scrollTo(0, y); if (player != null) playbackNavigationToken = selectionToken; }
                } else if (!downloadsPage && libraryResults != null) renderLibraryResults();
                error("刷新完成", "新增 " + added + " 集 · 刷新成功 " + (results.size() - failedMerge) + " 部"
                    + (errors + failedMerge > 0 ? "\n未完成 " + (errors + failedMerge) + " 部，可检查网络后重试" : "")
                    + (local > 0 ? "\n跳过本地视频 " + local + " 部" : "") + "\n观看进度和离线视频已保留。");
            });
        });
    }

    private void requestDownloadNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 106);
        }
    }

    private void showPendingDownloads() {
        List<PendingDownloads.Job> jobs = PendingDownloads.list(this);
        StringBuilder detail = new StringBuilder("已确认的任务独立保存在本机。系统结束后台服务后，再次打开应用会继续未处理的任务。\n");
        for (int i = 0; i < Math.min(jobs.size(), 20); i++) {
            PendingDownloads.Job job = jobs.get(i);
            detail.append("\n第 ").append(job.episode.index).append(" 集 · ").append(job.profile.label).append(" · ")
                .append(job.error.isEmpty() ? "等待加入" : job.error);
        }
        new AlertDialog.Builder(this).setTitle("后台批量任务 · " + jobs.size() + " 集").setMessage(detail.toString())
            .setPositiveButton("继续 / 重试", (d, w) -> {
                try { PendingDownloads.retry(this); media.resumeAll(); DownloadQueueService.start(this); renderDownloads(); }
                catch (Exception failure) { error("暂时无法启动", "队列已保留，请稍后重新打开应用重试。"); }
            }).setNeutralButton("停止未加入的任务", (d, w) -> new AlertDialog.Builder(this).setTitle("停止后台排队？")
                .setMessage("仅停止尚未加入下载器的任务，已加入的下载继续保留。").setNegativeButton("取消", null)
                .setPositiveButton("停止", (a, b) -> { PendingDownloads.clear(this); renderDownloads(); }).show())
            .setNegativeButton("关闭", null).show();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (intent.getBooleanExtra("openDownloads", false)) openDownloads();
    }

    private void settingRow(String heading, String detail, String description, Runnable action) {
        LinearLayout box = card(); box.setContentDescription(description);
        LinearLayout line = row();
        TextView name = text(heading, 17, TEXT); name.setTypeface(null, Typeface.BOLD);
        line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(text("›", 24, accentColor)); box.addView(line);
        TextView subtitle = text(detail, 13, MUTED); subtitle.setPadding(0, dp(7), 0, dp(3));
        box.addView(subtitle); box.setOnClickListener(v -> action.run());
        content.addView(box, gapLayout());
    }

    private String installedVersionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException ignored) { return "未知"; }
    }

    private void chooseDownloadNetwork() {
        if (!store.isWifiOnlyDownloads()) {
            store.setWifiOnlyDownloads(true); media.applyDownloadNetworkPolicy(); renderSettings(); return;
        }
        new AlertDialog.Builder(this).setTitle("允许使用移动数据下载？")
            .setMessage("高清视频可能消耗大量流量。关闭后，当前队列和后续下载都可使用移动数据，可能产生运营商费用。\n\n此设置只限制视频下载，在线播放与读取剧集信息仍会使用当前网络。")
            .setNegativeButton("保持仅 Wi-Fi", null).setPositiveButton("允许移动数据", (dialog, which) -> {
                store.setWifiOnlyDownloads(false); media.applyDownloadNetworkPolicy(); renderSettings();
            }).show();
    }

    private void checkForUpdates() {
        if (updateChecking) { notice("正在检查更新，请稍候"); return; }
        final int token = selectionToken;
        final int updateStatusToken = ++taskStatusToken;
        int code;
        try { code = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; }
        catch (PackageManager.NameNotFoundException ignored) { error("检查更新失败", "无法读取当前应用版本"); return; }
        final int currentCode = code;
        updateChecking = true;
        status.setText("正在检查更新…"); status.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                UpdateChecker.Release release = updateResolver.check(currentCode, Build.VERSION.SDK_INT);
                handler.post(() -> {
                    updateChecking = false;
                    if (!dead && updateStatusToken == taskStatusToken) status.setVisibility(View.GONE);
                    if (dead || isFinishing() || !settingsPage || token != selectionToken) return;
                    showUpdateRelease(release);
                });
            } catch (Exception failure) {
                handler.post(() -> {
                    updateChecking = false;
                    if (!dead && updateStatusToken == taskStatusToken) status.setVisibility(View.GONE);
                    if (dead || isFinishing() || !settingsPage || token != selectionToken) return;
                    error("检查更新失败", failure instanceof UpdateChecker.UpdateException ? failure.getMessage()
                        : "暂时无法读取更新信息，请检查网络后重试。当前版本仍可继续使用。");
                });
            }
        });
    }

    private void showUpdateRelease(UpdateChecker.Release release) {
        if (!release.isNewer) { error("检查更新", "当前版本 " + installedVersionName() + " 暂无可用更新。"); return; }
        StringBuilder details = new StringBuilder("当前 ").append(installedVersionName())
            .append(" → 新版 ").append(release.versionName).append("\n");
        for (String note : release.notes) details.append("\n• ").append(note);
        if (!release.isCompatible) {
            details.append("\n\n此版本需要 Android API ").append(release.minSdk).append(" 或更高版本，当前系统暂不支持。");
            error("发现新版本", details.toString()); return;
        }
        details.append("\n\n安装包约 ").append(android.text.format.Formatter.formatShortFileSize(this, release.apkBytes))
            .append("。点击后由浏览器下载，再由你确认安装。覆盖安装可保留数据。若 APK 下载卡住，可选择 ZIP，解压后安装。");
        new AlertDialog.Builder(this).setTitle("发现新版 " + release.versionName).setMessage(details.toString())
            .setNegativeButton("稍后", null).setPositiveButton("下载 APK", (d, w) -> openUpdateDownload(release.apkUrl))
            .setNeutralButton("下载 ZIP", (d, w) -> openUpdateDownload(release.zipUrl)).show();
    }

    private void openUpdateDownload(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception unavailable) { error("无法打开下载链接", "没有可处理此链接的浏览器，请安装或启用浏览器后重试。"); }
    }

    private void chooseTheme() {
        List<ThemePalette> palettes = ThemePalette.entries();
        String[] labels = new String[palettes.size()]; int selected = 0;
        for (int i = 0; i < palettes.size(); i++) {
            labels[i] = palettes.get(i).name;
            if (palettes.get(i).id.equals(store.getThemeId())) selected = i;
        }
        new AlertDialog.Builder(this).setTitle("主题色")
            .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                store.setThemeId(palettes.get(which).id); applyTheme();
                dialog.dismiss(); renderSettings();
            }).setNegativeButton("取消", null).show();
    }

    private void chooseDefaultSpeed() {
        float[] values = {.25f, .5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f};
        String[] labels = new String[values.length]; int selected = 3;
        for (int i = 0; i < values.length; i++) { labels[i] = speedText(values[i]); if (values[i] == store.getSpeed()) selected = i; }
        new AlertDialog.Builder(this).setTitle("常用倍速")
            .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                setPlaybackSpeed(values[which]); dialog.dismiss(); renderSettings();
            }).setNegativeButton("取消", null).show();
    }

    private void chooseHoldSpeed() {
        float[] values = {1.5f, 2f, 2.5f, 3f}; String[] labels = new String[values.length]; int selected = 1;
        for (int i = 0; i < values.length; i++) { labels[i] = speedText(values[i]); if (values[i] == store.getHoldSpeed()) selected = i; }
        new AlertDialog.Builder(this).setTitle("长按临时加速")
            .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                store.setHoldSpeed(values[which]); dialog.dismiss(); renderSettings();
            }).setNegativeButton("取消", null).show();
    }

    private void chooseControlsTimeout() {
        int[] values = {3500, 5000, 8000, 0}; String[] labels = {"3.5 秒（默认）", "5 秒", "8 秒", "不自动隐藏"}; int selected = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == store.getControlsTimeoutMs()) selected = i;
        new AlertDialog.Builder(this).setTitle("控制栏自动隐藏")
            .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                store.setControlsTimeoutMs(values[which]); playerOverlay.showControls();
                dialog.dismiss(); renderSettings();
            }).setNegativeButton("取消", null).show();
    }

    private void restoreHiddenSeriesMenu() {
        List<Models.Series> hidden = store.listHiddenSeries();
        if (hidden.isEmpty()) { error("已移除番剧", "没有已移除的番剧。"); return; }
        String[] titles = new String[hidden.size()];
        for (int i = 0; i < hidden.size(); i++) titles[i] = hidden.get(i).title;
        new AlertDialog.Builder(this).setTitle("选择要恢复的番剧")
            .setItems(titles, (dialog, which) -> {
                store.restoreSeries(hidden.get(which).id); notice("已恢复到首页");
                if (settingsPage) renderSettings(); else renderLibraryResults();
            }).setNegativeButton("取消", null).show();
    }

    private void confirmResetSettings() {
        new AlertDialog.Builder(this).setTitle("恢复默认外观和播放设置？")
            .setMessage("不会清除番剧、置顶、下载、观看记录或登录状态。")
            .setNegativeButton("取消", null).setPositiveButton("恢复默认", (dialog, which) -> {
                store.resetAppearanceAndPlaybackSettings(); media.applyDownloadNetworkPolicy(); applyTheme(); renderSettings();
            }).show();
    }

    private String preferredQualityText() {
        Models.Quality quality = store.getPreferredQuality();
        return quality == null ? "尚未记录，播放时选择一次即可" : quality.label;
    }

    private void chooseQualityPreference() {
        new AlertDialog.Builder(this).setTitle("画质偏好")
            .setItems(new String[]{store.isRememberQuality() ? "关闭记忆" : "开启记忆", "清除已记住的画质"}, (dialog, which) -> {
                if (which == 0) store.setRememberQuality(!store.isRememberQuality());
                else store.clearPreferredQuality();
                renderSettings();
            }).setNegativeButton("取消", null).show();
    }

    private void exportBackupMenu() {
        if (backupBusy) { notice("正在处理备份，请稍候"); return; }
        closePlayback();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "番匣备份-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".json");
        try { startActivityForResult(intent, EXPORT_BACKUP); }
        catch (Exception e) { error("无法选择备份位置", explain(e)); }
    }

    private void importBackupMenu() {
        if (backupBusy) { notice("正在处理备份，请稍候"); return; }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, IMPORT_BACKUP); }
        catch (Exception e) { error("无法选择备份文件", explain(e)); }
    }

    private void handleBackupResult(int request, Uri uri) {
        if (uri == null || backupBusy) return;
        backupBusy = true;
        if (request == EXPORT_BACKUP) {
            runTask("正在写入备份…", () -> {
                try {
                    byte[] bytes = store.exportBackup().getBytes(StandardCharsets.UTF_8);
                    try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                        if (output == null) throw new IOException("无法写入所选位置");
                        output.write(bytes); output.flush();
                    }
                    return bytes.length;
                } catch (Exception failure) {
                    throw new BackupMessage("备份未能完成，请检查所选位置是否可写、设备空间是否充足。未完成的文件不能用于恢复。");
                } finally { backupBusy = false; }
            }, size -> notice("片库备份已保存 · " + android.text.format.Formatter.formatShortFileSize(this, size)));
        } else {
            runTask("正在检查备份…", () -> {
                try {
                    byte[] bytes;
                    try (InputStream input = getContentResolver().openInputStream(uri)) {
                        if (input == null) throw new IOException("无法读取所选文件");
                        bytes = readBackupBytes(input);
                    }
                    String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                    try { return store.inspectBackup(json); }
                    catch (IllegalArgumentException invalid) { throw new BackupMessage(invalid.getMessage()); }
                } catch (BackupMessage message) { throw message; }
                catch (Exception failure) {
                    throw new BackupMessage("无法读取这份备份。请选择番匣导出的 JSON 文件，文件需小于 4 MB 且使用 UTF-8 编码。");
                } finally { backupBusy = false; }
            }, this::previewBackup);
        }
    }

    static byte[] readBackupBytes(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] chunk = new byte[8192]; int count;
        while ((count = input.read(chunk)) != -1) {
            if (count == 0) continue;
            if (bytes.size() + count > MAX_BACKUP_BYTES) throw new IOException("备份文件超过 4 MB，请选择番匣导出的 JSON 备份");
            bytes.write(chunk, 0, count);
        }
        return bytes.toByteArray();
    }

    private void previewBackup(LibraryBackup.Snapshot snapshot) {
        new AlertDialog.Builder(this).setTitle("恢复这份片库备份？")
            .setMessage(snapshot.summary() + "\n\n合并番剧，保留本机较新的观看进度。备份中的置顶、移除、看完标记与设置将恢复。"
                + "\n不会导入账号或视频文件，也不会删除现有下载。本地视频及其观看记录不会从备份恢复，需重新导入文件。")
            .setNegativeButton("取消", null).setPositiveButton("恢复备份", (dialog, which) -> {
                if (backupBusy) return;
                closePlayback(); backupBusy = true;
                runTask("正在恢复片库…", () -> {
                    try { return store.importBackup(snapshot); }
                    catch (IllegalArgumentException | IllegalStateException failure) { throw new BackupMessage(failure.getMessage()); }
                    finally { backupBusy = false; }
                }, result -> {
                    media.applyDownloadNetworkPolicy(); applyTheme(); renderSettings();
                    error("恢复完成", result.summary());
                });
            }).show();
    }

    private void renderSeries(Models.Series series) {
        store.markEpisodesSeen(series.id);
        dismissLibraryKeyboard(); settingsPage = false;
        clearListSelection();
        closePlayback();
        renderSeriesContent(series);
        scroll.scrollTo(0, 0);
    }

    /** Render the episode list belonging to the active playback without closing it. */
    private void renderSeriesContent(Models.Series series) {
        if (downloadSelectionMode || (episodeSelectionMode && !series.id.equals(selectionSeriesId))) clearListSelection();
        ++selectionToken;
        settingsPage = false; downloadsPage = false; seriesPage = true; currentSeries = series; content.removeAllViews();
        content.addView(button("‹  回到首页", false, v -> renderLibrary()));
        title(series.title, series.episodes.size() + " 集  ·  点击播放，长按多选下载");
        Button skipSettings = button("片头片尾 · " + skipSettingsText(series.id), false, v -> showSkipSettings(series));
        skipSettings.setContentDescription("剧集片头片尾设置"); content.addView(skipSettings, gapLayout());
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
            TextView num = text(String.format(Locale.ROOT, "%02d", episode.index), 22, accentColor);
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
        if (selectionBar != null) { selectionBar.removeAllViews(); updateSelectionBarVisibility(); }
    }

    private void updateSelectionBarVisibility() {
        boolean selecting = episodeSelectionMode || downloadSelectionMode;
        if (selectionBar != null) selectionBar.setVisibility(!fullScreen && selecting ? View.VISIBLE : View.GONE);
        if (nav != null) nav.setVisibility(!fullScreen && !selecting ? View.VISIBLE : View.GONE);
        if (root != null) root.requestLayout();
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
        if (selected) shape.setStroke(dp(2), accentColor);
        card.setBackground(shape);
    }

    private static boolean downloadable(Models.Episode episode) {
        return episode.localUri == null || episode.localUri.isEmpty();
    }

    private void beginEpisodeSelection(Models.Series series, Models.Episode episode) {
        if (!downloadable(episode)) { notice("本地视频已经在本机，无需下载"); return; }
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
        selectionBar.removeAllViews();
        selectionBar.addView(text("已选 " + chosen.size() + " 集", 12, AQUA));
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
        selectionBar.addView(actions); updateSelectionBarVisibility();
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
        selectionBar.removeAllViews();
        selectionBar.addView(text("已选 " + chosen.size() + " 份视频", 12, AQUA));
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
        selectionBar.addView(actions); updateSelectionBarVisibility();
    }

    private void selectEpisode(Models.Episode episode) {
        final int token = ++selectionToken;
        if (episode.localUri != null && !episode.localUri.isEmpty()) { play(episode, null, false); return; }
        Models.Quality local = preferredOfflineQuality(episode, currentSeries != null && currentSeries.id.equals(episode.seriesId)
            ? continuationProfile() : null);
        if (local != null && !(currentOffline && sourceReady && currentEpisode != null && currentEpisode.id.equals(episode.id))) {
            play(episode, local, true); return;
        }
        if (player != null && sourceReady && currentEpisode != null && episode.id.equals(currentEpisode.id)
                && player.getPlayerError() == null && player.getPlaybackState() != Player.STATE_IDLE) {
            playbackNavigationToken = token; playerOverlay.showControls();
            if (player.getPlaybackState() == Player.STATE_ENDED) {
                startSkipSession(episode.seriesId, 0); player.seekTo(0);
            }
            player.play(); return;
        }
        if (player != null && sourceReady && currentEpisode != null && currentQuality != null
                && currentEpisode.seriesId.equals(episode.seriesId)) {
            openContinuation(episode, currentOffline, currentQuality); return;
        }
        Store.SeriesPlaybackPreference saved = store.getSeriesPlaybackPreference(episode.seriesId);
        if (saved != null) { openContinuation(episode, saved.offline, saved.quality); return; }
        if (hasDownload(episode.id)) {
            new AlertDialog.Builder(this).setTitle(episode.title)
                .setItems(new String[]{"播放已下载版本", "选择在线清晰度 / 下载其他版本"}, (d, choice) -> {
                    if (token != selectionToken) return;
                    if (choice == 0) chooseOffline(episode); else loadQualities(episode);
                }).show();
        } else {
            Models.Quality preferred = store.isRememberQuality() ? store.getPreferredQuality() : null;
            if (preferred != null) openContinuation(episode, false, preferred); else loadQualities(episode);
        }
    }

    private void loadQualities(Models.Episode episode) {
        int token = ++selectionToken;
        String cookie = CookieManager.getInstance().getCookie("https://api.bilibili.com");
        runTask("正在获取实际可用清晰度…", token, () -> qualityResolver.load(episode, cookie), qualities -> {
            if (token != selectionToken) return;
            if (qualities.isEmpty()) { error("没有可播放格式", "当前账号或这集内容未返回可用视频源。请先检查 B站登录及播放权限。"); return; }
            showQualityChoices(episode, qualities, token);
        });
    }

    private void showQualityChoices(Models.Episode episode, List<Models.Quality> qualities, int token) {
        List<Models.Quality> sorted = new ArrayList<>(qualities);
        Models.Quality preference = store.isRememberQuality() ? store.getPreferredQuality() : null;
        Models.Quality match = PlaybackPolicy.preferredQuality(sorted, preference);
        if (match != null) { sorted.remove(match); sorted.add(0, match); }
        String[] names = new String[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) names[i] = (sorted.get(i) == match ? "★ 已记住 · " : "") + sorted.get(i).label;
        new AlertDialog.Builder(this).setTitle("第 " + episode.index + " 集 · "
            + (preference != null && match == null ? "偏好画质不可用，请选择" : "选择清晰度"))
            .setItems(names, (dialog, selected) -> { if (token == selectionToken) qualityAction(episode, sorted.get(selected), token); })
            .setNegativeButton("取消", null).show();
    }

    private void qualityAction(Models.Episode episode, Models.Quality quality, int token) {
        new AlertDialog.Builder(this).setTitle(quality.label)
            .setItems(new String[]{"在线播放", "下载到本机"}, (dialog, action) -> {
                if (token != selectionToken) return;
                if (action == 0) play(episode, quality, false);
                else prepareDownloads(java.util.Collections.singletonList(episode), quality);
            }).setNegativeButton("取消", null).show();
    }

    /** A missing inherited profile asks for a replacement once, without asking the mode again. */
    private void showContinuationQualityChoices(Models.Episode episode, List<Models.Quality> choices, int token) {
        String[] names = new String[choices.size()];
        for (int i = 0; i < names.length; i++) names[i] = choices.get(i).label;
        new AlertDialog.Builder(this).setTitle("第 " + episode.index + " 集 · 在线画质")
            .setItems(names, (dialog, index) -> { if (token == selectionToken) play(episode, choices.get(index), false); })
            .setNegativeButton("取消", null).show();
    }

    private String currentQualityLabel() {
        if (currentEpisode != null && !downloadable(currentEpisode)) return "本地";
        if (currentQuality == null) return "清晰度";
        switch (currentQuality.qn) {
            case 6: return "240P";
            case 16: return "360P";
            case 32: return "480P";
            case 64: return "720P";
            case 74: return "720P60";
            case 80: return "1080P";
            case 112: return "1080P+";
            case 116: return "1080P60";
            case 120: return "4K";
            case 125: return "HDR";
            case 126: return "杜比视界";
            case 127: return "8K";
            default:
                if (currentQuality.height > 0) return currentQuality.height + "P";
                return "清晰度";
        }
    }

    private void showCompactPlaybackMenu() {
        if (player == null) return;
        playerOverlay.cancelInteraction();
        new AlertDialog.Builder(this).setTitle("播放设置")
            .setItems(new String[]{"倍速", "选集", "画面设置", "定时停止 · " + sleepTimer.label(android.os.SystemClock.elapsedRealtime())}, (dialog, which) -> {
                if (player == null) return;
                if (which == 0) chooseSpeed();
                else if (which == 1) chooseEpisodeMenu();
                else if (which == 2) chooseDisplayMenu();
                else chooseSleepTimer();
            }).setNegativeButton("关闭", null).show();
    }

    private void choosePlaybackQuality() {
        if (player == null || currentEpisode == null) return;
        if (!downloadable(currentEpisode)) { notice("本地文件使用原始画质"); return; }
        playerOverlay.cancelInteraction();
        final int token = ++selectionToken;
        final ExoPlayer expectedPlayer = player;
        final Models.Episode episode = currentEpisode;
        playbackChoicePending = true;
        if (currentOffline) showPlaybackQualityChoices(episode, downloadedQualities(episode.id), true, token, expectedPlayer);
        else loadPlaybackQualityChoices(episode, token, expectedPlayer);
    }

    private void loadPlaybackQualityChoices(Models.Episode episode, int token, ExoPlayer expectedPlayer) {
        String cookie = CookieManager.getInstance().getCookie("https://api.bilibili.com");
        final int statusToken = ++taskStatusToken;
        status.setText("正在读取清晰度…"); status.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                List<Models.Quality> choices = qualityResolver.load(episode, cookie);
                handler.post(() -> {
                    if (dead || isFinishing()) return;
                    if (statusToken == taskStatusToken) status.setVisibility(View.GONE);
                    if (!resumed || token != selectionToken || player != expectedPlayer || currentEpisode != episode) return;
                    showPlaybackQualityChoices(episode, choices, false, token, expectedPlayer);
                });
            } catch (Exception failure) {
                handler.post(() -> {
                    if (dead || isFinishing()) return;
                    if (statusToken == taskStatusToken) status.setVisibility(View.GONE);
                    if (!resumed || token != selectionToken || player != expectedPlayer || currentEpisode != episode) return;
                    finishPlaybackChoice(token, expectedPlayer);
                    error("未能读取清晰度", explain(failure));
                });
            }
        });
    }

    private void showPlaybackQualityChoices(Models.Episode episode, List<Models.Quality> choices,
                                            boolean offline, int token, ExoPlayer expectedPlayer) {
        if (token != selectionToken || currentEpisode != episode || player != expectedPlayer) return;
        List<Models.Quality> available = new ArrayList<>(choices);
        String[] labels = new String[available.size()];
        int selected = -1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = available.get(i).label;
            if (offline == currentOffline && DownloadCatalogue.sameProfile(available.get(i), currentQuality) && selected < 0) selected = i;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(offline ? "已下载画质" : "在线画质")
            .setNegativeButton("取消", null);
        if (available.isEmpty()) builder.setMessage(offline ? "这集没有完整下载的版本。" : "暂时没有获取到可用清晰度，当前播放保留。");
        else builder.setSingleChoiceItems(labels, selected, (dialog, index) -> {
            switchPlaybackQuality(episode, available.get(index), offline, token, expectedPlayer); dialog.dismiss();
        });
        builder.setNeutralButton(offline ? "切换在线" : "离线版本", (dialog, which) -> {
            if (token != selectionToken || player != expectedPlayer || currentEpisode != episode) return;
            int nextToken = ++selectionToken;
            if (offline) loadPlaybackQualityChoices(episode, nextToken, expectedPlayer);
            else showPlaybackQualityChoices(episode, downloadedQualities(episode.id), true, nextToken, expectedPlayer);
        });
        AlertDialog dialog = builder.create();
        playbackQualityDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (playbackQualityDialog == dialog) playbackQualityDialog = null;
            finishPlaybackChoice(token, expectedPlayer);
        });
        dialog.show();
    }

    private void switchPlaybackQuality(Models.Episode episode, Models.Quality quality, boolean offline,
                                       int token, ExoPlayer expectedPlayer) {
        if (dead || !resumed || token != selectionToken || player != expectedPlayer || currentEpisode != episode) return;
        if (offline && !media.isDownloaded(episode, quality)) {
            finishPlaybackChoice(token, expectedPlayer); error("离线文件未就绪", "该版本尚未完整下载，当前播放保留。"); return;
        }
        long position = sourceReady ? Math.max(0, expectedPlayer.getCurrentPosition()) : requestedResumeMs;
        boolean keepPlaying = expectedPlayer.getPlayWhenReady();
        play(episode, quality, offline, position);
        if (!keepPlaying && player != null) player.pause();
    }

    private void finishPlaybackChoice(int token, ExoPlayer expectedPlayer) {
        if (!dead && token == selectionToken && player == expectedPlayer) {
            playbackChoicePending = false;
            playbackNavigationToken = selectionToken; playerOverlay.refresh();
        }
    }

    private void dismissPlaybackQualityDialog() {
        AlertDialog dialog = playbackQualityDialog; playbackQualityDialog = null;
        if (dialog != null) dialog.dismiss();
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
        play(episode, quality, offline, resumeOverrideMs, true);
    }

    private void play(Models.Episode episode, Models.Quality quality, boolean offline, long resumeOverrideMs, boolean startPlaying) {
        boolean keepSkipDisabled = resumeOverrideMs >= 0 && currentEpisode != null
            && currentEpisode.id.equals(episode.id) && skipPolicy.isDisabledForSession();
        dismissPlaybackQualityDialog(); playbackChoicePending = false;
        clearListSelection();
        autoNextQueued = false;
        if (seekPreviewLoader != null) seekPreviewLoader.clearSource();
        ++selectionToken; playerOverlay.setActive(false); savePosition(); sourceReady = false;
        if (player != null) { playerView.setPlayer(null); player.release(); player = null; }
        currentEpisode = episode; currentQuality = quality; currentOffline = offline;
        Store.Progress progress = store.getProgress(episode.id);
        long resume = resumeOverrideMs >= 0 ? resumeOverrideMs : progress.positionMs;
        if (resumeOverrideMs < 0 && progress.durationMs > 0 && resume >= progress.durationMs - 2000) resume = 0;
        requestedResumeMs = Math.max(0, resume);
        startSkipSession(episode.seriesId, requestedResumeMs);
        if (keepSkipDisabled) skipPolicy.disableForSession();
        Models.Series found = findSeries(episode.seriesId);
        currentSeries = found;
        try {
            ExoPlayer next = playerFactory.create(); player = next;
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
                    if (state == Player.STATE_READY) {
                        sourceReady = true; applyZoom(); savePosition();
                        if (quality != null && downloadable(episode)) {
                            store.setSeriesPlaybackPreference(episode.seriesId, offline, quality);
                            if (store.isRememberQuality()) store.setPreferredQuality(quality);
                        }
                    }
                    if (state == Player.STATE_ENDED) { savePosition(); playerOverlay.showControls(); finishEpisode(next); }
                    playerOverlay.refresh();
                }
            });
            next.prepare(); if (startPlaying) next.play(); else next.pause();
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
            playbackNavigationToken = selectionToken;
            try { seekPreviewLoader.setSource(media.createPreviewSource(episode, quality, offline)); }
            catch (Exception unavailablePreview) { seekPreviewLoader.clearSource(); }
            handler.removeCallbacks(skipTicker); handler.post(skipTicker);
        } catch (Exception e) { showPlaybackFailure(e); }
    }

    private void finishEpisode(ExoPlayer ended) {
        if (dead || !resumed || ended != player || currentEpisode == null || !sourceReady || autoNextQueued) return;
        if (sleepTimer.consumeEpisodeEnd() || sleepTimer.consumeTime(android.os.SystemClock.elapsedRealtime())) { stopForSleepTimer(); return; }
        if (!store.isAutoNext()) { notice("本集已看完，可通过选集继续"); return; }
        if (selectionToken != playbackNavigationToken) return;
        Models.Episode next = PlaybackPolicy.nextEpisode(findSeries(currentEpisode.seriesId), currentEpisode);
        if (next == null) { notice("已经看完最后一集"); return; }
        final int token = selectionToken;
        final boolean offline = currentOffline;
        final Models.Quality profile = continuationProfile();
        autoNextQueued = true;
        handler.post(() -> {
            autoNextQueued = false;
            if (dead || !resumed || player != ended || token != selectionToken || !store.isAutoNext()) return;
            openContinuation(next, offline, profile);
        });
    }

    private Models.Quality continuationProfile() {
        if (sourceReady && currentQuality != null) return currentQuality;
        Store.SeriesPlaybackPreference saved = currentEpisode == null ? null
            : store.getSeriesPlaybackPreference(currentEpisode.seriesId);
        if (saved != null) return saved.quality;
        return store.isRememberQuality() ? store.getPreferredQuality() : currentQuality;
    }

    private void openContinuation(Models.Episode episode, boolean offline, Models.Quality profile) {
        final int token = ++selectionToken;
        if (!downloadable(episode)) { play(episode, null, false); return; }
        Models.Quality local = preferredOfflineQuality(episode, profile);
        if (local != null) { play(episode, local, true); return; }
        if (offline) {
            List<Models.Quality> saved = downloadedQualities(episode.id);
            Models.Quality matched = PlaybackPolicy.preferredQuality(saved, profile);
            if (matched != null) { play(episode, matched, true); return; }
            new AlertDialog.Builder(this).setTitle("离线连播已暂停")
                .setMessage(saved.isEmpty() ? "这集还没有完整下载。请下载后继续，或手动选择在线播放。"
                    : "这集没有下载相同画质。请选择其他已下载版本继续。")
                .setNegativeButton("取消", null)
                .setNeutralButton("改为在线播放", (dialog, which) -> {
                    if (token == selectionToken) openContinuation(episode, false, profile);
                })
                .setPositiveButton(saved.isEmpty() ? "知道了" : "选择离线版本", (dialog, which) -> {
                    if (!saved.isEmpty() && token == selectionToken) chooseOffline(episode);
                }).show();
            return;
        }
        String cookie = CookieManager.getInstance().getCookie("https://api.bilibili.com");
        runTask("正在准备第 " + episode.index + " 集…", token, () -> qualityResolver.load(episode, cookie), choices -> {
            if (!resumed || token != selectionToken) return;
            Models.Quality matched = PlaybackPolicy.preferredQuality(choices, profile);
            if (matched != null) { play(episode, matched, false); return; }
            if (choices.isEmpty()) { error("下一集暂时无法播放", "没有获取到可用视频源，请检查登录、网络或权限。"); return; }
            new AlertDialog.Builder(this).setTitle("请选择下一集画质")
                .setMessage(profile == null ? "还没有可用的画质偏好，请先选择一次。" : "下一集没有返回相同清晰度与编码，已暂停连播。可手动选择其他版本。")
                .setNegativeButton("取消", null).setPositiveButton("选择画质", (dialog, which) -> {
                    if (token == selectionToken) showContinuationQualityChoices(episode, choices, token);
                }).show();
        });
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
        dismissEpisodeSidebar();
        cancelDownloadPreparation();
        autoNextQueued = false;
        dismissPlaybackQualityDialog(); playbackChoicePending = false;
        if (seekPreviewLoader != null) seekPreviewLoader.clearSource();
        handler.removeCallbacks(skipTicker); skipPolicy.reset(); hideSkipNotice();
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
        Models.Episode target = adjacentEpisode(direction);
        if (target == null) { notice(direction > 0 ? "已经是最后一集" : "已经是第一集"); return; }
        selectEpisode(target);
    }

    private Models.Episode adjacentEpisode(int direction) {
        return currentEpisode == null ? null : PlaybackPolicy.adjacentEpisode(findSeries(currentEpisode.seriesId), currentEpisode, direction);
    }

    private void togglePlayback() {
        if (player == null) return;
        if (player.getPlaybackState() == Player.STATE_ENDED) {
            playbackNavigationToken = ++selectionToken; autoNextQueued = false;
            if (currentEpisode != null) startSkipSession(currentEpisode.seriesId, 0);
            player.seekTo(0); player.play();
        } else if (player.getPlayerError() != null) {
            showPlaybackFailure(player.getPlayerError());
        } else if (player.getPlayWhenReady()) player.pause();
        else {
            playbackNavigationToken = ++selectionToken; autoNextQueued = false;
            player.play();
        }
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

    private void dismissEpisodeSidebar() {
        android.app.Dialog dialog = episodeSidebar; episodeSidebar = null; if (dialog != null) dialog.dismiss();
    }

    private void chooseGestures() {
        if (playerOverlay != null) playerOverlay.cancelInteraction();
        LinearLayout form = column(); form.setPadding(dp(20), dp(8), dp(20), dp(8));
        String[] names = {"左侧上下滑动调亮度", "右侧上下滑动调音量", "左右滑动调整进度", "长按临时加速", "双击播放 / 暂停"};
        android.widget.CheckBox[] choices = new android.widget.CheckBox[names.length];
        for (int i = 0; i < names.length; i++) {
            android.widget.CheckBox check = new android.widget.CheckBox(this); check.setText(names[i]); check.setTextColor(TEXT); check.setMinHeight(dp(48));
            check.setChecked((store.getGestureMask() & (1 << i)) != 0); check.setContentDescription(names[i]); choices[i] = check; form.addView(check);
        }
        form.addView(text("快进灵敏度 · 横滑一屏最多跳过的时间", 13, MUTED));
        android.widget.RadioGroup sensitivity = new android.widget.RadioGroup(this);
        int[] seconds = {90, 180, 300}; String[] labels = {"精细 · 90 秒", "标准 · 180 秒", "快速 · 300 秒"};
        for (int i = 0; i < seconds.length; i++) {
            android.widget.RadioButton radio = new android.widget.RadioButton(this); radio.setId(seconds[i]); radio.setText(labels[i]); radio.setTextColor(TEXT);
            sensitivity.addView(radio); if (seconds[i] == store.getSeekSeconds()) sensitivity.check(seconds[i]);
        }
        form.addView(sensitivity); form.addView(text("单击控制栏、进度条和按钮仍可操作。长按的播放速度使用原来的「长按加速」设置。", 12, MUTED));
        ScrollView wrapper = new ScrollView(this); wrapper.addView(form);
        new AlertDialog.Builder(this).setTitle("手势设置").setView(wrapper).setNegativeButton("取消", null)
            .setNeutralButton("恢复默认", (d, w) -> { store.setGestures(31, 180); if (settingsPage) renderSettings(); })
            .setPositiveButton("保存", (d, w) -> {
                int mask = 0; for (int i = 0; i < choices.length; i++) if (choices[i].isChecked()) mask |= 1 << i;
                store.setGestures(mask, sensitivity.getCheckedRadioButtonId()); if (settingsPage) renderSettings(); notice("手势设置已保存");
            }).show();
    }

    private void checkIntegrity(MediaRepository.OfflineItem entry) {
        if (!integrityBusy.add(entry.key)) { notice("正在检查本集文件"); return; }
        int generation = downloadMutationGeneration;
        try {
            Callable<MediaRepository.IntegrityCheck> work = media.integrityCheck(entry, false, () -> dead || generation != downloadMutationGeneration);
            runTask("正在检查本机文件…", () -> { try { return work.call(); } finally { handler.post(() -> integrityBusy.remove(entry.key)); } }, result -> {
                if (!downloadExists(entry.key) || generation != downloadMutationGeneration) return;
                if (downloadsPage) renderDownloads();
                AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("离线文件检查").setMessage(result.summary()
                    + "\n\n本次只读取本机文件。首次校验建立本机摘要，无法判断此前已经存在的内容损坏，也不等于解码兼容性检查。")
                    .setNegativeButton("关闭", null);
                if (!result.ok()) dialog.setPositiveButton("修复本集", (d, w) -> confirmRepair(entry));
                dialog.show();
            });
        } catch (Exception failure) { integrityBusy.remove(entry.key); error("无法检查", "请等待本集下载完成后再检查。"); }
    }

    private void confirmRepair(MediaRepository.OfflineItem entry) {
        new AlertDialog.Builder(this).setTitle("修复这一集的离线文件？")
            .setMessage("重新获取相同画质地址，删除异常缓存并补下缺失部分，保留正常数据和观看记录。若源文件已更换，则重新下载本集。下载遵循仅 Wi-Fi 设置。")
            .setNegativeButton("取消", null).setPositiveButton("开始修复", (d, w) -> repairDownload(entry)).show();
    }

    private void repairDownload(MediaRepository.OfflineItem entry) {
        if (!integrityBusy.add(entry.key)) { notice("正在检查或修复本集"); return; }
        if (currentOffline && currentEpisode != null && currentQuality != null && entry.key.equals(MediaRepository.key(currentEpisode, currentQuality))) closePlayback();
        final int generation = downloadMutationGeneration;
        final String cookie = CookieManager.getInstance().getCookie("https://api.bilibili.com");
        try {
            Callable<MediaRepository.IntegrityCheck> work = media.integrityCheck(entry, true, () -> dead || generation != downloadMutationGeneration);
            runTask("正在检查并准备修复…", () -> {
                try {
                    Models.Quality refreshed = DownloadCatalogue.matching(qualityResolver.load(entry.episode, cookie), entry.quality);
                    if (refreshed == null) throw new IllegalStateException("原画质暂时不可用");
                    work.call(); return refreshed;
                } finally { handler.post(() -> integrityBusy.remove(entry.key)); }
            }, quality -> {
                if (generation != downloadMutationGeneration || !downloadExists(entry.key)) return;
                media.enqueue(entry.episode, quality); requestDownloadNotifications();
                if (downloadsPage) renderDownloads(); notice("已加入修复下载，观看记录已保留");
            });
        } catch (Exception failure) { integrityBusy.remove(entry.key); error("暂时无法修复", "请等待当前下载完成后，再检查或修复文件。"); }
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
        if (fullScreen) {
            dismissEpisodeSidebar(); playerOverlay.cancelInteraction();
            Set<String> downloaded = new LinkedHashSet<>();
            for (MediaRepository.OfflineItem item : media.downloads()) if (item.complete) downloaded.add(item.episodeId);
            episodeSidebar = EpisodeSidebar.show(this, series, currentEpisode.id, store, downloaded, accentColor,
                this::selectEpisode, () -> { episodeSidebar = null; applySystemBars(); });
            return;
        }
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
        new AlertDialog.Builder(this).setTitle("画面与播放")
            .setItems(new String[]{"适应画面", "铺满画面（裁切边缘）", "缩小 −", "放大 ＋", "恢复 100%", "视频信息 / HDR",
                "本番片头片尾设置", skipPolicy.isDisabledForSession() ? "本集已取消自动跳过" : "本集不自动跳过", "定时停止", "手势设置"}, (dialog, index) -> {
                if (index == 0 || index == 1) {
                    zoom = 1; applyZoom();
                    playerView.setResizeMode(index == 0 ? AspectRatioFrameLayout.RESIZE_MODE_FIT : AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                } else if (index == 2) setZoom(zoom - .25f);
                else if (index == 3) setZoom(zoom + .25f);
                else if (index == 4) setZoom(1);
                else if (index == 5) showVideoInformation();
                else if (index == 6 && currentEpisode != null) {
                    Models.Series series = findSeries(currentEpisode.seriesId);
                    if (series != null) showSkipSettings(series);
                } else if (index == 7) { skipPolicy.disableForSession(); hideSkipNotice(); notice("本集不再自动跳过，下次播放仍使用本番设置"); }
                else if (index == 8) chooseSleepTimer();
                else if (index == 9) chooseGestures();
            }).setNegativeButton("关闭", null).show();
    }

    private String skipSettingsText(String seriesId) {
        Store.SkipSettings settings = store.getSkipSettings(seriesId);
        return settings.enabled ? "片头 " + settings.introSeconds + " 秒 / 片尾 " + settings.outroSeconds + " 秒" : "未开启";
    }

    private void showSkipSettings(Models.Series series) {
        Store.SkipSettings settings = store.getSkipSettings(series.id);
        LinearLayout form = column(); form.setPadding(dp(20), dp(8), dp(20), dp(8));
        Switch enabled = new Switch(this); enabled.setText("自动跳过这部番剧的片头片尾");
        enabled.setTextColor(TEXT); enabled.setTextSize(15); enabled.setChecked(settings.enabled);
        enabled.setContentDescription("启用本番自动跳过"); form.addView(enabled);
        form.addView(text("分别填写秒数，0 表示不跳过该部分（最多 600 秒）。", 12, MUTED));
        EditText intro = skipSecondsInput("片头秒数", settings.introSeconds);
        EditText outro = skipSecondsInput("片尾秒数", settings.outroSeconds);
        form.addView(text("片头秒数", 14, TEXT)); form.addView(intro);
        form.addView(text("片尾秒数", 14, TEXT)); form.addView(outro);
        TextView help = text("只按填写的时长跳过，不会识别片头或彩蛋。片尾先倒计时 5 秒，可取消；拖动进度或选择「本集不自动跳过」后，本次播放不再自动跳过。", 12, MUTED);
        help.setPadding(0, dp(12), 0, 0); form.addView(help);
        ScrollView wrapper = new ScrollView(this); wrapper.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("片头片尾 · " + series.title).setView(wrapper)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        skipDialogOpen = true;
        dialog.setOnDismissListener(d -> skipDialogOpen = false);
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Integer introSeconds = readSkipSeconds(intro), outroSeconds = readSkipSeconds(outro);
            if (introSeconds == null || outroSeconds == null) return;
            store.setSkipSettings(series.id, enabled.isChecked(), introSeconds, outroSeconds);
            if (player != null && currentEpisode != null && series.id.equals(currentEpisode.seriesId))
                startSkipSession(series.id, Math.max(0, player.getCurrentPosition()));
            dialog.dismiss();
            if (seriesPage && currentSeries != null && series.id.equals(currentSeries.id)) {
                boolean navigationWasCurrent = selectionToken == playbackNavigationToken;
                int y = scroll.getScrollY(); renderSeriesContent(series); scroll.scrollTo(0, y);
                if (player != null && navigationWasCurrent) playbackNavigationToken = selectionToken;
            }
            notice("已保存这部番剧的片头片尾设置");
        }));
        dialog.show();
    }

    private EditText skipSecondsInput(String description, int seconds) {
        EditText field = new EditText(this); field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); field.setTextColor(TEXT);
        field.setText(String.valueOf(seconds)); field.setContentDescription(description); return field;
    }

    private Integer readSkipSeconds(EditText field) {
        try {
            String value = field.getText().toString().trim();
            int seconds = value.isEmpty() ? 0 : Integer.parseInt(value);
            if (seconds >= 0 && seconds <= 600) return seconds;
        } catch (NumberFormatException ignored) { }
        field.setError("请输入 0～600 之间的秒数"); return null;
    }

    private void startSkipSession(String seriesId, long initialPosition) {
        Store.SkipSettings settings = store.getSkipSettings(seriesId);
        skipPolicy.startSession(settings.enabled, settings.introSeconds, settings.outroSeconds, initialPosition);
        hideSkipNotice();
    }

    private void updateSkipPlayback() {
        if (player == null || !resumed) { hideSkipNotice(); return; }
        long now = android.os.SystemClock.elapsedRealtime();
        boolean ready = sourceReady && player.getPlaybackState() == Player.STATE_READY && !skipDialogOpen
            && !playerOverlay.isLocked() && selectionToken == playbackNavigationToken;
        SkipPlaybackPolicy.Decision decision = skipPolicy.update(player.getCurrentPosition(), player.getDuration(),
            ready, player.isCurrentMediaItemSeekable(), player.isPlaying(), now);
        if (!ready) {
            if (!skipNoticeIsIntro || now >= skipNoticeUntil || skipDialogOpen || playerOverlay.isLocked()) hideSkipNotice();
            return;
        }
        switch (decision.action) {
            case SKIP_INTRO:
                skippedIntroFrom = player.getCurrentPosition(); player.seekTo(decision.targetMs);
                skipNoticeIsIntro = true; skipNoticeUntil = now + 5000;
                skipNoticeText.setText("已跳过片头"); skipNoticeAction.setText("撤销");
                skipNotice.setVisibility(View.VISIBLE); break;
            case COUNTDOWN_OUTRO:
                skipNoticeIsIntro = false; skipNoticeUntil = 0;
                skipNoticeText.setText(decision.countdownSeconds + " 秒后跳过片尾"); skipNoticeAction.setText("本集不跳过");
                skipNotice.setVisibility(View.VISIBLE); break;
            case SKIP_OUTRO:
                hideSkipNotice(); player.seekTo(decision.targetMs); savePosition(); break;
            case NONE:
                if (!skipNoticeIsIntro || now >= skipNoticeUntil || !player.isPlaying()) hideSkipNotice();
                break;
        }
    }

    private void hideSkipNotice() {
        if (skipNotice != null) skipNotice.setVisibility(View.GONE);
        skipNoticeIsIntro = false; skipNoticeUntil = 0;
    }

    private void cancelEpisodeSkip() {
        if (playerOverlay.isLocked()) { hideSkipNotice(); return; }
        boolean undoIntro = skipNoticeIsIntro;
        skipPolicy.disableForSession(); hideSkipNotice();
        if (undoIntro && player != null) { player.seekTo(skippedIntroFrom); savePosition(); }
        notice("本集不再自动跳过");
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
        if (value && !fullScreen) orientationBeforeFullScreen = getRequestedOrientation();
        fullScreen = value;
        // The video owns its navigation in fullscreen; no outer bars consume space.
        header.setVisibility(value ? View.GONE : View.VISIBLE);
        body.setVisibility(value ? View.GONE : View.VISIBLE);
        nav.setVisibility(value ? View.GONE : View.VISIBLE);
        updateSelectionBarVisibility();
        playerOverlay.setFullScreen(value);
        applySystemBars();
        setRequestedOrientation(value && !isInMultiWindowMode() ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : value ? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED : orientationBeforeFullScreen);
        root.requestApplyInsets();
        resizeVideo();
    }

    private void applySystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(fullScreen
            ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            : View.SYSTEM_UI_FLAG_VISIBLE);
    }

    private int currentWindowWidth() {
        if (layoutWidth > 0) return layoutWidth;
        if (root != null && root.getWidth() > 0) return root.getWidth();
        return getResources().getDisplayMetrics().widthPixels;
    }

    private boolean useDownloadColumns() {
        return !fullScreen && currentWindowWidth() - root.getPaddingLeft() - root.getPaddingRight() >= dp(840);
    }

    private void resizeVideo() {
        if (root == null) return;
        // The next measure owns the new window bounds. getWidth() can still describe the old
        // split-screen window here; reconfiguring from it also creates a redundant layout cycle.
        root.requestLayout();
    }

    /** Reparent only chrome; the player and its SurfaceView keep their identity while a window resizes. */
    private void configureTabletLayout(int width, int height) {
        if (layingOut || workspace == null || nav == null || selectionBar == null || width <= 0 || height <= 0) return;
        boolean playing = playerPanel.getVisibility() == View.VISIBLE;
        boolean selecting = episodeSelectionMode || downloadSelectionMode;
        int insetWidth = width - root.getPaddingLeft() - root.getPaddingRight();
        int insetHeight = height - root.getPaddingTop() - root.getPaddingBottom();
        int state = (fullScreen ? 1 : 0) | (playing ? 2 : 0) | (downloadsPage ? 4 : 0) | (selecting ? 8 : 0)
            | (root.getPaddingLeft() + root.getPaddingRight()) << 4 | (root.getPaddingTop() + root.getPaddingBottom()) << 16;
        if (layoutWidth == width && layoutHeight == height && layoutState == state) return;
        layingOut = true;
        boolean wasExpanded = expandedWindow;
        layoutWidth = width; layoutHeight = height; layoutState = state;
        navigationRail = !fullScreen && insetWidth >= dp(600);
        expandedWindow = insetWidth >= dp(840);
        boolean splitPlayer = !fullScreen && expandedWindow && playing;
        boolean splitDownloads = !fullScreen && expandedWindow && downloadsPage && !playing;
        boolean compactLandscape = !navigationRail && !fullScreen && playing && insetWidth > insetHeight;
        header.setVisibility(fullScreen || compactLandscape ? View.GONE : View.VISIBLE);
        body.setVisibility(fullScreen ? View.GONE : View.VISIBLE);
        nav.setVisibility(!fullScreen && !selecting ? View.VISIBLE : View.GONE);
        if (navigationRail) {
            if (nav.getParent() != mainArea) {
                ((ViewGroup) nav.getParent()).removeView(nav); mainArea.addView(nav, 0);
            }
            nav.setOrientation(LinearLayout.VERTICAL); nav.setGravity(Gravity.TOP);
            nav.setPadding(dp(6), dp(12), dp(6), dp(12));
            nav.setLayoutParams(new LinearLayout.LayoutParams(dp(80), -1));
        } else {
            if (nav.getParent() != root) { ((ViewGroup) nav.getParent()).removeView(nav); root.addView(nav); }
            nav.setOrientation(LinearLayout.HORIZONTAL); nav.setGravity(Gravity.CENTER_VERTICAL);
            nav.setPadding(dp(12), compactLandscape ? 0 : dp(4), dp(12), compactLandscape ? 0 : dp(4));
            nav.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        }
        for (Button item : navigationButtons) {
            LinearLayout.LayoutParams cell = navigationRail ? new LinearLayout.LayoutParams(-1, dp(76))
                : new LinearLayout.LayoutParams(0, -2, 1);
            cell.setMargins(dp(3), dp(3), dp(3), dp(3)); item.setLayoutParams(cell);
        }
        int workWidth = Math.max(dp(180), insetWidth - (navigationRail && !selecting ? dp(80) : 0));
        workspace.setOrientation(splitPlayer || splitDownloads ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        workspace.setGravity(Gravity.TOP);
        downloadMasterScroll.setVisibility(splitDownloads ? View.VISIBLE : View.GONE);
        int bodyWidth = workWidth;
        if (fullScreen) {
            playerPanel.setGravity(Gravity.TOP); playerPanel.setPadding(0, 0, 0, 0);
            playerPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
            videoFrame.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
            body.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        } else if (splitPlayer) {
            bodyWidth = Math.max(dp(280), Math.min(dp(360), Math.round(workWidth * .34f)));
            int playerWidth = workWidth - bodyWidth;
            playerPanel.setGravity(Gravity.CENTER_VERTICAL); playerPanel.setPadding(dp(12), dp(8), dp(12), dp(8));
            playerPanel.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
            int availableHeight = Math.max(dp(164), insetHeight - Math.max(dp(72), header.getMeasuredHeight()) - (selecting ? dp(100) : 0) - dp(16));
            int videoHeight = Math.min(availableHeight, Math.max(dp(164), (playerWidth - dp(24)) * 9 / 16));
            videoFrame.setLayoutParams(new LinearLayout.LayoutParams(-1, videoHeight));
            body.setLayoutParams(new LinearLayout.LayoutParams(bodyWidth, -1));
        } else if (splitDownloads) {
            downloadMasterScroll.setLayoutParams(new LinearLayout.LayoutParams(dp(280), -1));
            bodyWidth = workWidth - dp(280); body.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
            playerPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        } else {
            playerPanel.setGravity(Gravity.TOP); playerPanel.setPadding(0, 0, 0, 0);
            int reserved = compactLandscape ? dp(48) : Math.max(dp(92), header.getMeasuredHeight()) + (navigationRail ? 0 : dp(64));
            int desired = Math.max(dp(180), Math.min(workWidth * 9 / 16, insetHeight / 2));
            int videoHeight = Math.min(desired, Math.max(dp(164), insetHeight - reserved));
            playerPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            videoFrame.setLayoutParams(new LinearLayout.LayoutParams(-1, videoHeight));
            body.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        }
        int sidePadding = Math.max(dp(20), (bodyWidth - dp(1200)) / 2);
        content.setPadding(sidePadding, dp(10), sidePadding, dp(20));
        layingOut = false;
        if (wasExpanded != expandedWindow && downloadsPage && !fullScreen)
            handler.post(() -> { if (!dead && downloadsPage && !fullScreen) renderDownloads(); });
    }

    @Override public void onMultiWindowModeChanged(boolean inMultiWindowMode, Configuration configuration) {
        super.onMultiWindowModeChanged(inMultiWindowMode, configuration);
        if (inMultiWindowMode && fullScreen) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        layoutState = -1; resizeVideo();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration); dismissEpisodeSidebar(); playerOverlay.cancelInteraction(); layoutState = -1; resizeVideo(); root.requestApplyInsets();
    }
    @Override public void onBackPressed() {
        if (playerOverlay.isLocked()) { playerOverlay.unlock(); return; }
        if (fullScreen) { setFullScreen(false); return; }
        if (episodeSelectionMode || downloadSelectionMode) { exitListSelection(); return; }
        if (downloadsPage && downloadGroupId != null && !useDownloadColumns()) { downloadGroupId = null; renderDownloads(); scroll.scrollTo(0, 0); return; }
        if (player != null || playerPanel.getVisibility() == View.VISIBLE || seriesPage || downloadsPage || settingsPage) { renderLibrary(); return; }
        super.onBackPressed();
    }

    private void pickLocal() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, PICK_VIDEO); } catch (Exception e) { error("无法打开文件选择器", explain(e)); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == AUTO_BACKUP_FOLDER) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                Uri folder = data.getData();
                try {
                    int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    if (flags != (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) throw new SecurityException();
                    getContentResolver().takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    autoBackup.configure(folder); notice("已开启自动备份，首次完成后可在设置中查看时间");
                } catch (Exception unavailable) { error("未能设置自动备份", "请选择允许读写的文件夹，并保留文件夹访问权限。"); }
            }
            renderSettings(); return;
        }
        if (request == EXPORT_BACKUP || request == IMPORT_BACKUP) {
            if (result == RESULT_OK && data != null) handleBackupResult(request, data.getData());
            return;
        }
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
        dismissLibraryKeyboard(); settingsPage = false;
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
        settingsPage = false; downloadsPage = true; seriesPage = false; lastRender = System.currentTimeMillis();
        refreshNavigation();
        int previousScroll = scroll.getScrollY();
        content.removeAllViews();
        List<MediaRepository.OfflineItem> entries = media.downloads();
        Set<String> availableKeys = new LinkedHashSet<>();
        for (MediaRepository.OfflineItem item : entries) availableKeys.add(item.key);
        selectedDownloadKeys.retainAll(availableKeys);
        List<DownloadCatalogue.Group> groups = DownloadCatalogue.groups(entries);
        if (useDownloadColumns() && downloadGroupId == null && !groups.isEmpty()) {
            downloadGroupId = groups.get(0).id; downloadGroupTitle = groups.get(0).title;
        }
        renderDownloadMaster(groups);
        DownloadCatalogue.Group selected = null;
        for (DownloadCatalogue.Group group : groups) if (group.id.equals(downloadGroupId)) selected = group;
        if (downloadGroupId == null) {
            title("离线下载", groups.size() + " 部番剧 · 完整下载后可断网播放");
            addDownloadSummary(entries);
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
                    + group.completeCount + " 份", 12, MUTED);
                detail.setPadding(0, dp(7), 0, 0); card.addView(detail);
                DownloadProgressView progress = new DownloadProgressView(this, accentColor);
                progress.bind(group.items, "本番下载总进度"); progress.setTag("download-group-progress:" + group.id); card.addView(progress);
                TextView watched = text(offlineLastWatched(group), 12, TEXT);
                watched.setPadding(0, 0, 0, dp(10)); card.addView(watched);
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
            if (!useDownloadColumns()) content.addView(button("‹  全部离线番剧", false, v -> {
                clearListSelection(); downloadGroupId = null; renderDownloads(); scroll.scrollTo(0, 0);
            }));
            title(selected == null ? downloadGroupTitle : selected.title, "按集数从小到大排列 · 同集可保留多个清晰度");
            addDownloadSummary(selected == null ? new ArrayList<>() : selected.items);
            if (selected == null) {
                empty("这部番剧暂时没有下载", "已删除的离线文件不会删除观看记录。", "查看全部下载",
                    () -> { downloadGroupId = null; renderDownloads(); scroll.scrollTo(0, 0); });
                return;
            }
            if (downloadSelectionMode) addDownloadSelectionActions(selected.items); else addDownloadActions(selected.items);
            addOfflineResume(selected);
            for (MediaRepository.OfflineItem entry : selected.items) renderDownloadItem(entry);
        }
        scroll.scrollTo(0, previousScroll);
    }

    private void renderDownloadMaster(List<DownloadCatalogue.Group> groups) {
        StringBuilder fingerprint = new StringBuilder(String.valueOf(downloadGroupId));
        for (DownloadCatalogue.Group group : groups) fingerprint.append('|').append(group.id).append(':').append(group.title)
            .append(':').append(group.items.size()).append(':').append(group.completeCount);
        if (fingerprint.toString().equals(downloadMasterFingerprint)) return;
        downloadMasterFingerprint = fingerprint.toString(); int previousY = downloadMasterScroll.getScrollY();
        downloadMaster.removeAllViews();
        TextView heading = text("离线番剧", 20, TEXT); heading.setTypeface(null, Typeface.BOLD); heading.setPadding(dp(4), dp(6), 0, dp(16)); downloadMaster.addView(heading);
        for (DownloadCatalogue.Group group : groups) {
            LinearLayout item = card(); item.setPadding(dp(14), dp(14), dp(14), dp(14));
            item.setMinimumHeight(dp(88)); boolean chosen = group.id.equals(downloadGroupId);
            if (chosen) { GradientDrawable bg = background(PANEL_LIGHT, 14); bg.setStroke(dp(1), accentColor); item.setBackground(bg); }
            TextView name = text(group.title, 15, chosen ? accentColor : TEXT); name.setMaxLines(2); name.setTypeface(null, Typeface.BOLD); item.addView(name);
            TextView detail = text(group.episodeCount + " 集 · 已完成 " + group.completeCount + " 份", 12, MUTED); detail.setPadding(0, dp(8), 0, 0); item.addView(detail);
            item.setContentDescription("离线目录：" + group.id); item.setSelected(chosen);
            item.setOnClickListener(v -> openDownloadGroup(group));
            downloadMaster.addView(item, gapLayout());
        }
        if (groups.isEmpty()) downloadMaster.addView(text("暂无离线番剧", 14, MUTED));
        downloadMasterScroll.post(() -> downloadMasterScroll.scrollTo(0, previousY));
    }

    private String watchProgress(String episodeId) {
        Store.Progress p = store.getProgress(episodeId);
        if (p.updatedAt <= 0) return "尚未观看";
        return (p.completed ? "已看完 · " : "看到 ") + time(p.positionMs)
            + (p.durationMs > 0 ? " / " + time(p.durationMs) : "");
    }

    private String offlineLastWatched(DownloadCatalogue.Group group) {
        String last = store.getLastEpisode(group.id);
        Models.Episode episode = findEpisode(findSeries(group.id), last);
        if (episode == null) for (MediaRepository.OfflineItem item : group.items)
            if (item.episode.id.equals(last)) { episode = item.episode; break; }
        return episode == null ? "尚无观看记录" : "最近观看：第 " + episode.index + " 集 · "
            + time(store.getProgress(episode.id).positionMs) + (hasDownload(episode.id) ? "" : "（本集未下载）");
    }

    private void addOfflineResume(DownloadCatalogue.Group group) {
        TextView recent = text(offlineLastWatched(group), 14, TEXT);
        recent.setContentDescription("离线最近观看"); content.addView(recent, gapLayout());
        if (downloadSelectionMode) return;
        Models.Episode candidate = null; long latest = -1;
        String last = store.getLastEpisode(group.id);
        for (MediaRepository.OfflineItem item : group.items) if (item.complete) {
            Store.Progress progress = store.getProgress(item.episode.id);
            if (progress.updatedAt > latest || (progress.updatedAt == latest && item.episode.id.equals(last))) {
                latest = progress.updatedAt; candidate = item.episode;
            }
        }
        if (candidate == null || latest <= 0) return;
        final Models.Episode target = candidate;
        Button resume = button("继续第 " + target.index + " 集 · " + time(store.getProgress(target.id).positionMs), true, v -> {
            Models.Quality quality = preferredOfflineQuality(target, null);
            if (quality != null) play(target, quality, true); else notice("这集的离线文件已不可用");
        });
        resume.setContentDescription("离线继续观看：" + group.id); content.addView(resume, gapLayout());
    }

    private void addDownloadSummary(List<MediaRepository.OfflineItem> entries) {
        int active = 0, waiting = 0, failed = 0; long speed = 0; boolean measured = false;
        for (MediaRepository.OfflineItem entry : entries) {
            if (entry.downloading) active++;
            if (entry.queued || entry.waitingForNetwork) waiting++;
            if (entry.failed) failed++;
            if (entry.downloading && entry.bytesPerSecond >= 0) { speed += entry.bytesPerSecond; measured = true; }
        }
        long available = media.availableStorageBytes();
        String summary = "设备可用空间 " + (available < 0 ? "暂不可读" : android.text.format.Formatter.formatShortFileSize(this, available));
        if (!entries.isEmpty()) summary += "\n下载中 " + active + " · 等待 " + waiting + " · 失败 " + failed;
        if (measured) summary += " · " + android.text.format.Formatter.formatShortFileSize(this, speed) + "/s";
        summary += "\n" + media.downloadNetworkInfo().summary + (store.isWifiOnlyDownloads() ? "" : " · 已允许移动数据下载");
        if (media.isStoragePaused()) summary += "\n存储保护：下载已暂停，清理至至少 300 MB 后点继续。";
        else if (available >= 0 && available < 500L * 1024 * 1024) summary += "\n空间预警：低于 200 MB 时会自动暂停下载。";
        if (media.isStoragePaused() || (available >= 0 && available < 500L * 1024 * 1024))
            content.addView(button("清理存储空间", false, v -> StorageCleanup.show(this, store, media, this::confirmRemoveDownloads)), gapLayout());
        List<PendingDownloads.Job> pending = PendingDownloads.list(this);
        if (!pending.isEmpty()) {
            int failedJobs = 0; for (PendingDownloads.Job job : pending) if (!job.error.isEmpty()) failedJobs++;
            summary += "\n后台待加入 " + (pending.size() - failedJobs) + " 集 · 解析失败 " + failedJobs + " 集";
            content.addView(button("管理后台批量任务", false, v -> showPendingDownloads()), gapLayout());
        }
        TextView detail = text(summary, 12, MUTED); detail.setPadding(dp(4), 0, dp(4), dp(14));
        detail.setContentDescription("下载状态摘要"); content.addView(detail);
    }

    private void addDownloadActions(List<MediaRepository.OfflineItem> visible) {
        LinearLayout actions = row();
        addEqual(actions, button("暂停全部", false, v -> {
            try { media.pauseAll(); renderDownloads(); } catch (Exception e) { error("操作失败", explain(e)); }
        }));
        addEqual(actions, button("继续全部", false, v -> {
            try { media.resumeAll(); DownloadQueueService.start(this); renderDownloads(); } catch (Exception e) { error("操作失败", explain(e)); }
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
        String episodeTitle = entry.episode.title;
        if (!episodeTitle.matches("^第\\s*" + entry.episode.index + "\\s*集.*"))
            episodeTitle = "第 " + entry.episode.index + " 集 · " + episodeTitle;
        TextView name = text(episodeTitle, 16, TEXT);
        name.setTypeface(null, Typeface.BOLD); card.addView(name);
        card.addView(text(entry.quality.label + "  ·  " + entry.status, 12, AQUA));
        DownloadProgressView progress = new DownloadProgressView(this, accentColor);
        progress.bind(entry); progress.setTag("download-progress:" + entry.key); card.addView(progress);
        TextView watched = text(watchProgress(entry.episode.id), 13, TEXT);
        watched.setPadding(0, dp(7), 0, dp(7));
        watched.setContentDescription("离线观看进度：" + entry.episode.id); card.addView(watched);
        if (entry.downloading) {
            String speed = entry.bytesPerSecond < 0 ? "正在测量速度…" : android.text.format.Formatter.formatShortFileSize(this, entry.bytesPerSecond) + "/s";
            if (entry.estimatedRemainingSeconds >= 0) {
                long seconds = entry.estimatedRemainingSeconds;
                speed += " · 预计剩余 " + (seconds < 60 ? seconds + " 秒" : (seconds + 59) / 60 + " 分钟");
            }
            card.addView(text(speed, 12, MUTED));
        }
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
        if (entry.complete) addEqual(actions, button(store.getProgress(entry.episode.id).positionMs > 0 ? "继续离线观看" : "离线播放",
            true, v -> play(entry.episode, entry.quality, true)));
        else if (entry.needsRepair) addEqual(actions, button("修复本集", true, v -> confirmRepair(entry)));
        else if (entry.failed) addEqual(actions, button("重新解析重试", true, v -> retryDownload(entry)));
        else addEqual(actions, button(entry.paused ? "继续本集" : "暂停本集", false, v -> {
            try { if (entry.paused) media.resume(entry); else media.pause(entry); renderDownloads(); }
            catch (Exception e) { error("操作失败", explain(e)); }
        }));
        if (!entry.complete && !entry.failed) addEqual(actions, button(entry.priority ? "取消优先" : "优先下载", false, v -> {
            try { if (entry.priority) media.clearPriority(); else media.prioritize(entry); renderDownloads(); }
            catch (Exception failure) { error("操作失败", explain(failure)); }
        }));
        if (entry.complete) addEqual(actions, button(integrityBusy.contains(entry.key) ? "检查中…" : "检查文件", false, v -> checkIntegrity(entry)));
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
            prepareDownloads(java.util.Collections.singletonList(entry.episode), quality);
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
        cancelDownloadPreparation();
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

    private void cancelDownloadPreparation() {
        if (downloadPreparationCancelled != null) downloadPreparationCancelled.set(true);
        downloadPreparationCancelled = null;
        AlertDialog old = downloadPreparationDialog; downloadPreparationDialog = null;
        if (old != null) old.dismiss();
    }

    private String storageLabel(DownloadEstimate estimate) {
        return estimate.bytes < 0 ? "大小暂不可知" : (estimate.exact ? "" : "约 ")
            + android.text.format.Formatter.formatShortFileSize(this, estimate.bytes);
    }

    private void prepareDownloads(List<Models.Episode> chosen, Models.Quality profile) {
        if (chosen.isEmpty()) return;
        cancelDownloadPreparation();
        final int token = selectionToken;
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        downloadPreparationCancelled = cancelled;
        final String cookie = CookieManager.getInstance().getCookie("https://api.bilibili.com");
        final List<MediaRepository.OfflineItem> existing = media.downloads();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("下载前检查")
            .setMessage("正在计算所需空间，尚未开始下载…")
            .setNegativeButton("取消", (d, w) -> cancelDownloadPreparation()).create();
        dialog.setOnCancelListener(d -> cancelDownloadPreparation());
        downloadPreparationDialog = dialog; dialog.show();
        io.execute(() -> {
            try {
                DownloadPlan plan = DownloadPlan.prepare(chosen, profile, new DownloadPlan.Gateway() {
                    @Override public List<Models.Quality> resolve(Models.Episode episode) throws Exception {
                        if (episode.id.equals(chosen.get(0).id)) return java.util.Collections.singletonList(profile);
                        // Space checks only request metadata; no media bytes are downloaded here.
                        Thread.sleep(150);
                        if (cancelled.get() || token != selectionToken) throw new java.util.concurrent.CancellationException();
                        return qualityResolver.load(episode, cookie);
                    }
                    @Override public MediaRepository.OfflineItem existing(Models.Episode episode, Models.Quality quality) {
                        return DownloadCatalogue.existing(existing, episode, quality);
                    }
                    @Override public void checked(int done, int total) {
                        handler.post(() -> { if (downloadPreparationDialog == dialog && !cancelled.get())
                            dialog.setMessage("正在计算所需空间 " + done + " / " + total + " 集\n尚未开始下载…"); });
                    }
                }, () -> cancelled.get() || token != selectionToken || Thread.currentThread().isInterrupted());
                handler.post(() -> {
                    if (dead || !resumed || cancelled.get() || token != selectionToken || downloadPreparationDialog != dialog) return;
                    downloadPreparationDialog = null; dialog.dismiss();
                    showDownloadPlan(plan, profile, token, cancelled);
                });
            } catch (java.util.concurrent.CancellationException stopped) { }
            catch (Exception failure) {
                handler.post(() -> {
                    if (dead || cancelled.get() || token != selectionToken) return;
                    cancelDownloadPreparation(); error("无法检查下载", "未能获取剧集大小，请稍后重试。尚未添加下载任务。");
                });
            }
        });
    }

    private void showDownloadPlan(DownloadPlan plan, Models.Quality profile, int token,
                                  java.util.concurrent.atomic.AtomicBoolean cancelled) {
        long available = media.availableStorageBytes();
        StringBuilder message = new StringBuilder(profile.label).append("\n\n");
        if (plan.items.isEmpty()) message.append("没有需要新增的下载。");
        else {
            message.append(plan.unknownCount == 0 ? "预计还需空间：" : "已知部分还需空间：")
                .append(plan.allExact ? "" : "约 ").append(android.text.format.Formatter.formatShortFileSize(this, plan.knownBytes));
            if (plan.unknownCount > 0) message.append("\n另有 ").append(plan.unknownCount).append(" 集大小暂不可知");
            message.append("\n设备可用：").append(available < 0 ? "暂不可读" : android.text.format.Formatter.formatShortFileSize(this, available));
            message.append("\n包含视频和音轨；估算基于时长与码率，实际大小可能不同。\n不含其他排队任务及少量索引开销。\n");
            for (DownloadPlan.Item item : plan.items)
                message.append("\n第 ").append(item.episode.index).append(" 集 · ").append(storageLabel(item.size));
        }
        if (!plan.skipped.isEmpty()) {
            message.append("\n\n跳过 ").append(plan.skipped.size()).append(" 集：");
            for (String detail : plan.skipped) message.append("\n").append(detail);
        }
        boolean insufficient = available >= 0 && (available < 32L * 1024 * 1024 || plan.knownBytes > available - 32L * 1024 * 1024);
        if (insufficient) message.append("\n\n可用空间不足，请先清理存储。");
        ScrollView box = new ScrollView(this); TextView detail = text(message.toString(), 14, TEXT);
        detail.setPadding(dp(20), dp(12), dp(20), dp(16)); detail.setContentDescription("下载前存储说明"); box.addView(detail);
        AlertDialog confirm = new AlertDialog.Builder(this).setTitle("下载 " + plan.items.size() + " 集 · 存储空间")
            .setView(box).setNegativeButton("取消", (d, w) -> cancelDownloadPreparation())
            .setPositiveButton("开始下载", (d, w) -> {
                if (dead || !resumed || cancelled.get() || token != selectionToken) return;
                downloadPreparationDialog = null; downloadPreparationCancelled = null;
                if (plan.items.size() == 1) {
                    DownloadPlan.Item item = plan.items.get(0);
                    MediaRepository.OfflineItem now = DownloadCatalogue.existing(media.downloads(), item.episode, item.quality);
                    if (now != null && !now.failed) { notice("已有相同版本的下载任务"); return; }
                    try { media.enqueue(item.episode, item.quality); requestDownloadNotifications(); notice("已加入离线下载队列"); }
                    catch (Exception error) { error("无法添加下载", explain(error)); }
                } else {
                    List<Models.Episode> episodes = new ArrayList<>(); java.util.Map<String, Models.Quality> prepared = new LinkedHashMap<>();
                    for (DownloadPlan.Item item : plan.items) { episodes.add(item.episode); prepared.put(item.episode.id, item.quality); }
                    Models.Series series = findSeries(episodes.get(0).seriesId);
                    if (series == null) { series = new Models.Series(); series.id = episodes.get(0).seriesId; series.title = episodes.get(0).seriesTitle; }
                    startBatchDownloads(series, episodes, profile, prepared);
                }
            }).create();
        confirm.setOnCancelListener(d -> cancelDownloadPreparation());
        downloadPreparationDialog = confirm; confirm.show();
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!plan.items.isEmpty() && !insufficient);
    }

    private void chooseBatchQuality(Models.Series series, List<Models.Episode> chosen) {
        final int token = ++selectionToken;
        String cookie = CookieManager.getInstance().getCookie("https://api.bilibili.com");
        Models.Episode first = chosen.get(0);
        runTask("正在读取第 " + first.index + " 集的清晰度…", token,
            () -> qualityResolver.load(first, cookie), available -> {
                List<Models.Quality> qualities = new ArrayList<>();
                for (Models.Quality quality : available) if (DownloadCatalogue.sameProfile(quality, quality)) qualities.add(quality);
                if (qualities.isEmpty()) { error("无法批量匹配", "接口未提供明确的清晰度与编码，请尝试单集下载。"); return; }
                String[] names = new String[qualities.size()];
                for (int i = 0; i < names.length; i++) names[i] = qualities.get(i).label;
                new AlertDialog.Builder(this).setTitle("统一清晰度 · 参考第 " + first.index + " 集")
                    .setItems(names, (dialog, index) -> {
                        if (token != selectionToken) return;
                        Models.Quality profile = qualities.get(index);
                        prepareDownloads(chosen, profile);
                    }).setNegativeButton("取消", null).show();
            });
    }

    private void startBatchDownloads(Models.Series series, List<Models.Episode> episodes, Models.Quality profile,
                                     java.util.Map<String, Models.Quality> prepared) {
        try {
            int added = PendingDownloads.add(this, episodes, profile, prepared);
            DownloadQueueService.start(this);
            requestDownloadNotifications();
            openDownloads(); downloadGroupId = series.id; downloadGroupTitle = series.title; renderDownloads();
            notice("已保存 " + added + " 集后台任务，切换应用后继续处理");
        } catch (Exception failure) { error("后台队列未能启动", "任务会保存在本机。请回到离线下载，点击管理后台批量任务重试。"); }
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

    private Models.Quality preferredOfflineQuality(Models.Episode episode, Models.Quality preferred) {
        List<Models.Quality> choices = downloadedQualities(episode.id);
        if (choices.isEmpty()) return null;
        Store.SeriesPlaybackPreference saved = store.getSeriesPlaybackPreference(episode.seriesId);
        if (preferred == null && saved != null) preferred = saved.quality;
        Models.Quality matched = PlaybackPolicy.preferredQuality(choices, preferred);
        if (matched != null) return matched;
        choices.sort(java.util.Comparator.comparingInt((Models.Quality q) -> q.qn).reversed().thenComparing(q -> q.id));
        return choices.get(0);
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
        TextView symbol = text("▶", 36, accentColor); symbol.setGravity(Gravity.CENTER); box.addView(symbol);
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
        button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        button.setTextColor(primary ? BG : TEXT); button.setBackground(background(primary ? accentColor : PANEL_LIGHT, 11));
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
    private static String explain(Exception exception) {
        if (exception instanceof BackupMessage) return exception.getMessage();
        if (exception instanceof DownloadStoragePolicy.SpacePausedException) return exception.getMessage();
        if (exception instanceof DownloadStatus.StorageException) return exception.getMessage();
        return MediaErrors.describe(exception);
    }
    private static Models.Episode findEpisode(Models.Series series, String id) { if (series == null || id == null) return null; for (Models.Episode episode : series.episodes) if (id.equals(episode.id)) return episode; return null; }
    private static String speedText(float rate) { return String.format(Locale.ROOT, "%s×", rate); }
    private static String time(long milliseconds) { long total = Math.max(0, milliseconds) / 1000; return total >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60) : String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60); }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (playerOverlay != null) playerOverlay.cancelInteraction();
        savePosition();
        state.putBoolean("tabletState", true); state.putString("libraryQuery", libraryQuery);
        state.putString("page", downloadsPage ? "downloads" : settingsPage ? "settings" : seriesPage ? "series" : "library");
        state.putString("seriesId", currentSeries == null ? "" : currentSeries.id);
        state.putString("downloadGroupId", downloadGroupId); state.putString("downloadGroupTitle", downloadGroupTitle);
        state.putInt("contentScroll", scroll == null ? 0 : scroll.getScrollY());
        state.putBoolean("episodeSelection", episodeSelectionMode); state.putBoolean("downloadSelection", downloadSelectionMode);
        state.putString("selectionSeries", selectionSeriesId);
        state.putStringArrayList("episodeIds", new ArrayList<>(selectedEpisodeIds));
        state.putStringArrayList("downloadKeys", new ArrayList<>(selectedDownloadKeys));
        if (player != null && currentEpisode != null) {
            state.putString("episodeId", currentEpisode.id); state.putString("playbackSeries", currentEpisode.seriesId);
            state.putLong("playbackPosition", sourceReady ? Math.max(0, player.getCurrentPosition()) : requestedResumeMs);
            state.putBoolean("playbackOffline", currentOffline);
            state.putBoolean("playbackPlaying", resumed ? player.getPlayWhenReady() : playWhenReadyBeforePause);
            state.putBoolean("playbackFullscreen", fullScreen); state.putFloat("playbackSpeed", player.getPlaybackParameters().speed);
            state.putFloat("playbackZoom", zoom); state.putFloat("playbackBrightness", playbackBrightness);
            state.putInt("playbackResizeMode", playerView.getResizeMode());
            state.putBoolean("playbackSkipDisabled", skipPolicy.isDisabledForSession());
            state.putInt("orientationBeforeFullscreen", orientationBeforeFullScreen);
            // Only a portable quality identity goes into saved state, never media URLs or account cookies.
            if (currentQuality != null) { state.putString("qualityId", currentQuality.id); state.putInt("qualityQn", currentQuality.qn); }
        }
        super.onSaveInstanceState(state);
    }

    private void restoreTabletSelection(Bundle state) {
        episodeSelectionMode = state.getBoolean("episodeSelection", false) && seriesPage;
        downloadSelectionMode = state.getBoolean("downloadSelection", false) && downloadsPage;
        selectionSeriesId = state.getString("selectionSeries");
        selectedEpisodeIds.clear(); selectedDownloadKeys.clear();
        ArrayList<String> episodes = state.getStringArrayList("episodeIds"), downloads = state.getStringArrayList("downloadKeys");
        if (episodes != null) selectedEpisodeIds.addAll(episodes); if (downloads != null) selectedDownloadKeys.addAll(downloads);
        if (episodeSelectionMode || downloadSelectionMode) refreshSelectedList();
        int scrollY = state.getInt("contentScroll", 0); scroll.post(() -> scroll.scrollTo(0, scrollY));
    }

    private void restoreTabletState(Bundle state) {
        libraryQuery = state.getString("libraryQuery", "");
        String page = state.getString("page", "library");
        Models.Series series = findSeries(state.getString("seriesId", ""));
        if ("downloads".equals(page)) {
            downloadGroupId = state.getString("downloadGroupId"); downloadGroupTitle = state.getString("downloadGroupTitle", ""); renderDownloads();
        } else if ("settings".equals(page)) renderSettings();
        else if ("series".equals(page) && series != null) renderSeriesContent(series);
        else renderLibrary();
        restoreTabletSelection(state);
        Models.Series playingSeries = findSeries(state.getString("playbackSeries", ""));
        Models.Episode episode = findEpisode(playingSeries, state.getString("episodeId", ""));
        if (episode == null) return;
        Models.Quality profile = new Models.Quality(); profile.id = state.getString("qualityId", ""); profile.qn = state.getInt("qualityQn", 0);
        boolean offline = state.getBoolean("playbackOffline", false);
        java.util.function.Consumer<Models.Quality> resume = quality -> {
            play(episode, quality, offline, state.getLong("playbackPosition", 0), state.getBoolean("playbackPlaying", false));
            if (player == null) return;
            float speed = state.getFloat("playbackSpeed", 1f); if (speed >= .25f && speed <= 3f) player.setPlaybackSpeed(speed);
            zoom = Math.max(.5f, Math.min(3f, state.getFloat("playbackZoom", 1f))); applyZoom();
            playerView.setResizeMode(state.getInt("playbackResizeMode", AspectRatioFrameLayout.RESIZE_MODE_FIT) == AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT);
            if (state.getBoolean("playbackSkipDisabled", false)) skipPolicy.disableForSession();
            playbackBrightness = state.getFloat("playbackBrightness", -1f); applyPlaybackBrightness();
            restoreTabletSelection(state);
            if (state.getBoolean("playbackFullscreen", false)) { setFullScreen(true); orientationBeforeFullScreen = state.getInt("orientationBeforeFullscreen", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED); }
        };
        if (!downloadable(episode)) { resume.accept(null); return; }
        if (offline) {
            Models.Quality saved = null;
            for (Models.Quality quality : downloadedQualities(episode.id)) if (profile.id.equals(quality.id)) { saved = quality; break; }
            if (saved == null) saved = DownloadCatalogue.matching(downloadedQualities(episode.id), profile);
            if (saved == null) { notice("原离线版本暂不可用，请重新选择这一集"); return; }
            resume.accept(saved);
        } else {
            int token = ++selectionToken; String cookie = CookieManager.getInstance().getCookie("https://api.bilibili.com");
            runTask("正在恢复播放…", token, () -> DownloadCatalogue.matching(qualityResolver.load(episode, cookie), profile), quality -> {
                if (!resumed) return;
                if (quality == null) { notice("原画质暂不可用，请重新选择这一集"); return; }
                resume.accept(quality);
            });
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (pendingTabletState != null) handler.post(() -> {
            if (!dead && resumed && pendingTabletState != null) { Bundle restore = pendingTabletState; pendingTabletState = null; restoreTabletState(restore); }
        });
        if (autoBackup != null) autoBackup.flushSoon();
        try { media.restorePendingDownloads(); DownloadQueueService.start(this); } catch (RuntimeException ignored) { /* Journal remains available for an explicit retry. */ }
        if (resumeNavigation && player != null) playbackNavigationToken = selectionToken;
        resumeNavigation = false;
        applyPlaybackBrightness();
        applySystemBars();
        if (playerOverlay != null && player != null) playerOverlay.setActive(true);
        handler.removeCallbacks(skipTicker); if (player != null) handler.post(skipTicker);
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applySystemBars();
        else if (playerOverlay != null) playerOverlay.cancelInteraction();
    }
    @Override protected void onPause() {
        playWhenReadyBeforePause = player != null && player.getPlayWhenReady();
        dismissEpisodeSidebar();
        cancelDownloadPreparation();
        dismissPlaybackQualityDialog();
        resumeNavigation = player != null && (selectionToken == playbackNavigationToken || playbackChoicePending);
        playbackChoicePending = false;
        resumed = false; autoNextQueued = false; ++selectionToken;
        handler.removeCallbacks(skipTicker); hideSkipNotice();
        if (player != null) skipPolicy.update(player.getCurrentPosition(), player.getDuration(), false, false, false,
            android.os.SystemClock.elapsedRealtime());
        cancelBatchDownloads();
        if (playerOverlay != null) playerOverlay.setActive(false);
        restoreWindowBrightness();
        savePosition(); if (autoBackup != null) autoBackup.flushSoon();
        if (player != null) player.pause(); super.onPause();
    }
    @Override protected void onDestroy() {
        cancelDownloadPreparation();
        cancelBatchDownloads();
        savePosition(); playerOverlay.dispose(); dead = true; ++selectionToken; handler.removeCallbacksAndMessages(null); io.shutdownNow();
        if (coverLoader != null) coverLoader.close();
        if (seekPreviewLoader != null) seekPreviewLoader.close();
        coverMetadata.shutdownNow();
        if (player != null) { playerView.setPlayer(null); player.release(); player = null; }
        super.onDestroy();
    }
}
