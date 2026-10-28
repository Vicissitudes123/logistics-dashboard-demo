package com.animeshelf.app;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** A strict, versioned metadata format. Media URLs, login data and URI grants are never part of it. */
public final class LibraryBackup {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public static final int MAX_SERIES = 1000;
    public static final int MAX_EPISODES = 20000;
    public static final int CURRENT_VERSION = 4;
    private static final long MAX_DURATION_MS = 7L * 24 * 60 * 60 * 1000;
    private static final String FORMAT = "anime-shelf-library";
    private LibraryBackup() { }

    /** The validated document is private, so confirmation cannot be followed by a mutated import. */
    public static final class Snapshot {
        public final int seriesCount, episodeCount, progressCount, skippedLocalEpisodes;
        private final String document;
        private final Boolean wifiOnly;
        private Snapshot(JSONObject source, int episodes, int progress, int locals) throws JSONException {
            document = source.toString();
            seriesCount = source.getJSONArray("series").length();
            episodeCount = episodes; progressCount = progress; skippedLocalEpisodes = locals;
            JSONObject settings = source.getJSONObject("settings");
            wifiOnly = settings.has("wifiOnlyDownloads") ? settings.getBoolean("wifiOnlyDownloads") : null;
        }
        JSONObject data() throws JSONException { return new JSONObject(document); }
        /** Null means a version 1 backup: the phone's current network setting will be retained. */
        public Boolean wifiOnlyDownloads() { return wifiOnly; }
        public String summary() {
            return seriesCount + " 部番剧、" + episodeCount + " 集、" + progressCount + " 条观看记录。"
                + "\n合并片库，保留本机更新的观看进度；偏好、置顶、隐藏及手工看完标记采用备份。"
                + (Boolean.FALSE.equals(wifiOnly) ? "\n此备份允许移动网络下载，恢复后下载可能消耗手机流量。" : "")
                + (skippedLocalEpisodes > 0 ? "\n其中 " + skippedLocalEpisodes + " 集本地视频及其观看记录不会恢复，需重新导入文件。" : "")
                + "\n备份不含视频文件、下载任务、登录信息或本地文件访问授权。";
        }
    }

    public static final class Result {
        public final int addedSeries, mergedSeries, importedProgress, keptNewerProgress, skippedLocalEpisodes;
        Result(int added, int merged, int imported, int kept, int locals) {
            addedSeries = added; mergedSeries = merged; importedProgress = imported;
            keptNewerProgress = kept; skippedLocalEpisodes = locals;
        }
        public String summary() {
            return "新增 " + addedSeries + " 部，合并 " + mergedSeries + " 部；导入 " + importedProgress
                + " 条进度，保留 " + keptNewerProgress + " 条本机较新或相同时间的进度。"
                + (skippedLocalEpisodes > 0 ? "\n跳过 " + skippedLocalEpisodes + " 集，本地视频及其观看记录不会恢复，需重新导入文件。" : "")
                + "\n原有离线视频和登录状态已保留。";
        }
    }

    public static Snapshot inspect(String text) {
        try {
            preflight(text);
            JSONTokener reader = new JSONTokener(text);
            Object parsed = reader.nextValue();
            require(parsed instanceof JSONObject && reader.nextClean() == 0, "备份必须是一个完整 JSON 对象");
            JSONObject root = (JSONObject) parsed;
            keys(root, "format", "version", "exportedAt", "lastSeries", "series", "progress", "settings");
            require(FORMAT.equals(string(root, "format", 64, false)), "不是番匣片库备份");
            int version = (int) integer(root, "version", 1, CURRENT_VERSION);
            integer(root, "exportedAt", 0, latestTimestamp());
            String lastSeries = id(root, "lastSeries", true);
            JSONArray series = array(root, "series", MAX_SERIES);
            Map<String, String> owners = new HashMap<>();
            Set<String> localIds = new HashSet<>();
            Set<String> seriesIds = new HashSet<>();
            int episodeCount = 0, localCount = 0;
            for (int i = 0; i < series.length(); i++) {
                JSONObject item = object(series, i);
                if (version == 1) keys(item, "id", "title", "coverUrl", "pinned", "hidden", "watched", "lastEpisodeId", "episodes");
                else if (version == 2) keys(item, "id", "title", "coverUrl", "pinned", "hidden", "watched", "skip", "lastEpisodeId", "episodes");
                else keys(item, "id", "title", "coverUrl", "pinned", "hidden", "watched", "skip", "playback", "lastEpisodeId", "episodes");
                String seriesId = id(item, "id", false);
                require(seriesIds.add(seriesId), "备份含重复番剧 ID");
                string(item, "title", 512, false);
                String cover = string(item, "coverUrl", 2048, true);
                require(cover.isEmpty() || cover.equals(Models.safeCoverUrl(cover)), "封面地址不是允许的公共静态图片");
                bool(item, "pinned"); bool(item, "hidden");
                String watched = string(item, "watched", 4, false);
                require("auto".equals(watched) || "yes".equals(watched) || "no".equals(watched), "看完标记无效");
                if (version >= 2) readSkip(object(item, "skip"));
                if (version >= 3) {
                    Object playback = item.get("playback");
                    require(playback == JSONObject.NULL || playback instanceof JSONObject, "本番播放设置格式无效");
                    if (playback instanceof JSONObject) readPlayback((JSONObject) playback);
                }
                String lastEpisode = id(item, "lastEpisodeId", true);
                JSONArray episodes = array(item, "episodes", MAX_EPISODES);
                episodeCount += episodes.length();
                require(episodeCount <= MAX_EPISODES, "备份剧集数量超过上限");
                Set<String> thisSeries = new HashSet<>();
                for (int j = 0; j < episodes.length(); j++) {
                    JSONObject episode = object(episodes, j);
                    keys(episode, "id", "title", "index", "local", "bvid", "aid", "cid", "bangumi");
                    String episodeId = id(episode, "id", false);
                    boolean local = bool(episode, "local");
                    // The same local URI can appear in several user-created collections.
                    // Those entries are reported and skipped on restore, never made playable.
                    require(!owners.containsKey(episodeId) || (local && localIds.contains(episodeId)), "备份含重复在线剧集 ID");
                    owners.put(episodeId, seriesId); thisSeries.add(episodeId);
                    if (local) localIds.add(episodeId);
                    string(episode, "title", 1024, true);
                    integer(episode, "index", 0, 100000);
                    require(!episodeId.startsWith("local-") || local, "本地剧集不可冒充在线视频");
                    String bvid = string(episode, "bvid", 32, true);
                    require(bvid.isEmpty() || bvid.matches("BV[A-Za-z0-9]{10}"), "BV 号无效");
                    long aid = integer(episode, "aid", 0, 9007199254740991L);
                    long cid = integer(episode, "cid", 0, 9007199254740991L);
                    boolean bangumi = bool(episode, "bangumi");
                    if (local) {
                        localCount++;
                        require(bvid.isEmpty() && aid == 0 && cid == 0 && !bangumi, "本地剧集包含不适用的在线字段");
                    }
                }
                require(lastEpisode.isEmpty() || thisSeries.contains(lastEpisode), "上次观看剧集不属于对应番剧");
            }
            require(lastSeries.isEmpty() || seriesIds.contains(lastSeries), "上次观看番剧不存在");
            JSONArray progress = array(root, "progress", MAX_EPISODES);
            Set<String> recorded = new HashSet<>();
            for (int i = 0; i < progress.length(); i++) {
                JSONObject item = object(progress, i);
                keys(item, "episodeId", "positionMs", "durationMs", "updatedAt", "completed");
                String episodeId = id(item, "episodeId", false);
                require(owners.containsKey(episodeId) && recorded.add(episodeId), "观看记录引用无效或重复的剧集");
                long position = integer(item, "positionMs", 0, MAX_DURATION_MS);
                long duration = integer(item, "durationMs", 0, MAX_DURATION_MS);
                require(duration == 0 || position <= duration, "观看位置超过视频时长");
                integer(item, "updatedAt", 1, latestTimestamp());
                boolean completed = bool(item, "completed");
                require(!completed || (position > 0 && duration > 0 && position >= duration - 1000), "看完状态与观看进度不一致");
            }
            validateSettings(object(root, "settings"), version);
            return new Snapshot(root, episodeCount, progress.length(), localCount);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            // Parser messages may include a supplied URL or other private input.
            throw new IllegalArgumentException("备份格式损坏或字段不完整，未导入任何数据");
        }
    }

    static JSONObject document(JSONArray series, JSONArray progress, JSONObject settings, String lastSeries) throws JSONException {
        return new JSONObject().put("format", FORMAT).put("version", CURRENT_VERSION).put("exportedAt", System.currentTimeMillis())
            .put("lastSeries", lastSeries).put("series", series).put("progress", progress).put("settings", settings);
    }

    static Models.Quality profile(Models.Quality source) {
        if (source == null || source.qn <= 0 || source.qn > 10000 || source.id == null) return null;
        String[] parts = source.id.split(":", -1);
        if (parts.length != 3 || parts[2].isEmpty()) return null;
        try {
            if (Integer.parseInt(parts[0]) != source.qn || !validCodec(parts[1])) return null;
            return quality(source.qn, normalizedCodec(parts[1]));
        } catch (NumberFormatException ignored) { return null; }
    }

    static JSONObject profileJson(Models.Quality source) throws JSONException {
        Models.Quality safe = profile(source);
        return safe == null ? null : new JSONObject().put("qn", safe.qn).put("codec", safe.id.split(":")[1]);
    }

    static Models.Quality readProfile(JSONObject value) throws JSONException {
        keys(value, "qn", "codec");
        int qn = (int) integer(value, "qn", 1, 10000);
        String codec = string(value, "codec", 16, false);
        require(validCodec(codec), "默认清晰度编码无效");
        return quality(qn, normalizedCodec(codec));
    }

    private static Models.Quality quality(int qn, String codec) {
        Models.Quality result = new Models.Quality();
        result.qn = qn; result.id = qn + ":" + codec + ":0";
        String name;
        switch (qn) {
            case 6: name = "240P"; break;
            case 16: name = "360P"; break;
            case 32: name = "480P"; break;
            case 64: name = "720P"; break;
            case 74: name = "720P 60帧"; break;
            case 80: name = "1080P"; break;
            case 112: name = "1080P 高码率"; break;
            case 116: name = "1080P 60帧"; break;
            case 120: name = "4K"; break;
            case 125: name = "HDR"; break;
            case 126: name = "杜比视界"; break;
            case 127: name = "8K"; break;
            default: name = "清晰度 " + qn;
        }
        String encoder = "7".equals(codec) ? "AVC" : "12".equals(codec) ? "HEVC" : "13".equals(codec) ? "AV1"
            : "progressive".equals(codec) ? "单文件" : "编码 " + codec;
        result.label = name + " · " + encoder;
        return result;
    }

    private static String normalizedCodec(String codec) {
        return "progressive".equals(codec) ? codec : Integer.toString(Integer.parseInt(codec));
    }
    private static boolean validCodec(String codec) {
        if ("progressive".equals(codec)) return true;
        try { return codec.matches("[0-9]{1,4}") && Integer.parseInt(codec) > 0 && Integer.parseInt(codec) <= 1000; }
        catch (NumberFormatException ignored) { return false; }
    }

    static Store.SkipSettings readSkip(JSONObject value) throws JSONException {
        keys(value, "enabled", "introSeconds", "outroSeconds");
        boolean enabled = bool(value, "enabled");
        int intro = (int) integer(value, "introSeconds", 0, 600);
        int outro = (int) integer(value, "outroSeconds", 0, 600);
        return new Store.SkipSettings(enabled, intro, outro);
    }

    static Store.SeriesPlaybackPreference readPlayback(JSONObject value) throws JSONException {
        keys(value, "offline", "profile");
        return new Store.SeriesPlaybackPreference(bool(value, "offline"), readProfile(object(value, "profile")));
    }

    private static void validateSettings(JSONObject value, int version) throws JSONException {
        if (version == 1) keys(value, "theme", "speed", "holdSpeed", "controlsTimeoutMs", "autoNext", "rememberQuality", "preferredQuality");
        else if (version < 4) keys(value, "theme", "speed", "holdSpeed", "controlsTimeoutMs", "autoNext", "rememberQuality", "preferredQuality", "wifiOnlyDownloads", "libraryFilter");
        else keys(value, "theme", "speed", "holdSpeed", "controlsTimeoutMs", "autoNext", "rememberQuality", "preferredQuality", "wifiOnlyDownloads", "libraryFilter", "gestureMask", "seekSeconds");
        if (version >= 4) {
            integer(value, "gestureMask", 0, 31);
            long seconds = integer(value, "seekSeconds", 90, 300);
            require(seconds == 90 || seconds == 180 || seconds == 300, "快进手势灵敏度无效");
        }
        String theme = string(value, "theme", 16, false);
        require(theme.equals(ThemePalette.get(theme).id), "主题设置无效");
        double speed = number(value, "speed");
        require(speed >= .25 && speed <= 3, "播放速度设置无效");
        double hold = number(value, "holdSpeed");
        require(hold == 1.5 || hold == 2 || hold == 2.5 || hold == 3, "长按速度设置无效");
        long timeout = integer(value, "controlsTimeoutMs", 0, 8000);
        require(timeout == 0 || timeout == 3500 || timeout == 5000 || timeout == 8000, "控制栏隐藏时间无效");
        bool(value, "autoNext"); bool(value, "rememberQuality");
        if (version >= 2) {
            bool(value, "wifiOnlyDownloads");
            String filter = string(value, "libraryFilter", 16, false);
            require("all".equals(filter) || "unfinished".equals(filter) || "watched".equals(filter) || "offline".equals(filter), "片库筛选设置无效");
        }
        Object profile = value.get("preferredQuality");
        require(profile == JSONObject.NULL || profile instanceof JSONObject, "默认清晰度格式无效");
        if (profile instanceof JSONObject) readProfile((JSONObject) profile);
    }

    private static void preflight(String text) {
        require(text != null && text.length() <= MAX_BYTES && text.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES,
            "备份超过 4 MiB 上限");
        require(!text.trim().isEmpty(), "备份文件为空");
        int depth = 0; boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == '"') quoted = false;
            } else if (value == '"') quoted = true;
            else if (value == '{' || value == '[') { depth++; require(depth <= 12, "备份结构嵌套过深"); }
            else if (value == '}' || value == ']') { depth--; require(depth >= 0, "备份结构不完整"); }
        }
        require(!quoted && depth == 0, "备份结构不完整");
    }

    private static void keys(JSONObject object, String... allowed) throws JSONException {
        Set<String> wanted = new HashSet<>(Arrays.asList(allowed));
        require(object.length() == wanted.size(), "备份含缺失或不支持的字段");
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) require(wanted.contains(keys.next()), "备份含不支持的字段");
    }
    private static JSONObject object(JSONObject value, String field) throws JSONException {
        Object found = value.get(field); require(found instanceof JSONObject, "备份对象字段格式无效"); return (JSONObject) found;
    }
    private static JSONObject object(JSONArray value, int index) throws JSONException {
        Object found = value.get(index); require(found instanceof JSONObject, "备份列表条目格式无效"); return (JSONObject) found;
    }
    private static JSONArray array(JSONObject value, String field, int max) throws JSONException {
        Object found = value.get(field); require(found instanceof JSONArray, "备份列表格式无效");
        JSONArray array = (JSONArray) found; require(array.length() <= max, "备份条目数量超过上限"); return array;
    }
    private static String id(JSONObject value, String field, boolean empty) throws JSONException {
        String id = string(value, field, 128, empty);
        require((empty && id.isEmpty()) || id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"), "备份 ID 格式无效"); return id;
    }
    private static String string(JSONObject value, String field, int max, boolean empty) throws JSONException {
        Object found = value.get(field); require(found instanceof String, "备份文字字段格式无效");
        String text = (String) found;
        require(text.length() <= max && (empty || !text.trim().isEmpty()), "备份文字长度无效");
        for (int i = 0; i < text.length(); i++) require(text.charAt(i) >= 32 && text.charAt(i) != 127, "备份文字含控制字符");
        return text;
    }
    private static long integer(JSONObject value, String field, long min, long max) throws JSONException {
        Object found = value.get(field);
        require(found instanceof Number && found.toString().matches("0|[1-9][0-9]*"), "备份整数格式无效");
        try { long number = Long.parseLong(found.toString()); require(number >= min && number <= max, "备份整数超出范围"); return number; }
        catch (NumberFormatException ignored) { throw new IllegalArgumentException("备份整数超出范围"); }
    }
    private static double number(JSONObject value, String field) throws JSONException {
        Object found = value.get(field); require(found instanceof Number, "备份数字格式无效");
        double number = ((Number) found).doubleValue(); require(!Double.isNaN(number) && !Double.isInfinite(number), "备份数字无效"); return number;
    }
    private static boolean bool(JSONObject value, String field) throws JSONException {
        Object found = value.get(field); require(found instanceof Boolean, "备份开关格式无效"); return (Boolean) found;
    }
    private static long latestTimestamp() { return System.currentTimeMillis() + 24L * 60 * 60 * 1000; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
