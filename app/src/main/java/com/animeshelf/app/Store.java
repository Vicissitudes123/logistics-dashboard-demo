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
    public synchronized void saveSeries(Models.Series series) {
        List<Models.Series> all = listSeries();
        all.removeIf(s -> s.id.equals(series.id));
        all.add(0, series);
        writeSeries(all);
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
        prefs.edit().putString("downloads", new JSONArray(all).toString()).apply();
    }
}
