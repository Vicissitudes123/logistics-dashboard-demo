package com.animeshelf.app;

import androidx.media3.common.util.UnstableApi;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Playback choices that do not change stored preferences or episode ordering. */
@UnstableApi
public final class PlaybackPolicy {
    private PlaybackPolicy() { }

    /** Return a current candidate with the same video profile, never a lower fallback. */
    public static Models.Quality preferredQuality(List<Models.Quality> choices,
                                                  Models.Quality preferred) {
        if (choices != null) for (Models.Quality choice : choices) {
            if (DownloadCatalogue.sameProfile(choice, preferred)) return choice;
        }
        return null;
    }

    /** Locate the current episode within its series, then advance in numeric index order. */
    public static Models.Episode nextEpisode(Models.Series series, Models.Episode current) {
        return adjacentEpisode(series, current, 1);
    }

    /** Previous/next use catalogue identity and numeric ordering, not labels or stale episode indices. */
    public static Models.Episode adjacentEpisode(Models.Series series, Models.Episode current, int direction) {
        if (direction != -1 && direction != 1) return null;
        if (series == null || current == null || blank(series.id) || blank(current.id)
                || !series.id.equals(current.seriesId) || series.episodes == null) return null;
        List<Models.Episode> sameSeries = new ArrayList<>();
        for (Models.Episode episode : series.episodes) {
            if (episode != null && !blank(episode.id) && series.id.equals(episode.seriesId)) sameSeries.add(episode);
        }
        Set<String> seen = new HashSet<>();
        boolean found = false;
        Models.Episode previous = null;
        for (Models.Episode episode : DownloadCatalogue.episodes(sameSeries)) {
            if (!seen.add(episode.id)) continue;
            if (found) return episode;
            if (current.id.equals(episode.id)) {
                if (direction < 0) return previous;
                found = true;
            }
            previous = episode;
        }
        return null;
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
