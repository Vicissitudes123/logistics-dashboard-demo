package com.animeshelf.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Journal of explicitly confirmed work. Kept separate from backup/export and login data. */
final class PendingDownloads {
    static final class Job {
        String id, error;
        Models.Episode episode;
        Models.Quality profile, prepared;
        long preparedAt;
    }
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("confirmed_download_queue", Context.MODE_PRIVATE); }
    static synchronized List<Job> list(Context context) {
        List<Job> result = new ArrayList<>();
        try {
            JSONArray items = new JSONArray(prefs(context).getString("jobs", "[]"));
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i); Job job = new Job();
                job.id = item.getString("id"); job.error = item.optString("error");
                job.episode = Models.Episode.fromJson(item.getJSONObject("episode"));
                job.profile = Models.Quality.fromJson(item.getJSONObject("profile"));
                job.prepared = item.isNull("prepared") ? null : Models.Quality.fromJson(item.getJSONObject("prepared"));
                job.preparedAt = item.optLong("preparedAt"); result.add(job);
            }
        } catch (Exception failure) { throw new IllegalStateException("无法读取已确认的下载队列", failure); }
        return result;
    }
    private static void write(Context context, List<Job> jobs) {
        try {
            JSONArray items = new JSONArray();
            for (Job job : jobs) items.put(new JSONObject().put("id", job.id).put("error", job.error)
                .put("episode", job.episode.toJson()).put("profile", job.profile.toJson())
                .put("prepared", job.prepared == null ? JSONObject.NULL : job.prepared.toJson()).put("preparedAt", job.preparedAt));
            if (!prefs(context).edit().putString("jobs", items.toString()).commit()) throw new IllegalStateException("下载队列未能保存，请检查空间");
        } catch (org.json.JSONException error) { throw new IllegalStateException("无法保存下载队列", error); }
    }
    static synchronized int add(Context context, List<Models.Episode> episodes, Models.Quality profile, Map<String, Models.Quality> prepared) {
        List<Job> jobs = list(context); int added = 0;
        for (Models.Episode episode : episodes) {
            boolean duplicate = false;
            for (Job old : jobs) if (old.episode.id.equals(episode.id) && DownloadCatalogue.sameProfile(old.profile, profile)) duplicate = true;
            if (duplicate) continue;
            Job job = new Job(); job.id = UUID.randomUUID().toString(); job.error = "";
            job.episode = episode; job.profile = profile; job.prepared = prepared.get(episode.id); job.preparedAt = System.currentTimeMillis();
            jobs.add(job); added++;
        }
        write(context, jobs); return added;
    }
    static synchronized boolean contains(Context context, String id) { for (Job job : list(context)) if (job.id.equals(id)) return true; return false; }
    static synchronized void finish(Context context, String id, String error) {
        List<Job> jobs = list(context);
        if (error.isEmpty()) jobs.removeIf(job -> job.id.equals(id));
        else for (Job job : jobs) if (job.id.equals(id)) job.error = error;
        write(context, jobs);
    }
    static synchronized void cancel(Context context, Models.Episode episode, Models.Quality profile) {
        List<Job> jobs = list(context);
        jobs.removeIf(job -> job.episode.id.equals(episode.id) && DownloadCatalogue.sameProfile(job.profile, profile));
        write(context, jobs);
    }
    static synchronized void retry(Context context) { List<Job> jobs = list(context); for (Job job : jobs) { job.error = ""; job.prepared = null; } write(context, jobs); }
    static synchronized void clear(Context context) { write(context, new ArrayList<>()); }
    static boolean hasReady(Context context) { for (Job job : list(context)) if (job.error.isEmpty()) return true; return false; }
}
