package com.animeshelf.app;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;

/** Catalogue ordering must stay predictable as search and pinning change. */
public class LibraryCatalogueTest {
    @Test public void recentThenPinnedThenOthersKeepEachSeriesExactlyOnceAndPreserveInputOrder() {
        Models.Series first = series("first", "First");
        Models.Series pin = series("pin", "Pinned original");
        Models.Series recent = series("recent", "Recent");
        Models.Series other = series("other", "Other");
        Models.Series secondPin = series("second-pin", "Second pin");
        List<Models.Series> input = new ArrayList<>(Arrays.asList(first, pin, recent,
            series("pin", "Duplicate metadata"), other, secondPin));
        List<Models.Series> unchanged = new ArrayList<>(input);
        Set<String> pinnedIds = new HashSet<>(Arrays.asList("pin", "recent", "second-pin"));

        LibraryCatalogue.Sections result = LibraryCatalogue.build(input, "", "recent", pinnedIds::contains);

        assertSame(recent, result.recent);
        assertEquals(Arrays.asList("pin", "second-pin"), ids(result.pinned));
        assertSame(pin, result.pinned.get(0));
        assertEquals(Arrays.asList("first", "other"), ids(result.others));
        assertEquals(5, result.total);
        assertEquals(unchanged, input);
    }

    @Test public void seriesWithTheSameTitleRemainDistinctWhenTheirIdsDiffer() {
        LibraryCatalogue.Sections result = LibraryCatalogue.build(Arrays.asList(
            series("season-one", "同名番剧"), series("season-two", "同名番剧")),
            "同名", "missing-recent", id -> false);

        assertNull(result.recent);
        assertTrue(result.pinned.isEmpty());
        assertEquals(Arrays.asList("season-one", "season-two"), ids(result.others));
        assertEquals(2, result.total);
    }

    @Test public void searchNormalizesFullWidthTextAndTreatsUnicodeWhitespaceAsAndSeparators() {
        LibraryCatalogue.Sections result = LibraryCatalogue.build(Arrays.asList(
            series("match", "ＪＯＪＯ 的奇妙冒险"), series("missing-second", "JoJo 石之海"),
            series("missing-first", "奇妙冒险")),
            "\u2003jojo\u3000奇妙\u00a0冒险\t", "", id -> false);

        assertEquals(Collections.singletonList("match"), ids(result.others));
        assertEquals(1, result.total);
    }

    @Test public void matchingDoesNotDependOnTheDevicesTurkishCaseRules() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            LibraryCatalogue.Sections result = LibraryCatalogue.build(
                Collections.singletonList(series("title", "TITLE I")), "title i", "", id -> false);
            assertEquals(Collections.singletonList("title"), ids(result.others));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test public void filteringTheRecentSeriesDoesNotReplaceItOrHideOtherMatches() {
        LibraryCatalogue.Sections result = LibraryCatalogue.build(Arrays.asList(
            series("recent", "动画"), series("pinned", "电影 一"), series("other", "电影 二")),
            "电影", "recent", id -> id.equals("recent") || id.equals("pinned"));

        assertNull(result.recent);
        assertEquals(Collections.singletonList("pinned"), ids(result.pinned));
        assertEquals(Collections.singletonList("other"), ids(result.others));
        assertEquals(2, result.total);
    }

    @Test public void duplicateMetadataCannotChangeWhichSeriesMatchesASearch() {
        LibraryCatalogue.Sections result = LibraryCatalogue.build(Arrays.asList(
            series("same", "Alpha"), series("same", "Beta")), "Beta", "same", id -> true);

        assertNull(result.recent);
        assertTrue(result.pinned.isEmpty());
        assertTrue(result.others.isEmpty());
        assertEquals(0, result.total);
    }

    @Test public void nullEntriesAndBlankIdsDoNotBecomePhantomLibraryCards() {
        LibraryCatalogue.Sections result = LibraryCatalogue.build(Arrays.asList(
            null, series("", "No ID"), series(" \t ", "Blank ID"), series("valid", "Visible")),
            "\u3000\t", "", id -> false);
        assertEquals(Collections.singletonList("valid"), ids(result.others));
        assertEquals(1, result.total);

        LibraryCatalogue.Sections empty = LibraryCatalogue.build(Collections.emptyList(), "", "absent", id -> true);
        assertNull(empty.recent);
        assertTrue(empty.pinned.isEmpty());
        assertTrue(empty.others.isEmpty());
        assertEquals(0, empty.total);
    }

    @Test public void themeChoicesHaveStableUniqueIdsAndAnImmutableSafeDefault() {
        List<ThemePalette> choices = ThemePalette.entries();
        Set<String> ids = new HashSet<>();
        for (ThemePalette palette : choices) assertTrue(ids.add(palette.id));
        assertEquals(new HashSet<>(Arrays.asList("blue", "purple", "teal", "green", "orange", "pink")), ids);
        assertEquals(6, choices.size());
        assertEquals("blue", ThemePalette.get(null).id);
        assertSame(ThemePalette.get("blue"), ThemePalette.get("unrecognized"));
        assertEquals("pink", ThemePalette.get(" PiNk ").id);
        assertThrows(UnsupportedOperationException.class, choices::clear);
        assertThrows(UnsupportedOperationException.class, () -> choices.set(0, choices.get(1)));
    }

    private static Models.Series series(String id, String title) {
        Models.Series value = new Models.Series();
        value.id = id;
        value.title = title;
        return value;
    }

    private static List<String> ids(List<Models.Series> values) {
        List<String> result = new ArrayList<>();
        for (Models.Series value : values) result.add(value.id);
        return result;
    }
}
