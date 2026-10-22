package com.animeshelf.app;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/** Search and section ordering for the visible library without changing saved metadata. */
public final class LibraryCatalogue {
    private LibraryCatalogue() { }

    public static final class Sections {
        public final Models.Series recent;
        public final List<Models.Series> pinned, others;
        /** Number of matching, distinct series across all three sections. */
        public final int total;

        private Sections(Models.Series recent, List<Models.Series> pinned, List<Models.Series> others) {
            this.recent = recent;
            this.pinned = Collections.unmodifiableList(new ArrayList<>(pinned));
            this.others = Collections.unmodifiableList(new ArrayList<>(others));
            this.total = (recent == null ? 0 : 1) + pinned.size() + others.size();
        }
    }

    public static Sections build(List<Models.Series> visible, String query, String recentId,
                                 Predicate<String> pinned) {
        String normalizedQuery = normalize(query);
        String[] terms = normalizedQuery.isEmpty() ? new String[0] : normalizedQuery.split(" ");
        Models.Series recent = null;
        List<Models.Series> pinnedSeries = new ArrayList<>(), others = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (visible != null) for (Models.Series series : visible) {
            if (series == null || series.id == null || series.id.trim().isEmpty() || !seen.add(series.id)) continue;
            String title = normalize(series.title);
            boolean matches = true;
            for (String term : terms) if (!title.contains(term)) { matches = false; break; }
            if (!matches) continue;
            if (series.id.equals(recentId)) recent = series;
            else if (pinned != null && pinned.test(series.id)) pinnedSeries.add(series);
            else others.add(series);
        }
        return new Sections(recent, pinnedSeries, others);
    }

    /** NFKC handles full-width forms; all ordinary Unicode spaces share one separator. */
    private static String normalize(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        boolean pendingSpace = false;
        for (int i = 0; i < normalized.length();) {
            int point = normalized.codePointAt(i);
            i += Character.charCount(point);
            if (Character.isWhitespace(point) || Character.isSpaceChar(point) || point == 0x85) {
                pendingSpace = result.length() > 0;
            } else {
                if (pendingSpace) result.append(' ');
                result.appendCodePoint(point);
                pendingSpace = false;
            }
        }
        return result.toString();
    }
}
