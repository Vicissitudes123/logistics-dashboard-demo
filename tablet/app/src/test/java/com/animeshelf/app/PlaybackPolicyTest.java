package com.animeshelf.app;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class PlaybackPolicyTest {
    @Test public void preferredQualityReturnsFreshCandidateWithSameQualityAndCodec() {
        Models.Quality preferred = quality(112, "7", "30280");
        preferred.videoUrl = "https://media.example/expired.m4s";
        Models.Quality actual = quality(112, "7", "30216");
        actual.videoUrl = "https://media.example/fresh.m4s";

        assertSame(actual, PlaybackPolicy.preferredQuality(Arrays.asList(
            quality(112, "12", "30280"), quality(80, "7", "30280"), actual), preferred));
        assertEquals("https://media.example/expired.m4s", preferred.videoUrl);
    }

    @Test public void missingPreferredProfileDoesNotDowngradeOrSwitchCodec() {
        Models.Quality preferred = quality(112, "7", "30280");
        assertNull(PlaybackPolicy.preferredQuality(Arrays.asList(
            quality(80, "7", "30280"), quality(112, "12", "30280"),
            quality(112, "progressive", "0"), quality(120, "7", "30280")), preferred));
    }

    @Test public void missingOrMalformedPreferenceDoesNotChooseFirstAvailableQuality() {
        Models.Quality available = quality(80, "7", "30280");
        assertNull(PlaybackPolicy.preferredQuality(Collections.singletonList(available), null));
        Models.Quality malformed = quality(80, "7", "30280");
        malformed.id = "112:7:30280";
        assertNull(PlaybackPolicy.preferredQuality(Collections.singletonList(available), malformed));
        assertNull(PlaybackPolicy.preferredQuality(null, available));
        assertNull(PlaybackPolicy.preferredQuality(Collections.emptyList(), available));
    }

    @Test public void nullCandidatesAreIgnoredAndFirstExactMatchIsStable() {
        Models.Quality first = quality(80, "7", "30216");
        Models.Quality second = quality(80, "7", "30280");
        assertSame(first, PlaybackPolicy.preferredQuality(Arrays.asList(null, first, second), second));
    }

    @Test public void progressivePreferenceMatchesOnlyTheSameQualityAndTransportProfile() {
        Models.Quality preferred = quality(64, "progressive", "0");
        Models.Quality available = quality(64, "progressive", "1");
        assertSame(available, PlaybackPolicy.preferredQuality(Arrays.asList(
            quality(64, "7", "30280"), available), preferred));
    }

    @Test public void nextEpisodeSortsNumbersRatherThanTitlesAndDoesNotMutateSource() {
        Models.Episode ten = episode("ss1", "ep10", 10);
        Models.Episode two = episode("ss1", "ep2", 2);
        Models.Episode one = episode("ss1", "ep1", 1);
        one.title = "ZZZ"; two.title = "AAA";
        Models.Series series = series("ss1", ten, one, two);
        ArrayList<Models.Episode> original = new ArrayList<>(series.episodes);

        assertSame(two, PlaybackPolicy.nextEpisode(series, one));
        assertSame(ten, PlaybackPolicy.nextEpisode(series, two));
        assertEquals(original, series.episodes);
    }

    @Test public void nextEpisodeFiltersOtherSeriesEvenWhenEpisodeIdsMatch() {
        Models.Episode current = episode("ss1", "same", 1);
        Models.Episode next = episode("ss1", "ep3", 3);
        Models.Series series = series("ss1", episode("ss2", "same", 1), current,
            episode("ss2", "ep2", 2), next);

        assertSame(next, PlaybackPolicy.nextEpisode(series, current));
        assertNull(PlaybackPolicy.nextEpisode(series, episode("ss2", "same", 1)));
    }

    @Test public void missingCurrentEpisodeDoesNotJumpToTheFirstOrNearestEpisode() {
        Models.Series series = series("ss1", episode("ss1", "ep2", 2), episode("ss1", "ep3", 3));
        assertNull(PlaybackPolicy.nextEpisode(series, episode("ss1", "missing", 1)));
        assertNull(PlaybackPolicy.nextEpisode(series, episode("ss1", "ep2-copy", 2)));
    }

    @Test public void lastEpisodeDoesNotLoopOrCrossIntoAnotherSeries() {
        Models.Episode last = episode("ss1", "last", 5);
        assertNull(PlaybackPolicy.nextEpisode(series("ss1", last, episode("ss2", "later", 6)), last));
    }

    @Test public void restoredCurrentEpisodeUsesStoredIdentityAndCurrentCatalogueOrder() {
        Models.Episode stored = episode("ss1", "ep1", 1);
        Models.Episode next = episode("ss1", "ep2", 2);
        Models.Episode restored = episode("ss1", "ep1", 999);
        assertSame(next, PlaybackPolicy.nextEpisode(series("ss1", next, stored), restored));
    }

    @Test public void duplicateEpisodeRecordsAndInvalidEntriesCannotReplayTheSameEpisode() {
        Models.Episode one = episode("ss1", "one", 1);
        Models.Episode two = episode("ss1", "two", 2);
        Models.Episode three = episode("ss1", "three", 4);
        Models.Series series = series("ss1", null, one, episode("ss1", "", 2), two,
            episode("ss1", "one", 3), three);

        assertSame(two, PlaybackPolicy.nextEpisode(series, one));
        assertSame(three, PlaybackPolicy.nextEpisode(series, two));
    }

    @Test public void absentOrUnidentifiedSeriesCannotAutoAdvance() {
        Models.Episode current = episode("ss1", "ep1", 1);
        assertNull(PlaybackPolicy.nextEpisode(null, current));
        assertNull(PlaybackPolicy.nextEpisode(series("ss1", current), null));
        assertNull(PlaybackPolicy.nextEpisode(series("", current), current));
        assertNull(PlaybackPolicy.nextEpisode(series("ss1", current), episode("ss1", " ", 1)));
        Models.Series damaged = series("ss1", current);
        damaged.episodes = null;
        assertNull(PlaybackPolicy.nextEpisode(damaged, current));
    }

    @Test public void previousEpisodeUsesCatalogueOrderAndNeverCrossesSeriesOrWraps() {
        Models.Episode one = episode("ss1", "one", 1), two = episode("ss1", "two", 2), ten = episode("ss1", "ten", 10);
        Models.Series series = series("ss1", ten, episode("ss2", "other", 3), one, two);
        assertSame(two, PlaybackPolicy.adjacentEpisode(series, episode("ss1", "ten", 999), -1));
        assertSame(one, PlaybackPolicy.adjacentEpisode(series, two, -1));
        assertNull(PlaybackPolicy.adjacentEpisode(series, one, -1));
        assertNull(PlaybackPolicy.adjacentEpisode(series, ten, 1));
        assertNull(PlaybackPolicy.adjacentEpisode(series, episode("ss2", "two", 2), -1));
        assertNull(PlaybackPolicy.adjacentEpisode(series, episode("ss1", "missing", 2), -1));
        assertNull(PlaybackPolicy.adjacentEpisode(series, two, 0));
        assertNull(PlaybackPolicy.adjacentEpisode(series, two, 2));
    }

    @Test public void previousEpisodeIgnoresDuplicateAndInvalidCatalogueRows() {
        Models.Episode one = episode("ss1", "one", 1), two = episode("ss1", "two", 2);
        Models.Episode three = episode("ss1", "three", 3);
        Models.Series series = series("ss1", null, one, two, episode("ss1", "two", 2), episode("ss1", "", 2), three);
        assertSame(two, PlaybackPolicy.adjacentEpisode(series, three, -1));
        assertSame(one, PlaybackPolicy.adjacentEpisode(series, two, -1));
        assertEquals(6, series.episodes.size());
    }

    private static Models.Quality quality(int qn, String codec, String audio) {
        Models.Quality result = new Models.Quality();
        result.qn = qn; result.id = qn + ":" + codec + ":" + audio;
        return result;
    }

    private static Models.Episode episode(String seriesId, String id, int index) {
        Models.Episode result = new Models.Episode();
        result.seriesId = seriesId; result.id = id; result.index = index;
        return result;
    }

    private static Models.Series series(String id, Models.Episode... episodes) {
        Models.Series result = new Models.Series();
        result.id = id; result.episodes.addAll(Arrays.asList(episodes));
        return result;
    }
}
