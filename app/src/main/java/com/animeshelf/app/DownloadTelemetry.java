package com.animeshelf.app;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Monotonic, fixed-cadence progress sampling. Reading a rate never changes its baseline. */
public final class DownloadTelemetry {
    private static final long MIN_INTERVAL_MS = 1000;
    private static final long STALLED_MS = 5000;
    private final Map<String, Sample> samples = new HashMap<>();

    private static final class Sample {
        long timeMs, bytes, lastProgressMs, rate = -1;
        boolean active;
    }

    public synchronized void sample(String id, long nowMs, long bytes, boolean active) {
        bytes = Math.max(0, bytes);
        Sample old = samples.get(id);
        if (old == null || old.active != active || nowMs < old.timeMs || bytes < old.bytes) {
            Sample fresh = new Sample();
            fresh.timeMs = nowMs; fresh.lastProgressMs = nowMs; fresh.bytes = bytes;
            fresh.active = active; fresh.rate = active ? -1 : 0;
            samples.put(id, fresh);
            return;
        }
        if (!active) { old.timeMs = nowMs; old.bytes = bytes; old.rate = 0; return; }
        long elapsed = nowMs - old.timeMs;
        if (elapsed < MIN_INTERVAL_MS) return;
        long delta = bytes - old.bytes;
        double instantaneous = delta * 1000d / elapsed;
        if (delta > 0) old.lastProgressMs = nowMs;
        old.rate = nowMs - old.lastProgressMs >= STALLED_MS ? 0
            : Math.max(0, Math.round(old.rate < 0 ? instantaneous : old.rate * 0.65 + instantaneous * 0.35));
        old.timeMs = nowMs; old.bytes = bytes;
    }

    /** -1 means an active transfer has not yet had a full sample interval. */
    public synchronized long bytesPerSecond(String id) {
        Sample sample = samples.get(id);
        return sample == null ? -1 : sample.rate;
    }

    public synchronized void retain(Set<String> ids) { samples.keySet().retainAll(ids); }
    public synchronized void forget(String id) { samples.remove(id); }
    public synchronized void clear() { samples.clear(); }

    public static long remainingSeconds(long totalBytes, long downloadedBytes, long bytesPerSecond) {
        if (totalBytes < 0 || bytesPerSecond <= 0) return -1;
        long remaining = Math.max(0, totalBytes - Math.max(0, downloadedBytes));
        return remaining / bytesPerSecond + (remaining % bytesPerSecond == 0 ? 0 : 1);
    }
}
