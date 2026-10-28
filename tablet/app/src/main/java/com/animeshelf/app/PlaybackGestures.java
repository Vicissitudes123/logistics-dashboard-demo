package com.animeshelf.app;

/** Pointer-independent playback gestures. The UI decides when to apply previews. */
public final class PlaybackGestures {
    public enum Mode { PENDING, SEEK, BRIGHTNESS, VOLUME, BLOCKED_SEEK, IGNORED }

    public static final class Snapshot {
        public final long positionMs, durationMs;
        public final boolean seekable;
        public final float brightness;
        public final int volume, maxVolume;

        public Snapshot(long positionMs, long durationMs, boolean seekable,
                        float brightness, int volume, int maxVolume) {
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.seekable = seekable;
            this.brightness = brightness;
            this.volume = volume;
            this.maxVolume = maxVolume;
        }
    }

    public static final class Update {
        public final Mode mode;
        public final long positionMs;
        public final float brightness;
        public final int volume;
        public final boolean commitSeek;

        private Update(Mode mode, long positionMs, float brightness, int volume, boolean commitSeek) {
            this.mode = mode;
            this.positionMs = positionMs;
            this.brightness = brightness;
            this.volume = volume;
            this.commitSeek = commitSeek;
        }
    }

    public static final int BRIGHTNESS = 1, VOLUME = 2, SEEK = 4, HOLD = 8, DOUBLE_TAP = 16, ALL = 31;
    private int enabled = ALL, seekSeconds = 180;
    public void configure(int mask, int seconds) {
        enabled = mask >= 0 && mask <= ALL ? mask : ALL;
        seekSeconds = seconds == 90 || seconds == 300 ? seconds : 180;
    }
    private Mode mode = Mode.IGNORED;
    private boolean active, startsOnLeft, seekable;
    private float startX, startY, slop;
    private int width, height, initialVolume, maxVolume;
    private long initialPositionMs, durationMs, previewPositionMs;
    private float initialBrightness = .5f, previewBrightness = .5f;
    private int previewVolume;

    /** A new pointer cancels any previous gesture. System-edge touches are not owned. */
    public boolean begin(float x, float y, int width, int height, float edgePx,
                         float touchSlop, Snapshot initial) {
        active = false;
        mode = Mode.IGNORED;
        previewPositionMs = 0;
        previewBrightness = .5f;
        previewVolume = 0;
        if (initial == null || width <= 0 || height <= 0 || !finite(x) || !finite(y)
                || !finite(edgePx) || edgePx < 0 || !finite(touchSlop) || touchSlop < 0
                || x < 0 || y < 0 || x >= width || y >= height
                || (double) edgePx * 2 >= width || (double) edgePx * 2 >= height
                || x < edgePx || y < edgePx || x > width - edgePx || y > height - edgePx) {
            return false;
        }

        this.width = width;
        this.height = height;
        startX = x;
        startY = y;
        slop = touchSlop;
        startsOnLeft = x < width / 2f;
        durationMs = Math.max(0L, initial.durationMs);
        initialPositionMs = Math.max(0L, initial.positionMs);
        if (durationMs > 0) initialPositionMs = Math.min(initialPositionMs, durationMs);
        seekable = initial.seekable && durationMs > 0;
        initialBrightness = boundedBrightness(finite(initial.brightness) ? initial.brightness : .5f);
        maxVolume = Math.max(0, initial.maxVolume);
        initialVolume = Math.max(0, Math.min(initial.volume, maxVolume));
        previewPositionMs = initialPositionMs;
        previewBrightness = initialBrightness;
        previewVolume = initialVolume;
        active = true;
        mode = Mode.PENDING;
        return true;
    }

    /** Seek is previewed here, and can only be committed by finish(false). */
    public Update move(float x, float y) {
        if (!active) return update(false);
        if (!finite(x) || !finite(y)) {
            active = false;
            mode = Mode.IGNORED;
            return update(false);
        }
        double dx = (double) x - startX;
        double dy = (double) y - startY;
        if (mode == Mode.PENDING) {
            if (Math.max(Math.abs(dx), Math.abs(dy)) <= slop) return update(false);
            if (Math.abs(dx) >= Math.abs(dy)) mode = (enabled & SEEK) == 0 ? Mode.IGNORED : seekable ? Mode.SEEK : Mode.BLOCKED_SEEK;
            else mode = startsOnLeft ? ((enabled & BRIGHTNESS) != 0 ? Mode.BRIGHTNESS : Mode.IGNORED)
                : ((enabled & VOLUME) != 0 ? Mode.VOLUME : Mode.IGNORED);
            if (mode == Mode.IGNORED) active = false;
        }

        switch (mode) {
            case SEEK:
                double target = initialPositionMs + dx / width * Math.min(durationMs, seekSeconds * 1000L);
                // Clamp in double before rounding, so extreme coordinates cannot overflow a long.
                previewPositionMs = Math.min(durationMs,
                    Math.max(0L, Math.round(Math.max(0d, Math.min((double) durationMs, target)))));
                break;
            case BRIGHTNESS:
                previewBrightness = boundedBrightness(initialBrightness - dy / (height * 0.6d) * .95d);
                break;
            case VOLUME:
                double volume = initialVolume - dy / (height * 0.6d) * maxVolume;
                previewVolume = (int) Math.round(Math.max(0d, Math.min((double) maxVolume, volume)));
                break;
            default:
                break;
        }
        return update(false);
    }

    /** Cancel, second-pointer interruption, or repeated finish never commits a seek. */
    public Update finish(boolean canceled) {
        Update result = update(active && !canceled && mode == Mode.SEEK);
        active = false;
        mode = Mode.IGNORED;
        return result;
    }

    public Mode getMode() { return mode; }

    public boolean isDragging() {
        return active && mode != Mode.PENDING && mode != Mode.IGNORED;
    }

    private Update update(boolean commitSeek) {
        return new Update(mode, previewPositionMs, previewBrightness, previewVolume, commitSeek);
    }

    private static float boundedBrightness(double brightness) {
        return (float) Math.max(.05d, Math.min(1d, brightness));
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
