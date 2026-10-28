package com.animeshelf.app;

/** Session-local decisions only. The caller applies seeks and keeps its normal ENDED/next-episode rules. */
public final class SkipPlaybackPolicy {
    public static final long OUTRO_COUNTDOWN_MS = 5000;
    private static final long MIN_CONTENT_MS = 30000;
    private static final long START_TOLERANCE_MS = 1000;

    public enum Action { NONE, SKIP_INTRO, COUNTDOWN_OUTRO, SKIP_OUTRO }

    public static final class Decision {
        public final Action action;
        /** Seek destination, or -1 when there is no destination. */
        public final long targetMs;
        /** Rounded up so the cancel notice never displays zero before the seek. */
        public final int countdownSeconds;
        public final long remainingMs;

        private Decision(Action action, long targetMs, long remainingMs) {
            this.action = action;
            this.targetMs = targetMs;
            this.remainingMs = remainingMs;
            this.countdownSeconds = remainingMs > 0 ? (int) ((remainingMs + 999) / 1000) : 0;
        }
    }

    private static final Decision NONE = new Decision(Action.NONE, -1, 0);
    private boolean enabled, sessionDisabled = true, introPending, outroConsumed, countdownStarted;
    private long introMs, outroMs, countdownStartMs = -1, countdownDurationMs = -1;

    /** Call once for a new playback session, with the requested initial/resume position. */
    public void startSession(boolean enabled, int introSeconds, int outroSeconds, long initialPositionMs) {
        reset();
        this.enabled = enabled;
        sessionDisabled = false;
        introMs = clampedSeconds(introSeconds) * 1000L;
        outroMs = clampedSeconds(outroSeconds) * 1000L;
        introPending = enabled && introMs > 0 && initialPositionMs >= 0 && initialPositionMs <= START_TOLERANCE_MS;
    }

    /** Clear all decisions when closing playback; a later startSession is independent. */
    public void reset() {
        enabled = false;
        sessionDisabled = true;
        introPending = false;
        outroConsumed = false;
        countdownStarted = false;
        introMs = 0;
        outroMs = 0;
        clearCountdown();
    }

    /** Cancels both pending decisions and any notice; it does not change the saved series preference. */
    public void disableForSession() {
        sessionDisabled = true;
        introPending = false;
        outroConsumed = true;
        clearCountdown();
    }

    /** Manual seeking, including undoing the intro skip, always takes priority for this session. */
    public void onManualSeek() { disableForSession(); }

    public boolean isDisabledForSession() { return sessionDisabled; }

    /**
     * Poll with a monotonic elapsed clock, including transitions to pause/buffering/background.
     * A suspended countdown must give a new five-second cancellation window after playback resumes.
     */
    public Decision update(long positionMs, long durationMs, boolean ready, boolean seekable,
                           boolean playing, long nowElapsedMs) {
        if (!enabled || sessionDisabled) return NONE;
        if (!ready || !seekable || !playing || nowElapsedMs < 0 || positionMs < 0 || durationMs <= 0) {
            clearCountdown();
            return NONE;
        }
        if (positionMs >= durationMs) {
            introPending = false;
            outroConsumed = true;
            clearCountdown();
            return NONE;
        }
        // A short clip or overlapping settings must never skip almost the entire video.
        boolean reasonableDuration = durationMs >= introMs + outroMs + MIN_CONTENT_MS;
        if (introPending) {
            introPending = false;
            if (reasonableDuration && positionMs < introMs) {
                return new Decision(Action.SKIP_INTRO, introMs, 0);
            }
        }
        if (outroConsumed || outroMs <= OUTRO_COUNTDOWN_MS || !reasonableDuration) {
            clearCountdown();
            return NONE;
        }
        long outroStartMs = durationMs - outroMs;
        if (positionMs < outroStartMs) {
            // Leaving an already announced outro is another clear signal to let the user watch.
            if (countdownStarted) outroConsumed = true;
            clearCountdown();
            return NONE;
        }
        if (countdownStartMs < 0 || countdownDurationMs != durationMs || nowElapsedMs < countdownStartMs) {
            clearCountdown();
            // A five-second notice cannot help when less than five seconds of video remain.
            if (durationMs - positionMs <= OUTRO_COUNTDOWN_MS) return NONE;
            countdownStarted = true;
            countdownStartMs = nowElapsedMs;
            countdownDurationMs = durationMs;
        }
        long remaining = OUTRO_COUNTDOWN_MS - (nowElapsedMs - countdownStartMs);
        if (remaining > 0) return new Decision(Action.COUNTDOWN_OUTRO, durationMs, remaining);
        outroConsumed = true;
        clearCountdown();
        return new Decision(Action.SKIP_OUTRO, durationMs, 0);
    }

    private void clearCountdown() {
        countdownStartMs = -1;
        countdownDurationMs = -1;
    }

    private static int clampedSeconds(int seconds) { return Math.max(0, Math.min(600, seconds)); }
}
