package com.animeshelf.app;

import android.content.SharedPreferences;
import java.util.function.LongSupplier;

/** Sticky space pause. Reclaiming space never silently overrides the user's resume decision. */
final class DownloadStoragePolicy {
    static final long PAUSE_BYTES = 200_000_000L, RESUME_BYTES = 300_000_000L;
    static final int STOP_STORAGE = 1 << 19;
    static final class SpacePausedException extends IllegalStateException {
        SpacePausedException() { super("存储保护已暂停下载，请先清理至至少 300 MB 可用空间，再点继续下载。"); }
    }
    private final SharedPreferences prefs;
    private final LongSupplier free, clock;
    private long checkedAt = Long.MIN_VALUE;
    private boolean blocked;
    DownloadStoragePolicy(SharedPreferences prefs, LongSupplier free, LongSupplier clock) {
        this.prefs = prefs; this.free = free; this.clock = clock; blocked = prefs.getBoolean("spacePaused", false);
    }
    synchronized boolean allowsRead() {
        if (blocked) return false;
        long now = clock.getAsLong();
        if (checkedAt == Long.MIN_VALUE || now - checkedAt >= 500) {
            checkedAt = now; long available = free.getAsLong();
            if (available >= 0 && available < PAUSE_BYTES) latch();
        }
        return !blocked;
    }
    synchronized boolean isBlocked() { return blocked; }
    synchronized void latch() { blocked = true; prefs.edit().putBoolean("spacePaused", true).commit(); }
    synchronized void resume() {
        if (!blocked) { if (!allowsRead()) throw new SpacePausedException(); return; }
        if (free.getAsLong() < RESUME_BYTES) throw new SpacePausedException();
        blocked = false; checkedAt = clock.getAsLong(); prefs.edit().putBoolean("spacePaused", false).commit();
    }
}
