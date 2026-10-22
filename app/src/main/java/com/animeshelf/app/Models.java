package com.animeshelf.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

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
        /** Public media request headers only. Never contains login cookies. */
        public Map<String, String> headers = new LinkedHashMap<>();

        public Quality() {}

        public JSONObject toJson() throws JSONException {
            JSONObject headerJson = new JSONObject();
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (isPublicMediaHeader(entry.getKey())) headerJson.put(entry.getKey(), entry.getValue());
            }
            return new JSONObject().put("id", id).put("label", label).put("qn", qn)
                    .put("videoUrl", videoUrl).put("audioUrl", audioUrl).put("headers", headerJson);
        }

        public static Quality fromJson(JSONObject json) {
            Quality quality = new Quality();
            if (json == null) return quality;
            quality.id = json.optString("id", "");
            quality.label = json.optString("label", "");
            quality.qn = json.optInt("qn", 0);
            quality.videoUrl = json.optString("videoUrl", "");
            quality.audioUrl = json.optString("audioUrl", "");
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

        private static boolean isPublicMediaHeader(String key) {
            return "Referer".equalsIgnoreCase(key) || "User-Agent".equalsIgnoreCase(key)
                    || "Origin".equalsIgnoreCase(key);
        }
    }
}
