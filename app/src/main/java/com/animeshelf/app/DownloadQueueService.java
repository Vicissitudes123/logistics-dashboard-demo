package com.animeshelf.app;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.webkit.CookieManager;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns confirmed batch work independently of the activity, including URL renewal on resume. */
@androidx.media3.common.util.UnstableApi
public final class DownloadQueueService extends Service {
    private static final int NOTIFICATION = 2102;
    private final Handler handler = new Handler(android.os.Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean busy, dead;
    private int added;
    private PowerManager.WakeLock wake;
    private final Runnable pump = this::next;
    static void start(Context context) {
        if (PendingDownloads.hasReady(context)) context.startForegroundService(new Intent(context, DownloadQueueService.class));
    }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel("batch_queue", "批量下载准备", NotificationManager.IMPORTANCE_LOW));
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AnimeShelf:confirmed-downloads"); wake.setReferenceCounted(false);
        startForeground(NOTIFICATION, notification("正在恢复已确认的下载任务"));
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) { handler.removeCallbacks(pump); handler.post(pump); return START_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 2102, new Intent(this, MainActivity.class)
            .putExtra("openDownloads", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "batch_queue").setSmallIcon(R.drawable.ic_download).setContentTitle("番匣 · 批量下载")
            .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).build();
    }
    private void report(String text) { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification(text)); }
    private void next() {
        if (dead || busy) return;
        List<PendingDownloads.Job> jobs = PendingDownloads.list(this);
        PendingDownloads.Job selected = null;
        for (PendingDownloads.Job job : jobs) if (job.error.isEmpty()) { selected = job; break; }
        if (selected == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return; }
        MediaRepository repository = MediaRepository.get(this);
        if (!repository.downloadNetworkInfo().allowed || repository.isManuallyPaused()) {
            if (wake.isHeld()) wake.release();
            report(repository.isManuallyPaused() ? "已暂停 · 待加入 " + jobs.size() + " 集" : "等待允许的网络 · 待加入 " + jobs.size() + " 集");
            handler.postDelayed(pump, 5000); return;
        }
        if (!wake.isHeld()) wake.acquire(10 * 60_000L);
        PendingDownloads.Job job = selected;
        busy = true;
        report("已加入 " + added + " 集 · 待处理 " + jobs.size() + " 集 · 当前第 " + job.episode.index + " 集");
        String cookie = CookieManager.getInstance().getCookie("https://api.bilibili.com");
        io.execute(() -> {
            Models.Quality quality = null; String failure = "";
            try {
                quality = job.prepared;
                if (quality == null || System.currentTimeMillis() - job.preparedAt > 10 * 60_000L)
                    quality = DownloadCatalogue.matching(new BiliClient(cookie).qualities(job.episode), job.profile);
                if (quality == null) failure = "原画质不可用，请重新选择画质";
            } catch (Exception error) { failure = "解析未完成，请检查网络或登录后重试"; }
            Models.Quality ready = quality; String error = failure;
            handler.post(() -> {
                busy = false;
                if (dead) return;
                if (PendingDownloads.contains(this, job.id)) {
                    String result = error;
                    try {
                        if (result.isEmpty()) {
                            MediaRepository.OfflineItem existing = DownloadCatalogue.existing(repository.downloads(), job.episode, ready);
                            if (existing == null || existing.failed || existing.state == DownloadStatus.State.ADDING) repository.enqueue(job.episode, ready);
                            added++;
                        }
                    } catch (Exception failed) { result = DownloadStatus.safeFailure(failed); }
                    PendingDownloads.finish(this, job.id, result);
                }
                handler.postDelayed(pump, 350);
            });
        });
    }
    @Override public void onTimeout(int startId, int type) { stopSelf(); }
    @Override public void onDestroy() { dead = true; handler.removeCallbacksAndMessages(null); io.shutdownNow(); if (wake != null && wake.isHeld()) wake.release(); super.onDestroy(); }
}
