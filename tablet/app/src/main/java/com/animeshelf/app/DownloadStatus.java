package com.animeshelf.app;

import android.system.ErrnoException;
import android.system.OsConstants;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.scheduler.Requirements;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.file.FileSystemException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Aggregate the actual video/audio states; a queued track is not an active transfer. */
@UnstableApi
public final class DownloadStatus {
    private DownloadStatus() {}
    public static final long MIN_START_SPACE_BYTES = 32L * 1024 * 1024;
    public enum State { COMPLETED, FAILED, PAUSED, WAITING_NETWORK, WAITING_REQUIREMENTS, ADDING, QUEUED, DOWNLOADING, REMOVING }
    public enum FailureKind { NONE, NETWORK, ADDRESS_UNAVAILABLE, SERVER_REJECTED, STORAGE, OTHER }

    /** Only this class can create its fixed, locally authored storage explanation. */
    public static final class StorageException extends IllegalStateException {
        private StorageException() { super("应用可用存储不足 32 MB，已停止添加下载。请先清理空间后重试。"); }
    }

    public static void requireStartSpace(long usableBytes) {
        // Unknown media length is not evidence that the rest of an episode will fit.
        // This is only an early rejection when the app's storage is already critically low.
        if (usableBytes >= 0 && usableBytes < MIN_START_SPACE_BYTES) throw new StorageException();
    }

    public static final class Snapshot {
        public final State state;
        public final long bytesDownloaded, totalBytes;
        public final float percent;
        private Snapshot(State state, long bytesDownloaded, long totalBytes) {
            this.state = state; this.bytesDownloaded = bytesDownloaded; this.totalBytes = totalBytes;
            percent = state == State.COMPLETED ? 100f : totalBytes > 0
                ? Math.min(99f, (float) (100d * bytesDownloaded / totalBytes)) : -1f;
        }
    }

    public static Snapshot summarize(Download video, Download audio, boolean audioNeeded,
                                     boolean paused, int notMetRequirements) {
        if (!audioNeeded) audio = null;
        long bytes = add(bytes(video), bytes(audio));
        long videoTotal = total(video), audioTotal = audioNeeded ? total(audio) : 0;
        long total = videoTotal < 0 || audioTotal < 0 ? -1 : add(videoTotal, audioTotal);
        State state;
        if (is(video, Download.STATE_COMPLETED) && (!audioNeeded || is(audio, Download.STATE_COMPLETED))) state = State.COMPLETED;
        else if (is(video, Download.STATE_FAILED) || is(audio, Download.STATE_FAILED)) state = State.FAILED;
        else if (is(video, Download.STATE_REMOVING) || is(audio, Download.STATE_REMOVING)) state = State.REMOVING;
        else if (paused) state = State.PAUSED;
        else if (is(video, Download.STATE_DOWNLOADING) || is(audio, Download.STATE_DOWNLOADING)) state = State.DOWNLOADING;
        else if (is(video, Download.STATE_STOPPED) || is(audio, Download.STATE_STOPPED)) state = State.PAUSED;
        else if ((notMetRequirements & (Requirements.NETWORK | Requirements.NETWORK_UNMETERED)) != 0) state = State.WAITING_NETWORK;
        else if (notMetRequirements != 0) state = State.WAITING_REQUIREMENTS;
        else if (video == null || (audioNeeded && audio == null)) state = State.ADDING;
        else state = State.QUEUED;
        return new Snapshot(state, bytes, total);
    }

    public static String label(State state) {
        switch (state) {
            case COMPLETED: return "已离线 · 可断网播放";
            case FAILED: return "下载失败";
            case PAUSED: return "已暂停";
            case WAITING_NETWORK: return "等待网络";
            case WAITING_REQUIREMENTS: return "等待下载条件";
            case ADDING: return "正在加入队列";
            case DOWNLOADING: return "下载中";
            case REMOVING: return "正在删除";
            default: return "排队等待下载";
        }
    }

    public static FailureKind classify(Throwable error) {
        List<Throwable> chain = causes(error);
        for (Throwable cause : chain) {
            if (cause instanceof StorageException) return FailureKind.STORAGE;
            if (cause instanceof ErrnoException) {
                int errno = ((ErrnoException) cause).errno;
                if (errno == OsConstants.ENOSPC || errno == OsConstants.EDQUOT) return FailureKind.STORAGE;
            }
            if (cause instanceof FileSystemException) {
                String reason = ((FileSystemException) cause).getReason();
                if ("No space left on device".equals(reason) || "Disk quota exceeded".equals(reason)
                    || "ENOSPC".equals(reason) || "EDQUOT".equals(reason)) return FailureKind.STORAGE;
            }
        }
        for (Throwable cause : chain) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                int status = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                if (status == 401 || status == 403) return FailureKind.SERVER_REJECTED;
                if (status == 404 || status == 410 || status == 416) return FailureKind.ADDRESS_UNAVAILABLE;
                return FailureKind.OTHER;
            }
        }
        for (Throwable cause : chain) if (cause instanceof UnknownHostException
            || cause instanceof SocketException || cause instanceof InterruptedIOException) return FailureKind.NETWORK;
        return error == null ? FailureKind.NONE : FailureKind.OTHER;
    }

    public static String failureSummary(FailureKind kind) {
        switch (kind) {
            case STORAGE: return "存储空间不足，请先清理应用所在存储，再重试下载。";
            case NETWORK: return "网络连接失败或中断，请检查网络后重试。";
            case ADDRESS_UNAVAILABLE: return "下载地址不可用或已失效，请重新解析后重试。";
            case SERVER_REJECTED: return "服务器拒绝下载请求；请重新解析，仍失败时检查观看权限及网络。";
            case NONE: return "";
            default: return "下载未能完成，可查看详情并重试。";
        }
    }

    public static String safeFailure(Throwable error) {
        String summary = failureSummary(classify(error));
        return (summary.isEmpty() ? "" : summary + "\n") + MediaErrors.describe(error);
    }

    public static FailureKind classifySaved(String detail) {
        if (detail == null || detail.isEmpty()) return FailureKind.OTHER;
        for (FailureKind kind : FailureKind.values()) {
            String summary = failureSummary(kind);
            if (!summary.isEmpty() && detail.startsWith(summary)) return kind;
        }
        // Older versions saved only MediaErrors' fixed safe diagnostic text.
        if (detail.contains("HTTP 401") || detail.contains("HTTP 403")) return FailureKind.SERVER_REJECTED;
        if (detail.contains("HTTP 404") || detail.contains("HTTP 410") || detail.contains("HTTP 416")) return FailureKind.ADDRESS_UNAVAILABLE;
        if (detail.contains("域名解析失败") || detail.contains("连接或读取超时") || detail.contains("无法连接服务器")
            || detail.contains("网络连接异常") || detail.contains("无法到达媒体服务器")) return FailureKind.NETWORK;
        return FailureKind.OTHER;
    }

    private static boolean is(Download download, int state) { return download != null && download.state == state; }
    private static long bytes(Download download) { return download == null ? 0 : Math.max(0, download.getBytesDownloaded()); }
    private static long total(Download download) {
        if (download == null) return -1;
        if (download.contentLength >= 0) return download.contentLength;
        return download.state == Download.STATE_COMPLETED ? bytes(download) : -1;
    }
    static long add(long a, long b) { return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b; }
    private static List<Throwable> causes(Throwable error) {
        List<Throwable> result = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = error; current != null && result.size() < 16 && seen.add(current); current = current.getCause()) result.add(current);
        return result;
    }
}
