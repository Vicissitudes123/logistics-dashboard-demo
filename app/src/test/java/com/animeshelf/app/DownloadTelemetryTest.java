package com.animeshelf.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class DownloadTelemetryTest {
    @Test public void repeatedUiReadsAndSubsecondSamplesDoNotMoveBaseline() {
        DownloadTelemetry telemetry = new DownloadTelemetry();
        telemetry.sample("video", 1000, 1000, true);
        for (int i = 0; i < 100; i++) assertEquals(-1, telemetry.bytesPerSecond("video"));
        telemetry.sample("video", 1500, 1500, true);
        assertEquals(-1, telemetry.bytesPerSecond("video"));
        telemetry.sample("video", 2000, 2000, true);
        assertEquals(1000, telemetry.bytesPerSecond("video"));
        for (int i = 0; i < 100; i++) assertEquals(1000, telemetry.bytesPerSecond("video"));
        telemetry.sample("video", 2500, 2500, true);
        assertEquals(1000, telemetry.bytesPerSecond("video"));
        telemetry.sample("video", 3000, 3000, true);
        assertEquals(1000, telemetry.bytesPerSecond("video"));
    }

    @Test public void rateSmoothsBurstsAndStallsEventuallyBecomeZero() {
        DownloadTelemetry telemetry = new DownloadTelemetry();
        telemetry.sample("v", 0, 0, true);
        telemetry.sample("v", 1000, 1000, true);
        telemetry.sample("v", 2000, 4000, true);
        assertEquals(1700, telemetry.bytesPerSecond("v"));
        for (int seconds = 3; seconds <= 7; seconds++) telemetry.sample("v", seconds * 1000L, 4000, true);
        assertEquals(0, telemetry.bytesPerSecond("v"));
    }

    @Test public void pauseResumeAndTaskRestartDiscardOldTimingAndBytes() {
        DownloadTelemetry telemetry = new DownloadTelemetry();
        telemetry.sample("v", 0, 0, true);
        telemetry.sample("v", 1000, 1000, true);
        telemetry.sample("v", 2000, 1000, false);
        assertEquals(0, telemetry.bytesPerSecond("v"));
        telemetry.sample("v", 90000, 1000, true);
        assertEquals(-1, telemetry.bytesPerSecond("v"));
        telemetry.sample("v", 91000, 2000, true);
        assertEquals(1000, telemetry.bytesPerSecond("v"));
        telemetry.sample("v", 92000, 0, true);
        assertEquals(-1, telemetry.bytesPerSecond("v"));
        telemetry.sample("v", 93000, 500, true);
        assertEquals(500, telemetry.bytesPerSecond("v"));
    }

    @Test public void backwardsClockResetsAndRemovingSamplesDoesNotReuseOldRate() {
        DownloadTelemetry telemetry = new DownloadTelemetry();
        telemetry.sample("v", 1000, 0, true);
        telemetry.sample("v", 2000, 1000, true);
        telemetry.sample("v", 100, 1000, true);
        assertEquals(-1, telemetry.bytesPerSecond("v"));
        telemetry.forget("v");
        assertEquals(-1, telemetry.bytesPerSecond("v"));
    }

    @Test public void estimateNeedsKnownTotalAndPositiveRateAndRoundsUp() {
        assertEquals(-1, DownloadTelemetry.remainingSeconds(-1, 1000, 100));
        assertEquals(-1, DownloadTelemetry.remainingSeconds(5000, 1000, -1));
        assertEquals(-1, DownloadTelemetry.remainingSeconds(5000, 1000, 0));
        assertEquals(41, DownloadTelemetry.remainingSeconds(5001, 1000, 100));
        assertEquals(0, DownloadTelemetry.remainingSeconds(1000, 2000, 100));
    }
}
