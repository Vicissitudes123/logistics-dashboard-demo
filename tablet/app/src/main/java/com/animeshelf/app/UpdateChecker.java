package com.animeshelf.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Fetches public update metadata only. Downloads and installation remain explicit user actions. */
public final class UpdateChecker {
    private static final String DOWNLOAD_ROOT = "https://raw.githubusercontent.com/Vicissitudes123/"
            + "logistics-dashboard-demo/anime-shelf-apk/downloads/tablet/";
    public static final String MANIFEST_URL = DOWNLOAD_ROOT + "latest.json";
    public static final String PACKAGE_NAME = "com.animeshelf.app";
    static final int MAX_MANIFEST_BYTES = 64 * 1024;
    static final long MAX_ARTIFACT_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long MAX_READ_NANOS = 10_000_000_000L;
    private static final Set<String> FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "schemaVersion", "versionCode", "versionName", "packageName", "minSdk", "apkUrl", "zipUrl",
            "apkSha256", "zipSha256", "apkBytes", "zipBytes", "notes", "sourceCommit")));

    private UpdateChecker() { }

    /** Validated immutable metadata; hashes describe the release, not a verified local download. */
    public static final class Release {
        public final int schemaVersion, versionCode, minSdk;
        public final String versionName, packageName, apkUrl, zipUrl, apkSha256, zipSha256, sourceCommit;
        public final long apkBytes, zipBytes;
        public final List<String> notes;
        public final boolean isNewer, isCompatible;

        private Release(JSONObject json, int currentVersion, int sdk, List<String> releaseNotes)
                throws JSONException {
            schemaVersion = json.getInt("schemaVersion");
            versionCode = json.getInt("versionCode"); versionName = json.getString("versionName");
            packageName = json.getString("packageName"); minSdk = json.getInt("minSdk");
            apkUrl = json.getString("apkUrl"); zipUrl = json.getString("zipUrl");
            apkSha256 = json.getString("apkSha256"); zipSha256 = json.getString("zipSha256");
            apkBytes = json.getLong("apkBytes"); zipBytes = json.getLong("zipBytes");
            sourceCommit = json.getString("sourceCommit");
            notes = Collections.unmodifiableList(new ArrayList<>(releaseNotes));
            isNewer = versionCode > currentVersion; isCompatible = sdk >= minSdk;
        }
    }

    /** Messages contain only local text and validated HTTP status numbers. */
    public static final class UpdateException extends IOException {
        private UpdateException(String message) { super(message); }
    }

    interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }

    /** Call on a background thread. Uses one fixed HTTPS endpoint without account cookies. */
    public static Release check(int currentVersionCode, int sdkInt) throws IOException {
        return check(currentVersionCode, sdkInt, url -> (HttpURLConnection) url.openConnection());
    }

    static Release check(int currentVersionCode, int sdkInt, ConnectionFactory connections) throws IOException {
        validateCurrent(currentVersionCode, sdkInt);
        // A process-wide Java CookieHandler could attach unrelated cookies implicitly.
        // Android WebView's CookieManager is separate and is never read here.
        require(CookieHandler.getDefault() == null, "当前网络环境无法保证无账号信息检查更新，请稍后重试");
        HttpURLConnection connection = null;
        try {
            connection = connections.open(new URL(MANIFEST_URL));
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "AnimeShelf-UpdateChecker/1");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Cache-Control", "no-cache");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                if (status >= 100 && status <= 599)
                    throw new UpdateException("检查更新失败（HTTP " + status + "），请稍后重试");
                throw new UpdateException("更新服务器响应异常，请稍后重试");
            }
            require(connection.getContentLengthLong() <= MAX_MANIFEST_BYTES, "更新信息超过大小限制");
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = readBounded(input);
                String json = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
                return parse(json, currentVersionCode, sdkInt);
            }
        } catch (UpdateException exception) {
            throw exception;
        } catch (CharacterCodingException exception) {
            throw new UpdateException("更新信息不是有效的 UTF-8 文本");
        } catch (IOException | RuntimeException exception) {
            // Transport exceptions may contain a URL or a proxy message; do not show their raw text.
            throw new UpdateException("无法连接更新服务器，请检查网络后重试");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Strict schema validation, independent of Android and network state. */
    public static Release parse(String json, int currentVersionCode, int sdkInt) throws IOException {
        validateCurrent(currentVersionCode, sdkInt);
        require(json != null && !json.isEmpty() && json.length() <= MAX_MANIFEST_BYTES,
                "更新信息为空或超过大小限制");
        require(json.getBytes(StandardCharsets.UTF_8).length <= MAX_MANIFEST_BYTES, "更新信息超过大小限制");
        try {
            new StrictJson(json).validate();
            JSONObject object = new JSONObject(json);
            require(object.length() == FIELDS.size(), "更新信息字段不完整或包含未知字段");
            for (Iterator<String> keys = object.keys(); keys.hasNext(); )
                require(FIELDS.contains(keys.next()), "更新信息包含未知字段");
            require(integer(object, "schemaVersion", 1, Integer.MAX_VALUE) == 1, "暂不支持此更新信息版本");
            integer(object, "versionCode", 1, Integer.MAX_VALUE);
            String version = string(object, "versionName", 64);
            require(version.matches("[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?"),
                    "更新版本号格式无效");
            require(PACKAGE_NAME.equals(string(object, "packageName", 128)), "更新信息不属于番匣应用");
            integer(object, "minSdk", 26, 1000);
            String apk = string(object, "apkUrl", 512), zip = string(object, "zipUrl", 512);
            require(validArtifactUrl(apk, version, false) && validArtifactUrl(zip, version, true),
                    "更新下载地址不是本项目的可信地址");
            require(string(object, "apkSha256", 64).matches("[a-fA-F0-9]{64}")
                    && string(object, "zipSha256", 64).matches("[a-fA-F0-9]{64}"), "更新文件校验值无效");
            integer(object, "apkBytes", 1, MAX_ARTIFACT_BYTES);
            integer(object, "zipBytes", 1, MAX_ARTIFACT_BYTES);
            require(string(object, "sourceCommit", 40).matches("[a-fA-F0-9]{40}"), "更新来源版本无效");
            Object rawNotes = object.get("notes");
            require(rawNotes instanceof JSONArray, "更新说明格式无效");
            JSONArray notes = (JSONArray) rawNotes;
            require(notes.length() <= 32, "更新说明条目过多");
            ArrayList<String> values = new ArrayList<>();
            int characters = 0;
            for (int i = 0; i < notes.length(); i++) {
                Object note = notes.get(i);
                require(note instanceof String && safeText((String) note, 1000), "更新说明内容无效");
                String value = (String) note;
                characters += value.length(); values.add(value);
            }
            require(characters <= 12000, "更新说明过长");
            return new Release(object, currentVersionCode, sdkInt, values);
        } catch (UpdateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new UpdateException("更新信息格式损坏，请稍后重试");
        }
    }

    private static boolean validArtifactUrl(String value, String version, boolean zip) {
        String expected = DOWNLOAD_ROOT + "anime-shelf-" + version + (zip ? "-install.zip" : ".apk");
        if (!expected.equals(value)) return false;
        try {
            URI uri = new URI(value);
            return "https".equals(uri.getScheme()) && "raw.githubusercontent.com".equals(uri.getHost())
                    && uri.getUserInfo() == null && uri.getPort() == -1
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && uri.getRawPath().equals(uri.getPath());
        } catch (Exception exception) { return false; }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        long started = System.nanoTime();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (true) {
            require(!Thread.currentThread().isInterrupted(), "检查更新已取消");
            require(System.nanoTime() - started <= MAX_READ_NANOS, "读取更新信息超时，请稍后重试");
            int count = input.read(buffer);
            if (count < 0) break;
            require(output.size() + count <= MAX_MANIFEST_BYTES, "更新信息超过大小限制");
            output.write(buffer, 0, count);
        }
        require(System.nanoTime() - started <= MAX_READ_NANOS, "读取更新信息超时，请稍后重试");
        return output.toByteArray();
    }

    private static void validateCurrent(int version, int sdk) throws UpdateException {
        require(version >= 0 && sdk > 0, "当前应用或系统版本信息无效");
    }

    private static long integer(JSONObject object, String key, long minimum, long maximum)
            throws JSONException, UpdateException {
        Object value = object.get(key);
        require(value instanceof Integer || value instanceof Long, "更新信息数字字段格式无效");
        long number = ((Number) value).longValue();
        require(number >= minimum && number <= maximum, "更新信息数字字段超出范围");
        return number;
    }

    private static String string(JSONObject object, String key, int maximum) throws JSONException, UpdateException {
        Object value = object.get(key);
        require(value instanceof String && safeText((String) value, maximum), "更新信息文本字段格式无效");
        return (String) value;
    }

    private static boolean safeText(String text, int maximum) {
        if (text.isEmpty() || text.length() > maximum || text.trim().isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029'
                    || (c >= '\u202a' && c <= '\u202e') || (c >= '\u2066' && c <= '\u2069')) return false;
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    private static void require(boolean valid, String message) throws UpdateException {
        if (!valid) throw new UpdateException(message);
    }

    /** Android's JSONTokener accepts non-JSON syntax and duplicate keys; reject both before parsing. */
    private static final class StrictJson {
        private final String source;
        private int position;
        StrictJson(String source) { this.source = source; }
        void validate() throws JSONException, UpdateException {
            whitespace(); require(peek() == '{', "更新信息必须是 JSON 对象");
            value(0); whitespace(); require(position == source.length(), "更新信息包含多余内容");
        }
        private void value(int depth) throws JSONException, UpdateException {
            require(depth <= 3, "更新信息嵌套过深");
            whitespace(); char current = peek();
            if (current == '"') { quoted(); return; }
            if (current == '{') {
                position++; whitespace(); Set<String> keys = new HashSet<>();
                if (take('}')) return;
                do {
                    whitespace(); String key = quoted();
                    require(keys.add(key), "更新信息包含重复字段");
                    whitespace(); require(take(':'), "更新信息 JSON 格式无效");
                    value(depth + 1); whitespace();
                    if (take('}')) return;
                    require(take(','), "更新信息 JSON 格式无效");
                } while (true);
            }
            if (current == '[') {
                position++; whitespace(); if (take(']')) return;
                do {
                    value(depth + 1); whitespace(); if (take(']')) return;
                    require(take(','), "更新信息 JSON 格式无效");
                } while (true);
            }
            int start = position;
            while (position < source.length() && ",]} \t\r\n".indexOf(source.charAt(position)) < 0) position++;
            String token = source.substring(start, position);
            require(token.matches("(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)"),
                    "更新信息 JSON 格式无效");
        }
        private String quoted() throws JSONException, UpdateException {
            int start = position;
            require(take('"'), "更新信息 JSON 字符串格式无效");
            while (position < source.length()) {
                char c = source.charAt(position++);
                if (c == '"') return (String) new JSONTokener(source.substring(start, position)).nextValue();
                require(c >= 0x20, "更新信息 JSON 字符串格式无效");
                if (c == '\\') {
                    require(position < source.length(), "更新信息 JSON 字符串格式无效");
                    char escaped = source.charAt(position++);
                    if (escaped == 'u') {
                        require(position + 4 <= source.length(), "更新信息 JSON 转义无效");
                        for (int i = 0; i < 4; i++)
                            require(Character.digit(source.charAt(position++), 16) >= 0, "更新信息 JSON 转义无效");
                    } else require("\"\\/bfnrt".indexOf(escaped) >= 0, "更新信息 JSON 转义无效");
                }
            }
            throw new UpdateException("更新信息 JSON 字符串不完整");
        }
        private char peek() { return position < source.length() ? source.charAt(position) : 0; }
        private boolean take(char c) { if (peek() != c) return false; position++; return true; }
        private void whitespace() {
            while (position < source.length() && " \t\r\n".indexOf(source.charAt(position)) >= 0) position++;
        }
    }
}
