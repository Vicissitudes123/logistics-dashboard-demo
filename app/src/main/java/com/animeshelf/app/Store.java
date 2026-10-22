package com.animeshelf.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

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
    public void saveProgress(Models.Episode episode, long positionMs, long durationMs) {
        if (episode == null || positionMs < 0) return;
        try {
            Progress old = getProgress(episode.id);
            long duration = durationMs > 0 ? durationMs : old.durationMs;
            long position = duration > 0 ? Math.min(positionMs, duration) : positionMs;
            JSONObject value = new JSONObject().put("position", position).put("duration", duration)
                .put("updated", System.currentTimeMillis()).put("completed", duration > 0 && position >= duration - 1000);
            prefs.edit().putString("progress:" + episode.id, value.toString())
                .putString("last:" + episode.seriesId, episode.id).putString("lastSeries", episode.seriesId).apply();
        } catch (Exception ex) { throw new IllegalStateException("保存观看记录失败", ex); }
    }
    public String getLastEpisode(String seriesId) { return prefs.getString("last:" + seriesId, ""); }
    public String getLastSeries() { return prefs.getString("lastSeries", ""); }
    public float getSpeed() { return prefs.getFloat("speed", 1f); }
    public void setSpeed(float speed) { prefs.edit().putFloat("speed", speed).apply(); }
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
    /** Reset preferences only: library membership, pins, history, login and downloads survive. */
    public void resetAppearanceAndPlaybackSettings() {
        prefs.edit().remove("theme").remove("speed").remove("holdSpeed").remove("controlsTimeoutMs").apply();
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
