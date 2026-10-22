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
    @Override protected DownloadManager getDownloadManager() { return MediaRepository.get(this).manager; }
    @Override protected Scheduler getScheduler() { return null; }
    @Override public void onTimeout(int startId, int foregroundServiceType) {
        // Android 15 limits background dataSync services; retain partial files for a later resume.
        getDownloadManager().pauseDownloads();
        stopSelf();
    }
    @Override protected Notification getForegroundNotification(List<Download> downloads, int unmetRequirements) {
        if (helper == null) helper = new DownloadNotificationHelper(this, "anime_downloads");
        return helper.buildProgressNotification(this, R.drawable.ic_download, null, "番匣 · 正在保存离线视频", downloads, unmetRequirements);
    }
}
