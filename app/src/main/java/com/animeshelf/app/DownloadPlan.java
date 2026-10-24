package com.animeshelf.app;

import androidx.media3.common.util.UnstableApi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Prepares metadata only. Nothing is enqueued until the user has reviewed storage requirements. */
@UnstableApi
public final class DownloadPlan {
    public interface Gateway {
        List<Models.Quality> resolve(Models.Episode episode) throws Exception;
        MediaRepository.OfflineItem existing(Models.Episode episode, Models.Quality profile);
        default void checked(int done, int total) { }
    }
    public static final class Item {
        public final Models.Episode episode;
        public final Models.Quality quality;
        public final DownloadEstimate size;
        public final long remainingBytes;
        Item(Models.Episode episode, Models.Quality quality, MediaRepository.OfflineItem old) {
            this.episode = episode; this.quality = quality; size = DownloadEstimate.of(quality);
            long cached = old != null && MediaRepository.key(episode, quality).equals(old.key) ? Math.max(0, old.bytesDownloaded) : 0;
            remainingBytes = size.bytes < 0 ? -1 : Math.max(0, size.bytes - cached);
        }
    }
    public final List<Item> items;
    public final List<String> skipped;
    public final long knownBytes;
    public final int unknownCount;
    public final boolean allExact;
    private DownloadPlan(List<Item> items, List<String> skipped) {
        this.items = Collections.unmodifiableList(items); this.skipped = Collections.unmodifiableList(skipped);
        long sum = 0; int unknown = 0; boolean exact = true;
        for (Item item : items) {
            if (item.remainingBytes < 0) { unknown++; exact = false; }
            else {
                sum = item.remainingBytes > Long.MAX_VALUE - sum ? Long.MAX_VALUE : sum + item.remainingBytes;
                exact &= item.size.exact;
            }
        }
        knownBytes = sum; unknownCount = unknown; allExact = exact;
    }
    public static DownloadPlan prepare(List<Models.Episode> episodes, Models.Quality profile, Gateway gateway, BooleanSupplier cancelled) {
        List<Item> result = new ArrayList<>(); List<String> skipped = new ArrayList<>();
        List<Models.Episode> ordered = DownloadCatalogue.episodes(episodes); int done = 0;
        for (Models.Episode episode : ordered) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            String name = "第 " + episode.index + " 集：";
            try {
                MediaRepository.OfflineItem old = gateway.existing(episode, profile);
                if (old != null && !old.failed) skipped.add(name + "已有同档位任务");
                else {
                    Models.Quality match = DownloadCatalogue.matching(gateway.resolve(episode), profile);
                    if (cancelled.getAsBoolean()) throw new CancellationException();
                    if (match == null) skipped.add(name + "没有相同清晰度与编码");
                    else result.add(new Item(episode, match, old));
                }
            } catch (CancellationException stopped) { throw stopped; }
            catch (Exception unavailable) { skipped.add(name + "未能获取视频信息"); }
            gateway.checked(++done, ordered.size());
        }
        if (cancelled.getAsBoolean()) throw new CancellationException();
        return new DownloadPlan(result, skipped);
    }
}
