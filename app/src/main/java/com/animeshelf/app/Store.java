package com.animeshelf.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Small, device-local catalogue and viewing history. Media bytes live in Media3's cache. */
public final class Store {
    private final SharedPreferences prefs;
    public Store(Context context) { prefs = context.getApplicationContext().getSharedPreferences("library", Context.MODE_PRIVATE); }
    public static final class Progress {
        public long positionMs, durationMs, updatedAt;
        public boolean completed;
    }
    public synchronized List<Models.Series> listSeries() {
        ArrayList<Models.Series> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("series", "[]"));
            for (int i = 0; i < array.length(); i++) result.add(Models.Series.fromJson(array.getJSONObject(i)));
        } catch (Exception ignored) { }
        return result;
    }
    /** Hiding affects only home-page membership, never offline metadata or viewing history. */
    public synchronized List<Models.Series> listVisibleSeries() {
        ArrayList<Models.Series> visible = new ArrayList<>();
        for (Models.Series series : listSeries()) if (!prefs.getBoolean("hidden:" + series.id, false)) visible.add(series);
        return visible;
    }
    public synchronized List<Models.Series> listHiddenSeries() {
        ArrayList<Models.Series> hidden = new ArrayList<>();
        for (Models.Series series : listSeries()) if (prefs.getBoolean("hidden:" + series.id, false)) hidden.add(series);
        return hidden;
    }
    public void hideSeries(String id) {
        if (validId(id)) prefs.edit().putBoolean("hidden:" + id, true).apply();
    }
    public void restoreSeries(String id) {
        if (validId(id)) prefs.edit().remove("hidden:" + id).apply();
    }
    public boolean isPinned(String id) { return validId(id) && prefs.getBoolean("pinned:" + id, false); }
    public void setPinned(String id, boolean pinned) {
        if (!validId(id)) return;
        if (pinned) prefs.edit().putBoolean("pinned:" + id, true).apply();
        else prefs.edit().remove("pinned:" + id).apply();
    }
    private static boolean validId(String id) { return id != null && !id.trim().isEmpty(); }

    /** An unplayed import never becomes "recent" merely because it is first in the catalogue. */
    public synchronized String getRecentVisibleSeriesId() {
        String preferred = getLastSeries(), recentId = "";
        long recentAt = 0;
        for (Models.Series series : listVisibleSeries()) {
            long watchedAt = 0;
            for (Models.Episode episode : series.episodes)
                if (episode != null && validId(episode.id)) watchedAt = Math.max(watchedAt, getProgress(episode.id).updatedAt);
            if (watchedAt <= 0) continue;
            if (series.id.equals(preferred)) return series.id;
            if (watchedAt > recentAt) { recentAt = watchedAt; recentId = series.id; }
        }
        return recentId;
    }
    public synchronized void saveSeries(Models.Series series) {
        List<Models.Series> all = listSeries();
        all.removeIf(s -> s.id.equals(series.id));
        all.add(0, series);
        writeSeries(all);
        restoreSeries(series.id);
    }
    /** A delayed cover lookup must not reorder or restore a hidden library entry. */
    public synchronized void updateSeriesCover(String id, String url) {
        String cover = Models.safeCoverUrl(url);
        if (!validId(id) || cover.isEmpty()) return;
        List<Models.Series> all = listSeries();
        for (Models.Series series : all) if (id.equals(series.id)) {
            if (!cover.equals(series.coverUrl)) { series.coverUrl = cover; writeSeries(all); }
            return;
        }
    }
    /** Merge current metadata by stable episode ID; a partial remote response never deletes local entries. */
    public synchronized int mergeRefreshedSeries(Models.Series fresh) {
        if (fresh == null || !validId(fresh.id) || fresh.episodes.isEmpty()) throw new IllegalArgumentException("刷新未返回有效剧集");
        List<Models.Series> all = listSeries();
        Models.Series target = null;
        for (Models.Series series : all) if (series.id.equals(fresh.id)) target = series;
        if (target == null) throw new IllegalArgumentException("番剧已移除，未重新导入");
        Map<String, Models.Episode> merged = new LinkedHashMap<>();
        for (Models.Episode episode : target.episodes) merged.put(episode.id, episode);
        Set<String> newIds = new HashSet<>(prefs.getStringSet("newEpisodes:" + fresh.id, java.util.Collections.emptySet()));
        int added = 0;
        for (Models.Episode episode : fresh.episodes) {
            if (!validId(episode.id) || !fresh.id.equals(episode.seriesId)) throw new IllegalArgumentException("剧集归属不一致");
            Models.Episode old = merged.get(episode.id);
            if (old != null && old.localUri != null && !old.localUri.isEmpty()) continue;
            if (old == null) { added++; newIds.add(episode.id); }
            merged.put(episode.id, episode);
        }
        target.episodes = new ArrayList<>(merged.values());
        target.episodes.sort(java.util.Comparator.comparingInt((Models.Episode e) -> e.index).thenComparing(e -> e.id));
        if (validId(fresh.title)) target.title = fresh.title;
        if (!Models.safeCoverUrl(fresh.coverUrl).isEmpty()) target.coverUrl = fresh.coverUrl;
        for (Models.Episode episode : target.episodes) episode.seriesTitle = target.title;
        // Keep membership, manual watched flags, pinning, progress and last episode intact.
        try {
            JSONArray array = new JSONArray(); for (Models.Series series : all) array.put(series.toJson());
            if (!prefs.edit().putString("series", array.toString()).putStringSet("newEpisodes:" + fresh.id, newIds).commit())
                throw new IllegalStateException("刷新结果未能保存");
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        return added;
    }
    public int newEpisodeCount(String id) { return prefs.getStringSet("newEpisodes:" + id, java.util.Collections.emptySet()).size(); }
    public void markEpisodesSeen(String id) { prefs.edit().remove("newEpisodes:" + id).apply(); }
    public synchronized void deleteSeries(String id) {
        List<Models.Series> all = listSeries();
        all.removeIf(s -> s.id.equals(id));
        writeSeries(all);
    }
    private void writeSeries(List<Models.Series> all) {
        try {
            JSONArray array = new JSONArray();
            for (Models.Series s : all) array.put(s.toJson());
            prefs.edit().putString("series", array.toString()).apply();
        } catch (Exception ex) { throw new IllegalStateException("保存片库失败", ex); }
    }
    public Progress getProgress(String episodeId) {
        Progress p = new Progress();
        try {
            JSONObject value = new JSONObject(prefs.getString("progress:" + episodeId, "{}"));
            p.positionMs = value.optLong("position");
            p.durationMs = value.optLong("duration");
            p.updatedAt = value.optLong("updated");
            p.completed = value.optBoolean("completed");
        } catch (Exception ignored) { }
        return p;
    }
    public synchronized void saveProgress(Models.Episode episode, long positionMs, long durationMs) {
        if (episode == null || positionMs < 0) return;
        try {
            Progress old = getProgress(episode.id);
            long duration = durationMs > 0 ? durationMs : old.durationMs;
            long position = duration > 0 ? Math.min(positionMs, duration) : positionMs;
            JSONObject value = new JSONObject().put("position", position).put("duration", duration)
                .put("updated", System.currentTimeMillis()).put("completed", position > 0 && duration > 0 && position >= duration - 1000);
            prefs.edit().putString("progress:" + episode.id, value.toString())
                .putString("last:" + episode.seriesId, episode.id).putString("lastSeries", episode.seriesId).apply();
        } catch (Exception ex) { throw new IllegalStateException("保存观看记录失败", ex); }
    }
    public String getLastEpisode(String seriesId) { return prefs.getString("last:" + seriesId, ""); }
    public String getLastSeries() { return prefs.getString("lastSeries", ""); }
    public float getSpeed() {
        try { return safeSpeed(prefs.getFloat("speed", 1f)); }
        catch (ClassCastException ignored) { return 1f; }
    }
    public void setSpeed(float speed) { prefs.edit().putFloat("speed", safeSpeed(speed)).apply(); }
    private static float safeSpeed(float speed) { return speed >= .25f && speed <= 3f ? speed : 1f; }
    public String getThemeId() {
        try { return ThemePalette.get(prefs.getString("theme", ThemePalette.DEFAULT_ID)).id; }
        catch (ClassCastException ignored) { return ThemePalette.DEFAULT_ID; }
    }
    public void setThemeId(String themeId) { prefs.edit().putString("theme", ThemePalette.get(themeId).id).apply(); }
    public float getHoldSpeed() {
        try { return holdSpeed(prefs.getFloat("holdSpeed", 2f)); }
        catch (ClassCastException ignored) { return 2f; }
    }
    public void setHoldSpeed(float speed) { prefs.edit().putFloat("holdSpeed", holdSpeed(speed)).apply(); }
    private static float holdSpeed(float speed) {
        return speed == 1.5f || speed == 2f || speed == 2.5f || speed == 3f ? speed : 2f;
    }
    public int getControlsTimeoutMs() {
        try { return controlsTimeout(prefs.getInt("controlsTimeoutMs", 3500)); }
        catch (ClassCastException ignored) { return 3500; }
    }
    public void setControlsTimeoutMs(int timeoutMs) { prefs.edit().putInt("controlsTimeoutMs", controlsTimeout(timeoutMs)).apply(); }
    private static int controlsTimeout(int timeoutMs) {
        return timeoutMs == 3500 || timeoutMs == 5000 || timeoutMs == 8000 || timeoutMs == 0 ? timeoutMs : 3500;
    }
    public boolean isAutoNext() { return booleanPreference("autoNext", false); }
    public void setAutoNext(boolean enabled) { prefs.edit().putBoolean("autoNext", enabled).apply(); }
    public boolean isRememberQuality() { return booleanPreference("rememberQuality", true); }
    public void setRememberQuality(boolean enabled) { prefs.edit().putBoolean("rememberQuality", enabled).apply(); }
    public boolean isWifiOnlyDownloads() { return booleanPreference("wifiOnlyDownloads", true); }
    public void setWifiOnlyDownloads(boolean enabled) { prefs.edit().putBoolean("wifiOnlyDownloads", enabled).apply(); }
    public String getLibraryFilter() {
        try { return libraryFilter(prefs.getString("libraryFilter", "all")); }
        catch (ClassCastException ignored) { return "all"; }
    }
    public void setLibraryFilter(String filter) { prefs.edit().putString("libraryFilter", libraryFilter(filter)).apply(); }
    private static String libraryFilter(String filter) {
        String normalized = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        return "unfinished".equals(normalized) || "watched".equals(normalized) || "offline".equals(normalized) ? normalized : "all";
    }

    public static final class SkipSettings {
        public final boolean enabled;
        public final int introSeconds, outroSeconds;
        public SkipSettings(boolean enabled, int introSeconds, int outroSeconds) {
            this.enabled = enabled;
            this.introSeconds = Math.max(0, Math.min(600, introSeconds));
            this.outroSeconds = Math.max(0, Math.min(600, outroSeconds));
        }
        JSONObject toJson() throws org.json.JSONException {
            return new JSONObject().put("enabled", enabled).put("introSeconds", introSeconds).put("outroSeconds", outroSeconds);
        }
    }
    public SkipSettings getSkipSettings(String seriesId) {
        if (validId(seriesId)) try {
            String value = prefs.getString("skip:" + seriesId, "");
            if (!value.isEmpty()) return LibraryBackup.readSkip(new JSONObject(value));
        } catch (Exception ignored) { }
        return new SkipSettings(false, 0, 0);
    }
    public void setSkipSettings(String seriesId, boolean enabled, int introSeconds, int outroSeconds) {
        if (!validId(seriesId)) return;
        try {
            prefs.edit().putString("skip:" + seriesId, new SkipSettings(enabled, introSeconds, outroSeconds).toJson().toString()).apply();
        } catch (org.json.JSONException exception) { throw new IllegalStateException("无法保存片头片尾设置", exception); }
    }
    private boolean booleanPreference(String key, boolean fallback) {
        try { return prefs.getBoolean(key, fallback); }
        catch (ClassCastException ignored) { return fallback; }
    }
    /** A preference is a video profile only; it never retains signed addresses or headers. */
    public Models.Quality getPreferredQuality() {
        try {
            String encoded = prefs.getString("preferredQuality", "");
            return encoded.isEmpty() ? null : LibraryBackup.readProfile(new JSONObject(encoded));
        } catch (Exception ignored) { return null; }
    }
    public void setPreferredQuality(Models.Quality quality) {
        try {
            JSONObject profile = LibraryBackup.profileJson(quality);
            if (profile == null) clearPreferredQuality();
            else prefs.edit().putString("preferredQuality", profile.toString()).apply();
        } catch (Exception ignored) { clearPreferredQuality(); }
    }
    public void clearPreferredQuality() { prefs.edit().remove("preferredQuality").apply(); }
    /** Last successfully played online/offline choice for one series, without media addresses. */
    public static final class SeriesPlaybackPreference {
        public final boolean offline;
        public final Models.Quality quality;
        SeriesPlaybackPreference(boolean offline, Models.Quality quality) {
            this.offline = offline;
            this.quality = quality;
        }
        JSONObject toJson() throws org.json.JSONException {
            return new JSONObject().put("offline", offline).put("profile", LibraryBackup.profileJson(quality));
        }
    }
    public SeriesPlaybackPreference getSeriesPlaybackPreference(String seriesId) {
        if (validId(seriesId)) try {
            String encoded = prefs.getString("playback:" + seriesId, "");
            if (!encoded.isEmpty()) return LibraryBackup.readPlayback(new JSONObject(encoded));
        } catch (Exception ignored) { }
        return null;
    }
    /** Call only after playback becomes ready. Missing/invalid source profiles never replace a working choice. */
    public void setSeriesPlaybackPreference(String seriesId, boolean offline, Models.Quality quality) {
        if (!validId(seriesId)) return;
        Models.Quality safe = LibraryBackup.profile(quality);
        if (safe == null) return;
        try {
            prefs.edit().putString("playback:" + seriesId,
                new SeriesPlaybackPreference(offline, safe).toJson().toString()).apply();
        } catch (org.json.JSONException exception) { throw new IllegalStateException("无法保存本番播放设置", exception); }
    }
    public void clearSeriesPlaybackPreference(String seriesId) {
        if (validId(seriesId)) prefs.edit().remove("playback:" + seriesId).apply();
    }
    /** Explicit yes/no takes precedence; otherwise every episode must have real completed progress. */
    public boolean isSeriesWatched(Models.Series series) {
        if (series == null || !validId(series.id)) return false;
        String override = watchedOverride(series.id);
        if (!"auto".equals(override)) return "yes".equals(override);
        if (series.episodes == null || series.episodes.isEmpty()) return false;
        for (Models.Episode episode : series.episodes) {
            if (episode == null || !validId(episode.id)) return false;
            Progress progress = getProgress(episode.id);
            if (!progress.completed || progress.positionMs <= 0 || progress.durationMs <= 0
                    || progress.positionMs < progress.durationMs - 1000) return false;
        }
        return true;
    }
    public void setSeriesWatched(String id, boolean watched) {
        if (validId(id)) prefs.edit().putBoolean("watched:" + id, watched).apply();
    }
    public void clearSeriesWatchedOverride(String id) { if (validId(id)) prefs.edit().remove("watched:" + id).apply(); }
    private String watchedOverride(String id) {
        if (!prefs.contains("watched:" + id)) return "auto";
        try { return prefs.getBoolean("watched:" + id, false) ? "yes" : "no"; }
        catch (ClassCastException ignored) { return "auto"; }
    }
    /** Reset preferences only: library membership, pins, history, login and downloads survive. */
    public void resetAppearanceAndPlaybackSettings() {
        prefs.edit().remove("theme").remove("speed").remove("holdSpeed").remove("controlsTimeoutMs")
            .remove("autoNext").remove("rememberQuality").remove("preferredQuality")
            .remove("wifiOnlyDownloads").remove("libraryFilter").apply();
    }

    /** Export by an explicit field allowlist, never by copying SharedPreferences or media JSON. */
    public synchronized String exportBackup() {
        try {
            JSONArray seriesJson = new JSONArray(), progressJson = new JSONArray();
            Set<String> exportedIds = new HashSet<>();
            Set<String> exportedProgress = new HashSet<>();
            for (Models.Series series : listSeries()) {
                JSONArray episodes = new JSONArray();
                Set<String> episodeIds = new HashSet<>();
                for (Models.Episode episode : series.episodes) {
                    boolean local = episode.localUri != null && !episode.localUri.isEmpty();
                    local |= episode.id.startsWith("local-");
                    episodes.put(new JSONObject().put("id", episode.id).put("title", episode.title)
                        .put("index", episode.index).put("local", local).put("bvid", local ? "" : episode.bvid)
                        .put("aid", local ? 0 : episode.aid).put("cid", local ? 0 : episode.cid).put("bangumi", !local && episode.bangumi));
                    episodeIds.add(episode.id);
                    Progress progress = getProgress(episode.id);
                    if (progress.updatedAt > 0 && exportedProgress.add(episode.id)) progressJson.put(new JSONObject().put("episodeId", episode.id)
                        .put("positionMs", progress.positionMs).put("durationMs", progress.durationMs).put("updatedAt", progress.updatedAt)
                        .put("completed", progress.completed && progress.positionMs > 0 && progress.durationMs > 0
                            && progress.positionMs >= progress.durationMs - 1000));
                }
                String last = getLastEpisode(series.id);
                SeriesPlaybackPreference playback = getSeriesPlaybackPreference(series.id);
                seriesJson.put(new JSONObject().put("id", series.id).put("title", series.title)
                    .put("coverUrl", Models.safeCoverUrl(series.coverUrl)).put("pinned", isPinned(series.id))
                    .put("hidden", booleanPreference("hidden:" + series.id, false)).put("watched", watchedOverride(series.id))
                    .put("skip", getSkipSettings(series.id).toJson())
                    .put("playback", playback == null ? JSONObject.NULL : playback.toJson())
                    .put("lastEpisodeId", episodeIds.contains(last) ? last : "").put("episodes", episodes));
                exportedIds.add(series.id);
            }
            JSONObject profile = LibraryBackup.profileJson(getPreferredQuality());
            JSONObject settings = new JSONObject().put("theme", getThemeId()).put("speed", getSpeed())
                .put("holdSpeed", getHoldSpeed()).put("controlsTimeoutMs", getControlsTimeoutMs())
                .put("autoNext", isAutoNext()).put("rememberQuality", isRememberQuality())
                .put("wifiOnlyDownloads", isWifiOnlyDownloads()).put("libraryFilter", getLibraryFilter())
                .put("preferredQuality", profile == null ? JSONObject.NULL : profile);
            String lastSeries = getLastSeries();
            String document = LibraryBackup.document(seriesJson, progressJson, settings,
                exportedIds.contains(lastSeries) ? lastSeries : "").toString();
            LibraryBackup.inspect(document);
            return document;
        } catch (IllegalArgumentException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalStateException("无法生成片库备份，请检查本机片库数据"); }
    }

    public LibraryBackup.Snapshot inspectBackup(String text) { return LibraryBackup.inspect(text); }

    /** All validation and merge work precedes one preferences transaction. Nothing is cleared. */
    public synchronized LibraryBackup.Result importBackup(LibraryBackup.Snapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("请先读取并检查备份");
        try {
            JSONObject backup = snapshot.data();
            List<Models.Series> all = listSeries();
            Map<String, Models.Series> bySeries = new LinkedHashMap<>();
            Map<String, String> owner = new LinkedHashMap<>();
            Map<String, Models.Episode> byEpisode = new LinkedHashMap<>();
            for (Models.Series series : all) {
                bySeries.put(series.id, series);
                for (Models.Episode episode : series.episodes) { owner.put(episode.id, series.id); byEpisode.put(episode.id, episode); }
            }
            Set<String> eligible = new HashSet<>();
            Map<String, JSONObject> flags = new LinkedHashMap<>();
            int added = 0, merged = 0;
            JSONArray incoming = backup.getJSONArray("series");
            for (int i = 0; i < incoming.length(); i++) {
                JSONObject item = incoming.getJSONObject(i);
                String id = item.getString("id");
                JSONArray episodes = item.getJSONArray("episodes");
                List<Models.Episode> online = new ArrayList<>();
                int localCount = 0;
                for (int j = 0; j < episodes.length(); j++) {
                    JSONObject entry = episodes.getJSONObject(j);
                    if (entry.getBoolean("local")) { localCount++; continue; }
                    String episodeId = entry.getString("id");
                    if (owner.containsKey(episodeId) && !id.equals(owner.get(episodeId)))
                        throw new IllegalArgumentException("备份剧集与本机番剧归属冲突，未导入任何数据");
                    Models.Episode existing = byEpisode.get(episodeId);
                    if (existing != null && existing.localUri != null && !existing.localUri.isEmpty())
                        throw new IllegalArgumentException("备份在线视频与本地文件身份冲突，未导入任何数据");
                    Models.Episode episode = new Models.Episode();
                    episode.id = episodeId; episode.title = entry.getString("title"); episode.index = entry.getInt("index");
                    episode.seriesId = id; episode.seriesTitle = item.getString("title"); episode.bvid = entry.getString("bvid");
                    episode.aid = entry.getLong("aid"); episode.cid = entry.getLong("cid"); episode.bangumi = entry.getBoolean("bangumi");
                    online.add(episode); eligible.add(episodeId);
                }
                if (online.isEmpty() && localCount > 0) continue;
                Models.Series series = bySeries.get(id);
                if (series == null) {
                    series = new Models.Series(); series.id = id; series.title = item.getString("title");
                    series.coverUrl = item.getString("coverUrl");
                    bySeries.put(id, series); all.add(series); added++;
                } else {
                    merged++;
                    if (series.coverUrl == null || series.coverUrl.isEmpty()) series.coverUrl = item.getString("coverUrl");
                }
                for (Models.Episode episode : online) if (!byEpisode.containsKey(episode.id)) {
                    episode.seriesTitle = series.title; series.episodes.add(episode);
                    byEpisode.put(episode.id, episode); owner.put(episode.id, id);
                }
                flags.put(id, item);
            }
            Map<String, String> progressWrites = new LinkedHashMap<>();
            Map<String, Long> timestamps = new LinkedHashMap<>();
            for (String id : byEpisode.keySet()) timestamps.put(id, getProgress(id).updatedAt);
            int imported = 0, kept = 0;
            JSONArray progress = backup.getJSONArray("progress");
            for (int i = 0; i < progress.length(); i++) {
                JSONObject item = progress.getJSONObject(i); String id = item.getString("episodeId");
                if (!eligible.contains(id)) continue;
                long timestamp = item.getLong("updatedAt");
                if (timestamps.get(id) >= timestamp) { kept++; continue; }
                progressWrites.put(id, new JSONObject().put("position", item.getLong("positionMs"))
                    .put("duration", item.getLong("durationMs")).put("updated", timestamp).put("completed", item.getBoolean("completed")).toString());
                timestamps.put(id, timestamp); imported++;
            }
            Map<String, String> lastEpisodes = new LinkedHashMap<>();
            Map<String, Long> seriesTimes = new LinkedHashMap<>();
            for (Models.Series series : all) {
                String last = getLastEpisode(series.id);
                long time = series.id.equals(owner.get(last)) ? timestamps.get(last) : 0;
                if (time <= 0) { time = 0; last = ""; }
                JSONObject original = flags.get(series.id);
                if (original != null) {
                    String candidate = original.getString("lastEpisodeId");
                    long candidateTime = series.id.equals(owner.get(candidate)) ? timestamps.get(candidate) : 0;
                    if (candidateTime > time) { last = candidate; time = candidateTime; }
                }
                for (Models.Episode episode : series.episodes) if (timestamps.get(episode.id) > time) {
                    last = episode.id; time = timestamps.get(episode.id);
                }
                if (time > 0) { lastEpisodes.put(series.id, last); seriesTimes.put(series.id, time); }
            }
            String recent = getLastSeries();
            long recentTime = seriesTimes.containsKey(recent) ? seriesTimes.get(recent) : 0;
            String backupRecent = backup.getString("lastSeries");
            long backupTime = seriesTimes.containsKey(backupRecent) ? seriesTimes.get(backupRecent) : 0;
            if (backupTime > recentTime) { recent = backupRecent; recentTime = backupTime; }
            for (Map.Entry<String, Long> entry : seriesTimes.entrySet()) if (entry.getValue() > recentTime) {
                recent = entry.getKey(); recentTime = entry.getValue();
            }
            JSONArray combined = new JSONArray();
            for (Models.Series series : all) combined.put(series.toJson());
            JSONObject settings = backup.getJSONObject("settings");
            String profile = settings.isNull("preferredQuality") ? null : LibraryBackup.profileJson(
                LibraryBackup.readProfile(settings.getJSONObject("preferredQuality"))).toString();
            // No editor exists until metadata, ownership, history and settings are all ready.
            SharedPreferences.Editor edit = prefs.edit().putString("series", combined.toString());
            for (Map.Entry<String, JSONObject> entry : flags.entrySet()) {
                String id = entry.getKey(); JSONObject item = entry.getValue();
                edit.putBoolean("pinned:" + id, item.getBoolean("pinned")).putBoolean("hidden:" + id, item.getBoolean("hidden"));
                String watched = item.getString("watched");
                if ("auto".equals(watched)) edit.remove("watched:" + id); else edit.putBoolean("watched:" + id, "yes".equals(watched));
                if (item.has("skip")) edit.putString("skip:" + id, LibraryBackup.readSkip(item.getJSONObject("skip")).toJson().toString());
                // Versions 1 and 2 did not record per-series playback choices.
                // Only an explicit v3 null removes an existing choice.
                if (item.has("playback")) {
                    if (item.isNull("playback")) edit.remove("playback:" + id);
                    else edit.putString("playback:" + id, LibraryBackup.readPlayback(item.getJSONObject("playback")).toJson().toString());
                }
            }
            for (Map.Entry<String, String> entry : progressWrites.entrySet()) edit.putString("progress:" + entry.getKey(), entry.getValue());
            for (Map.Entry<String, String> entry : lastEpisodes.entrySet()) edit.putString("last:" + entry.getKey(), entry.getValue());
            if (recentTime > 0) edit.putString("lastSeries", recent);
            edit.putString("theme", settings.getString("theme")).putFloat("speed", (float) settings.getDouble("speed"))
                .putFloat("holdSpeed", (float) settings.getDouble("holdSpeed")).putInt("controlsTimeoutMs", settings.getInt("controlsTimeoutMs"))
                .putBoolean("autoNext", settings.getBoolean("autoNext")).putBoolean("rememberQuality", settings.getBoolean("rememberQuality"));
            // Version 1 had no network/filter/skip preferences. An old backup must
            // not overwrite newer per-device choices that it could not contain.
            if (settings.has("wifiOnlyDownloads")) edit.putBoolean("wifiOnlyDownloads", settings.getBoolean("wifiOnlyDownloads"));
            if (settings.has("libraryFilter")) edit.putString("libraryFilter", settings.getString("libraryFilter"));
            if (profile == null) edit.remove("preferredQuality"); else edit.putString("preferredQuality", profile);
            if (!edit.commit()) throw new IllegalStateException("备份合并未能写入磁盘，请检查可用空间");
            return new LibraryBackup.Result(added, merged, imported, kept, snapshot.skippedLocalEpisodes);
        } catch (IllegalArgumentException | IllegalStateException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalStateException("备份合并未完成，请重新打开片库检查结果"); }
    }
    public synchronized List<JSONObject> savedDownloads() {
        ArrayList<JSONObject> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("downloads", "[]"));
            for (int i = 0; i < array.length(); i++) result.add(array.getJSONObject(i));
        } catch (Exception ignored) { }
        return result;
    }
    public synchronized void saveDownload(String key, Models.Episode episode, Models.Quality quality) {
        List<JSONObject> all = savedDownloads();
        all.removeIf(j -> key.equals(j.optString("key")));
        try {
            all.add(new JSONObject().put("key", key).put("episode", episode.toJson()).put("quality", quality.toJson()));
            prefs.edit().putString("downloads", new JSONArray(all).toString()).apply();
        } catch (Exception ex) { throw new IllegalStateException("保存下载任务失败", ex); }
    }
    public synchronized void deleteDownload(String key) {
        List<JSONObject> all = savedDownloads();
        all.removeIf(j -> key.equals(j.optString("key")));
        prefs.edit().putString("downloads", new JSONArray(all).toString())
            .remove("downloadError:" + key + ":v").remove("downloadError:" + key + ":a").apply();
    }
    public String downloadError(String trackId) { return prefs.getString("downloadError:" + trackId, ""); }
    public void saveDownloadError(String trackId, String safeDetail) {
        prefs.edit().putString("downloadError:" + trackId, safeDetail).apply();
    }
    public void clearDownloadError(String trackId) { prefs.edit().remove("downloadError:" + trackId).apply(); }
}
