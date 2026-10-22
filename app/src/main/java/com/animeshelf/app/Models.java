package com.animeshelf.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.net.URL;

/** Small, stable local-storage models. Session cookies are never stored here. */
public final class Models {
    private Models() {}

    public static class Series {
        public String id = "";
        public String title = "";
        public ArrayList<Episode> episodes = new ArrayList<>();

        public Series() {}

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("title", title);
            JSONArray items = new JSONArray();
            for (Episode episode : episodes) items.put(episode.toJson());
            json.put("episodes", items);
            return json;
        }

        public static Series fromJson(JSONObject json) {
            Series series = new Series();
            if (json == null) return series;
            series.id = json.optString("id", "");
            series.title = json.optString("title", "");
            JSONArray items = json.optJSONArray("episodes");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item != null) series.episodes.add(Episode.fromJson(item));
                }
            }
            return series;
        }
    }

    public static class Episode {
        public String id = "";
        public String title = "";
        public String bvid = "";
        public long aid;
        public long cid;
        public String seriesId = "";
        public String seriesTitle = "";
        public int index;
        public boolean bangumi;
        /** A persistently granted content:// URI for an imported local video. */
        public String localUri = "";

        public Episode() {}

        public JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("title", title)
                    .put("bvid", bvid).put("aid", aid).put("cid", cid)
                    .put("seriesId", seriesId).put("seriesTitle", seriesTitle)
                    .put("index", index).put("bangumi", bangumi).put("localUri", localUri);
        }

        public static Episode fromJson(JSONObject json) {
            Episode episode = new Episode();
            if (json == null) return episode;
            episode.id = json.optString("id", "");
            episode.title = json.optString("title", "");
            episode.bvid = json.optString("bvid", "");
            episode.aid = json.optLong("aid", 0);
            episode.cid = json.optLong("cid", 0);
            episode.seriesId = json.optString("seriesId", "");
            episode.seriesTitle = json.optString("seriesTitle", "");
            episode.index = json.optInt("index", 0);
            episode.bangumi = json.optBoolean("bangumi", false);
            episode.localUri = json.optString("localUri", "");
            return episode;
        }
    }

    public static class Quality {
        public String id = "";
        public String label = "";
        public int qn;
        public String videoUrl = "";
        /** Empty for a single file that already contains both audio and video. */
        public String audioUrl = "";
        /** Ordered, deduplicated HTTPS candidates returned for this exact video track. */
        public ArrayList<String> videoUrls = new ArrayList<>();
        /** Ordered, deduplicated HTTPS candidates returned for this exact audio track. */
        public ArrayList<String> audioUrls = new ArrayList<>();
        /** Public media request headers only. Never contains login cookies. */
        public Map<String, String> headers = new LinkedHashMap<>();

        public Quality() {}

        public JSONObject toJson() throws JSONException {
            JSONObject headerJson = new JSONObject();
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (isPublicMediaHeader(entry.getKey())) headerJson.put(entry.getKey(), entry.getValue());
            }
            List<String> videos = videoCandidates();
            List<String> audios = audioCandidates();
            return new JSONObject().put("id", id).put("label", label).put("qn", qn)
                    .put("videoUrl", first(videos)).put("audioUrl", first(audios))
                    .put("videoUrls", new JSONArray(videos)).put("audioUrls", new JSONArray(audios))
                    .put("headers", headerJson);
        }

        public static Quality fromJson(JSONObject json) {
            Quality quality = new Quality();
            if (json == null) return quality;
            quality.id = json.optString("id", "");
            quality.label = json.optString("label", "");
            quality.qn = json.optInt("qn", 0);
            quality.videoUrl = json.optString("videoUrl", "");
            quality.audioUrl = json.optString("audioUrl", "");
            quality.videoUrls = candidates(quality.videoUrl, strings(json.optJSONArray("videoUrls")));
            quality.audioUrls = candidates(quality.audioUrl, strings(json.optJSONArray("audioUrls")));
            quality.videoUrl = first(quality.videoUrls);
            quality.audioUrl = first(quality.audioUrls);
            JSONObject headerJson = json.optJSONObject("headers");
            if (headerJson != null) {
                Iterator<String> keys = headerJson.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (isPublicMediaHeader(key)) quality.headers.put(key, headerJson.optString(key, ""));
                }
            }
            return quality;
        }

        /** These helpers also accept a legacy single-URL record or an unsaved Quality. */
        public ArrayList<String> videoCandidates() { return candidates(videoUrl, videoUrls); }

        public ArrayList<String> audioCandidates() { return candidates(audioUrl, audioUrls); }

        private static ArrayList<String> candidates(String primary, List<String> alternatives) {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            String first = httpsMediaUrl(primary);
            if (!first.isEmpty()) values.add(first);
            if (alternatives != null) {
                for (String candidate : alternatives) {
                    String normalized = httpsMediaUrl(candidate);
                    if (!normalized.isEmpty()) values.add(normalized);
                }
            }
            return new ArrayList<>(values);
        }

        private static ArrayList<String> strings(JSONArray array) {
            ArrayList<String> values = new ArrayList<>();
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    Object value = array.opt(i);
                    if (value instanceof String) values.add((String) value);
                }
            }
            return values;
        }

        private static String first(List<String> values) { return values.isEmpty() ? "" : values.get(0); }

        /** Validate syntax without changing a CDN path, signature, query or port. */
        static String httpsMediaUrl(String value) {
            if (value == null || value.isEmpty()) return "";
            if (value.startsWith("//")) value = "https:" + value;
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch <= 32 || ch == 127) return "";
            }
            try {
                URL url = new URL(value);
                if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null
                        || url.getHost().isEmpty() || url.getRef() != null
                        || (url.getPort() != -1 && (url.getPort() < 1 || url.getPort() > 65535))) return "";
                url.toURI();
                return url.toExternalForm();
            } catch (Exception ignored) { return ""; }
        }

        private static boolean isPublicMediaHeader(String key) {
            return "Referer".equalsIgnoreCase(key) || "User-Agent".equalsIgnoreCase(key)
                    || "Origin".equalsIgnoreCase(key);
        }
    }
}
