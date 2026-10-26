package com.animeshelf.app;

import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

/** Persistent episode-level intent, independent of transient track/network states. */
final class DownloadControls {
    static final int STOP_PRIORITY = 1 << 18;
    private final SharedPreferences prefs;
    DownloadControls(SharedPreferences prefs) { this.prefs = prefs; }
    boolean paused(String key) { return prefs.getStringSet("pausedEpisodes", java.util.Collections.emptySet()).contains(key); }
    String priority() { return prefs.getString("priorityEpisode", ""); }
    void pause(String key, boolean paused) {
        Set<String> keys = new HashSet<>(prefs.getStringSet("pausedEpisodes", java.util.Collections.emptySet()));
        if (paused) keys.add(key); else keys.remove(key);
        SharedPreferences.Editor edit = prefs.edit().putStringSet("pausedEpisodes", keys);
        if (paused && key.equals(priority())) edit.remove("priorityEpisode");
        edit.commit();
    }
    void prioritize(String key) { pause(key, false); prefs.edit().putString("priorityEpisode", key).commit(); }
    void clearPriority() { prefs.edit().remove("priorityEpisode").commit(); }
    void forget(String key) { pause(key, false); if (key.equals(priority())) clearPriority(); }
    void resumeAll() { prefs.edit().remove("pausedEpisodes").remove("priorityEpisode").commit(); }
    int reason(String key, int global) {
        if (paused(key)) return global | DownloadNetworkPolicy.STOP_USER_PAUSED;
        String first = priority();
        return global | (!first.isEmpty() && !first.equals(key) ? STOP_PRIORITY : 0);
    }
}
