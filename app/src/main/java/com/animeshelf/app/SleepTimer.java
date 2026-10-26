package com.animeshelf.app;

/** Session-only timer using elapsed time, unaffected by timezone or clock adjustments. */
final class SleepTimer {
    private long deadline;
    private boolean episodeEnd;
    void minutes(int minutes, long now) {
        if (minutes != 15 && minutes != 30 && minutes != 60) throw new IllegalArgumentException("Unsupported timer");
        deadline = now + minutes * 60_000L; episodeEnd = false;
    }
    void afterEpisode() { deadline = 0; episodeEnd = true; }
    void cancel() { deadline = 0; episodeEnd = false; }
    boolean consumeTime(long now) {
        if (deadline == 0 || now < deadline) return false;
        cancel(); return true;
    }
    boolean consumeEpisodeEnd() { if (!episodeEnd) return false; cancel(); return true; }
    String label(long now) {
        if (episodeEnd) return "本集结束后停止";
        if (deadline == 0) return "未开启";
        return "剩余 " + Math.max(0, (deadline - now + 59999) / 60000) + " 分钟";
    }
}
