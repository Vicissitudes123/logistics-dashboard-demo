package com.animeshelf.app;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class UpdateCheckerTest {
    private static final String ROOT = "https://raw.githubusercontent.com/Vicissitudes123/"
            + "logistics-dashboard-demo/anime-shelf-apk/downloads/";
    private CookieHandler previousCookies;

    @Before public void setUp() {
        previousCookies = CookieHandler.getDefault(); CookieHandler.setDefault(null);
    }

    @After public void tearDown() { CookieHandler.setDefault(previousCookies); }

    @Test public void parsesReleaseAndComparesIntegerVersionAndAndroidRequirement() throws Exception {
        UpdateChecker.Release next = UpdateChecker.parse(manifest().toString(), 8, 28);
        assertEquals(1, next.schemaVersion); assertEquals(9, next.versionCode);
        assertEquals("0.1.8", next.versionName); assertEquals("com.animeshelf.app", next.packageName);
        assertTrue(next.isNewer); assertTrue(next.isCompatible);
        assertEquals(1234567L, next.apkBytes); assertEquals(1234000L, next.zipBytes);
        assertEquals(ROOT + "anime-shelf-0.1.8.apk", next.apkUrl);
        assertEquals(ROOT + "anime-shelf-0.1.8-install.zip", next.zipUrl);
        assertEquals(Arrays.asList("新增手动检查更新", "改进播放控制"), next.notes);
        assertThrows(UnsupportedOperationException.class, () -> next.notes.add("mutated"));
        assertFalse(UpdateChecker.parse(manifest().toString(), 9, 28).isNewer);
        assertFalse(UpdateChecker.parse(manifest().toString(), 10, 28).isNewer);
        UpdateChecker.Release incompatible = UpdateChecker.parse(manifest().put("minSdk", 30).toString(), 8, 29);
        assertTrue(incompatible.isNewer); assertFalse(incompatible.isCompatible);
        assertTrue(UpdateChecker.parse(manifest().put("minSdk", 30).toString(), 8, 30).isCompatible);
        // Android versionCode is authoritative, not lexicographic versionName ordering.
        JSONObject oldNamedVersion = manifest().put("versionName", "0.1.2")
                .put("apkUrl", ROOT + "anime-shelf-0.1.2.apk").put("zipUrl", ROOT + "anime-shelf-0.1.2-install.zip");
        assertTrue(UpdateChecker.parse(oldNamedVersion.toString(), 8, 28).isNewer);
    }

    @Test public void rejectsMissingExtraWrongTypeAndOutOfRangeFields() throws Exception {
        List<String> invalid = new ArrayList<>();
        JSONObject missing = manifest(); missing.remove("apkSha256"); invalid.add(missing.toString());
        invalid.add(manifest().put("another", "extra").toString());
        invalid.add(manifest().put("schemaVersion", 2).toString());
        invalid.add(manifest().put("schemaVersion", "1").toString());
        invalid.add(manifest().put("versionCode", "9").toString());
        invalid.add(manifest().put("versionCode", 9.5).toString());
        invalid.add(manifest().put("versionCode", 0).toString());
        invalid.add(manifest().put("versionCode", 2147483648L).toString());
        invalid.add(manifest().put("versionName", "../other").toString());
        invalid.add(manifest().put("packageName", "com.other.app").toString());
        invalid.add(manifest().put("minSdk", 25).toString());
        invalid.add(manifest().put("minSdk", 1001).toString());
        invalid.add(manifest().put("minSdk", true).toString());
        invalid.add(manifest().put("apkBytes", 0).toString());
        invalid.add(manifest().put("zipBytes", -1).toString());
        invalid.add(manifest().put("apkBytes", UpdateChecker.MAX_ARTIFACT_BYTES + 1).toString());
        invalid.add(manifest().put("zipBytes", "1234000").toString());
        invalid.add(manifest().put("apkSha256", "a".repeat(63)).toString());
        invalid.add(manifest().put("zipSha256", "z".repeat(64)).toString());
        invalid.add(manifest().put("sourceCommit", "1".repeat(39)).toString());
        invalid.add(manifest().put("notes", JSONObject.NULL).toString());
        for (String input : invalid) assertThrows(UpdateChecker.UpdateException.class, () -> parse(input));
        assertThrows(UpdateChecker.UpdateException.class, () -> UpdateChecker.parse(manifest().toString(), -1, 28));
        assertThrows(UpdateChecker.UpdateException.class, () -> UpdateChecker.parse(manifest().toString(), 8, 0));
    }

    @Test public void rejectsForeignAndAmbiguousArtifactUrlsIncludingVersionMismatch() throws Exception {
        String valid = ROOT + "anime-shelf-0.1.8.apk";
        List<String> urls = Arrays.asList(
                valid.replace("https://", "http://"),
                valid.replace("raw.githubusercontent.com", "raw.githubusercontent.com.evil.test"),
                valid.replace("https://", "https://user:secret@"),
                valid.replace("Vicissitudes123", "other-owner"),
                valid.replace("logistics-dashboard-demo", "other-repo"),
                valid.replace("anime-shelf-apk/downloads", "main/downloads"),
                valid.replace("0.1.8.apk", "0.1.9.apk"),
                valid.replace("/anime-shelf-", "/../anime-shelf-"),
                valid.replace("anime-shelf-0.1.8", "anime-shelf-%30.1.8"),
                valid + "?token=secret", valid + "#fragment", valid.replace(".com/", ".com:443/"),
                "javascript:alert(1)", "file:///private/update.apk", "content://private/update.apk");
        for (String url : urls) {
            String input = manifest().put("apkUrl", url).toString();
            assertThrows(UpdateChecker.UpdateException.class, () -> parse(input));
        }
        assertThrows(UpdateChecker.UpdateException.class,
                () -> parse(manifest().put("zipUrl", valid).toString()));
    }

    @Test public void limitsNotesAndRejectsControlCharactersWithoutReflectingThemInErrors() throws Exception {
        JSONArray tooMany = new JSONArray(); for (int i = 0; i < 33; i++) tooMany.put("note");
        JSONArray tooLong = new JSONArray(); for (int i = 0; i < 13; i++) tooLong.put("n".repeat(1000));
        for (JSONArray notes : Arrays.asList(tooMany, tooLong, new JSONArray().put(123), new JSONArray().put(""),
                new JSONArray().put("n".repeat(1001)), new JSONArray().put("line\nnext"),
                new JSONArray().put("DISPLAY_SECRET_CANARY\u202eAPK"))) {
            IOException failure = assertThrows(UpdateChecker.UpdateException.class,
                    () -> parse(manifest().put("notes", notes).toString()));
            assertFalse(failure.getMessage().contains("DISPLAY_SECRET_CANARY"));
        }
        assertTrue(parse(manifest().put("notes", new JSONArray()).toString()).notes.isEmpty());
    }

    @Test public void requiresStrictCompleteBoundedJsonAndRejectsDuplicateKeys() throws Exception {
        String valid = manifest().toString();
        List<String> invalid = Arrays.asList("", "{}", "[]", valid + " trailing-data", valid + "{}",
                "{\"versionCode\":1," + valid.substring(1),
                "{\"version\\u0043ode\":1," + valid.substring(1),
                valid.replace("\"versionCode\":9", "\"versionCode\":09"),
                valid.replace("\"versionCode\":9", "\"versionCode\":0x9"),
                valid.replace("\"versionCode\":9", "versionCode:9"),
                valid.replace("\"versionCode\"", "'versionCode'"),
                valid.substring(0, valid.length() - 1) + ",}",
                "{\"notes\":[[[[[[]]]]]]}", " ".repeat(UpdateChecker.MAX_MANIFEST_BYTES + 1),
                "汉".repeat(UpdateChecker.MAX_MANIFEST_BYTES / 2), "\ufeff" + valid);
        for (String input : invalid) assertThrows(UpdateChecker.UpdateException.class, () -> parse(input));
        assertThrows(UpdateChecker.UpdateException.class, () -> parse(null));
        assertEquals(9, parse(" \r\n" + valid + "\t").versionCode);
    }

    @Test public void requestsOnlyFixedManifestWithoutCredentialsAndSetsNetworkBounds() throws Exception {
        FakeConnection connection = new FakeConnection(manifest().toString().getBytes(StandardCharsets.UTF_8));
        List<URL> requested = new ArrayList<>();
        UpdateChecker.Release result = UpdateChecker.check(8, 28, url -> { requested.add(url); return connection; });
        assertTrue(result.isNewer); assertEquals(1, requested.size());
        assertEquals(UpdateChecker.MANIFEST_URL, requested.get(0).toString());
        assertEquals(5000, connection.getConnectTimeout()); assertEquals(5000, connection.getReadTimeout());
        assertFalse(connection.getInstanceFollowRedirects()); assertFalse(connection.getUseCaches());
        assertEquals("GET", connection.getRequestMethod()); assertEquals("identity", connection.getRequestProperty("Accept-Encoding"));
        assertNull(connection.getRequestProperty("Cookie")); assertNull(connection.getRequestProperty("Authorization"));
        assertTrue(connection.disconnected); assertTrue(connection.streamClosed);
    }

    @Test public void doesNotFollowRedirectsOrExposeHttpResponseText() throws Exception {
        for (int status : new int[] {301, 302, 307, 308, 403, 404, 500}) {
            FakeConnection connection = new FakeConnection(new byte[0]); connection.status = status;
            int[] requests = {0};
            IOException failure = assertThrows(UpdateChecker.UpdateException.class,
                    () -> UpdateChecker.check(8, 28, url -> { requests[0]++; return connection; }));
            assertEquals(1, requests[0]); assertFalse(connection.streamRequested); assertTrue(connection.disconnected);
            assertTrue(failure.getMessage().contains("HTTP " + status));
            assertFalse(failure.getMessage().contains("SERVER_SECRET_CANARY"));
        }
    }

    @Test public void rejectsOversizedDeclaredAndStreamedBodiesAndInvalidUtf8() throws Exception {
        FakeConnection declared = new FakeConnection(new byte[0]);
        declared.declaredLength = UpdateChecker.MAX_MANIFEST_BYTES + 1;
        assertThrows(UpdateChecker.UpdateException.class, () -> UpdateChecker.check(8, 28, url -> declared));
        assertFalse(declared.streamRequested); assertTrue(declared.disconnected);
        FakeConnection streamed = new FakeConnection(new byte[UpdateChecker.MAX_MANIFEST_BYTES + 1]);
        assertThrows(UpdateChecker.UpdateException.class, () -> UpdateChecker.check(8, 28, url -> streamed));
        assertTrue(streamed.streamClosed); assertTrue(streamed.disconnected);
        FakeConnection invalidUtf8 = new FakeConnection(new byte[] {(byte) 0xc3, (byte) 0x28});
        IOException failure = assertThrows(UpdateChecker.UpdateException.class,
                () -> UpdateChecker.check(8, 28, url -> invalidUtf8));
        assertTrue(failure.getMessage().contains("UTF-8")); assertTrue(invalidUtf8.streamClosed);
    }

    @Test public void blocksGlobalCookieInjectionAndSanitizesNetworkFailures() throws Exception {
        CookieHandler.setDefault(new CookieManager());
        int[] requests = {0};
        assertThrows(UpdateChecker.UpdateException.class, () -> UpdateChecker.check(8, 28, url -> {
            requests[0]++; return new FakeConnection(new byte[0]);
        }));
        assertEquals(0, requests[0]); CookieHandler.setDefault(null);
        IOException failure = assertThrows(UpdateChecker.UpdateException.class,
                () -> UpdateChecker.check(8, 28, url -> { throw new IOException("NETWORK_SECRET_CANARY"); }));
        assertFalse(failure.getMessage().contains("NETWORK_SECRET_CANARY")); assertNull(failure.getCause());
    }

    @Test public void interruptedReadStopsAndClosesTransportWithoutClearingInterrupt() throws Exception {
        FakeConnection connection = new FakeConnection(manifest().toString().getBytes(StandardCharsets.UTF_8));
        Thread.currentThread().interrupt();
        try {
            IOException failure = assertThrows(UpdateChecker.UpdateException.class,
                    () -> UpdateChecker.check(8, 28, url -> connection));
            assertTrue(failure.getMessage().contains("取消"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(connection.streamClosed); assertTrue(connection.disconnected);
        } finally { Thread.interrupted(); }
    }

    private static UpdateChecker.Release parse(String input) throws IOException { return UpdateChecker.parse(input, 8, 28); }

    private static JSONObject manifest() throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("versionCode", 9).put("versionName", "0.1.8")
                .put("packageName", "com.animeshelf.app").put("minSdk", 26)
                .put("apkUrl", ROOT + "anime-shelf-0.1.8.apk").put("zipUrl", ROOT + "anime-shelf-0.1.8-install.zip")
                .put("apkSha256", "a".repeat(64)).put("zipSha256", "b".repeat(64))
                .put("apkBytes", 1234567).put("zipBytes", 1234000)
                .put("notes", new JSONArray().put("新增手动检查更新").put("改进播放控制"))
                .put("sourceCommit", "c".repeat(40));
    }

    private static final class FakeConnection extends HttpURLConnection {
        final byte[] body;
        int status = 200;
        long declaredLength = -1;
        boolean streamRequested, streamClosed, disconnected;
        FakeConnection(byte[] body) throws IOException { super(new URL(UpdateChecker.MANIFEST_URL)); this.body = body; }
        @Override public void connect() { }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return status; }
        @Override public String getResponseMessage() { return "SERVER_SECRET_CANARY"; }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? "https://evil.test/SERVER_SECRET_CANARY" : null; }
        @Override public long getContentLengthLong() { return declaredLength; }
        @Override public InputStream getInputStream() {
            streamRequested = true;
            return new ByteArrayInputStream(body) {
                @Override public void close() throws IOException { streamClosed = true; super.close(); }
            };
        }
    }
}
