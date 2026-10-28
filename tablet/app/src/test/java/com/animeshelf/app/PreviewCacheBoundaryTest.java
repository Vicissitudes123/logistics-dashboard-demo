package com.animeshelf.app;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PreviewCacheBoundaryTest {
    private SimpleCache cache;
    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        cache = new SimpleCache(new File(context.getCacheDir(), "preview-boundary"),
            new NoOpCacheEvictor(), new StandaloneDatabaseProvider(context));
    }
    @After public void tearDown() { cache.release(); }

    private static DataSpec request() {
        return new DataSpec.Builder().setUri("https://media.example.test/preview.m4s").setKey("preview:v").build();
    }

    @Test public void offlineCacheMissCannotConstructANetworkUpstream() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        DataSource.Factory forbidden = () -> { opens.incrementAndGet(); throw new AssertionError("Offline preview attempted network"); };
        DataSource input = MediaRepository.previewDataFactory(cache, forbidden, true).createDataSource();
        try {
            input.open(request()); input.read(new byte[8], 0, 8); fail("Missing offline bytes must fail");
        } catch (IOException expected) { }
        finally { input.close(); }
        assertEquals(0, opens.get()); assertEquals(0, cache.getCacheSpace());
    }

    @Test public void onlinePreviewReadsSourceBytesWithoutCreatingOfflineCacheSpans() throws Exception {
        AtomicInteger opens = new AtomicInteger(); byte[] content = {1, 2, 3, 4};
        DataSource.Factory online = () -> { opens.incrementAndGet(); return new ByteArrayDataSource(content); };
        DataSource input = MediaRepository.previewDataFactory(cache, online, false).createDataSource();
        byte[] read = new byte[4];
        try { assertEquals(4, input.open(request())); assertEquals(4, input.read(read, 0, read.length)); }
        finally { input.close(); }
        assertArrayEquals(content, read); assertEquals(1, opens.get());
        assertEquals(0, cache.getCacheSpace()); assertTrue(cache.getCachedSpans("preview:v").isEmpty());
    }
}
