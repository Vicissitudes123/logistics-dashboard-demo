package com.animeshelf.app;

import androidx.media3.common.util.UnstableApi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Serial, cancelable preparation of download jobs. This class does no network or
 * threading: callers deliver all methods/callbacks on one scheduling thread and
 * provide a scheduler that defers the next step rather than invoking it inline.
 */
@UnstableApi
public final class BatchDownloadSession {
    public interface Gateway {
        MediaRepository.OfflineItem existing(Models.Episode episode, Models.Quality profile);
        void resolve(Models.Episode episode, Result callback);
        void enqueue(Models.Episode episode, Models.Quality quality) throws Exception;
    }
    public interface Result {
        void success(List<Models.Quality> qualities);
        void failure(Exception exception);
    }
    public interface Listener {
        void changed(BatchDownloadSession job);
        void finished(BatchDownloadSession job);
    }

    private final List<Models.Episode> episodes;
    private final Models.Quality profile;
    private final Gateway gateway;
    private final Consumer<Runnable> schedule;
    private final Listener listener;
    private final List<String> details = new ArrayList<>();
    private Models.Episode current;
    private boolean started, finished, cancelled, awaiting;
    private int nextIndex, generation, added, skipped, failed, unavailable;

    public BatchDownloadSession(List<Models.Episode> episodes, Models.Quality profile,
                                Gateway gateway, Consumer<Runnable> schedule, Listener listener) {
        this.episodes = Collections.unmodifiableList(DownloadCatalogue.episodes(episodes));
        this.profile = Objects.requireNonNull(profile, "profile");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.schedule = Objects.requireNonNull(schedule, "schedule");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public void start() {
        if (started || finished) return;
        started = true;
        listener.changed(this);
        scheduleNext();
    }

    /** Already enqueued downloads are retained. A late resolver cannot enqueue anything. */
    public void cancel() {
        if (finished) return;
        cancelled = true;
        finish();
    }

    public boolean isFinished() { return finished; }
    public boolean isCancelled() { return cancelled; }
    public int total() { return episodes.size(); }
    public int processed() { return added + skipped + failed + unavailable; }
    public int added() { return added; }
    public int skipped() { return skipped; }
    public int failed() { return failed; }
    public int unavailable() { return unavailable; }
    public Models.Episode current() { return current; }
    public List<String> details() { return Collections.unmodifiableList(new ArrayList<>(details)); }

    public String summary() {
        return (cancelled ? "已取消后续添加" : finished ? "批量添加完成" : "正在逐集添加")
            + "\n已处理 " + processed() + " / " + total() + " 集"
            + "\n已加入队列 " + added + "，已有任务 " + skipped
            + "\n缺少所选档位 " + unavailable + "，失败 " + failed
            + (cancelled && added > 0 ? "\n已加入的任务会继续下载。" : "");
    }

    private void scheduleNext() {
        if (finished) return;
        if (nextIndex >= episodes.size()) { finish(); return; }
        schedule.accept(this::advance);
    }

    private void advance() {
        if (finished || awaiting) return;
        if (nextIndex >= episodes.size()) { finish(); return; }
        current = episodes.get(nextIndex);
        final Models.Episode episode = current;
        final int ticket = ++generation;
        final MediaRepository.OfflineItem original;
        try {
            original = gateway.existing(episode, profile);
            if (alreadyPresent(original)) {
                skipped++;
                details.add(label(episode) + "已有同档位任务，已跳过");
                next();
                return;
            }
        } catch (RuntimeException ignored) {
            failed++;
            details.add(label(episode) + "读取已有任务失败");
            next();
            return;
        }
        final String originalKey = original == null ? null : original.key;
        awaiting = true;
        listener.changed(this);
        if (!accepts(ticket)) return;
        try {
            gateway.resolve(episode, new Result() {
                @Override public void success(List<Models.Quality> choices) {
                    if (!accepts(ticket)) return;
                    awaiting = false;
                    Models.Quality selected = DownloadCatalogue.matching(choices, profile);
                    if (selected == null) {
                        unavailable++;
                        details.add(label(episode) + "没有所选清晰度和编码，未降档下载");
                    } else {
                        try {
                            // Another operation may have added the same episode while resolving.
                            MediaRepository.OfflineItem latest = gateway.existing(episode, profile);
                            if (alreadyPresent(latest)) {
                                skipped++;
                                details.add(label(episode) + "已有同档位任务，已跳过");
                            } else if (original != null && (latest == null || !Objects.equals(originalKey, latest.key))) {
                                skipped++;
                                details.add(label(episode) + "原失败任务已删除或替换，已跳过");
                            } else if (!finished && ticket == generation) {
                                gateway.enqueue(episode, selected);
                                added++;
                                details.add(label(episode) + "已加入下载队列");
                            }
                        } catch (Exception ignored) {
                            failed++;
                            details.add(label(episode) + "加入下载队列失败");
                        }
                    }
                    next();
                }
                @Override public void failure(Exception exception) {
                    failResolution(ticket, episode);
                }
            });
        } catch (RuntimeException ignored) {
            failResolution(ticket, episode);
        }
    }

    private boolean accepts(int ticket) { return !finished && awaiting && ticket == generation; }

    private void failResolution(int ticket, Models.Episode episode) {
        if (!accepts(ticket)) return;
        awaiting = false;
        failed++;
        // Remote exception messages may contain signed URLs or credentials.
        details.add(label(episode) + "解析失败，可稍后单独重试查看诊断");
        next();
    }

    private void next() {
        if (finished) return;
        current = null;
        nextIndex++;
        listener.changed(this);
        scheduleNext();
    }

    private void finish() {
        if (finished) return;
        finished = true;
        awaiting = false;
        ++generation;
        current = null;
        listener.finished(this);
    }

    private static boolean alreadyPresent(MediaRepository.OfflineItem existing) {
        return existing != null && (existing.complete || !existing.failed);
    }
    private static String label(Models.Episode episode) { return "第 " + episode.index + " 集："; }
}
