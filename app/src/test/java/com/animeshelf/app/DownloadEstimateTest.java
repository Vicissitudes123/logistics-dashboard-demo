package com.animeshelf.app;

import static org.junit.Assert.*;
import java.lang.reflect.Method;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 28)
public class DownloadEstimateTest {
    @Test public void dashParsingIncludesBothTracksAndKeepsTheEstimateHonest() throws Exception {
        Models.Quality q = parsed("{\"timelength\":240000,\"dash\":{\"video\":[{\"id\":80,\"codecid\":7,\"bandwidth\":4000000,\"baseUrl\":\"https://example.test/video.m4s\"}],\"audio\":[{\"id\":30280,\"bandwidth\":128000,\"baseUrl\":\"https://example.test/audio.m4s\"}]}}");
        assertEquals(123840000, DownloadEstimate.of(q).bytes); assertFalse(DownloadEstimate.of(q).exact);
        Models.Quality restored = Models.Quality.fromJson(q.toJson());
        assertEquals(123840000, DownloadEstimate.of(restored).bytes); assertEquals(128000, restored.audioBandwidth);
    }
    @Test public void progressiveReportedSizeIsExactAndDoesNotInventAnAudioTrack() throws Exception {
        Models.Quality q = parsed("{\"quality\":80,\"durl\":[{\"url\":\"https://example.test/video.mp4\",\"size\":123456,\"length\":10000}]}");
        assertTrue(DownloadEstimate.of(q).exact); assertEquals(123456, DownloadEstimate.of(q).bytes);
    }
    @Test public void missingAudioOrDurationCannotUnderstateTheSizeAsVideoOnly() {
        Models.Quality q = quality(); q.audioBandwidth = 0;
        assertEquals(-1, DownloadEstimate.of(q).bytes);
        q.audioBandwidth = 128000; q.durationMs = 0; assertEquals(-1, DownloadEstimate.of(q).bytes);
        q.videoBytes = 100; q.audioBytes = 30; assertEquals(130, DownloadEstimate.of(q).bytes);
        assertTrue(DownloadEstimate.of(q).exact);
    }
    @Test public void olderSavedFormatsAndOverflowStayUnknownRatherThanZeroOrNegativeSizes() throws Exception {
        assertEquals(-1, DownloadEstimate.of(Models.Quality.fromJson(new JSONObject())).bytes);
        Models.Quality q = quality(); q.videoBytes = Long.MAX_VALUE; q.audioBytes = 100;
        assertEquals(-1, DownloadEstimate.of(q).bytes);
        q.videoBytes = 0; q.audioBytes = 0; q.bandwidth = Long.MAX_VALUE; assertEquals(-1, DownloadEstimate.of(q).bytes);
    }
    @Test public void dashSecondsAreUsedOnlyWhenMillisecondDurationIsAbsent() throws Exception {
        Models.Quality q = parsed("{\"dash\":{\"duration\":12.5,\"video\":[{\"id\":80,\"codecid\":7,\"bandwidth\":800000,\"baseUrl\":\"https://example.test/v\"}],\"audio\":[{\"id\":30280,\"bandwidth\":160000,\"baseUrl\":\"https://example.test/a\"}]}}");
        assertEquals(12500, q.durationMs); assertEquals(1500000, DownloadEstimate.of(q).bytes);
    }
    static Models.Quality quality() {
        Models.Quality q = new Models.Quality(); q.qn = 80; q.id = "80:7:30280"; q.videoUrl = "https://example.test/v";
        q.audioUrl = "https://example.test/a"; q.durationMs = 240000; q.bandwidth = 4000000; q.audioBandwidth = 128000; return q;
    }
    @SuppressWarnings("unchecked") private Models.Quality parsed(String json) throws Exception {
        Method m = BiliClient.class.getDeclaredMethod("parseQualities", JSONObject.class); m.setAccessible(true);
        return ((List<Models.Quality>) m.invoke(new BiliClient(""), new JSONObject(json))).get(0);
    }
}
