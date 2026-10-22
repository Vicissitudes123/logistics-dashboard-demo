package com.animeshelf.app;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DefaultDataSource;
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
import java.util.List;
import java.util.Map;
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
    private final DefaultDataSource.Factory upstream;
    private final Map<String, MediaHttpDataSource.Request> routes = new ConcurrentHashMap<>();
    final DownloadManager manager;
    public static final class OfflineItem {
        public String key, episodeId, title, qualityLabel, status, errorDetail;
        public Models.Episode episode;
        public Models.Quality quality;
        public boolean complete, failed, paused;
        public float percent;
        public long bytesDownloaded;
    }
    private MediaRepository(Context context) {
        this.context = context;
        store = new Store(context);
        StandaloneDatabaseProvider database = new StandaloneDatabaseProvider(context);
        cache = new SimpleCache(new File(context.getFilesDir(), "offline-media"), new NoOpCacheEvictor(), database);
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
        CacheDataSource.Factory data = new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream);
        manager = new DownloadManager(context, new androidx.media3.exoplayer.offline.DefaultDownloadIndex(database),
            new DefaultDownloaderFactory(data, Executors.newFixedThreadPool(3)));
        manager.setMaxParallelDownloads(2);
        manager.addListener(new DownloadManager.Listener() {
            @Override public void onDownloadChanged(DownloadManager downloadManager, Download download, Exception finalException) {
                if (finalException != null) store.saveDownloadError(download.request.id, MediaErrors.describe(finalException));
                else if (download.state == Download.STATE_COMPLETED) store.clearDownloadError(download.request.id);
            }
        });
    }
    private void register(String key, Models.Quality q) {
        registerTrack(key + ":v", q.videoCandidates(), q.headers, "视频");
        if (hasAudio(q)) registerTrack(key + ":a", q.audioCandidates(), q.headers, "音轨");
    }
    private void registerTrack(String id, List<String> urls, Map<String, String> qualityHeaders, String label) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", BiliClient.USER_AGENT);
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
        String key = key(e, q);
        for (OfflineItem old : downloads()) {
            if (old.episodeId.equals(e.id) && old.quality.id.equals(q.id) && !old.key.equals(key) && !old.complete)
                remove(old);
        }
        store.saveDownload(key, e, q);
        register(key, q);
        store.clearDownloadError(key + ":v");
        store.clearDownloadError(key + ":a");
        addTrack(key + ":v", q.videoUrl);
        if (q.audioUrl != null && !q.audioUrl.isEmpty()) addTrack(key + ":a", q.audioUrl);
        DownloadService.sendResumeDownloads(context, AnimeDownloadService.class, true);
    }
    private void addTrack(String id, String url) {
        DownloadRequest request = new DownloadRequest.Builder(id, Uri.parse(url))
            .setMimeType("video/mp4").setCustomCacheKey(id).build();
        DownloadService.sendAddDownload(context, AnimeDownloadService.class, request, true);
    }
    public void pauseAll() { DownloadService.sendPauseDownloads(context, AnimeDownloadService.class, false); }
    public void resumeAll() { DownloadService.sendResumeDownloads(context, AnimeDownloadService.class, true); }
    public void remove(OfflineItem item) {
        DownloadService.sendRemoveDownload(context, AnimeDownloadService.class, item.key + ":v", true);
        if (hasAudio(item.quality)) DownloadService.sendRemoveDownload(context, AnimeDownloadService.class, item.key + ":a", true);
        store.deleteDownload(item.key);
    }
    private boolean hasAudio(Models.Quality quality) { return quality.audioUrl != null && !quality.audioUrl.isEmpty(); }
    private Download track(String id) {
        for (Download d : manager.getCurrentDownloads()) if (id.equals(d.request.id)) return d;
        try { return manager.getDownloadIndex().getDownload(id); } catch (Exception ex) { return null; }
    }
    public List<OfflineItem> downloads() {
        ArrayList<OfflineItem> items = new ArrayList<>();
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
                item.complete = complete(video) && (!audioNeeded || complete(audio));
                item.failed = failed(video) || failed(audio);
                item.errorDetail = failureDetail(item.key, video, audio);
                item.paused = !item.complete && !item.failed && manager.getDownloadsPaused();
                item.bytesDownloaded = bytes(video) + bytes(audio);
                long total = total(video) + (audioNeeded ? total(audio) : 0);
                boolean known = total(video) > 0 && (!audioNeeded || total(audio) > 0);
                item.percent = item.complete ? 100f : known ? Math.min(99f, 100f * item.bytesDownloaded / total) : -1f;
                if (item.complete) item.status = "已离线 · 可断网播放";
                else if (item.failed) item.status = "下载失败 · 可重新解析重试";
                else if (item.paused) item.status = "已暂停";
                else if (manager.isWaitingForRequirements()) item.status = "等待网络";
                else if (video == null || (audioNeeded && audio == null)) item.status = "正在加入队列";
                else item.status = "下载中";
                items.add(item);
            } catch (Exception ignored) { }
        }
        return items;
    }
    private boolean complete(Download d) { return d != null && d.state == Download.STATE_COMPLETED; }
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
    private long bytes(Download d) { return d == null ? 0 : d.getBytesDownloaded(); }
    private long total(Download d) { return d == null || d.contentLength < 0 ? 0 : d.contentLength; }
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
    private MediaSource source(CacheDataSource.Factory data, String uri, String key) {
        return new ProgressiveMediaSource.Factory(data).createMediaSource(new MediaItem.Builder()
            .setUri(uri).setCustomCacheKey(key).build());
    }
}
