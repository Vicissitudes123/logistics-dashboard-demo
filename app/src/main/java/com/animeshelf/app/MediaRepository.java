package com.animeshelf.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

@UnstableApi
public final class MediaRepository {
    private static MediaRepository instance;
    public static synchronized MediaRepository get(Context context) {
        if (instance == null) instance = new MediaRepository(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final Store store;
    private final SimpleCache cache;
    private final File storageDirectory;
    private final DefaultDataSource.Factory upstream;
    private final DownloadNetworkPolicy networkPolicy;
    private final SharedPreferences policyPreferences;
    private final DownloadControls controls;
    private volatile boolean manuallyPaused;
    private volatile boolean released;
    private int appliedPolicyStopReason = Integer.MIN_VALUE;
    private final Map<String, MediaHttpDataSource.Request> routes = new ConcurrentHashMap<>();
    private final DownloadTelemetry telemetry = new DownloadTelemetry();
    private final Handler telemetryHandler = new Handler(Looper.getMainLooper());
    private boolean telemetryScheduled;
    private final Runnable telemetryTick = () -> { telemetryScheduled = false; sampleTelemetry(); };
    final DownloadManager manager;
    public static final class OfflineItem {
        public String key, episodeId, title, qualityLabel, status, errorDetail;
        public Models.Episode episode;
        public Models.Quality quality;
        public boolean complete, failed, paused, queued, downloading, waitingForNetwork, waitingForWifi, priority;
        public DownloadStatus.State state;
        public DownloadStatus.FailureKind failureKind = DownloadStatus.FailureKind.NONE;
        public float percent;
        public long bytesDownloaded;
        /** -1 means unknown; speed is 0 while the item is not actively downloading. */
        public long totalBytes = -1, bytesPerSecond = -1, estimatedRemainingSeconds = -1, availableBytes = -1;
    }
    private MediaRepository(Context context) {
        this.context = context;
        store = new Store(context);
        policyPreferences = context.getSharedPreferences("anime_download_policy", Context.MODE_PRIVATE);
        controls = new DownloadControls(policyPreferences);
        manuallyPaused = policyPreferences.getBoolean("manually_paused", false);
        networkPolicy = new DownloadNetworkPolicy(context, store::isWifiOnlyDownloads,
            telemetryHandler, this::applyDownloadNetworkPolicy);
        storageDirectory = context.getFilesDir();
        StandaloneDatabaseProvider database = new StandaloneDatabaseProvider(context);
        cache = new SimpleCache(new File(storageDirectory, "offline-media"), new NoOpCacheEvictor(), database);
        // Restore routes before the manager can resume persisted requests after process death.
        for (JSONObject saved : store.savedDownloads()) {
            try { register(saved.getString("key"), Models.Quality.fromJson(saved.getJSONObject("quality"))); }
            catch (Exception ignored) { }
        }
        MediaHttpDataSource.Factory http = new MediaHttpDataSource.Factory(spec -> {
            MediaHttpDataSource.Request route = spec.key == null ? null : routes.get(spec.key);
            return route == null ? routes.get(spec.uri.toString()) : route;
        });
        // Login cookies are used only by BiliClient. Never send account cookies to media CDNs.
        upstream = new DefaultDataSource.Factory(context, http);
        // Playback keeps the ordinary upstream. Only offline downloads receive the final
        // open/read guard, including tasks restored from the persisted download index.
        DownloadPolicyDataSource.Factory downloadUpstream = new DownloadPolicyDataSource.Factory(
            upstream, this::downloadNetworkAllowed, this::scheduleNetworkPolicyApply);
        CacheDataSource.Factory data = new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(downloadUpstream);
        manager = new DownloadManager(context, new androidx.media3.exoplayer.offline.DefaultDownloadIndex(database),
            new DefaultDownloaderFactory(data, Executors.newFixedThreadPool(3)));
        // DownloadService.onCreate() calls resumeDownloads itself. Install persistent stop
        // reasons before the service can do that, not only after new tasks are enqueued.
        applyDownloadNetworkPolicy();
        manager.setMaxParallelDownloads(2);
        manager.addListener(new DownloadManager.Listener() {
            @Override public void onDownloadChanged(DownloadManager downloadManager, Download download, Exception finalException) {
                if (isPolicyBlock(finalException)) {
                    // A network change can race the final read. Keep the task resumable instead
                    // of making a deliberate policy stop look like a permanent download failure.
                    if (isSavedDownloadRequest(download.request.id))
                        manager.addDownload(download.request, trackStopReason(download.request.id));
                    scheduleNetworkPolicyApply();
                    return;
                }
                if (finalException != null) store.saveDownloadError(download.request.id, DownloadStatus.safeFailure(finalException));
                else if (download.state == Download.STATE_COMPLETED) store.clearDownloadError(download.request.id);
                releaseFinishedPriority();
                sampleTelemetry();
            }
            @Override public void onInitialized(DownloadManager downloadManager) { releaseFinishedPriority(); applyDownloadNetworkPolicy(); sampleTelemetry(); }
            @Override public void onDownloadsPausedChanged(DownloadManager downloadManager, boolean downloadsPaused) { sampleTelemetry(); }
        });
        networkPolicy.start();
        sampleTelemetry();
    }

    public DownloadNetworkPolicy.Info downloadNetworkInfo() { return networkPolicy.info(); }

    /** Apply after the Wi-Fi-only preference changes. Network callbacks use the same path. */
    public void applyDownloadNetworkPolicy() {
        if (released) return;
        if (Looper.myLooper() != Looper.getMainLooper()) { scheduleNetworkPolicyApply(); return; }
        int reason = currentPolicyStopReason();
        if (appliedPolicyStopReason != reason) { appliedPolicyStopReason = reason; telemetry.clear(); }
        for (JSONObject saved : store.savedDownloads()) {
            String key = saved.optString("key");
            int stop = controls.reason(key, reason);
            manager.setStopReason(key + ":v", stop);
            manager.setStopReason(key + ":a", stop);
        }
        // Deliberately never call resumeDownloads here: reconnecting must not undo a user pause.
    }

    private int currentPolicyStopReason() { return DownloadNetworkPolicy.stopReason(networkPolicy.info(), manuallyPaused); }
    private boolean downloadNetworkAllowed() { return !manuallyPaused && networkPolicy.info().allowed; }
    private void scheduleNetworkPolicyApply() {
        if (!released) telemetryHandler.post(this::applyDownloadNetworkPolicy);
    }

    /** Dispose singleton resources between isolated Android tests. */
    synchronized void releaseForTests() {
        released = true;
        networkPolicy.close();
        telemetryHandler.removeCallbacksAndMessages(null);
        manager.release();
        cache.release();
        if (instance == this) instance = null;
    }

    private static boolean isPolicyBlock(Throwable error) {
        for (int depth = 0; error != null && depth < 16; depth++, error = error.getCause())
            if (error instanceof DownloadPolicyDataSource.PolicyBlockedException) return true;
        return false;
    }

    private boolean isSavedDownloadRequest(String id) {
        // A pending read-failure callback must not resurrect a request deleted by the user.
        for (JSONObject saved : store.savedDownloads()) {
            String key = saved.optString("key", "");
            if (!key.isEmpty() && (id.equals(key + ":v") || id.equals(key + ":a"))) return true;
        }
        return false;
    }

    /** Read the partition that contains this application's offline-media cache. */
    public long availableStorageBytes() {
        try { return storageDirectory.getUsableSpace(); }
        catch (SecurityException unavailable) { return -1; }
    }

    private void sampleTelemetry() {
        if (released) return;
        if (Looper.myLooper() != Looper.getMainLooper()) { telemetryHandler.post(this::sampleTelemetry); return; }
        long now = SystemClock.elapsedRealtime();
        boolean anyActive = false;
        Set<String> live = new HashSet<>();
        for (Download download : manager.getCurrentDownloads()) {
            live.add(download.request.id);
            boolean active = download.state == Download.STATE_DOWNLOADING && !manager.getDownloadsPaused();
            anyActive |= active;
            telemetry.sample(download.request.id, now, download.getBytesDownloaded(), active);
        }
        telemetry.retain(live);
        if (anyActive && !telemetryScheduled) {
            telemetryScheduled = true;
            telemetryHandler.postDelayed(telemetryTick, 1000);
        } else if (!anyActive && telemetryScheduled) {
            telemetryHandler.removeCallbacks(telemetryTick);
            telemetryScheduled = false;
        }
    }
    private void register(String key, Models.Quality q) {
        registerTrack(key + ":v", q.videoCandidates(), q.headers, "视频");
        if (hasAudio(q)) registerTrack(key + ":a", q.audioCandidates(), q.headers, "音轨");
    }
    private void registerTrack(String id, List<String> urls, Map<String, String> qualityHeaders, String label) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", BiliClient.MEDIA_USER_AGENT);
        headers.put("Referer", BiliClient.REFERER);
        headers.put("Origin", "https://www.bilibili.com");
        if (qualityHeaders != null) headers.putAll(qualityHeaders);
        // The data source applies a final public-header allowlist on every attempt.
        MediaHttpDataSource.Request request = new MediaHttpDataSource.Request(new ArrayList<>(urls), headers, label);
        routes.put(id, request);
        for (String url : urls) routes.put(url, request);
    }
    public static String key(Models.Episode e, Models.Quality q) {
        try {
            String identity = e.id + "|" + q.id + "|" + resourcePath(q.videoUrl) + "|" + resourcePath(q.audioUrl);
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private static String resourcePath(String url) {
        if (url == null || url.isEmpty()) return "";
        // Expiry tokens/CDN mirrors can change while the resource remains the same.
        // A new resource path must never reuse partial bytes from an older encode.
        return Uri.parse(url).getEncodedPath();
    }
    public void enqueue(Models.Episode e, Models.Quality q) {
        if (q.videoUrl == null || q.videoUrl.isEmpty()) throw new IllegalArgumentException("视频地址为空，请重新解析");
        if (e.localUri != null && !e.localUri.isEmpty()) throw new IllegalArgumentException("本地视频无需下载");
        DownloadStatus.requireStartSpace(availableStorageBytes());
        applyDownloadNetworkPolicy();
        String key = key(e, q);
        for (OfflineItem old : downloads()) {
            if (old.episodeId.equals(e.id) && old.quality.id.equals(q.id) && !old.key.equals(key) && !old.complete)
                remove(old);
        }
        store.saveDownload(key, e, q);
        register(key, q);
        store.clearDownloadError(key + ":v");
        store.clearDownloadError(key + ":a");
        telemetry.forget(key + ":v"); telemetry.forget(key + ":a");
        addTrack(key + ":v", q.videoUrl);
        if (q.audioUrl != null && !q.audioUrl.isEmpty()) addTrack(key + ":a", q.audioUrl);
        if (!manuallyPaused) DownloadService.sendResumeDownloads(context, AnimeDownloadService.class, true);
    }
    private void addTrack(String id, String url) {
        DownloadRequest request = new DownloadRequest.Builder(id, Uri.parse(url))
            .setMimeType("video/mp4").setCustomCacheKey(id).build();
        DownloadService.sendAddDownload(context, AnimeDownloadService.class, request, trackStopReason(id), true);
    }
    public boolean isManuallyPaused() { return manuallyPaused; }
    public void pauseAll() {
        markManuallyPaused(true);
        DownloadService.sendPauseDownloads(context, AnimeDownloadService.class, false);
    }
    public void resumeAll() {
        controls.resumeAll();
        markManuallyPaused(false);
        DownloadService.sendResumeDownloads(context, AnimeDownloadService.class, true);
    }
    void pauseForServiceTimeout() {
        markManuallyPaused(true);
        manager.pauseDownloads();
    }
    private void markManuallyPaused(boolean paused) {
        manuallyPaused = paused;
        policyPreferences.edit().putBoolean("manually_paused", paused).apply();
        telemetry.clear();
        applyDownloadNetworkPolicy();
    }
    private int trackStopReason(String id) {
        return controls.reason(id.endsWith(":v") || id.endsWith(":a") ? id.substring(0, id.length() - 2) : id, currentPolicyStopReason());
    }
    public void pause(OfflineItem item) {
        controls.pause(item.key, true); applyDownloadNetworkPolicy();
    }
    public void resume(OfflineItem item) {
        // Resuming one item after "pause all" keeps every other unfinished item paused.
        if (manuallyPaused) {
            for (OfflineItem other : downloads()) if (!other.complete && !other.key.equals(item.key)) controls.pause(other.key, true);
            manuallyPaused = false; policyPreferences.edit().putBoolean("manually_paused", false).apply();
        }
        controls.pause(item.key, false); applyDownloadNetworkPolicy();
        DownloadService.sendResumeDownloads(context, AnimeDownloadService.class, true);
    }
    public void prioritize(OfflineItem item) {
        if (item.complete || item.failed) return;
        resume(item); controls.prioritize(item.key); applyDownloadNetworkPolicy();
    }
    public void clearPriority() { controls.clearPriority(); applyDownloadNetworkPolicy(); }
    private void releaseFinishedPriority() {
        String key = controls.priority();
        if (key.isEmpty()) return;
        for (JSONObject saved : store.savedDownloads()) if (key.equals(saved.optString("key"))) {
            Models.Quality q = Models.Quality.fromJson(saved.optJSONObject("quality"));
            Download v = track(key + ":v"), a = hasAudio(q) ? track(key + ":a") : null;
            if (!(complete(v) && (!hasAudio(q) || complete(a))) && !failed(v) && !failed(a)) return;
            break;
        }
        controls.clearPriority(); applyDownloadNetworkPolicy();
    }
    public void remove(OfflineItem item) {
        PendingDownloads.cancel(context, item.episode, item.quality);
        controls.forget(item.key);
        DownloadService.sendRemoveDownload(context, AnimeDownloadService.class, item.key + ":v", true);
        if (hasAudio(item.quality)) DownloadService.sendRemoveDownload(context, AnimeDownloadService.class, item.key + ":a", true);
        store.deleteDownload(item.key);
        applyDownloadNetworkPolicy();
        telemetry.forget(item.key + ":v"); telemetry.forget(item.key + ":a");
    }
    private boolean hasAudio(Models.Quality quality) { return quality.audioUrl != null && !quality.audioUrl.isEmpty(); }
    private Download track(String id) {
        for (Download d : manager.getCurrentDownloads()) if (id.equals(d.request.id)) return d;
        try { return manager.getDownloadIndex().getDownload(id); } catch (Exception ex) { return null; }
    }
    public List<OfflineItem> downloads() {
        ArrayList<OfflineItem> items = new ArrayList<>();
        long availableBytes = availableStorageBytes();
        DownloadNetworkPolicy.Info network = networkPolicy.info();
        for (JSONObject saved : store.savedDownloads()) {
            try {
                OfflineItem item = new OfflineItem();
                item.key = saved.getString("key");
                item.episode = Models.Episode.fromJson(saved.getJSONObject("episode"));
                item.quality = Models.Quality.fromJson(saved.getJSONObject("quality"));
                item.episodeId = item.episode.id;
                item.title = item.episode.seriesTitle + " · " + item.episode.title;
                item.qualityLabel = item.quality.label;
                Download video = track(item.key + ":v"), audio = hasAudio(item.quality) ? track(item.key + ":a") : null;
                boolean audioNeeded = hasAudio(item.quality);
                DownloadStatus.Snapshot snapshot = DownloadStatus.summarize(video, audio, audioNeeded,
                    manuallyPaused || manager.getDownloadsPaused(), manager.getNotMetRequirements());
                item.state = snapshot.state;
                if (!manuallyPaused && !controls.paused(item.key) && network.wifiOnly && !network.allowed
                    && item.state != DownloadStatus.State.COMPLETED && item.state != DownloadStatus.State.FAILED
                    && item.state != DownloadStatus.State.REMOVING) {
                    item.state = DownloadStatus.State.WAITING_NETWORK;
                    item.waitingForWifi = true;
                }
                boolean priorityWaiting = !controls.priority().isEmpty() && !controls.priority().equals(item.key)
                    && !manuallyPaused && !controls.paused(item.key) && network.allowed
                    && item.state != DownloadStatus.State.COMPLETED && item.state != DownloadStatus.State.FAILED
                    && item.state != DownloadStatus.State.REMOVING;
                if (priorityWaiting) item.state = DownloadStatus.State.QUEUED;
                item.priority = controls.priority().equals(item.key);
                item.complete = item.state == DownloadStatus.State.COMPLETED;
                item.failed = item.state == DownloadStatus.State.FAILED;
                item.paused = item.state == DownloadStatus.State.PAUSED;
                item.queued = item.state == DownloadStatus.State.QUEUED || item.state == DownloadStatus.State.ADDING;
                item.downloading = item.state == DownloadStatus.State.DOWNLOADING;
                item.waitingForNetwork = item.state == DownloadStatus.State.WAITING_NETWORK;
                item.errorDetail = failureDetail(item.key, video, audio);
                item.failureKind = failureKind(item.key, video, audio);
                item.bytesDownloaded = snapshot.bytesDownloaded;
                item.totalBytes = snapshot.totalBytes;
                item.percent = snapshot.percent;
                item.availableBytes = availableBytes;
                long videoRate = speed(video), audioRate = audioNeeded ? speed(audio) : 0;
                item.bytesPerSecond = !item.downloading ? 0 : videoRate < 0 || audioRate < 0
                    ? -1 : DownloadStatus.add(videoRate, audioRate);
                item.estimatedRemainingSeconds = item.complete ? 0 : item.downloading
                    ? DownloadTelemetry.remainingSeconds(item.totalBytes, item.bytesDownloaded, item.bytesPerSecond) : -1;
                item.status = DownloadStatus.label(item.state);
                if (priorityWaiting) item.status = "等待优先集下载";
                if (item.priority) item.status += " · 优先下载";
                if (item.waitingForWifi) item.status = "等待 Wi-Fi · 自动继续下载";
                if (item.failed) item.status += " · " + DownloadStatus.failureSummary(item.failureKind);
                items.add(item);
            } catch (Exception ignored) { }
        }
        return items;
    }
    private boolean complete(Download d) { return d != null && d.state == Download.STATE_COMPLETED; }
    private long speed(Download download) {
        return download == null || download.state != Download.STATE_DOWNLOADING ? 0
            : telemetry.bytesPerSecond(download.request.id);
    }
    private DownloadStatus.FailureKind failureKind(String key, Download video, Download audio) {
        DownloadStatus.FailureKind result = DownloadStatus.FailureKind.NONE;
        if (failed(video)) result = DownloadStatus.classifySaved(store.downloadError(key + ":v"));
        if (failed(audio)) {
            DownloadStatus.FailureKind next = DownloadStatus.classifySaved(store.downloadError(key + ":a"));
            if (result == DownloadStatus.FailureKind.NONE || result == DownloadStatus.FailureKind.OTHER
                || next == DownloadStatus.FailureKind.STORAGE) result = next;
        }
        return result;
    }
    private String failureDetail(String key, Download video, Download audio) {
        StringBuilder detail = new StringBuilder();
        if (failed(video)) appendFailure(detail, "视频", store.downloadError(key + ":v"));
        if (failed(audio)) appendFailure(detail, "音轨", store.downloadError(key + ":a"));
        return detail.toString();
    }
    private void appendFailure(StringBuilder detail, String label, String failure) {
        if (detail.length() > 0) detail.append("\n\n");
        detail.append(label).append("下载失败\n").append(failure.isEmpty()
            ? "旧任务未保存 HTTP 详情，请重新解析重试后查看。" : failure);
    }
    private boolean failed(Download d) { return d != null && d.state == Download.STATE_FAILED; }
    public boolean isDownloaded(Models.Episode e, Models.Quality q) {
        String key = key(e, q);
        return complete(track(key + ":v")) && (!hasAudio(q) || complete(track(key + ":a")));
    }
    public MediaSource createMediaSource(Models.Episode e, Models.Quality q, boolean offline) {
        if (e.localUri != null && !e.localUri.isEmpty())
            return new DefaultMediaSourceFactory(context).createMediaSource(MediaItem.fromUri(e.localUri));
        if (offline && !isDownloaded(e, q)) throw new IllegalStateException("这一清晰度尚未完整下载");
        if (!offline) register(key(e, q), q);
        CacheDataSource.Factory data = new CacheDataSource.Factory().setCache(cache)
            .setCacheWriteDataSinkFactory(null).setUpstreamDataSourceFactory(offline ? null : upstream);
        String key = key(e, q);
        MediaSource video = source(data, q.videoUrl, key + ":v");
        return hasAudio(q) ? new MergingMediaSource(video, source(data, q.audioUrl, key + ":a")) : video;
    }

    /** Does not open media. The frame worker gets only the video track and cannot modify downloads. */
    public SeekPreviewLoader.Source createPreviewSource(Models.Episode episode, Models.Quality quality, boolean offline) {
        if (episode.localUri != null && !episode.localUri.isEmpty()) {
            return new SeekPreviewLoader.Source("local:" + episode.id,
                new DefaultDataSource.Factory(context), new DataSpec.Builder().setUri(episode.localUri).build());
        }
        if (quality == null || quality.videoUrl == null || quality.videoUrl.isEmpty())
            throw new IllegalArgumentException("缺少预览视频源");
        if (offline && !isDownloaded(episode, quality)) throw new IllegalStateException("预览离线文件未完整保存");
        String key = key(episode, quality);
        if (!offline) register(key, quality);
        MediaHttpDataSource.Request request = routes.get(key + ":v");
        // Short preview-specific timeouts leave normal playback/download retry behavior unchanged.
        DefaultDataSource.Factory previewUpstream = new DefaultDataSource.Factory(context,
            new MediaHttpDataSource.Factory(spec -> request,
                new DefaultHttpDataSource.Factory().setConnectTimeoutMs(1500).setReadTimeoutMs(1500)));
        CacheDataSource.Factory readOnly = previewDataFactory(cache, previewUpstream, offline);
        return new SeekPreviewLoader.Source((offline ? "offline:" : "online:") + key, readOnly,
            new DataSpec.Builder().setUri(quality.videoUrl).setKey(key + ":v").build());
    }

    static CacheDataSource.Factory previewDataFactory(SimpleCache cache, DataSource.Factory online, boolean offline) {
        return new CacheDataSource.Factory().setCache(cache).setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(offline ? null : online);
    }
    private MediaSource source(CacheDataSource.Factory data, String uri, String key) {
        return new ProgressiveMediaSource.Factory(data).createMediaSource(new MediaItem.Builder()
            .setUri(uri).setCustomCacheKey(key).build());
    }
}
