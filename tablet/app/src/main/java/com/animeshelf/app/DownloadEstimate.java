package com.animeshelf.app;

/** Whole episode sizing, including sound. Unknown is explicit; all arithmetic is overflow checked. */
public final class DownloadEstimate {
    public final long bytes;
    public final boolean exact;
    private DownloadEstimate(long bytes, boolean exact) { this.bytes = bytes; this.exact = exact; }
    public static DownloadEstimate of(Models.Quality q) {
        if (q == null) return new DownloadEstimate(-1, false);
        boolean audio = !q.audioCandidates().isEmpty();
        try {
            if (q.videoBytes > 0 && (!audio || q.audioBytes > 0))
                return new DownloadEstimate(Math.addExact(q.videoBytes, audio ? q.audioBytes : 0), true);
            if (q.durationMs <= 0 || q.bandwidth <= 0 || (audio && q.audioBandwidth <= 0))
                return new DownloadEstimate(-1, false);
            double rate = Math.addExact(q.bandwidth, audio ? q.audioBandwidth : 0);
            double estimate = rate * q.durationMs / 8000d;
            if (!Double.isFinite(estimate) || estimate >= Long.MAX_VALUE || estimate <= 0) return new DownloadEstimate(-1, false);
            return new DownloadEstimate((long) Math.ceil(estimate), false);
        } catch (ArithmeticException overflow) { return new DownloadEstimate(-1, false); }
    }
}
