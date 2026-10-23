package com.animeshelf.app;

import android.app.Notification;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadNotificationHelper;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.scheduler.Scheduler;
import java.util.List;

@UnstableApi
public final class AnimeDownloadService extends DownloadService {
    private DownloadNotificationHelper helper;
    public AnimeDownloadService() { super(2101, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, "anime_downloads", R.string.download_channel, 0); }
    @Override public void onCreate() { super.onCreate(); helper = new DownloadNotificationHelper(this, "anime_downloads"); }
    @Override protected DownloadManager getDownloadManager() {
        MediaRepository repository = MediaRepository.get(this);
        repository.applyDownloadNetworkPolicy();
        return repository.manager;
    }
    @Override protected Scheduler getScheduler() { return null; }
    @Override public void onTimeout(int startId, int foregroundServiceType) {
        // Android 15 limits background dataSync services; retain partial files for a later resume.
        MediaRepository.get(this).pauseForServiceTimeout();
        stopSelf();
    }
    @Override protected Notification getForegroundNotification(List<Download> downloads, int unmetRequirements) {
        if (helper == null) helper = new DownloadNotificationHelper(this, "anime_downloads");
        MediaRepository repository = MediaRepository.get(this);
        DownloadNetworkPolicy.Info network = repository.downloadNetworkInfo();
        boolean userPaused = false;
        for (Download download : downloads)
            userPaused |= (download.stopReason & DownloadNetworkPolicy.STOP_USER_PAUSED) != 0;
        String description = userPaused ? "番匣 · 下载已暂停"
            : network.wifiOnly && !network.allowed
            ? "番匣 · 等待 Wi-Fi 后继续下载" : "番匣 · 正在保存离线视频";
        return helper.buildProgressNotification(this, R.drawable.ic_download, null, description, downloads, unmetRequirements);
    }
}
