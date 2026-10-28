package com.animeshelf.app;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;

public class DownloadCatalogueTest {
    @Test public void identicalTitlesDoNotMergeDifferentSeriesOrMissingSeriesIds() {
        List<DownloadCatalogue.Group> groups = DownloadCatalogue.groups(Arrays.asList(
            item("a", "ss1", "同名番剧", "ep1", 1, quality(80, "7", "30280")),
            item("b", "ss2", "同名番剧", "ep2", 1, quality(80, "7", "30280")),
            item("c", "", "同名番剧", "ep3", 1, quality(80, "7", "30280")),
            item("d", "", "同名番剧", "ep4", 1, quality(80, "7", "30280"))));

        assertEquals(4, groups.size());
        HashSet<String> ids = new HashSet<>();
        for (DownloadCatalogue.Group group : groups) {
            ids.add(group.id);
            assertEquals(1, group.items.size());
        }
        assertEquals(4, ids.size());
        assertTrue(ids.contains("ss1"));
        assertTrue(ids.contains("ss2"));
    }

    @Test public void episodeOrderIsNumericAndAllSavedQualityVersionsRemainAvailable() {
        MediaRepository.OfflineItem tenth = item("ten", "ss1", "番剧", "ep10", 10, quality(80, "7", "30280"));
        MediaRepository.OfflineItem secondHigh = item("two-high", "ss1", "番剧", "ep2", 2, quality(112, "7", "30280"));
        MediaRepository.OfflineItem second = item("two", "ss1", "番剧", "ep2", 2, quality(80, "7", "30280"));
        MediaRepository.OfflineItem oldEncode = item("two-old-encode", "ss1", "番剧", "ep2", 2, quality(80, "7", "30280"));
        secondHigh.complete = true; oldEncode.complete = true;
        tenth.bytesDownloaded = 100; secondHigh.bytesDownloaded = 200; second.bytesDownloaded = 300; oldEncode.bytesDownloaded = 400;
        List<MediaRepository.OfflineItem> source = Arrays.asList(tenth, second, oldEncode, secondHigh);

        DownloadCatalogue.Group group = DownloadCatalogue.groups(source).get(0);

        assertEquals(4, group.items.size());
        assertEquals(2, group.episodeCount);
        assertEquals(2, group.completeCount); // Versions, not unique episodes.
        assertEquals(1000, group.bytes);
        assertSame(secondHigh, group.items.get(0));
        assertSame(tenth, group.items.get(3));
        assertTrue(group.items.contains(second));
        assertTrue(group.items.contains(oldEncode));
        assertSame(tenth, source.get(0)); // Rendering does not reorder the saved list.
    }

    @Test public void damagedRecordsDoNotHideValidDownloads() {
        MediaRepository.OfflineItem incomplete = new MediaRepository.OfflineItem();
        MediaRepository.OfflineItem valid = item("ok", "ss1", "番剧", "ep1", 1, quality(80, "7", "30280"));
        List<DownloadCatalogue.Group> groups = DownloadCatalogue.groups(Arrays.asList(null, incomplete, valid));
        assertEquals(1, groups.size());
        assertEquals(Collections.singletonList(valid), groups.get(0).items);
        assertTrue(DownloadCatalogue.groups(null).isEmpty());
    }

    @Test public void episodeSelectionSortsNumbersWithoutMutatingTheSeries() {
        Models.Episode ten = episode("ss1", "番剧", "ep10", 10);
        Models.Episode two = episode("ss1", "番剧", "ep2", 2);
        Models.Episode one = episode("ss1", "番剧", "ep1", 1);
        List<Models.Episode> source = new ArrayList<>(Arrays.asList(ten, null, two, one));
        assertEquals(Arrays.asList(one, two, ten), DownloadCatalogue.episodes(source));
        assertEquals(Arrays.asList(ten, null, two, one), source);
    }

    @Test public void sameVideoProfileAllowsAnEpisodesDifferentAudioTrackButNotDifferentVideo() {
        Models.Quality selected = quality(112, "7", "30280");
        assertTrue(DownloadCatalogue.sameProfile(selected, quality(112, "7", "30216")));
        assertFalse(DownloadCatalogue.sameProfile(selected, quality(80, "7", "30280")));
        assertFalse(DownloadCatalogue.sameProfile(selected, quality(112, "12", "30280")));
        assertFalse(DownloadCatalogue.sameProfile(selected, quality(112, "progressive", "0")));
    }

    @Test public void malformedProfilesNeverFallBackToAnUnverifiedQuality() {
        Models.Quality selected = quality(80, "7", "30280");
        for (String id : new String[]{"unknown", "80:0:30280", "80:unknown:30280", "112:7:30280", "80:7:"}) {
            Models.Quality malformed = quality(80, "7", "30280");
            malformed.id = id;
            assertFalse(id, DownloadCatalogue.sameProfile(selected, malformed));
        }
        assertNull(DownloadCatalogue.matching(Arrays.asList(quality(64, "7", "30280"), quality(80, "12", "30280")), selected));
        Models.Quality actual = quality(80, "7", "30216");
        assertSame(actual, DownloadCatalogue.matching(Arrays.asList(quality(64, "7", "30280"), actual), selected));
    }

    @Test public void existingPrefersCompleteThenAlreadyQueuedOverFailedVersions() {
        Models.Quality wanted = quality(80, "7", "30280");
        Models.Episode episode = episode("ss1", "番剧", "ep1", 1);
        MediaRepository.OfflineItem failed = item("old-failure", "ss1", "番剧", "ep1", 1, wanted);
        failed.failed = true;
        MediaRepository.OfflineItem queued = item("queued-new-path", "ss1", "番剧", "ep1", 1, quality(80, "7", "30216"));
        MediaRepository.OfflineItem complete = item("complete-old-path", "ss1", "番剧", "ep1", 1, wanted);
        complete.complete = true;

        assertSame(complete, DownloadCatalogue.existing(Arrays.asList(failed, queued, complete), episode, wanted));
        assertSame(queued, DownloadCatalogue.existing(Arrays.asList(failed, queued), episode, wanted));
        assertSame(failed, DownloadCatalogue.existing(Collections.singletonList(failed), episode, wanted));
        assertNull(DownloadCatalogue.existing(Collections.singletonList(complete), episode("ss1", "番剧", "ep2", 2), wanted));
    }

    static Models.Quality quality(int qn, String codec, String audio) {
        Models.Quality result = new Models.Quality();
        result.qn = qn; result.id = qn + ":" + codec + ":" + audio; result.label = result.id;
        result.videoUrl = "https://media.example/video-" + qn + "-" + codec + ".m4s";
        result.audioUrl = "https://media.example/audio-" + audio + ".m4s";
        return result;
    }

    static Models.Episode episode(String series, String title, String id, int index) {
        Models.Episode result = new Models.Episode();
        result.seriesId = series; result.seriesTitle = title; result.id = id; result.index = index;
        return result;
    }

    static MediaRepository.OfflineItem item(String key, String series, String title, String episodeId,
                                           int index, Models.Quality quality) {
        MediaRepository.OfflineItem result = new MediaRepository.OfflineItem();
        result.key = key; result.episodeId = episodeId;
        result.episode = episode(series, title, episodeId, index); result.quality = quality;
        return result;
    }
}
