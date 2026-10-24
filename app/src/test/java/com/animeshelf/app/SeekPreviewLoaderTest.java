package com.animeshelf.app;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSpec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class SeekPreviewLoaderTest {
    private ScheduledExecutorService worker;
    private SeekPreviewLoader loader;

    @Before public void setUp() { worker = Executors.newSingleThreadScheduledExecutor(); }
    @After public void tearDown() throws Exception {
        if (loader != null) loader.close(); else worker.shutdownNow();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void create(SeekPreviewLoader.DecoderFactory factory) {
        loader = new SeekPreviewLoader(new Handler(Looper.getMainLooper()), worker, factory, () -> 0, 0);
    }
    private static SeekPreviewLoader.Source source(String id) {
        return new SeekPreviewLoader.Source(id, () -> new ByteArrayDataSource(new byte[]{1, 2, 3}),
            new DataSpec.Builder().setUri("file:///preview-fixture.mp4").setKey(id).build());
    }
    private void drain() throws Exception {
        worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
        worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void sourceIsLazyAndNearbyRequestsReuseSmallFrameCache() throws Exception {
        AtomicInteger created = new AtomicInteger(); FakeDecoder decoder = new FakeDecoder();
        create(input -> { created.incrementAndGet(); return decoder; });
        loader.setSource(source("same")); drain(); assertEquals(0, created.get());
        List<Bitmap> delivered = new ArrayList<>();
        loader.request(4200, image -> { assertEquals(Looper.getMainLooper(), Looper.myLooper()); delivered.add(image); });
        drain();
        loader.request(5900, delivered::add); drain();
        assertEquals(1, created.get()); assertEquals(Collections.singletonList(4000L), decoder.positions);
        assertEquals(2, delivered.size()); assertSame(delivered.get(0), delivered.get(1));
    }

    @Test public void pendingWorkKeepsOnlyTheLatestPositionAndNeverDeliversAnOldFrame() throws Exception {
        CountDownLatch started = new CountDownLatch(1), unblock = new CountDownLatch(1);
        FakeDecoder decoder = new FakeDecoder() {
            @Override public Bitmap frame(long position) throws Exception {
                if (positions.isEmpty()) { started.countDown(); assertTrue(unblock.await(5, TimeUnit.SECONDS)); }
                return super.frame(position);
            }
        };
        create(input -> decoder); loader.setSource(source("a"));
        List<String> callbacks = new ArrayList<>();
        try {
            loader.request(0, bitmap -> callbacks.add("old")); assertTrue(started.await(5, TimeUnit.SECONDS));
            loader.request(4000, bitmap -> callbacks.add("superseded"));
            loader.request(8000, bitmap -> callbacks.add("latest"));
        } finally { unblock.countDown(); }
        drain();
        assertEquals(java.util.Arrays.asList(0L, 8000L), decoder.positions);
        assertEquals(Collections.singletonList("latest"), callbacks);
    }

    @Test public void changingSourceCancelsItsInputAndCannotReturnThePreviousEpisodeImage() throws Exception {
        CountDownLatch started = new CountDownLatch(1), unblock = new CountDownLatch(1);
        FakeDecoder old = new FakeDecoder() {
            @Override public Bitmap frame(long position) throws Exception {
                started.countDown(); assertTrue(unblock.await(5, TimeUnit.SECONDS)); return super.frame(position);
            }
        };
        FakeDecoder fresh = new FakeDecoder();
        create(input -> "old".equals(input.identity) ? old : fresh);
        loader.setSource(source("old")); List<Bitmap> delivered = new ArrayList<>();
        try {
            loader.request(0, delivered::add); assertTrue(started.await(5, TimeUnit.SECONDS));
            loader.setSource(source("new")); assertTrue(old.canceled);
            loader.request(0, delivered::add);
        } finally { unblock.countDown(); }
        drain();
        assertTrue(old.closed); assertEquals(1, delivered.size()); assertSame(fresh.bitmap, delivered.get(0));
    }

    @Test public void cancelAndCloseAlsoInvalidateCallbacksAlreadyWaitingOnTheMainThread() throws Exception {
        FakeDecoder decoder = new FakeDecoder(); create(input -> decoder); loader.setSource(source("a"));
        List<Bitmap> delivered = new ArrayList<>();
        loader.request(0, delivered::add); worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
        loader.cancel(); drain(); assertTrue(delivered.isEmpty()); assertTrue(decoder.canceled);
        loader.request(0, delivered::add); worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
        loader.close(); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertTrue(delivered.isEmpty());
    }

    @Test public void unavailableDecoderReturnsNullAndDoesNotStopTheNextSource() throws Exception {
        FakeDecoder good = new FakeDecoder();
        create(input -> {
            if ("bad".equals(input.identity)) throw new IOException("simulated unsupported fragmented track");
            return good;
        });
        List<Bitmap> delivered = new ArrayList<>(); loader.setSource(source("bad"));
        loader.request(5000, delivered::add); drain(); assertEquals(1, delivered.size()); assertNull(delivered.get(0));
        loader.setSource(source("good")); loader.request(5000, delivered::add); drain();
        assertEquals(2, delivered.size()); assertSame(good.bitmap, delivered.get(1));
    }

    @Test public void noSourceReturnsNoFabricatedPictureAndNeverCreatesADecoder() throws Exception {
        AtomicInteger created = new AtomicInteger(); create(input -> { created.incrementAndGet(); return new FakeDecoder(); });
        List<Bitmap> delivered = new ArrayList<>(); loader.request(100, delivered::add); drain();
        assertEquals(0, created.get()); assertEquals(1, delivered.size()); assertNull(delivered.get(0));
    }

    @Test public void missingFramesBackOffAcrossPositionsBeforeTryingAgain() throws Exception {
        AtomicLong clock = new AtomicLong(); AtomicInteger attempts = new AtomicInteger();
        FakeDecoder missing = new FakeDecoder() {
            @Override public Bitmap frame(long position) { attempts.incrementAndGet(); return null; }
        };
        loader = new SeekPreviewLoader(new Handler(Looper.getMainLooper()), worker,
            input -> missing, clock::get, 0);
        List<Bitmap> delivered = new ArrayList<>(); loader.setSource(source("missing"));
        loader.request(0, delivered::add); drain(); assertEquals(1, attempts.get());
        loader.request(4000, delivered::add); drain(); assertEquals(1, attempts.get());
        clock.set(5000);
        loader.request(8000, delivered::add); drain(); assertEquals(2, attempts.get());
        assertEquals(3, delivered.size());
        for (Bitmap bitmap : delivered) assertNull(bitmap);
    }

    @Test public void frameCacheIsBoundedAndAnEvictedPositionCanBeDecodedAgain() throws Exception {
        FakeDecoder decoder = new FakeDecoder(); create(input -> decoder); loader.setSource(source("a"));
        for (int i = 0; i < 30; i++) { loader.request(i * 2000, bitmap -> { }); drain(); }
        assertEquals(30, decoder.positions.size());
        loader.request(29 * 2000, bitmap -> { }); drain(); assertEquals(30, decoder.positions.size());
        loader.request(0, bitmap -> { }); drain(); assertEquals(31, decoder.positions.size());
    }

    private static class FakeDecoder implements SeekPreviewLoader.FrameDecoder {
        final List<Long> positions = Collections.synchronizedList(new ArrayList<>());
        final Bitmap bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888);
        volatile boolean canceled, closed;
        @Override public Bitmap frame(long position) throws Exception { positions.add(position); return bitmap; }
        @Override public void cancel() { canceled = true; }
        @Override public void close() { closed = true; }
    }
}
