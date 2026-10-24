package com.animeshelf.app;

import static org.junit.Assert.*;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PreviewMediaDataSourceTest {
    private static DataSpec spec() {
        return new DataSpec.Builder().setUri("https://media.example.test/video.m4s")
            .setKey("offline-video:v").setHttpRequestHeaders(Collections.singletonMap("Referer", "https://www.bilibili.com/"))
            .build();
    }

    @Test public void randomReadsPreserveRangeIdentityAndReuseSequentialConnection() throws Exception {
        FakeFactory factory = new FakeFactory(1024);
        PreviewMediaDataSource input = new PreviewMediaDataSource(factory, spec(), () -> 0);
        assertEquals(1024, input.getSize());
        byte[] bytes = new byte[8];
        assertEquals(4, input.readAt(0, bytes, 0, 4));
        assertEquals(4, input.readAt(4, bytes, 4, 4));
        assertEquals(1, factory.requests.size());
        for (int i = 0; i < 8; i++) assertEquals((byte) i, bytes[i]);
        assertEquals(4, input.readAt(100, bytes, 2, 4));
        assertEquals(2, factory.requests.size());
        DataSpec ranged = factory.requests.get(1);
        assertEquals(100, ranged.position); assertEquals(C.LENGTH_UNSET, ranged.length);
        assertEquals(spec().key, ranged.key); assertEquals(spec().uri, ranged.uri);
        assertEquals(spec().httpRequestHeaders, ranged.httpRequestHeaders);
        assertEquals(1024, input.getSize());
        input.close(); assertEquals(2, factory.closes);
    }

    @Test public void cancellationNeverOpensOrReadsMoreData() throws Exception {
        FakeFactory factory = new FakeFactory(100);
        PreviewMediaDataSource input = new PreviewMediaDataSource(factory, spec(), () -> 0);
        byte[] buffer = new byte[4]; input.readAt(0, buffer, 0, buffer.length);
        input.cancel();
        try { input.readAt(4, buffer, 0, buffer.length); fail(); } catch (InterruptedIOException expected) { }
        assertEquals(4, factory.readBytes); assertEquals(1, factory.requests.size());
        input.beginOperation();
        try { input.getSize(); fail("Beginning another operation cannot revive canceled input"); }
        catch (InterruptedIOException expected) { }
        input.close();
    }

    @Test public void byteBudgetStopsBeforeReadingAnEntireLargeVideo() throws Exception {
        FakeFactory factory = new FakeFactory(1024L * 1024 * 1024);
        PreviewMediaDataSource input = new PreviewMediaDataSource(factory, spec(), () -> 0);
        byte[] buffer = new byte[256 * 1024]; long position = 0;
        while (position < PreviewMediaDataSource.MAX_BYTES) position += input.readAt(position, buffer, 0, buffer.length);
        assertEquals(PreviewMediaDataSource.MAX_BYTES, factory.readBytes);
        assertEquals(1, factory.requests.size());
        try { input.readAt(position, buffer, 0, buffer.length); fail(); } catch (InterruptedIOException expected) { }
        assertEquals(PreviewMediaDataSource.MAX_BYTES, factory.readBytes); input.close();
    }

    @Test public void rangeCountAndElapsedBudgetBothBoundNativeExtractorRequests() throws Exception {
        FakeFactory factory = new FakeFactory(10000);
        AtomicLong clock = new AtomicLong(100);
        PreviewMediaDataSource input = new PreviewMediaDataSource(factory, spec(), clock::get);
        byte[] buffer = new byte[1];
        for (int i = 0; i < PreviewMediaDataSource.MAX_OPENS; i++) input.readAt(i * 10, buffer, 0, 1);
        try { input.readAt(8000, buffer, 0, 1); fail(); } catch (InterruptedIOException expected) { }
        assertEquals(PreviewMediaDataSource.MAX_OPENS, factory.requests.size());
        input.beginOperation(); clock.addAndGet(PreviewMediaDataSource.MAX_OPERATION_MS);
        try { input.readAt(9000, buffer, 0, 1); fail(); } catch (InterruptedIOException expected) { }
        assertEquals(PreviewMediaDataSource.MAX_OPENS, factory.requests.size()); input.close();
    }

    @Test public void knownEndAndEmptyReadsDoNotOpenAnotherRequest() throws Exception {
        FakeFactory factory = new FakeFactory(3);
        PreviewMediaDataSource input = new PreviewMediaDataSource(factory, spec(), () -> 0);
        byte[] bytes = new byte[4];
        assertEquals(0, input.readAt(0, bytes, 0, 0)); assertTrue(factory.requests.isEmpty());
        assertEquals(3, input.getSize());
        assertEquals(-1, input.readAt(3, bytes, 0, 1));
        assertEquals(1, factory.requests.size()); assertEquals(0, factory.readBytes);
        input.close();
    }

    @Test public void sourceFailureIsNotSilentlyReopenedOrConvertedToAFrame() throws Exception {
        FakeFactory factory = new FakeFactory(100) {
            @Override public DataSource createDataSource() {
                return new FakeInput(this) {
                    @Override public long open(DataSpec request) throws IOException {
                        owner.requests.add(request); throw new IOException("simulated unavailable cache");
                    }
                };
            }
        };
        PreviewMediaDataSource input = new PreviewMediaDataSource(factory, spec(), () -> 0);
        try { input.getSize(); fail(); } catch (IOException expected) { }
        assertEquals(1, factory.requests.size()); assertEquals(1, factory.closes);
        input.close();
    }

    @Test public void ignoredHttpRangeCannotReadAnEntirePrefixInsideOpen() throws Exception {
        IgnoringRangeFactory factory = new IgnoringRangeFactory(1024L * 1024 * 1024);
        PreviewMediaDataSource input = new PreviewMediaDataSource(factory, spec(), () -> 0);
        try {
            input.readAt(512L * 1024 * 1024, new byte[1], 0, 1);
            fail("The hidden HTTP 200 prefix read must exhaust the preview budget");
        } catch (InterruptedIOException expected) { }
        assertEquals(PreviewMediaDataSource.MAX_BYTES, factory.wireBytes);
        assertEquals(0, factory.readBytes); assertEquals(1, factory.closes);
        input.close();
    }

    @Test public void transferCallbacksStopAnOpenWhenCanceledOrTimedOut() throws Exception {
        IgnoringRangeFactory canceled = new IgnoringRangeFactory(1024L * 1024 * 1024);
        PreviewMediaDataSource input = new PreviewMediaDataSource(canceled, spec(), () -> 0);
        canceled.onTransfer = input::cancel;
        try { input.readAt(512L * 1024 * 1024, new byte[1], 0, 1); fail(); }
        catch (InterruptedIOException expected) { }
        assertEquals(4096, canceled.wireBytes); assertEquals(1, canceled.closes); input.close();

        AtomicLong clock = new AtomicLong();
        IgnoringRangeFactory timedOut = new IgnoringRangeFactory(1024L * 1024 * 1024);
        PreviewMediaDataSource timedInput = new PreviewMediaDataSource(timedOut, spec(), clock::get);
        timedOut.onTransfer = () -> clock.set(PreviewMediaDataSource.MAX_OPERATION_MS);
        try { timedInput.readAt(512L * 1024 * 1024, new byte[1], 0, 1); fail(); }
        catch (InterruptedIOException expected) { }
        assertEquals(4096, timedOut.wireBytes); assertEquals(1, timedOut.closes); timedInput.close();
    }

    @Test public void readsAlsoCountThePrefixBytesAndConvertBudgetFailureToIOException() throws Exception {
        IgnoringRangeFactory factory = new IgnoringRangeFactory(1024L * 1024 * 1024);
        PreviewMediaDataSource input = new PreviewMediaDataSource(factory, spec(), () -> 0);
        long position = PreviewMediaDataSource.MAX_BYTES - 4096;
        try { input.readAt(position, new byte[8192], 0, 8192); fail(); }
        catch (InterruptedIOException expected) { }
        assertEquals(PreviewMediaDataSource.MAX_BYTES, factory.wireBytes);
        assertEquals(4096, factory.readBytes); assertEquals(1, factory.closes); input.close();
    }

    private static class FakeFactory implements DataSource.Factory {
        final long length;
        final List<DataSpec> requests = new ArrayList<>();
        long readBytes; int closes;
        FakeFactory(long length) { this.length = length; }
        @Override public DataSource createDataSource() { return new FakeInput(this); }
    }
    private static class FakeInput implements DataSource {
        final FakeFactory owner; long position;
        TransferListener listener;
        FakeInput(FakeFactory owner) { this.owner = owner; }
        @Override public long open(DataSpec request) throws IOException {
            owner.requests.add(request); position = request.position; return owner.length - position;
        }
        @Override public int read(byte[] buffer, int offset, int length) {
            if (position >= owner.length) return C.RESULT_END_OF_INPUT;
            int count = (int) Math.min(length, owner.length - position);
            for (int i = 0; i < count; i++) buffer[offset + i] = (byte) (position + i);
            position += count; owner.readBytes += count; return count;
        }
        @Override public Uri getUri() { return spec().uri; }
        @Override public void addTransferListener(TransferListener listener) { this.listener = listener; }
        @Override public void close() { owner.closes++; }
    }

    /** Models DefaultHttpDataSource.skipFully(): network callbacks occur before open() returns. */
    private static final class IgnoringRangeFactory extends FakeFactory {
        long wireBytes;
        Runnable onTransfer = () -> { };
        IgnoringRangeFactory(long length) { super(length); }
        @Override public DataSource createDataSource() {
            return new FakeInput(this) {
                DataSpec current;
                private void transferred(int count) {
                    wireBytes += count; onTransfer.run();
                    listener.onBytesTransferred(this, current, true, count);
                }
                @Override public long open(DataSpec request) throws IOException {
                    current = request;
                    listener.onTransferInitializing(this, current, true);
                    long remaining = super.open(request);
                    listener.onTransferStart(this, current, true);
                    for (long skipped = 0; skipped < request.position;) {
                        int count = (int) Math.min(4096, request.position - skipped);
                        transferred(count); skipped += count;
                    }
                    return remaining;
                }
                @Override public int read(byte[] buffer, int offset, int length) {
                    int count = super.read(buffer, offset, length);
                    if (count > 0) transferred(count);
                    return count;
                }
            };
        }
    }
}
