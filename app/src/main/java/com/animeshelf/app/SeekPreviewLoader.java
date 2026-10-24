package com.animeshelf.app;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Real nearby keyframes, with one bounded worker and no player seek or audio side effects. */
@UnstableApi
public final class SeekPreviewLoader implements AutoCloseable {
    private static final int WIDTH = 320, HEIGHT = 180, CACHE_ENTRIES = 24;
    private static final long BUCKET_MS = 2000, INTERVAL_MS = 300;

    public interface Callback { void onPreview(Bitmap bitmap); }

    /** Immutable read-only input. Constructing it does not open a file or a network connection. */
    public static final class Source {
        public final String identity;
        final DataSource.Factory factory;
        final DataSpec dataSpec;
        public Source(String identity, DataSource.Factory factory, DataSpec dataSpec) {
            if (identity == null || factory == null || dataSpec == null) throw new NullPointerException();
            this.identity = identity; this.factory = factory; this.dataSpec = dataSpec;
        }
    }

    interface FrameDecoder extends AutoCloseable {
        Bitmap frame(long positionMs) throws Exception;
        void cancel();
        @Override void close();
    }
    interface DecoderFactory { FrameDecoder create(Source source) throws Exception; }
    private static final class Request {
        final Source source;
        final long generation, token, bucket;
        final Callback callback;
        Request(Source source, long generation, long token, long bucket, Callback callback) {
            this.source = source; this.generation = generation; this.token = token;
            this.bucket = bucket; this.callback = callback;
        }
    }

    private final Handler main;
    private final ScheduledExecutorService worker;
    private final DecoderFactory decoders;
    private final LongSupplier clock;
    private final long intervalMs;
    private final Map<Long, Bitmap> cache = new LinkedHashMap<Long, Bitmap>(CACHE_ENTRIES, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Bitmap> entry) { return size() > CACHE_ENTRIES; }
    };
    private Source source;
    private Request pending;
    private long generation, token, lastStarted = -INTERVAL_MS;
    private long unavailableUntil;
    private boolean running, closed;
    private volatile FrameDecoder decoder;
    private long decoderGeneration = -1;

    public SeekPreviewLoader() {
        this(new Handler(Looper.getMainLooper()), Executors.newSingleThreadScheduledExecutor(),
            NativeDecoder::new, android.os.SystemClock::elapsedRealtime, INTERVAL_MS);
    }

    SeekPreviewLoader(Handler main, ScheduledExecutorService worker, DecoderFactory decoders,
                      LongSupplier clock, long intervalMs) {
        this.main = main; this.worker = worker; this.decoders = decoders;
        this.clock = clock; this.intervalMs = Math.max(0, intervalMs);
    }

    public void setSource(Source source) {
        synchronized (this) {
            if (closed) return;
            this.source = source; generation++; token++; pending = null; cache.clear(); unavailableUntil = 0;
            if (decoder != null) decoder.cancel();
            worker.execute(this::closeStaleDecoder);
        }
    }

    public void clearSource() { setSource(null); }

    /** Main-thread callback. A superseded request is never delivered, including a queued callback. */
    public synchronized void request(long positionMs, Callback callback) {
        if (closed || callback == null) return;
        long bucket = Math.max(0, Math.min(Long.MAX_VALUE / 1000, positionMs)) / BUCKET_MS;
        pending = new Request(source, generation, ++token, bucket, callback);
        schedule();
    }

    /** Ends the current drag without discarding its source or already decoded small frames. */
    public synchronized void cancel() {
        if (closed) return;
        generation++; token++; pending = null;
        if (decoder != null) decoder.cancel();
        worker.execute(this::closeStaleDecoder);
    }

    private synchronized void schedule() {
        if (closed || running || pending == null) return;
        running = true;
        long delay = Math.max(0, intervalMs - Math.max(0, clock.getAsLong() - lastStarted));
        worker.schedule(this::runLatest, delay, TimeUnit.MILLISECONDS);
    }

    private void runLatest() {
        Request request;
        synchronized (this) {
            request = pending; pending = null;
            if (closed || request == null) { running = false; return; }
            lastStarted = clock.getAsLong();
        }
        Bitmap bitmap = null;
        try {
            boolean cached, backingOff;
            synchronized (this) {
                cached = request.generation == generation && request.source != null && cache.containsKey(request.bucket);
                backingOff = clock.getAsLong() < unavailableUntil;
                if (cached) bitmap = cache.get(request.bucket);
            }
            if (!cached && !backingOff && request.source != null && isCurrentSource(request)) {
                if (decoder == null || decoderGeneration != request.generation) {
                    closeDecoder();
                    if (isCurrentSource(request)) {
                        decoder = decoders.create(request.source); decoderGeneration = request.generation;
                    }
                }
                if (decoder != null && isCurrentSource(request)) bitmap = decoder.frame(request.bucket * BUCKET_MS);
                synchronized (this) {
                    if (request.generation == generation && !closed) {
                        cache.put(request.bucket, bitmap);
                        if (bitmap == null) unavailableUntil = clock.getAsLong() + 5000;
                    }
                }
            }
        } catch (Exception | LinkageError | OutOfMemoryError unavailable) {
            // Frame extraction is optional. Never expose signed URLs or disrupt playback.
            closeDecoder(); bitmap = null;
            synchronized (this) {
                if (request.generation == generation && !closed) unavailableUntil = clock.getAsLong() + 5000;
            }
        }
        final Bitmap result = bitmap;
        main.post(() -> {
            synchronized (SeekPreviewLoader.this) {
                if (closed || token != request.token || generation != request.generation) return;
            }
            request.callback.onPreview(result);
        });
        synchronized (this) { running = false; schedule(); }
    }

    private synchronized boolean isCurrentSource(Request request) {
        return !closed && request.generation == generation && request.source == source;
    }

    private void closeStaleDecoder() {
        synchronized (this) {
            if (!closed && decoderGeneration == generation && source != null) return;
        }
        closeDecoder();
    }

    private void closeDecoder() {
        FrameDecoder old = decoder; decoder = null; decoderGeneration = -1;
        if (old != null) try { old.close(); } catch (RuntimeException ignored) { }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true; generation++; token++; pending = null; cache.clear();
        if (decoder != null) decoder.cancel();
        main.removeCallbacksAndMessages(null);
        worker.execute(this::closeDecoder); worker.shutdown();
    }

    private static final class NativeDecoder implements FrameDecoder {
        private final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        private final PreviewMediaDataSource input;
        private boolean initialized;
        NativeDecoder(Source source) { input = new PreviewMediaDataSource(source.factory, source.dataSpec); }
        @Override public Bitmap frame(long positionMs) throws Exception {
            input.beginOperation();
            if (!initialized) { retriever.setDataSource(input); initialized = true; }
            Bitmap result;
            if (Build.VERSION.SDK_INT >= 27) {
                result = retriever.getScaledFrameAtTime(positionMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT);
            } else {
                // Android 8.0 has no scaled extraction. Avoid allocating an 8K full frame.
                long width = dimension(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                long height = dimension(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                if (width > 0 && height > 0 && height > (4096L * 2160) / width) return null;
                result = retriever.getFrameAtTime(positionMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (result == null || result.getWidth() <= 0 || result.getHeight() <= 0) return null;
            float scale = Math.min(1f, Math.min(WIDTH / (float) result.getWidth(), HEIGHT / (float) result.getHeight()));
            if (scale < 1f) {
                Bitmap small = Bitmap.createScaledBitmap(result, Math.max(1, Math.round(result.getWidth() * scale)),
                    Math.max(1, Math.round(result.getHeight() * scale)), true);
                if (small != result) result.recycle(); result = small;
            }
            return result;
        }
        private long dimension(int key) {
            try { return Long.parseLong(retriever.extractMetadata(key)); }
            catch (RuntimeException missing) { return 0; }
        }
        @Override public void cancel() { input.cancel(); }
        @Override public void close() {
            input.cancel();
            try { retriever.release(); } catch (Exception ignored) { }
            try { input.close(); } catch (Exception ignored) { }
        }
    }
}
