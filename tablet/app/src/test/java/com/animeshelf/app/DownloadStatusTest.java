package com.animeshelf.app;

import static org.junit.Assert.*;
import android.net.Uri;
import android.system.ErrnoException;
import android.system.OsConstants;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadProgress;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.scheduler.Requirements;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class DownloadStatusTest {
    private static final String SECRET = "private-download-secret";

    private static Download track(String id, int state, long total, long bytes) {
        DownloadProgress progress = new DownloadProgress();
        progress.bytesDownloaded = bytes;
        DownloadRequest request = new DownloadRequest.Builder(id, Uri.parse("https://cdn.example/file?token=" + SECRET)).build();
        return new Download(request, state, 0, 0, total,
            state == Download.STATE_STOPPED ? 1 : 0,
            state == Download.STATE_FAILED ? Download.FAILURE_REASON_UNKNOWN : Download.FAILURE_REASON_NONE, progress);
    }

    @Test public void queuedDownloadingNetworkWaitAndManualPauseAreDistinct() {
        Download queued = track("v", Download.STATE_QUEUED, -1, 0);
        assertEquals(DownloadStatus.State.QUEUED, DownloadStatus.summarize(queued, null, false, false, 0).state);
        assertEquals(DownloadStatus.State.WAITING_NETWORK,
            DownloadStatus.summarize(queued, null, false, false, Requirements.NETWORK).state);
        assertEquals(DownloadStatus.State.WAITING_REQUIREMENTS,
            DownloadStatus.summarize(queued, null, false, false, Requirements.DEVICE_CHARGING).state);
        assertEquals(DownloadStatus.State.PAUSED,
            DownloadStatus.summarize(queued, null, false, true, Requirements.NETWORK).state);
        assertEquals(DownloadStatus.State.DOWNLOADING,
            DownloadStatus.summarize(track("v", Download.STATE_DOWNLOADING, 100, 20), null, false, false, 0).state);
        assertEquals("排队等待下载", DownloadStatus.label(DownloadStatus.State.QUEUED));
    }

    @Test public void twoTracksRequireBothToCompleteAndBothTotalsToBeKnown() {
        Download video = track("v", Download.STATE_COMPLETED, 100, 100);
        Download audio = track("a", Download.STATE_QUEUED, C.LENGTH_UNSET, 10);
        DownloadStatus.Snapshot partial = DownloadStatus.summarize(video, audio, true, false, 0);
        assertEquals(DownloadStatus.State.QUEUED, partial.state);
        assertEquals(110, partial.bytesDownloaded);
        assertEquals(-1, partial.totalBytes);
        assertEquals(-1f, partial.percent, 0f);
        audio = track("a", Download.STATE_DOWNLOADING, 50, 10);
        partial = DownloadStatus.summarize(video, audio, true, false, 0);
        assertEquals(DownloadStatus.State.DOWNLOADING, partial.state);
        assertEquals(150, partial.totalBytes);
        assertEquals(100f * 110 / 150, partial.percent, 0.001f);
        DownloadStatus.Snapshot complete = DownloadStatus.summarize(video,
            track("a", Download.STATE_COMPLETED, -1, 50), true, false, 0);
        assertEquals(DownloadStatus.State.COMPLETED, complete.state);
        assertEquals(150, complete.totalBytes);
        assertEquals(100f, complete.percent, 0f);
    }

    @Test public void missingAudioIsNotCompleteAndUnneededAudioIsIgnored() {
        Download video = track("v", Download.STATE_COMPLETED, 100, 100);
        assertEquals(DownloadStatus.State.ADDING, DownloadStatus.summarize(video, null, true, false, 0).state);
        Download failedAudio = track("a", Download.STATE_FAILED, -1, 12);
        DownloadStatus.Snapshot muxed = DownloadStatus.summarize(video, failedAudio, false, false, 0);
        assertEquals(DownloadStatus.State.COMPLETED, muxed.state);
        assertEquals(100, muxed.totalBytes);
        assertEquals(100, muxed.bytesDownloaded);
    }

    @Test public void failedAndRemovingTracksAreNotReportedAsDownloadingOrComplete() {
        Download complete = track("v", Download.STATE_COMPLETED, 100, 100);
        assertEquals(DownloadStatus.State.FAILED, DownloadStatus.summarize(complete,
            track("a", Download.STATE_FAILED, -1, 10), true, false, 0).state);
        assertEquals(DownloadStatus.State.REMOVING, DownloadStatus.summarize(
            track("v", Download.STATE_REMOVING, 100, 100), null, false, false, 0).state);
        assertEquals(DownloadStatus.State.PAUSED, DownloadStatus.summarize(
            track("v", Download.STATE_STOPPED, 100, 10), null, false, false, 0).state);
    }

    @Test public void forbiddenIsNotAutomaticallyCalledExpiredAndErrorsStaySafe() {
        IOException denied = http(403);
        assertEquals(DownloadStatus.FailureKind.SERVER_REJECTED, DownloadStatus.classify(denied));
        String detail = DownloadStatus.safeFailure(denied);
        assertTrue(detail, detail.contains("观看权限及网络"));
        assertFalse(detail, detail.contains("已失效"));
        assertEquals(DownloadStatus.FailureKind.SERVER_REJECTED, DownloadStatus.classifySaved(detail));
        assertSafe(detail);
        for (int status : new int[]{404, 410, 416}) {
            assertEquals(DownloadStatus.FailureKind.ADDRESS_UNAVAILABLE, DownloadStatus.classify(http(status)));
            assertSafe(DownloadStatus.safeFailure(http(status)));
        }
    }

    @Test public void storageNetworkAndGenericFailuresHaveDifferentActions() {
        IOException noSpace = new IOException(SECRET, new ErrnoException("write", OsConstants.ENOSPC));
        assertEquals(DownloadStatus.FailureKind.STORAGE, DownloadStatus.classify(noSpace));
        assertTrue(DownloadStatus.safeFailure(noSpace).contains("清理"));
        assertSafe(DownloadStatus.safeFailure(noSpace));
        assertEquals(DownloadStatus.FailureKind.STORAGE, DownloadStatus.classify(
            new FileSystemException(SECRET, null, "No space left on device")));
        IOException network = new IOException(SECRET, new SocketTimeoutException(SECRET));
        assertEquals(DownloadStatus.FailureKind.NETWORK, DownloadStatus.classify(network));
        assertSafe(DownloadStatus.safeFailure(network));
        assertEquals(DownloadStatus.FailureKind.OTHER, DownloadStatus.classify(new IOException(SECRET)));
    }

    @Test public void criticallyLowStorageBlocksBeforeEnqueueButUnknownSizeDoesNotClaimEnoughSpace() {
        DownloadStatus.StorageException error = assertThrows(DownloadStatus.StorageException.class,
            () -> DownloadStatus.requireStartSpace(DownloadStatus.MIN_START_SPACE_BYTES - 1));
        assertTrue(error.getMessage().contains("存储不足"));
        assertEquals(DownloadStatus.FailureKind.STORAGE, DownloadStatus.classify(error));
        DownloadStatus.requireStartSpace(DownloadStatus.MIN_START_SPACE_BYTES);
        DownloadStatus.requireStartSpace(-1);
    }

    private static IOException http(int code) {
        DataSpec spec = new DataSpec.Builder().setUri("https://cdn.example/" + SECRET + "?token=" + SECRET)
            .setHttpRequestHeaders(Collections.singletonMap("Cookie", "SESSDATA=" + SECRET)).build();
        return new HttpDataSource.InvalidResponseCodeException(code, SECRET, null,
            Collections.emptyMap(), spec, SECRET.getBytes(StandardCharsets.UTF_8));
    }
    private static void assertSafe(String value) {
        assertFalse(value, value.contains(SECRET));
        assertFalse(value, value.contains("SESSDATA"));
        assertFalse(value, value.contains("token="));
        assertFalse(value, value.contains("https://"));
    }
}
