package com.animeshelf.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Small public artwork only. Does not read login state, change click listeners or retain an Activity. */
public final class CoverLoader implements AutoCloseable {
    static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    static final long MAX_DISK_BYTES = 16L * 1024 * 1024;
    static final int MAX_DISK_FILES = 64;
    private static final int MAX_EDGE = 512;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File directory;
    private final ExecutorService workers;
    private final ConnectionFactory connections;
    private final Map<String, Task> inFlight = new HashMap<>();
    private final Map<ImageView, Object> bindings = new WeakHashMap<>();
    private final LinkedHashSet<String> failed = new LinkedHashSet<>();
    private final Set<HttpURLConnection> activeConnections = new HashSet<>();
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private volatile boolean closed;

    interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }

    private static final class Target {
        final WeakReference<ImageView> view;
        final Object token;
        Target(ImageView view, Object token) { this.view = new WeakReference<>(view); this.token = token; }
    }

    private static final class Task {
        final List<Target> targets = new ArrayList<>();
        Future<?> future;
    }

    public CoverLoader(Context context) {
        this(context, url -> (HttpURLConnection) url.openConnection(), Executors.newFixedThreadPool(2));
    }

    CoverLoader(Context context, ConnectionFactory connections, ExecutorService workers) {
        this.directory = new File(context.getApplicationContext().getCacheDir(), "cover-thumbnails");
        this.connections = connections;
        this.workers = workers;
    }

    /** Call on the UI thread. The caller's background remains visible when artwork is absent. */
    public void load(ImageView view, String address) {
        requireMainThread();
        if (view == null) return;
        String url = Models.safeCoverUrl(address);
        Object token = new Object();
        synchronized (this) {
            bindings.put(view, token);
            view.setImageDrawable(null);
            if (closed || url.isEmpty()) return;
            Bitmap cached = memory.get(url);
            if (cached != null) { view.setImageBitmap(cached); return; }
            if (failed.contains(url)) return;
            Task task = inFlight.get(url);
            if (task != null) { task.targets.add(new Target(view, token)); return; }
            // Bound queued work when a large library is rendered without a scrolling recycler.
            if (inFlight.size() >= 128) return;
            task = new Task();
            task.targets.add(new Target(view, token));
            inFlight.put(url, task);
            final Task pending = task;
            task.future = workers.submit(() -> {
                Bitmap result = null;
                try { result = readThumbnail(url); }
                catch (IOException | RuntimeException ignored) { }
                // Malformed images must never take down the media player on a memory-constrained device.
                catch (OutOfMemoryError ignored) { }
                final Bitmap bitmap = result;
                if (!closed) main.post(() -> deliver(url, pending, bitmap));
            });
        }
    }

    /** Revoke a reused/removed view's pending result without touching its click listeners. */
    public void clear(ImageView view) {
        requireMainThread();
        if (view == null) return;
        synchronized (this) { bindings.remove(view); }
        view.setImageDrawable(null);
    }

    private void deliver(String url, Task task, Bitmap bitmap) {
        synchronized (this) {
            if (closed || inFlight.get(url) != task) return;
            inFlight.remove(url);
            if (bitmap == null) {
                failed.add(url);
                while (failed.size() > 256) failed.remove(failed.iterator().next());
            } else memory.put(url, bitmap);
            for (Target target : task.targets) {
                ImageView view = target.view.get();
                if (view != null && bindings.get(view) == target.token && bitmap != null) view.setImageBitmap(bitmap);
            }
        }
    }

    private Bitmap readThumbnail(String url) throws IOException {
        checkCancelled();
        File file = new File(directory, cacheName(url));
        if (file.isFile() && file.length() > 0 && file.length() <= MAX_IMAGE_BYTES) {
            try (InputStream stream = new FileInputStream(file)) {
                Bitmap bitmap = decode(readLimited(stream));
                if (bitmap != null) { file.setLastModified(System.currentTimeMillis()); return bitmap; }
            } catch (IOException ignored) { }
            file.delete();
        }
        byte[] bytes = fetch(url);
        checkCancelled();
        Bitmap bitmap = decode(bytes);
        if (bitmap == null) throw new IOException("Invalid cover image");
        save(file, bytes);
        return bitmap;
    }

    private byte[] fetch(String address) throws IOException {
        String current = address;
        for (int redirect = 0; redirect <= 2; redirect++) {
            checkCancelled();
            if (Models.safeCoverUrl(current).isEmpty()) throw new IOException("Invalid cover address");
            // WebView cookies are never queried. Also refuse a process-wide Java cookie handler,
            // which could otherwise inject credentials despite no explicit Cookie request header.
            if (CookieHandler.getDefault() != null) throw new IOException("Cookie-free image request unavailable");
            HttpURLConnection connection = connections.open(new URL(current));
            synchronized (this) {
                if (closed) { connection.disconnect(); throw new IOException("Cancelled"); }
                activeConnections.add(connection);
            }
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("User-Agent", BiliClient.MEDIA_USER_AGENT);
                connection.setRequestProperty("Referer", BiliClient.REFERER);
                connection.setRequestProperty("Accept", "image/webp,image/jpeg,image/png,image/*;q=0.8");
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing image redirect");
                    current = Models.safeCoverUrl(new URL(new URL(current), location).toExternalForm());
                    if (current.isEmpty()) throw new IOException("Invalid image redirect");
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) throw new IOException("Cover request failed");
                if (connection.getContentLengthLong() > MAX_IMAGE_BYTES) throw new IOException("Image too large");
                try (InputStream stream = connection.getInputStream()) { return readLimited(stream); }
            } finally {
                connection.disconnect();
                synchronized (this) { activeConnections.remove(connection); }
            }
        }
        throw new IOException("Too many image redirects");
    }

    private byte[] readLimited(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long started = System.nanoTime();
        int read;
        while ((read = stream.read(buffer)) != -1) {
            checkCancelled();
            if (out.size() + read > MAX_IMAGE_BYTES || System.nanoTime() - started > 10_000_000_000L)
                throw new IOException("Image read limit exceeded");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 20000 || bounds.outHeight > 20000
                || (long) bounds.outWidth * bounds.outHeight > 100_000_000) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > MAX_EDGE) options.inSampleSize *= 2;
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private void save(File target, byte[] bytes) {
        synchronized (directory) {
            File temp = null;
            try {
                checkCancelled();
                if (!directory.isDirectory() && !directory.mkdirs()) return;
                temp = File.createTempFile("cover-", ".tmp", directory);
                try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); }
                checkCancelled();
                if (!temp.renameTo(target)) return;
                trimDisk();
            } catch (IOException ignored) {
                // A full/unwritable thumbnail cache must not prevent displaying the downloaded image.
            } finally { if (temp != null && temp.exists()) temp.delete(); }
        }
    }

    private void trimDisk() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".img"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long bytes = 0;
        for (File file : files) bytes += file.length();
        int count = files.length;
        for (File file : files) {
            if (bytes <= MAX_DISK_BYTES && count <= MAX_DISK_FILES) break;
            long length = file.length();
            if (file.delete()) { bytes -= length; count--; }
        }
    }

    private static String cacheName(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte value : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return name + ".img";
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private void checkCancelled() throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Cover views must be bound on the UI thread");
    }

    /** Call from Activity.onDestroy(); queued work and old view results are discarded. */
    @Override public void close() {
        synchronized (this) {
            closed = true;
            for (Task task : inFlight.values()) if (task.future != null) task.future.cancel(true);
            for (HttpURLConnection connection : activeConnections) connection.disconnect();
            activeConnections.clear();
            inFlight.clear();
            bindings.clear();
            failed.clear();
            memory.evictAll();
        }
        workers.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }
}
