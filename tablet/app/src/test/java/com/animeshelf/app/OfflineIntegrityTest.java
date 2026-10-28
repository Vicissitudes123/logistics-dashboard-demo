package com.animeshelf.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class OfflineIntegrityTest {
    private SimpleCache cache;
    private OfflineIntegrity integrity;
    private SharedPreferences prefs;
    @Before public void setup() {
        Context context = RuntimeEnvironment.getApplication();
        cache = new SimpleCache(new File(context.getCacheDir(), "integrity-" + java.util.UUID.randomUUID()), new NoOpCacheEvictor(), new StandaloneDatabaseProvider(context));
        prefs = context.getSharedPreferences("test_integrity", 0); prefs.edit().clear().commit(); integrity = new OfflineIntegrity(cache, prefs);
    }
    @After public void cleanup() { cache.release(); }
    private void put(String key, long position, byte[] data) throws Exception {
        CacheSpan hole = cache.startReadWrite(key, position, data.length);
        try { File file = cache.startFile(key, position, data.length); try (FileOutputStream output = new FileOutputStream(file)) { output.write(data); } cache.commitFile(file, data.length); }
        finally { cache.releaseHoleSpan(hole); }
    }
    @Test public void firstScanCreatesBaselineThenDetectsSameLengthCorruptionAndRepairsOnlyBadSpan() throws Exception {
        put("v", 0, new byte[]{1,2,3,4}); put("v", 4, new byte[]{5,6,7,8}); put("audio", 0, new byte[]{9,10});
        OfflineIntegrity.Report first = integrity.inspect("v", 8, () -> false);
        assertTrue(first.ok()); assertTrue(first.baselineCreated);
        assertFalse(integrity.inspect("v", 8, () -> false).baselineCreated);
        CacheSpan damaged = cache.getCachedSpans("v").first();
        try (RandomAccessFile file = new RandomAccessFile(damaged.file, "rw")) { file.writeByte(42); }
        OfflineIntegrity.Report check = integrity.inspect("v", 8, () -> false);
        assertFalse(check.ok()); assertEquals(1, check.damaged.size()); assertTrue(integrity.bad("v"));
        integrity.removeDamaged(check, () -> false);
        assertEquals(4, cache.getCachedBytes("v", 0, 8)); assertEquals(2, cache.getCachedBytes("audio", 0, 2));
        put("v", 0, new byte[]{1,2,3,4}); assertTrue(integrity.inspect("v", 8, () -> false).ok()); assertFalse(integrity.bad("v"));
    }
    @Test public void detectsMissingRangeAndTruncatedFilesWithoutOpeningNetwork() throws Exception {
        put("v", 4, new byte[]{5,6,7,8}); assertTrue(integrity.inspect("v", 8, () -> false).missing);
        put("v", 0, new byte[]{1,2,3,4});
        CacheSpan span = cache.getCachedSpans("v").first(); try (RandomAccessFile file = new RandomAccessFile(span.file, "rw")) { file.setLength(2); }
        OfflineIntegrity.Report report = integrity.inspect("v", 8, () -> false); assertEquals(1, report.damaged.size());
        integrity.removeDamaged(report, () -> false); assertEquals(4, cache.getCachedBytes("v", 0, 8));
    }
    @Test public void cancelledScanCannotBlessCorruptBytesOrReplaceBaseline() throws Exception {
        put("v", 0, new byte[512 * 1024]); integrity.inspect("v", 512 * 1024, () -> false);
        String original = prefs.getString("hash:v", ""); AtomicInteger checks = new AtomicInteger();
        assertThrows(java.util.concurrent.CancellationException.class, () -> integrity.inspect("v", 512 * 1024, () -> checks.incrementAndGet() > 2));
        assertEquals(original, prefs.getString("hash:v", "")); assertFalse(integrity.bad("v"));
    }
    @Test public void unknownLengthCannotBeReportedAsVerified() throws Exception {
        put("v", 0, new byte[]{1,2}); OfflineIntegrity.Report report = integrity.inspect("v", -1, () -> false);
        assertFalse(report.ok()); assertTrue(report.unknownLength); assertFalse(prefs.contains("hash:v"));
    }
}
