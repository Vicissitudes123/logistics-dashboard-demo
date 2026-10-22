package com.animeshelf.app;

import androidx.media3.common.util.UnstableApi;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Stable presentation and matching rules shared by offline lists and batch downloads. */
@UnstableApi
public final class DownloadCatalogue {
    private DownloadCatalogue() { }

    public static final class Group {
        public final String id, title;
        public final List<MediaRepository.OfflineItem> items;
        /** Completed download versions; multiple qualities of one episode count separately. */
        public final int completeCount;
        /** Distinct episodes, independent of the number of saved qualities. */
        public final int episodeCount;
        public final long bytes;

        private Group(String id, List<MediaRepository.OfflineItem> entries) {
            this.id = id;
            ArrayList<MediaRepository.OfflineItem> sorted = new ArrayList<>(entries);
            sorted.sort(ITEM_ORDER);
            items = Collections.unmodifiableList(sorted);
            String chosenTitle = "";
            int completed = 0;
            long byteCount = 0;
            Set<String> episodes = new HashSet<>();
            for (MediaRepository.OfflineItem item : sorted) {
                if (chosenTitle.isEmpty()) chosenTitle = value(item.episode.seriesTitle);
                if (item.complete) completed++;
                long next = Math.max(0L, item.bytesDownloaded);
                byteCount = next > Long.MAX_VALUE - byteCount ? Long.MAX_VALUE : byteCount + next;
                String episodeId = episodeId(item);
                episodes.add(episodeId.isEmpty() ? "key:" + value(item.key) : "episode:" + episodeId);
            }
            title = chosenTitle.isEmpty() ? "未命名番剧" : chosenTitle;
            completeCount = completed;
            episodeCount = episodes.size();
            bytes = byteCount;
        }
    }

    private static final Comparator<MediaRepository.OfflineItem> ITEM_ORDER =
        Comparator.comparingInt((MediaRepository.OfflineItem item) -> item.episode.index)
            .thenComparing(DownloadCatalogue::episodeId)
            .thenComparing(Comparator.comparingInt((MediaRepository.OfflineItem item) -> item.quality.qn).reversed())
            .thenComparing(item -> value(item.quality.id))
            .thenComparing(item -> value(item.key));

    public static List<Group> groups(List<MediaRepository.OfflineItem> source) {
        Map<String, List<MediaRepository.OfflineItem>> grouped = new LinkedHashMap<>();
        Map<String, String> publicIds = new LinkedHashMap<>();
        int anonymous = 0;
        if (source != null) for (MediaRepository.OfflineItem item : source) {
            if (item == null || item.episode == null || item.quality == null) continue;
            String seriesId = value(item.episode.seriesId);
            String mapKey, id;
            if (!seriesId.isEmpty()) {
                mapKey = "series:" + seriesId;
                id = seriesId;
            } else {
                String episodeId = episodeId(item);
                if (!episodeId.isEmpty()) id = "missing-series:episode:" + episodeId;
                else if (!value(item.key).isEmpty()) id = "missing-series:key:" + item.key;
                else id = "missing-series:anonymous:" + anonymous++;
                mapKey = "fallback:" + id;
            }
            grouped.computeIfAbsent(mapKey, ignored -> new ArrayList<>()).add(item);
            publicIds.put(mapKey, id);
        }
        ArrayList<Group> result = new ArrayList<>();
        for (Map.Entry<String, List<MediaRepository.OfflineItem>> entry : grouped.entrySet())
            result.add(new Group(publicIds.get(entry.getKey()), entry.getValue()));
        Collator collator = Collator.getInstance(Locale.CHINA);
        result.sort((left, right) -> {
            int titleOrder = collator.compare(left.title, right.title);
            return titleOrder != 0 ? titleOrder : left.id.compareTo(right.id);
        });
        return result;
    }

    /** Copies the input list; episode numbers are compared numerically, never as titles. */
    public static List<Models.Episode> episodes(List<Models.Episode> source) {
        ArrayList<Models.Episode> result = new ArrayList<>();
        if (source != null) for (Models.Episode episode : source) if (episode != null) result.add(episode);
        result.sort(Comparator.comparingInt((Models.Episode episode) -> episode.index)
            .thenComparing(episode -> value(episode.id)));
        return result;
    }

    /** Keep the selected video quality/codec; an episode may use a different AAC track ID. */
    public static boolean sameProfile(Models.Quality left, Models.Quality right) {
        if (left == null || right == null || left.qn <= 0 || left.qn != right.qn) return false;
        String codec = codecKey(left);
        return !codec.isEmpty() && codec.equals(codecKey(right));
    }

    public static Models.Quality matching(List<Models.Quality> choices, Models.Quality selected) {
        if (choices != null) for (Models.Quality choice : choices) if (sameProfile(choice, selected)) return choice;
        return null;
    }

    /** Prefer an already complete or queued version over a stale failed entry. */
    public static MediaRepository.OfflineItem existing(List<MediaRepository.OfflineItem> entries,
                                                       Models.Episode episode, Models.Quality quality) {
        if (episode == null || value(episode.id).isEmpty() || entries == null) return null;
        MediaRepository.OfflineItem selected = null;
        for (MediaRepository.OfflineItem item : entries) {
            if (item == null || !episode.id.equals(episodeId(item)) || !sameProfile(item.quality, quality)) continue;
            if (selected == null || rank(item) < rank(selected)
                    || (rank(item) == rank(selected) && value(item.key).compareTo(value(selected.key)) < 0)) selected = item;
        }
        return selected;
    }

    private static int rank(MediaRepository.OfflineItem item) { return item.complete ? 0 : item.failed ? 2 : 1; }

    private static String codecKey(Models.Quality quality) {
        String[] fields = value(quality.id).split(":", -1);
        if (fields.length != 3 || fields[2].isEmpty()) return "";
        try {
            if (Integer.parseInt(fields[0]) != quality.qn) return "";
            if ("progressive".equals(fields[1])) return "progressive";
            int codec = Integer.parseInt(fields[1]);
            return codec > 0 ? Integer.toString(codec) : "";
        } catch (NumberFormatException ignored) { return ""; }
    }

    private static String episodeId(MediaRepository.OfflineItem item) {
        if (item.episode != null && !value(item.episode.id).isEmpty()) return item.episode.id;
        return value(item.episodeId);
    }
    private static String value(String value) { return value == null || value.trim().isEmpty() ? "" : value; }
}
