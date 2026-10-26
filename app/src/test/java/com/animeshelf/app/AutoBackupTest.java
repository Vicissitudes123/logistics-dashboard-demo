package com.animeshelf.app;

import static org.junit.Assert.*;
import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowContentResolver;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class AutoBackupTest {
    private AutoBackup backup;
    private FakeFiles provider;
    private Context context;
    private SharedPreferences config;
    private static final String TREE = "content://backup.test/tree/folder";
    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        provider = new FakeFiles(); ProviderInfo info = new ProviderInfo(); info.authority = "backup.test"; info.exported = true; info.grantUriPermissions = true;
        provider.attachInfo(context, info); ShadowContentResolver.registerProviderInternal(info.authority, provider);
        config = context.getSharedPreferences("auto_backup", 0); config.edit().clear().putString("folder", TREE).commit();
        Constructor<AutoBackup> constructor = AutoBackup.class.getDeclaredConstructor(Context.class); constructor.setAccessible(true); backup = constructor.newInstance(context);
    }
    private String document(int offset) {
        Store store = new Store(context); Models.Series series = new Models.Series(); series.id = "ss1"; series.title = "备份番剧";
        Models.Episode ep = new Models.Episode(); ep.id = "ep1"; ep.seriesId = series.id; ep.seriesTitle = series.title;
        ep.index = 1; ep.title = "第一集"; ep.bangumi = true; ep.cid = 1; ep.aid = 1;
        series.episodes.add(ep); store.saveSeries(series); store.saveProgress(ep, offset * 1000, 600000);
        return store.exportBackup();
    }
    private void write(String document) throws Exception {
        Method method = AutoBackup.class.getDeclaredMethod("writeVerified", String.class, String.class); method.setAccessible(true);
        try { method.invoke(backup, TREE, document); }
        catch (InvocationTargetException failure) { throw (Exception) failure.getCause(); }
    }
    @Test public void retainsSevenVerifiedBackupsWithoutTouchingUntrackedFiles() throws Exception {
        provider.files.put("unrelated.json", File.createTempFile("unrelated", ".json", context.getCacheDir()));
        for (int i = 1; i <= 9; i++) write(document(i));
        assertEquals(7, AutoBackup.recordedFiles(config.getString("files", "[]")).size());
        assertEquals(8, provider.files.size()); assertTrue(provider.files.containsKey("unrelated.json"));
        assertEquals(2, provider.deleted.size()); assertTrue(config.getLong("last", 0) > 0);
        for (String recorded : AutoBackup.recordedFiles(config.getString("files", "[]"))) {
            String id = DocumentsContract.getDocumentId(Uri.parse(recorded));
            LibraryBackup.inspect(Files.readString(provider.files.get(id).toPath()));
        }
    }
    @Test public void corruptReadbackDoesNotRotateOrReplaceLastSuccessfulBackup() throws Exception {
        String original = document(1); write(original); String before = config.getString("files", "[]"); long last = config.getLong("last", 0);
        provider.corruptRead = true;
        assertThrows(Exception.class, () -> write(document(2)));
        assertEquals(before, config.getString("files", "[]")); assertEquals(last, config.getLong("last", 0));
        assertEquals(1, provider.files.size()); assertEquals(original, config.getString("lastDocument", ""));
    }
    @Test public void rejectedWritePreservesExistingBackupAndReportsNoFalseSuccess() throws Exception {
        write(document(1)); String before = config.getString("files", "[]"); provider.rejectWrite = true;
        assertThrows(Exception.class, () -> write(document(2)));
        assertEquals(before, config.getString("files", "[]")); assertEquals(1, provider.files.size());
    }
    public static final class FakeFiles extends ContentProvider {
        final Map<String, File> files = new LinkedHashMap<>(); final List<String> deleted = new ArrayList<>();
        boolean corruptRead, rejectWrite;
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            try {
                Bundle result = new Bundle();
                if (method.endsWith("createDocument")) {
                    String id = UUID.randomUUID().toString(); files.put(id, File.createTempFile("backup", ".json", getContext().getCacheDir()));
                    result.putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(Uri.parse(TREE), id));
                } else if (method.endsWith("deleteDocument")) {
                    Uri uri = extras.getParcelable("uri"); String id = DocumentsContract.getDocumentId(uri);
                    File file = files.remove(id); if (file != null) file.delete(); deleted.add(id);
                } else throw new UnsupportedOperationException(method);
                return result;
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
            File file = files.get(DocumentsContract.getDocumentId(uri));
            if (file == null || (rejectWrite && mode.contains("w"))) throw new java.io.FileNotFoundException();
            if (corruptRead && "r".equals(mode)) {
                try { File corrupt = File.createTempFile("corrupt", ".json", getContext().getCacheDir()); Files.writeString(corrupt.toPath(), "broken"); file = corrupt; }
                catch (java.io.IOException failure) { throw new java.io.FileNotFoundException(); }
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
        }
        @Override public String getType(Uri uri) { return "application/json"; }
        @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
        @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    }
}
