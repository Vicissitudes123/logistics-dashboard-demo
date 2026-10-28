package com.animeshelf.app;

import android.content.SharedPreferences;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheSpan;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Checks local cache coverage and SHA-256 baselines. Never reads from the network. */
@UnstableApi
final class OfflineIntegrity {
    static final class Report {
        final String track;
        final List<CacheSpan> damaged = new ArrayList<>();
        boolean missing, baselineCreated, unknownLength;
        long expected;
        Report(String track) { this.track = track; }
        boolean ok() { return !missing && damaged.isEmpty() && !unknownLength; }
        String summary() { return unknownLength ? "无法确定文件完整长度" : !ok() ? "发现文件缺失、长度异常或内容变化"
            : baselineCreated ? "文件结构完整，已建立本机校验记录" : "文件完整，与本机校验记录一致"; }
    }
    private final Cache cache;
    private final SharedPreferences prefs;
    OfflineIntegrity(Cache cache, SharedPreferences prefs) { this.cache = cache; this.prefs = prefs; }
    boolean bad(String track) { return prefs.getBoolean("bad:" + track, false); }
    String status(String track) { return prefs.getString("status:" + track, "尚未检查"); }
    synchronized void forget(String track) { prefs.edit().remove("hash:" + track).remove("bad:" + track).remove("status:" + track).apply(); }
    synchronized void beginRepair(String track) { prefs.edit().remove("hash:" + track).putBoolean("bad:" + track, true).putString("status:" + track, "等待修复完成").apply(); }

    Report inspect(String track, long length, BooleanSupplier cancelled) throws Exception {
        Report report = new Report(track); report.expected = length;
        if (length <= 0) { report.unknownLength = true; return report; }
        List<CacheSpan> spans = new ArrayList<>(cache.getCachedSpans(track));
        String stored = prefs.getString("hash:" + track, "");
        JSONObject baseline = stored.isEmpty() ? null : new JSONObject(stored), hashes = new JSONObject();
        long covered = 0;
        for (CacheSpan span : spans) {
            check(cancelled);
            if (span.position > covered) report.missing = true;
            if (span.position < 0 || span.length <= 0 || span.position > Long.MAX_VALUE - span.length) { report.damaged.add(span); continue; }
            covered = Math.max(covered, span.position + span.length);
            if (span.position + span.length > length) { report.damaged.add(span); continue; }
            if (span.file == null || !span.file.isFile() || span.file.length() != span.length) { report.damaged.add(span); continue; }
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); long read = 0;
            try (FileInputStream input = new FileInputStream(span.file)) {
                byte[] bytes = new byte[128 * 1024]; int count;
                while ((count = input.read(bytes)) != -1) { check(cancelled); digest.update(bytes, 0, count); read += count; }
            } catch (IOException missing) { report.damaged.add(span); continue; }
            if (read != span.length) { report.damaged.add(span); continue; }
            String id = span.position + ":" + span.length, hash = hex(digest.digest()); hashes.put(id, hash);
            if (baseline != null && (!baseline.has(id) || !hash.equals(baseline.optString(id)))) report.damaged.add(span);
        }
        if (covered < length || spans.isEmpty()) report.missing = true;
        synchronized (this) {
        check(cancelled);
        report.baselineCreated = report.ok() && baseline == null;
        SharedPreferences.Editor edit = prefs.edit().putBoolean("bad:" + track, !report.ok()).putString("status:" + track, report.summary());
        if (report.ok()) edit.putString("hash:" + track, hashes.toString());
        if (!edit.commit()) throw new IOException("校验记录未能保存");
        }
        return report;
    }

    /** Call only after an explicit repair request. Good spans and the other track remain cached. */
    void removeDamaged(Report report, BooleanSupplier cancelled) throws Exception {
        check(cancelled);
        for (CacheSpan span : report.damaged) { check(cancelled); cache.removeSpan(span); }
        // A removed file can leave stale cache metadata; remove its stale span too.
        for (CacheSpan span : new ArrayList<>(cache.getCachedSpans(report.track))) {
            check(cancelled);
            if (span.file == null || !span.file.isFile() || span.file.length() != span.length) cache.removeSpan(span);
        }
        synchronized (this) { check(cancelled); beginRepair(report.track); }
    }
    private static void check(BooleanSupplier cancelled) { if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException(); }
    private static String hex(byte[] bytes) { StringBuilder value = new StringBuilder(); for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return value.toString(); }
}
