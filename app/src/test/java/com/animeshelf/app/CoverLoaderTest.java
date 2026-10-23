package com.animeshelf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.widget.ImageView;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class CoverLoaderTest {
    private static final String FIRST = "https://i0.hdslb.com/bfs/bangumi/one.jpg";
    private static final String SECOND = "https://i1.hdslb.com/bfs/bangumi/two.jpg";
    private Context context;
    private CoverLoader loader;
    private ManualExecutor executor;
    private CookieHandler previousCookies;
    private final List<FakeConnection> opened = new ArrayList<>();
    private byte[] picture;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        deleteTree(new File(context.getCacheDir(), "cover-thumbnails"));
        previousCookies = CookieHandler.getDefault();
        CookieHandler.setDefault(null);
        picture = png(1200, 600);
        create(url -> new FakeConnection(url, picture));
    }

    @After public void tearDown() {
        if (loader != null) loader.close();
        CookieHandler.setDefault(previousCookies);
        deleteTree(new File(context.getCacheDir(), "cover-thumbnails"));
    }

    @Test public void coalescesRepeatedRequestsAndSamplesLargeArtworkWithoutChangingClicks() {
        ImageView first = new ImageView(context), second = new ImageView(context);
        int[] clicks = {0};
        first.setOnClickListener(v -> clicks[0]++);
        loader.load(first, FIRST);
        loader.load(second, FIRST);
        loader.load(first, FIRST);
        assertEquals(1, executor.tasks.size());
        runAll();
        assertEquals(1, opened.size());
        Bitmap bitmap = ((BitmapDrawable) first.getDrawable()).getBitmap();
        assertTrue(bitmap.getWidth() <= 512 && bitmap.getHeight() <= 512);
        assertSame(bitmap, ((BitmapDrawable) second.getDrawable()).getBitmap());
        ImageView third = new ImageView(context);
        loader.load(third, FIRST);
        assertEquals(0, executor.tasks.size());
        assertSame(bitmap, ((BitmapDrawable) third.getDrawable()).getBitmap());
        first.performClick();
        assertEquals(1, clicks[0]);
        assertNull(opened.get(0).getRequestProperty("Cookie"));
        assertNull(opened.get(0).getRequestProperty("Authorization"));
        assertFalse(opened.get(0).getInstanceFollowRedirects());
    }

    @Test public void staleResultsDoNotOverwriteANewerBindingOrAClearedView() throws Exception {
        byte[] oldPicture = png(100, 100), newPicture = png(200, 100);
        create(url -> new FakeConnection(url, url.toString().equals(FIRST) ? oldPicture : newPicture));
        ImageView view = new ImageView(context);
        loader.load(view, FIRST);
        executor.runAll(); // Its result is queued on the UI thread but has not been delivered.
        loader.load(view, SECOND);
        runAll();
        assertEquals(200, ((BitmapDrawable) view.getDrawable()).getBitmap().getWidth());
        ImageView removed = new ImageView(context);
        loader.load(removed, "https://i2.hdslb.com/bfs/archive/three.jpg");
        executor.runAll();
        loader.clear(removed);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(removed.getDrawable());
    }

    @Test public void failedRequestsStayPlaceholdersAndDoNotRetryOnEveryRender() {
        create(url -> { FakeConnection connection = new FakeConnection(url, picture); connection.status = 403; return connection; });
        ImageView first = new ImageView(context);
        loader.load(first, FIRST);
        runAll();
        assertNull(first.getDrawable());
        loader.load(new ImageView(context), FIRST);
        runAll();
        assertEquals(1, opened.size());
    }

    @Test public void rejectsUntrustedAddressesAndRedirectsBeforeTheyAreRequested() {
        loader.load(new ImageView(context), "https://evil.test/bfs/a.jpg");
        loader.load(new ImageView(context), FIRST + "?secret=token");
        runAll();
        assertEquals(0, opened.size());
        create(url -> { FakeConnection connection = new FakeConnection(url, picture); connection.status = 302;
            connection.location = "https://evil.test/bfs/a.jpg"; return connection; });
        ImageView view = new ImageView(context);
        loader.load(view, FIRST);
        runAll();
        assertEquals(1, opened.size());
        assertNull(view.getDrawable());
    }

    @Test public void validatedImageRedirectsWorkWithoutCredentials() {
        create(url -> { FakeConnection connection = new FakeConnection(url, picture);
            if (url.toString().equals(FIRST)) { connection.status = 302; connection.location = SECOND; }
            return connection; });
        ImageView view = new ImageView(context);
        loader.load(view, FIRST);
        runAll();
        assertEquals(2, opened.size());
        assertNotNull(view.getDrawable());
        for (FakeConnection connection : opened) assertNull(connection.getRequestProperty("Cookie"));
    }

    @Test public void oversizedDeclaredOrStreamedBodiesAreRejected() {
        create(url -> { FakeConnection connection = new FakeConnection(url, new byte[CoverLoader.MAX_IMAGE_BYTES + 1]);
            connection.declaredLength = url.toString().equals(FIRST) ? CoverLoader.MAX_IMAGE_BYTES + 1 : -1;
            return connection; });
        ImageView first = new ImageView(context), second = new ImageView(context);
        loader.load(first, FIRST); loader.load(second, SECOND);
        runAll();
        assertNull(first.getDrawable()); assertNull(second.getDrawable());
        assertFalse(opened.get(0).streamRequested);
        assertTrue(opened.get(1).streamRequested);
    }

    @Test public void cachedArtworkRemainsAvailableWithoutNetworkAndDiskCountIsBounded() throws Exception {
        File folder = new File(context.getCacheDir(), "cover-thumbnails");
        assertTrue(folder.mkdirs());
        for (int i = 0; i < 65; i++) {
            File file = new File(folder, "old-" + i + ".img");
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(1); }
            file.setLastModified(1);
        }
        loader.load(new ImageView(context), FIRST); runAll();
        File[] cached = folder.listFiles((dir, name) -> name.endsWith(".img"));
        assertNotNull(cached); assertTrue(cached.length <= CoverLoader.MAX_DISK_FILES);
        long total = 0; for (File file : cached) total += file.length();
        assertTrue(total <= CoverLoader.MAX_DISK_BYTES);
        create(url -> { throw new IOException("Offline"); });
        ImageView offline = new ImageView(context);
        loader.load(offline, FIRST); runAll();
        assertNotNull(offline.getDrawable());
        assertEquals(0, opened.size());
    }

    @Test public void closeCancelsQueuedWorkAndDiscardsAlreadyQueuedUiResults() {
        ImageView view = new ImageView(context);
        loader.load(view, FIRST);
        executor.runAll();
        loader.close();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(view.getDrawable());
        create(url -> new FakeConnection(url, picture));
        loader.load(new ImageView(context), SECOND);
        loader.close();
        executor.runAll();
        assertEquals(0, opened.size());
    }

    @Test public void globalCookieHandlerCannotInjectCookiesIntoImageRequests() {
        CookieHandler.setDefault(new java.net.CookieManager());
        ImageView view = new ImageView(context);
        loader.load(view, FIRST); runAll();
        assertNull(view.getDrawable());
        assertEquals(0, opened.size());
    }

    private void create(CoverLoader.ConnectionFactory factory) {
        if (loader != null) loader.close();
        executor = new ManualExecutor();
        opened.clear();
        loader = new CoverLoader(context, url -> {
            FakeConnection connection = (FakeConnection) factory.open(url);
            opened.add(connection); return connection;
        }, executor);
    }

    private void runAll() { executor.runAll(); Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    private static byte[] png(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        bitmap.recycle(); return bytes.toByteArray();
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static final class FakeConnection extends HttpURLConnection {
        final byte[] bytes;
        int status = 200;
        long declaredLength = -1;
        String location;
        boolean streamRequested;
        FakeConnection(URL url, byte[] bytes) { super(url); this.bytes = bytes; }
        @Override public void connect() { }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return status; }
        @Override public long getContentLengthLong() { return declaredLength; }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? location : null; }
        @Override public InputStream getInputStream() { streamRequested = true; return new ByteArrayInputStream(bytes); }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        final List<Runnable> tasks = new ArrayList<>();
        boolean stopped;
        @Override public void execute(Runnable task) { tasks.add(task); }
        void runAll() { while (!tasks.isEmpty()) tasks.remove(0).run(); }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; List<Runnable> old = new ArrayList<>(tasks); tasks.clear(); return old; }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
