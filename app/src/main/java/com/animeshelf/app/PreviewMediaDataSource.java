package com.animeshelf.app;

import android.media.MediaDataSource;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.function.LongSupplier;

/** Read-only, bounded random access for the platform frame extractor. Never writes a cache span. */
@UnstableApi
final class PreviewMediaDataSource extends MediaDataSource {
    static final long MAX_BYTES = 8L * 1024 * 1024;
    static final int MAX_OPENS = 24;
    static final long MAX_OPERATION_MS = 8000;
    private static final int MAX_READ = 128 * 1024;
    private final DataSource.Factory factory;
    private final DataSpec original;
    private final LongSupplier clock;
    private DataSource input;
    private long position, size = -1, bytes, wireBytes, deadline;
    private int opens;
    private volatile boolean canceled, closed;
    // DefaultHttpDataSource may skip a large prefix inside open() when a server ignores Range.
    // Count those bytes too, and abort inside the transfer callback before open can read the film.
    private final TransferListener transferBudget = new TransferListener() {
        @Override public void onTransferInitializing(DataSource source, DataSpec spec, boolean network) {
            checkTransferBudget();
        }
        @Override public void onTransferStart(DataSource source, DataSpec spec, boolean network) {
            checkTransferBudget();
        }
        @Override public void onBytesTransferred(DataSource source, DataSpec spec, boolean network, int count) {
            if (network && count > 0) wireBytes += count;
            checkTransferBudget();
        }
        @Override public void onTransferEnd(DataSource source, DataSpec spec, boolean network) {
            // Cleanup must still succeed after cancellation or exhaustion of the budget.
        }
    };

    /** TransferListener cannot throw checked exceptions. Convert this before returning to native code. */
    private static final class TransferBudgetExceeded extends RuntimeException {
        TransferBudgetExceeded(InterruptedIOException cause) { super(cause); }
    }

    private void checkTransferBudget() {
        try { check(); } catch (InterruptedIOException stopped) { throw new TransferBudgetExceeded(stopped); }
    }

    PreviewMediaDataSource(DataSource.Factory factory, DataSpec original) {
        this(factory, original, android.os.SystemClock::elapsedRealtime);
    }

    PreviewMediaDataSource(DataSource.Factory factory, DataSpec original, LongSupplier clock) {
        this.factory = factory; this.original = original; this.clock = clock;
        beginOperation();
    }

    /** Worker-only. Cancellation remains permanent for this extractor instance. */
    synchronized void beginOperation() {
        bytes = 0; wireBytes = 0; opens = 0;
        long now = clock.getAsLong();
        deadline = now > Long.MAX_VALUE - MAX_OPERATION_MS ? Long.MAX_VALUE : now + MAX_OPERATION_MS;
    }

    /** Safe from the UI thread: never calls a blocking native or network close. */
    void cancel() { canceled = true; }

    private void check() throws InterruptedIOException {
        if (closed || canceled || Thread.currentThread().isInterrupted()
                || clock.getAsLong() >= deadline || bytes >= MAX_BYTES || wireBytes >= MAX_BYTES)
            throw new InterruptedIOException("预览读取已取消或超过限制");
    }

    private void openAt(long target) throws IOException {
        check();
        if (input != null && position == target) return;
        closeInput();
        if (++opens > MAX_OPENS) throw new InterruptedIOException("预览读取次数超过限制");
        input = factory.createDataSource();
        position = target;
        try {
            input.addTransferListener(transferBudget);
            long remaining = input.open(original.buildUpon().setPosition(target).setLength(C.LENGTH_UNSET).build());
            if (remaining >= 0 && remaining <= Long.MAX_VALUE - target) size = target + remaining;
            check();
        } catch (TransferBudgetExceeded stopped) {
            try { closeInput(); } catch (IOException ignored) { }
            throw (InterruptedIOException) stopped.getCause();
        } catch (IOException | RuntimeException failure) {
            try { closeInput(); } catch (IOException ignored) { }
            throw failure;
        }
    }

    @Override public synchronized long getSize() throws IOException {
        check();
        if (size < 0) openAt(0);
        return size;
    }

    @Override public synchronized int readAt(long target, byte[] buffer, int offset, int length) throws IOException {
        if (buffer == null) throw new NullPointerException("buffer");
        if (target < 0 || offset < 0 || length < 0 || offset > buffer.length - length)
            throw new IndexOutOfBoundsException();
        if (length == 0) return 0;
        check();
        if (size >= 0 && target >= size) return -1;
        openAt(target);
        int requested = (int) Math.min(Math.min(length, MAX_READ), MAX_BYTES - Math.max(bytes, wireBytes));
        int count;
        try { count = input.read(buffer, offset, requested); }
        catch (TransferBudgetExceeded stopped) {
            try { closeInput(); } catch (IOException ignored) { }
            throw (InterruptedIOException) stopped.getCause();
        }
        if (count > 0) { bytes += count; position += count; }
        else if (count == C.RESULT_END_OF_INPUT) { size = position; return -1; }
        checkAfterRead();
        return count;
    }

    private void checkAfterRead() throws InterruptedIOException {
        if (closed || canceled || Thread.currentThread().isInterrupted() || clock.getAsLong() >= deadline)
            throw new InterruptedIOException("预览读取已取消或超时");
    }

    private void closeInput() throws IOException {
        DataSource previous = input; input = null;
        if (previous != null) previous.close();
    }

    /** Worker-only. In-flight extraction is canceled first and released after its native call returns. */
    @Override public synchronized void close() throws IOException {
        canceled = true; closed = true; closeInput();
    }
}
