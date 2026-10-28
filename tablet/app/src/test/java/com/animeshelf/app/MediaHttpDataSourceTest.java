package com.animeshelf.app;

import static org.junit.Assert.*;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class MediaHttpDataSourceTest {
    private static final String PRIMARY = "https://primary.example/video.m4s?token=signed_secret_123";
    private static final String BACKUP = "https://backup.example/video.m4s?token=backup_secret_456";

    @Test public void forbiddenPrimaryUsesRealBackupAndRetainsRangeCacheKeyAndHeaders() throws Exception {
        FakeDataSource primary = new FakeDataSource(403);
        FakeDataSource backup = new FakeDataSource(0);
        FakeFactory factory = new FakeFactory(primary, backup);
        Map<String, String> qualityHeaders = new LinkedHashMap<>();
        qualityHeaders.put("user-agent", "quality-specific-agent");
        qualityHeaders.put("referer", "https://www.bilibili.com/bangumi/play/ep1");
        qualityHeaders.put("Cookie", "SESSDATA=account_secret");
        qualityHeaders.put("Authorization", "Bearer account_secret");
        Map<String, String> extraHeaders = new LinkedHashMap<>();
        extraHeaders.put("Origin", "https://www.bilibili.com");
        extraHeaders.put("cOoKiE", "SESSDATA=another_account_secret");
        extraHeaders.put("pRoXy-AuThOrIzAtIoN", "Basic account_secret");
        MediaHttpDataSource source = new MediaHttpDataSource(dataSpec ->
            new MediaHttpDataSource.Request(Arrays.asList(PRIMARY, BACKUP), qualityHeaders, "视频"), factory);
        TransferListener listener = new EmptyListener();
        source.addTransferListener(listener);
        byte[] body = new byte[] {1, 2, 3};
        Object customData = new Object();
        DataSpec request = new DataSpec.Builder().setUri(PRIMARY).setPosition(4096).setLength(128)
            .setKey("episode-quality:v").setHttpMethod(DataSpec.HTTP_METHOD_POST).setHttpBody(body)
            .setFlags(DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN).setCustomData(customData)
            .setHttpRequestHeaders(extraHeaders).build();

        assertEquals(128, source.open(request));
        assertEquals(2, factory.created);
        assertEquals(1, primary.closeCount);
        assertEquals(0, backup.closeCount);
        for (FakeDataSource attempt : Arrays.asList(primary, backup)) {
            assertEquals(4096, attempt.spec.position);
            assertEquals(128, attempt.spec.length);
            assertEquals("episode-quality:v", attempt.spec.key);
            assertEquals(DataSpec.HTTP_METHOD_POST, attempt.spec.httpMethod);
            assertArrayEquals(body, attempt.spec.httpBody);
            assertEquals(request.flags, attempt.spec.flags);
            assertSame(customData, attempt.spec.customData);
            assertEquals(BiliClient.MEDIA_USER_AGENT, attempt.spec.httpRequestHeaders.get("User-Agent"));
            assertEquals(qualityHeaders.get("referer"), attempt.spec.httpRequestHeaders.get("Referer"));
            assertEquals("https://www.bilibili.com", attempt.spec.httpRequestHeaders.get("Origin"));
            assertEquals(3, attempt.spec.httpRequestHeaders.size());
            assertFalse(attempt.spec.httpRequestHeaders.toString().contains("account_secret"));
            assertEquals(Collections.singletonList(listener), attempt.listeners);
        }
        assertEquals(Uri.parse(PRIMARY), primary.spec.uri);
        assertEquals(Uri.parse(BACKUP), backup.spec.uri);
        assertEquals(Uri.parse(BACKUP), source.getUri());
        assertEquals(backup.getResponseHeaders(), source.getResponseHeaders());
        source.close();
        assertEquals(1, backup.closeCount);
        assertNull(source.getUri());
        assertTrue(source.getResponseHeaders().isEmpty());
    }

    @Test public void allFailuresReportTrackHostAndStatusWithoutSignedUrlsOrAccountData() throws Exception {
        FakeDataSource primary = new FakeDataSource(403);
        FakeDataSource backup = new FakeDataSource(503);
        FakeFactory factory = new FakeFactory(primary, backup);
        MediaHttpDataSource source = new MediaHttpDataSource(spec -> new MediaHttpDataSource.Request(
            Arrays.asList(PRIMARY, BACKUP), Collections.emptyMap(), "音轨"), factory);

        IOException failure = assertThrows(IOException.class,
            () -> source.open(new DataSpec(Uri.parse(PRIMARY))));
        String diagnostic = MediaErrors.describe(failure);
        assertTrue(diagnostic, diagnostic.contains("音轨"));
        assertTrue(diagnostic, diagnostic.contains("primary.example"));
        assertTrue(diagnostic, diagnostic.contains("backup.example"));
        assertTrue(diagnostic, diagnostic.contains("403"));
        assertTrue(diagnostic, diagnostic.contains("503"));
        assertSafe(diagnostic);
        assertSafe(failure.getMessage());
        assertEquals(2, factory.created);
        assertEquals(1, primary.closeCount);
        assertEquals(1, backup.closeCount);
        assertNull(source.getUri());
        source.close();
        assertEquals(1, backup.closeCount);
    }

    @Test public void onlyFiveDistinctValidHttpsCandidatesCanBeAttempted() {
        List<String> urls = new ArrayList<>(Arrays.asList(null, "not a URL",
            "http://plain.example/file", "https://account:password@credential.example/file",
            "https://fragment.example/file#part", "https://invalid.example:0/file",
            "https://invalid.example:65536/file", "https://invalid.example:-2/file",
            "https://example.com\\@invalid.example/file", PRIMARY, PRIMARY));
        for (int i = 0; i < 8; i++) urls.add("https://backup" + i + ".example/file?token=secret");
        FakeFactory factory = new FakeFactory(new FakeDataSource(403), new FakeDataSource(403),
            new FakeDataSource(403), new FakeDataSource(403), new FakeDataSource(403));
        MediaHttpDataSource source = new MediaHttpDataSource(spec ->
            new MediaHttpDataSource.Request(urls, Collections.emptyMap(), "视频"), factory);

        assertThrows(IOException.class, () -> source.open(new DataSpec(Uri.parse(PRIMARY))));
        assertEquals(5, factory.created);
        assertEquals(Uri.parse(PRIMARY), factory.sources.get(0).spec.uri);
        assertEquals(Uri.parse("https://backup3.example/file?token=secret"), factory.sources.get(4).spec.uri);
        for (FakeDataSource attempt : factory.sources) assertEquals(1, attempt.closeCount);
    }

    @Test public void invalidCandidatesNeverCreateNetworkConnections() {
        FakeFactory factory = new FakeFactory();
        MediaHttpDataSource source = new MediaHttpDataSource(spec -> new MediaHttpDataSource.Request(
            Arrays.asList("http://plain.example/file", "https://user@host.example/file"), null, null), factory);
        IOException failure = assertThrows(IOException.class,
            () -> source.open(new DataSpec(Uri.parse(PRIMARY))));
        assertEquals(0, factory.created);
        assertNotNull(failure.getMessage());
        assertFalse(failure.getMessage().contains("user@"));
    }

    @Test public void missingResolverResultUsesOriginalUriAndSharedHeadersWithoutCredentials() throws Exception {
        FakeDataSource original = new FakeDataSource(0);
        MediaHttpDataSource source = new MediaHttpDataSource(spec -> null, new FakeFactory(original));
        DataSpec request = new DataSpec.Builder().setUri(PRIMARY)
            .setHttpRequestHeaders(Collections.singletonMap("Authorization", "Bearer account_secret")).build();
        source.open(request);
        assertEquals(Uri.parse(PRIMARY), original.spec.uri);
        assertEquals(BiliClient.MEDIA_USER_AGENT, original.spec.httpRequestHeaders.get("User-Agent"));
        assertEquals(BiliClient.REFERER, original.spec.httpRequestHeaders.get("Referer"));
        assertEquals(2, original.spec.httpRequestHeaders.size());
        source.close();
    }

    @Test public void restoredLegacyDownloadAndDataSpecCannotRestoreMobileMediaUserAgent() throws Exception {
        JSONObject legacyRecord = new JSONObject()
            .put("id", "80-avc").put("label", "1080P AVC").put("qn", 80)
            .put("videoUrl", PRIMARY)
            .put("headers", new JSONObject()
                .put("user-agent", BiliClient.USER_AGENT)
                .put("Referer", "https://www.bilibili.com/bangumi/play/ep1")
                .put("Cookie", "SESSDATA=account_secret"));
        Models.Quality restored = Models.Quality.fromJson(
            new JSONObject(Models.Quality.fromJson(legacyRecord).toJson().toString()));
        // The old persisted value is still present, so this verifies the final transport policy.
        assertEquals(BiliClient.USER_AGENT, restored.headers.get("user-agent"));
        FakeDataSource original = new FakeDataSource(0);
        MediaHttpDataSource source = new MediaHttpDataSource(spec -> new MediaHttpDataSource.Request(
            restored.videoCandidates(), restored.headers, "视频"), new FakeFactory(original));
        Map<String, String> staleDataSpecHeaders = new LinkedHashMap<>();
        staleDataSpecHeaders.put("USER-AGENT", BiliClient.USER_AGENT);
        staleDataSpecHeaders.put("Cookie", "SESSDATA=another_account_secret");
        source.open(new DataSpec.Builder().setUri(restored.videoUrl).setPosition(2048).setLength(512)
            .setKey("saved-download:v").setHttpRequestHeaders(staleDataSpecHeaders).build());

        assertEquals(BiliClient.MEDIA_USER_AGENT, original.spec.httpRequestHeaders.get("User-Agent"));
        assertFalse(original.spec.httpRequestHeaders.get("User-Agent").contains("Android"));
        assertFalse(original.spec.httpRequestHeaders.get("User-Agent").contains("Mobile"));
        assertEquals("https://www.bilibili.com/bangumi/play/ep1", original.spec.httpRequestHeaders.get("Referer"));
        assertEquals(2, original.spec.httpRequestHeaders.size());
        assertFalse(original.spec.httpRequestHeaders.toString().contains("account_secret"));
        assertEquals(Uri.parse(PRIMARY), original.spec.uri);
        assertEquals(2048, original.spec.position);
        assertEquals(512, original.spec.length);
        assertEquals("saved-download:v", original.spec.key);
        source.close();
    }

    @Test public void readFailureDoesNotReopenOrReplayBytesAndNextMedia3OpenKeepsNewOffset() throws Exception {
        FakeDataSource first = new FakeDataSource(0);
        FakeDataSource next = new FakeDataSource(0);
        FakeFactory factory = new FakeFactory(first, next);
        MediaHttpDataSource source = new MediaHttpDataSource(spec -> new MediaHttpDataSource.Request(
            Arrays.asList(PRIMARY, BACKUP), Collections.emptyMap(), "视频"), factory);
        DataSpec initial = new DataSpec.Builder().setUri(PRIMARY).setPosition(1024).setKey("track:v").build();
        source.open(initial);
        byte[] buffer = new byte[16];
        assertEquals(16, source.read(buffer, 0, buffer.length));
        first.readFailure = new IOException("signed_secret_123 https://private.example/path?SESSDATA=account_secret");

        IOException failure = assertThrows(IOException.class, () -> source.read(buffer, 0, buffer.length));
        assertSafe(MediaErrors.describe(failure));
        assertEquals(1, factory.created);
        assertEquals(0, first.closeCount);
        assertEquals(2, first.readCount);
        source.close();
        // Only the caller (Media3) reopens, after adjusting its position for the bytes consumed.
        source.open(initial.subrange(16));
        assertEquals(1040, next.spec.position);
        assertEquals("track:v", next.spec.key);
        source.close();
    }

    @Test public void failedAttemptCleanupDoesNotMaskHttpStatusOrPreventBackup() throws Exception {
        FakeDataSource primary = new FakeDataSource(403);
        primary.closeFailure = new IOException("cleanup failed");
        FakeDataSource backup = new FakeDataSource(0);
        MediaHttpDataSource source = new MediaHttpDataSource(spec -> new MediaHttpDataSource.Request(
            Arrays.asList(PRIMARY, BACKUP), null, "视频"), new FakeFactory(primary, backup));
        assertEquals(128, source.open(new DataSpec(Uri.parse(PRIMARY))));
        assertEquals(Uri.parse(BACKUP), source.getUri());
        TransferListener lateListener = new EmptyListener();
        source.addTransferListener(lateListener);
        assertEquals(Collections.singletonList(lateListener), backup.listeners);
        source.close();
    }

    @Test public void earlyEofInKnownResponseKeepsTrackAndHostWithoutSilentlyCompletingOrReplaying() throws Exception {
        FakeDataSource first = new FakeDataSource(0);
        first.defaultLength = 32;
        FakeFactory factory = new FakeFactory(first);
        MediaHttpDataSource source = new MediaHttpDataSource(spec -> new MediaHttpDataSource.Request(
            Arrays.asList(PRIMARY, BACKUP), null, "视频"), factory);
        source.open(new DataSpec.Builder().setUri(PRIMARY).build());
        assertEquals(16, source.read(new byte[16], 0, 16));
        first.endOfInput = true;
        IOException error = assertThrows(IOException.class, () -> source.read(new byte[16], 0, 16));
        String diagnostic = MediaErrors.describe(error);
        assertTrue(diagnostic, diagnostic.contains("视频"));
        assertTrue(diagnostic, diagnostic.contains("primary.example"));
        assertTrue(diagnostic, diagnostic.contains("EOFException"));
        assertSafe(diagnostic);
        assertEquals(1, factory.created);
        source.close();
    }

    @Test public void completeKnownResponseAndUnknownLengthCanEndNormally() throws Exception {
        for (long length : new long[]{16, C.LENGTH_UNSET}) {
            FakeDataSource first = new FakeDataSource(0);
            first.defaultLength = C.LENGTH_UNSET;
            MediaHttpDataSource source = new MediaHttpDataSource(null, new FakeFactory(first));
            source.open(new DataSpec.Builder().setUri(PRIMARY).setLength(length).build());
            assertEquals(16, source.read(new byte[16], 0, 16));
            first.endOfInput = true;
            assertEquals(C.RESULT_END_OF_INPUT, source.read(new byte[16], 0, 16));
            source.close();
        }
    }

    @Test public void boundedRequestPastResourceEndMayEndBeforeRequestedLength() throws Exception {
        FakeDataSource first = new FakeDataSource(0);
        MediaHttpDataSource source = new MediaHttpDataSource(null, new FakeFactory(first));
        assertEquals(32, source.open(new DataSpec.Builder().setUri(PRIMARY).setPosition(100).setLength(32).build()));
        assertEquals(16, source.read(new byte[16], 0, 16));
        first.endOfInput = true;
        assertEquals(C.RESULT_END_OF_INPUT, source.read(new byte[16], 0, 16));
        source.close();
    }

    @Test public void boundedRequestAtResourceEndMayImmediatelyReturnEndOfInput() throws Exception {
        FakeDataSource first = new FakeDataSource(0);
        first.endOfInput = true;
        MediaHttpDataSource source = new MediaHttpDataSource(null, new FakeFactory(first));
        // DefaultHttpDataSource allows position == documentSize (including HTTP 416),
        // while returning the caller's bounded requested length from open().
        assertEquals(32, source.open(new DataSpec.Builder().setUri(PRIMARY).setPosition(116).setLength(32).build()));
        assertEquals(C.RESULT_END_OF_INPUT, source.read(new byte[16], 0, 16));
        source.close();
    }

    private static void assertSafe(String text) {
        assertNotNull(text);
        assertFalse(text, text.contains("signed_secret_123"));
        assertFalse(text, text.contains("backup_secret_456"));
        assertFalse(text, text.contains("account_secret"));
        assertFalse(text, text.contains("/video.m4s"));
        assertFalse(text, text.contains("token="));
        assertFalse(text, text.contains("SESSDATA="));
    }

    private static final class FakeFactory implements DataSource.Factory {
        final List<FakeDataSource> sources;
        int created;
        FakeFactory(FakeDataSource... sources) { this.sources = Arrays.asList(sources); }
        @Override public DataSource createDataSource() { return sources.get(created++); }
    }

    private static final class FakeDataSource implements DataSource {
        final int status;
        final List<TransferListener> listeners = new ArrayList<>();
        DataSpec spec;
        IOException readFailure, closeFailure;
        boolean endOfInput;
        long defaultLength = 128;
        int closeCount, readCount;
        FakeDataSource(int status) { this.status = status; }
        @Override public void addTransferListener(TransferListener listener) { listeners.add(listener); }
        @Override public long open(DataSpec spec) throws IOException {
            this.spec = spec;
            if (status != 0) throw new HttpDataSource.InvalidResponseCodeException(status,
                "signed_secret_123", null, Collections.emptyMap(), spec,
                "SESSDATA=account_secret".getBytes(StandardCharsets.UTF_8));
            return spec.length == C.LENGTH_UNSET ? defaultLength : spec.length;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            readCount++;
            if (readFailure != null) throw readFailure;
            if (endOfInput) return C.RESULT_END_OF_INPUT;
            Arrays.fill(buffer, offset, offset + length, (byte) 7);
            return length;
        }
        @Override public Uri getUri() { return spec == null ? null : spec.uri; }
        @Override public Map<String, List<String>> getResponseHeaders() {
            return Collections.singletonMap("Content-Type", Collections.singletonList("video/mp4"));
        }
        @Override public void close() throws IOException {
            closeCount++;
            if (closeFailure != null) throw closeFailure;
        }
    }

    private static final class EmptyListener implements TransferListener {
        @Override public void onTransferInitializing(DataSource source, DataSpec spec, boolean isNetwork) { }
        @Override public void onTransferStart(DataSource source, DataSpec spec, boolean isNetwork) { }
        @Override public void onBytesTransferred(DataSource source, DataSpec spec, boolean isNetwork, int bytes) { }
        @Override public void onTransferEnd(DataSource source, DataSpec spec, boolean isNetwork) { }
    }
}
