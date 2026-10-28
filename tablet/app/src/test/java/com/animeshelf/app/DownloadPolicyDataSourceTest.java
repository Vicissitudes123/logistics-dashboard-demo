package com.animeshelf.app;

import static org.junit.Assert.*;

import android.net.Uri;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class DownloadPolicyDataSourceTest {
    private static DataSpec request(long position) {
        return new DataSpec.Builder().setUri("https://media.example/video.m4s?token=private")
            .setPosition(position).setLength(128).setKey("saved-episode:v")
            .setHttpRequestHeaders(Collections.singletonMap("Referer", "https://www.bilibili.com/"))
            .build();
    }

    @Test public void deniedStartupNeverOpensTheNetwork() {
        FakeSource upstream = new FakeSource();
        AtomicInteger blocked = new AtomicInteger();
        DownloadPolicyDataSource source = new DownloadPolicyDataSource(upstream, () -> false,
            blocked::incrementAndGet);
        IOException error = assertThrows(DownloadPolicyDataSource.PolicyBlockedException.class,
            () -> source.open(request(4096)));
        assertEquals(0, upstream.opens);
        assertEquals(1, blocked.get());
        assertFalse(error.getMessage().contains("private"));
        assertFalse(error.getMessage().contains("https://"));
    }

    @Test public void handoverStopsBeforeAnotherReadWithoutReplayingData() throws Exception {
        FakeSource upstream = new FakeSource();
        AtomicBoolean allowed = new AtomicBoolean(true);
        AtomicInteger blocked = new AtomicInteger();
        DownloadPolicyDataSource source = new DownloadPolicyDataSource(upstream, allowed::get,
            blocked::incrementAndGet);
        source.open(request(0));
        assertEquals(4, source.read(new byte[4], 0, 4));
        allowed.set(false);
        assertThrows(DownloadPolicyDataSource.PolicyBlockedException.class,
            () -> source.read(new byte[4], 0, 4));
        assertEquals(1, upstream.opens);
        assertEquals(1, upstream.reads);
        assertEquals(1, blocked.get());
        source.close();
        assertEquals(1, upstream.closes);
    }

    @Test public void lowDiskSpaceStopsAnActiveReadAndExplicitResumePreservesTheRange() throws Exception {
        java.util.concurrent.atomic.AtomicLong free = new java.util.concurrent.atomic.AtomicLong(500_000_000L);
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        DownloadStoragePolicy policy = new DownloadStoragePolicy(org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("guard_space", 0), free::get, clock::get);
        FakeSource upstream = new FakeSource(); AtomicInteger blocked = new AtomicInteger();
        DownloadPolicyDataSource source = new DownloadPolicyDataSource(upstream, policy::allowsRead, blocked::incrementAndGet);
        source.open(request(0)); source.read(new byte[4], 0, 4); free.set(100_000_000L); clock.set(500);
        assertThrows(DownloadPolicyDataSource.PolicyBlockedException.class, () -> source.read(new byte[4], 0, 4));
        assertEquals(1, upstream.reads); assertEquals(1, blocked.get()); source.close();
        free.set(500_000_000L); assertFalse(policy.allowsRead()); policy.resume(); source.open(request(4));
        assertEquals(4, upstream.spec.position); assertEquals(4, source.read(new byte[4], 0, 4));
    }

    @Test public void resumedCallerRangeAndCacheKeyAreNotRewritten() throws Exception {
        FakeSource upstream = new FakeSource();
        AtomicBoolean allowed = new AtomicBoolean(false);
        DownloadPolicyDataSource source = new DownloadPolicyDataSource(upstream, allowed::get, () -> {});
        assertThrows(DownloadPolicyDataSource.PolicyBlockedException.class, () -> source.open(request(0)));
        allowed.set(true);
        DataSpec resumed = request(8192);
        assertEquals(128, source.open(resumed));
        assertSame(resumed, upstream.spec);
        assertEquals(8192, upstream.spec.position);
        assertEquals("saved-episode:v", upstream.spec.key);
        assertSame(resumed.httpRequestHeaders, upstream.spec.httpRequestHeaders);
        assertEquals(upstream.getUri(), source.getUri());
        assertEquals(upstream.getResponseHeaders(), source.getResponseHeaders());
    }

    @Test public void zeroLengthReadDoesNotCauseFailureOrNetworkTraffic() throws Exception {
        FakeSource upstream = new FakeSource();
        AtomicInteger blocked = new AtomicInteger();
        DownloadPolicyDataSource source = new DownloadPolicyDataSource(upstream, () -> false,
            blocked::incrementAndGet);
        assertEquals(0, source.read(new byte[0], 0, 0));
        assertEquals(0, upstream.reads);
        assertEquals(0, blocked.get());
    }

    @Test public void ordinaryUpstreamFailureIsNotRetriedOrReclassified() throws Exception {
        FakeSource upstream = new FakeSource();
        upstream.failure = new IOException("fixed fixture");
        DownloadPolicyDataSource source = new DownloadPolicyDataSource(upstream, () -> true, () -> fail());
        source.open(request(0));
        IOException observed = assertThrows(IOException.class, () -> source.read(new byte[4], 0, 4));
        assertSame(upstream.failure, observed);
        assertEquals(1, upstream.reads);
        assertEquals(1, upstream.opens);
    }

    private static final class FakeSource implements DataSource {
        int opens, reads, closes;
        DataSpec spec;
        IOException failure;
        @Override public long open(DataSpec dataSpec) { opens++; spec = dataSpec; return dataSpec.length; }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            reads++;
            if (failure != null) throw failure;
            return length;
        }
        @Override public void addTransferListener(TransferListener listener) { }
        @Override public Uri getUri() { return spec == null ? null : spec.uri; }
        @Override public Map<String, List<String>> getResponseHeaders() {
            return Collections.singletonMap("Content-Length", Collections.singletonList("128"));
        }
        @Override public void close() { closes++; }
    }
}
