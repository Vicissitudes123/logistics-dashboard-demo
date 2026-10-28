package com.animeshelf.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import org.json.JSONArray;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Debounced, verified SAF backups. Deletes only URIs recorded as created by this feature. */
@androidx.media3.common.util.UnstableApi
final class AutoBackup {
    private static AutoBackup instance;
    static synchronized AutoBackup get(Context context) {
        if (instance == null) instance = new AutoBackup(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final SharedPreferences config, library;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean busy, scheduled, dirty;
    private long nextAttempt;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
        if (key != null && !key.equals("downloads") && !key.startsWith("downloadError:")) changed();
    };
    private AutoBackup(Context context) {
        this.context = context;
        config = context.getSharedPreferences("auto_backup", Context.MODE_PRIVATE);
        library = context.getSharedPreferences("library", Context.MODE_PRIVATE);
        library.registerOnSharedPreferenceChangeListener(listener);
        dirty = config.getBoolean("dirty", false);
        schedule(false);
    }
    String folder() { return config.getString("folder", ""); }
    boolean enabled() { return !folder().isEmpty(); }
    String status() {
        if (!enabled()) return "未开启 · 选择文件夹后，自动保留最近 7 份";
        long last = config.getLong("last", 0);
        String text = last == 0 ? "等待首次备份" : "上次成功：" + new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(new java.util.Date(last));
        if (!config.getString("error", "").isEmpty()) text += "\n" + config.getString("error", "");
        return text + "\n片库、进度和设置 · 不包含视频或登录信息";
    }
    synchronized void configure(Uri tree) {
        String value = tree.toString();
        SharedPreferences.Editor edit = config.edit().putString("folder", value).putString("error", "").putBoolean("dirty", true);
        if (!value.equals(folder())) edit.remove("files").remove("last").remove("lastDocument");
        if (!edit.commit()) throw new IllegalStateException("无法保存备份位置");
        dirty = true; nextAttempt = 0; schedule(true);
    }
    synchronized void disable() { config.edit().remove("folder").commit(); handler.removeCallbacks(run); scheduled = false; }
    private synchronized void changed() { dirty = true; config.edit().putBoolean("dirty", true).apply(); schedule(false); }
    synchronized void requestNow() { dirty = true; config.edit().putBoolean("dirty", true).apply(); nextAttempt = 0; schedule(true); }
    synchronized void flushSoon() { if (dirty) schedule(true); }
    private final Runnable run = () -> { synchronized (this) { scheduled = false; } begin(); };
    private synchronized void schedule(boolean soon) {
        if (!enabled() || !dirty || busy) return;
        if (scheduled && !soon) return;
        handler.removeCallbacks(run); scheduled = true;
        long now = android.os.SystemClock.elapsedRealtime();
        handler.postDelayed(run, Math.max(soon ? 1000 : 60_000, nextAttempt - now));
    }
    private synchronized void begin() {
        if (!enabled() || busy || !dirty) return;
        busy = true; dirty = false;
        String target = folder();
        io.execute(() -> {
            String failure = "";
            try {
                String document = new Store(context).exportBackup();
                // A restart still verifies/creates a fresh backup when the library changed.
                if (!document.equals(config.getString("lastDocument", ""))) writeVerified(target, document);
            } catch (Exception error) { failure = "自动备份未完成，请检查文件夹授权和可用空间；旧备份已保留"; }
            String result = failure;
            handler.post(() -> {
                synchronized (AutoBackup.this) {
                    busy = false;
                    if (target.equals(folder())) {
                        if (!result.isEmpty()) dirty = true;
                        config.edit().putString("error", result).putBoolean("dirty", dirty).apply();
                        nextAttempt = android.os.SystemClock.elapsedRealtime() + (result.isEmpty() ? 5 : 10) * 60_000L;
                    }
                    schedule(false);
                }
            });
        });
    }
    private void writeVerified(String target, String document) throws Exception {
        Uri tree = Uri.parse(target);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        String name = "fanxia-auto-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date())
            + "-" + UUID.randomUUID().toString().substring(0, 8) + ".json";
        Uri file = DocumentsContract.createDocument(context.getContentResolver(), parent, "application/json", name);
        if (file == null) throw new java.io.IOException("Could not create backup");
        boolean verified = false;
        try {
            byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = context.getContentResolver().openOutputStream(file, "w")) {
                if (output == null) throw new java.io.IOException("Could not write backup");
                output.write(bytes); output.flush();
            }
            try (InputStream input = context.getContentResolver().openInputStream(file)) {
                if (input == null || !java.util.Arrays.equals(bytes, MainActivity.readBackupBytes(input))) throw new java.io.IOException("Backup verification failed");
            }
            LibraryBackup.inspect(document); verified = true;
        } finally {
            if (!verified) try { DocumentsContract.deleteDocument(context.getContentResolver(), file); } catch (Exception ignored) { }
        }
        synchronized (this) {
            if (!target.equals(folder())) return; // A newly selected folder must not inherit old retention records.
            List<String> files = recordedFiles(config.getString("files", "[]"));
            files.add(file.toString());
            // Persist the new verified file before removing any older one.
            if (!config.edit().putString("files", new JSONArray(files).toString()).putLong("last", System.currentTimeMillis())
                    .putString("lastDocument", document).commit()) throw new java.io.IOException("Could not record backup");
            while (files.size() > 7) {
                Uri old = Uri.parse(files.get(0));
                if (!sameTree(tree, old)) break;
                if (!DocumentsContract.deleteDocument(context.getContentResolver(), old)) break;
                files.remove(0);
                config.edit().putString("files", new JSONArray(files).toString()).commit();
            }
        }
    }
    static boolean sameTree(Uri tree, Uri document) {
        try { return "content".equals(document.getScheme()) && tree.getAuthority().equals(document.getAuthority())
            && DocumentsContract.getTreeDocumentId(tree).equals(DocumentsContract.getTreeDocumentId(document)); }
        catch (Exception invalid) { return false; }
    }
    static List<String> recordedFiles(String json) throws Exception {
        List<String> files = new ArrayList<>(); JSONArray items = new JSONArray(json);
        for (int i = 0; i < items.length(); i++) files.add(items.getString(i));
        return files;
    }
}
