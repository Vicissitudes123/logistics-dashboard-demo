package com.animeshelf.app;

import com.animeshelf.app.Models.Episode;
import com.animeshelf.app.Models.Quality;
import com.animeshelf.app.Models.Series;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.List;

/** Offline parser regression checks. Run with a test-only org.json jar, no account needed. */
public final class ParserSelfTest {
    private static int assertions;
    private static final BiliClient CLIENT = new BiliClient("");

    public static void main(String[] args) throws Exception {
        actualFormatsAndPairedAudio();
        protectedAndMissingStreams();
        previewAndBusinessFlags();
        progressiveAndSegmentedFiles();
        cdnCandidatesAndPersistence();
        confirmedLoginState();
        trustedHostsAndCookies();
        persistenceRoundTrip();
        serverErrorsRemainErrors();
        System.out.println("ParserSelfTest passed: " + assertions + " assertions (offline fixtures)");
    }

    private static void actualFormatsAndPairedAudio() throws Exception {
        JSONObject data = json("""
                {"accept_quality":[120,80,64,32],"accept_description":["4K","1080P","720P","480P"],
                 "dash":{"audio":[
                   {"id":30216,"bandwidth":64000,"baseUrl":"https://cdn.example/audio-low.m4s"},
                   {"id":30280,"bandwidth":192000,"base_url":"https://cdn.example/audio-high.m4s"}],
                   "video":[
                     {"id":64,"codecid":7,"baseUrl":"https://cdn.example/720.m4s"},
                     {"id":80,"codecid":7,"baseUrl":"https://cdn.example/1080.m4s"},
                     {"id":80,"codecid":12,"baseUrl":"https://cdn.example/1080-hevc.m4s"},
                     {"id":120,"codecid":12,"is_drm":true,"baseUrl":"https://cdn.example/protected.m4s"}]}}
                """);
        List<Quality> qualities = parse(data);
        check(qualities.size() == 3, "Only actual clear video representations should be offered");
        check(qualities.get(0).qn == 80, "Actual highest quality first, not advertised 4K");
        check(qualities.get(0).id.equals("80:7:30280"), "Stable qn/codec/audio identity");
        check(qualities.get(1).id.equals("80:12:30280"), "Separate codecs retained");
        for (Quality quality : qualities) {
            check(quality.audioUrl.endsWith("audio-high.m4s"), "Best clear audio is paired with every video");
            check(quality.headers.containsKey("Referer") && quality.headers.containsKey("User-Agent"), "CDN headers present");
            check(!quality.headers.containsKey("Cookie"), "Account cookie never reaches media headers");
            check(quality.qn != 32 && quality.qn != 120, "Advertised but absent/DRM resolutions are excluded");
        }
        data.getJSONObject("dash").getJSONArray("video").getJSONObject(0)
                .put("baseUrl", "https://cdn.example/720.m4s?expires=other");
        check(parse(data).get(2).id.equals("64:7:30280"), "Signed URL renewal does not change quality identity");
    }

    private static void protectedAndMissingStreams() throws Exception {
        expectFailure("DRM", () -> parse(json("""
                {"dash":{"audio":[{"id":30280,"baseUrl":"https://cdn.example/audio"}],
                  "video":[{"id":80,"codecid":7,"widevine_pssh":"abc","baseUrl":"https://cdn.example/video"}]}}
                """)));
        expectFailure("音轨", () -> parse(json("""
                {"dash":{"audio":[],"video":[{"id":80,"codecid":7,"baseUrl":"https://cdn.example/video"}]}}
                """)));
        expectFailure("未返回可用媒体流", () -> parse(json("""
                {"accept_quality":[80,64,32],"accept_description":["1080P","720P","480P"],"dash":{"video":[],"audio":[]}}
                """)));
        expectFailure("未返回可用媒体流", () -> parse(json("{}")));
        List<Quality> safeAudio = parse(json("""
                {"dash":{"audio":[
                    {"id":30280,"bandwidth":192000,"is_drm":true,"baseUrl":"https://cdn.example/protected"},
                    {"id":30216,"bandwidth":64000,"baseUrl":"https://cdn.example/clear"}],
                  "video":[{"id":80,"codecid":7,"baseUrl":"https://cdn.example/video"}]}}
                """));
        check(safeAudio.get(0).id.endsWith(":30216"), "Encrypted audio excluded even when higher quality");
    }

    private static void previewAndBusinessFlags() throws Exception {
        expectFailure("试看", () -> restrictions(json("{\"is_preview\":1}")));
        expectFailure("试看", () -> restrictions(json("{\"business\":{\"is_preview\":true}}")));
        expectFailure("DRM", () -> restrictions(json("{\"is_drm\":true}")));
        expectFailure("DRM", () -> restrictions(json("{\"play_view_business\":{\"drm_tech_type\":2}}")));
        restrictions(json("{\"is_drm\":false,\"drm_tech_type\":0,\"is_preview\":0}"));
        check(true, "Explicit clear/non-preview flags accepted");
    }

    private static void progressiveAndSegmentedFiles() throws Exception {
        List<Quality> qualities = parse(json("""
                {"quality":80,"durl":[{"url":"https://cdn.example/complete.mp4"}]}
                """));
        check(qualities.size() == 1, "One progressive file accepted");
        check(qualities.get(0).audioUrl.isEmpty(), "Muxed source does not acquire a fake second track");
        check(qualities.get(0).id.equals("80:progressive:0"), "Progressive stable ID");
        expectFailure("多段旧格式", () -> parse(json("""
                {"quality":80,"durl":[{"url":"https://cdn.example/one.flv"},{"url":"https://cdn.example/two.flv"}]}
                """)));
    }

    private static void cdnCandidatesAndPersistence() throws Exception {
        List<Quality> qualities = parse(json("""
                {"dash":{"audio":[{"id":30280,"bandwidth":192000,
                    "baseUrl":"https://user:password@invalid.example/audio",
                    "backup_url":["https://audio-one.example/a.m4s?token=fixture","https://audio-two.example/a.m4s?token=fixture"]}],
                  "video":[{"id":80,"codecid":7,
                    "baseUrl":"http://legacy.example/v.m4s?token=fixture",
                    "base_url":"https://primary.example/v.m4s?token=a%2Fb&part=1",
                    "url":"https://other-primary.example/v.m4s?token=fixture",
                    "backupUrl":["https://camel.example/v.m4s?token=fixture","//relative.example/v.m4s?token=fixture"],
                    "backup_url":["https://primary.example/v.m4s?token=a%2Fb&part=1",
                      "https://port.example:8443/v.m4s?token=fixture","file:///invalid",null,{},
                      "https://user@invalid.example/v.m4s","https://invalid.example/v.m4s#fragment"]},
                    {"id":80,"codecid":7,"baseUrl":"https://repeated-track.example/v.m4s?token=fixture"}]}}
                """));
        check(qualities.size() == 1, "Repeated representation contributes candidates rather than duplicate quality entries");
        Quality quality = qualities.get(0);
        check(quality.videoUrl.equals("https://primary.example/v.m4s?token=a%2Fb&part=1"), "An exact HTTPS alternative is preferred over rewriting HTTP");
        check(quality.videoUrls.size() == 7, "All valid alias and backup candidates retained and deduplicated");
        check(quality.videoUrls.get(1).contains("other-primary.example"), "Second base URL spelling is not discarded");
        check(quality.videoUrls.contains("https://port.example:8443/v.m4s?token=fixture"), "Valid HTTPS non-default CDN port retained");
        check(quality.videoUrls.get(4).contains("port.example"), "Exact HTTPS candidates precede upgraded legacy HTTP");
        check(quality.videoUrls.get(5).equals("https://legacy.example/v.m4s?token=fixture"), "Legacy HTTP upgrade is only a later candidate");
        check(quality.videoUrls.get(6).contains("repeated-track.example"), "Repeated stream source preserved");
        check(quality.audioUrls.size() == 2 && quality.audioUrl.contains("audio-one.example"), "Backup-only audio is a usable track");
        check(quality.headers.get("User-Agent").equals(BiliClient.USER_AGENT)
                && quality.headers.get("Referer").equals(BiliClient.REFERER), "API and media use shared request identity");
        Quality loaded = Quality.fromJson(new JSONObject(quality.toJson().toString()));
        check(loaded.videoUrls.equals(quality.videoUrls) && loaded.audioUrls.equals(quality.audioUrls), "Every CDN candidate persists across process recreation");
        check(loaded.videoUrl.equals(quality.videoUrl) && loaded.audioUrl.equals(quality.audioUrl), "Legacy primary URL fields remain consistent");
        Quality legacy = Quality.fromJson(json("{\"videoUrl\":\"https://legacy.example/video\",\"audioUrl\":\"https://legacy.example/audio\"}"));
        check(legacy.videoUrls.size() == 1 && legacy.audioUrls.size() == 1, "Old stored single-URL downloads gain candidates");
        Quality arraysOnly = Quality.fromJson(json("{\"videoUrls\":[\"file:///invalid\",\"https://backup.example/video\",\"https://backup.example/video\"]}"));
        check(arraysOnly.videoUrl.equals("https://backup.example/video") && arraysOnly.videoUrls.size() == 1, "Candidate-only records restore a safe primary");
        check(Quality.httpsMediaUrl("https://example.test/line\nbreak").isEmpty(), "Control characters cannot enter a CDN URL");
        check(Quality.httpsMediaUrl("https://example.test:99999/video").isEmpty(), "Invalid CDN ports rejected");
        Quality progressive = parse(json("{\"quality\":80,\"durl\":[{\"url\":\"https://first.example/video.mp4\",\"backup_url\":[\"https://backup.example/video.mp4\"]}]}" )).get(0);
        check(progressive.videoUrls.size() == 2 && progressive.audioUrls.isEmpty(), "Progressive backup URLs are retained without a fake audio track");
    }

    private static void confirmedLoginState() throws Exception {
        check(login(json("{\"code\":-101,\"message\":\"账号未登录\"}")).isEmpty(), "Explicit nav unauthenticated code returns logged out");
        check(login(json("{\"code\":0,\"data\":{\"isLogin\":false}}")).isEmpty(), "Explicit false login flag returns logged out");
        check(login(json("{\"code\":0,\"data\":{\"isLogin\":true,\"uname\":\"测试用户\"}}")).equals("测试用户"), "Only nav confirmation produces account name");
        JSONObject account = json("{\"code\":0,\"data\":{\"isLogin\":true}}");
        check(login(account).equals("B站账号"), "Confirmed login without a nickname still succeeds");
        account.getJSONObject("data").put("uname", "  昵称\n\t\u202e测试  ");
        check(login(account).equals("昵称测试"), "Account name removes control and bidi formatting characters");
        account.getJSONObject("data").put("uname", "😀".repeat(100));
        String shortened = login(account);
        check(shortened.codePointCount(0, shortened.length()) == 80 && !Character.isHighSurrogate(shortened.charAt(shortened.length() - 1)), "Account name is capped without splitting emoji");
        expectFailure("未返回明确", () -> login(json("{\"code\":0,\"data\":{\"uname\":\"Unverified avatar\"}}")));
        expectFailure("无法识别", () -> login(json("{\"code\":0,\"data\":{\"isLogin\":\"true\"}}")));
        expectFailure("-352", () -> login(json("{\"code\":-352,\"message\":\"需要验证\"}")));
    }

    private static void trustedHostsAndCookies() throws Exception {
        check(trusted("http://www.bilibili.com/video/BV1234567890").getProtocol().equals("https"), "Input upgraded to HTTPS before authentication");
        check(trusted("https://b23.tv/abc").getHost().equals("b23.tv"), "B23 short link accepted");
        check(trusted("https://api.bilibili.com/x/player/playurl").getHost().equals("api.bilibili.com"), "Exact Bilibili subdomain accepted");
        for (String address : new String[]{
                "https://bilibili.com.evil.example/video/BV1234567890",
                "https://evilbilibili.com/video/BV1234567890",
                "https://www.bilibili.com@evil.example/video/BV1234567890",
                "https://user:pass@www.bilibili.com/video/BV1234567890",
                "https://b23.tv.evil.example/abc",
                "https://www.bilibili.com:8443/video/BV1234567890",
                "file:///etc/passwd"}) {
            expectFailure("仅接受", () -> trusted(address));
        }
        expectFailure("登录状态格式无效", () -> new BiliClient("SESSDATA=example\r\nX-Evil: yes"));
        expectFailure("仅接受", () -> CLIENT.resolve("分享 https://evil.example/video/BV1234567890"));
        Method open = BiliClient.class.getDeclaredMethod("open", URL.class);
        open.setAccessible(true);
        BiliClient authenticated = new BiliClient("SESSDATA=test-fixture");
        java.net.HttpURLConnection bili = (java.net.HttpURLConnection) invoke(open, authenticated, trusted("https://api.bilibili.com/test"));
        java.net.HttpURLConnection shortLink = (java.net.HttpURLConnection) invoke(open, authenticated, trusted("https://b23.tv/test"));
        // Opening a URLConnection object does not contact the network until connect/read.
        check("SESSDATA=test-fixture".equals(bili.getRequestProperty("Cookie")), "Session attached only to Bilibili API");
        check(shortLink.getRequestProperty("Cookie") == null, "B23 requests never receive login cookies");
        check(!shortLink.getInstanceFollowRedirects(), "Redirects require explicit trusted-host checking");
        bili.disconnect();
        shortLink.disconnect();
    }

    private static void persistenceRoundTrip() throws Exception {
        Series series = new Series();
        series.id = "ss123";
        series.title = "测试番剧";
        Episode episode = new Episode();
        episode.id = "ep456";
        episode.title = "第 1 集 · 标题";
        episode.bvid = "BV1234567890";
        episode.aid = 123456789012L;
        episode.cid = 223456789012L;
        episode.seriesId = series.id;
        episode.seriesTitle = series.title;
        episode.index = 1;
        episode.bangumi = true;
        episode.localUri = "content://com.example.documents/video/1";
        series.episodes.add(episode);
        Series loaded = Series.fromJson(new JSONObject(series.toJson().toString()));
        check(loaded.title.equals(series.title) && loaded.episodes.size() == 1, "Series JSON round trip");
        check(loaded.episodes.get(0).aid == episode.aid && loaded.episodes.get(0).cid == episode.cid, "64-bit IDs preserved");
        check(loaded.episodes.get(0).localUri.equals(episode.localUri), "Persistently granted local URI preserved");
        check(loaded.episodes.get(0).toJson().toString().equals(episode.toJson().toString()), "Every episode field round trips");
        check(Series.fromJson(new JSONObject()).episodes.isEmpty(), "Missing arrays remain safe empty collections");
        check(Episode.fromJson(null).localUri.equals(""), "Missing optional fields have safe defaults");
        Quality quality = new Quality();
        quality.id = "80:7:30280";
        quality.label = "1080P · AVC";
        quality.qn = 80;
        quality.videoUrl = "https://cdn.example/video";
        quality.audioUrl = "https://cdn.example/audio";
        quality.headers.put("Referer", BiliClient.REFERER);
        quality.headers.put("User-Agent", BiliClient.USER_AGENT);
        quality.headers.put("Cookie", "SESSDATA=must-not-persist");
        quality.headers.put("Authorization", "must-not-persist");
        JSONObject serialized = quality.toJson();
        check(!serialized.toString().contains("must-not-persist"), "Sensitive headers never serialized");
        Quality restored = Quality.fromJson(serialized);
        check(restored.id.equals(quality.id) && restored.qn == 80 && restored.audioUrl.equals(quality.audioUrl), "Quality identity and both track URLs round trip");
        check(restored.headers.size() == 2, "Only public media headers restored");
        serialized.getJSONObject("headers").put("Cookie", "injected");
        check(!Quality.fromJson(serialized).headers.containsKey("Cookie"), "Legacy/injected cookies are not loaded from storage");
    }

    private static void serverErrorsRemainErrors() throws Exception {
        Method method = BiliClient.class.getDeclaredMethod("checkedPayload", JSONObject.class, String.class);
        method.setAccessible(true);
        expectFailure("-101", () -> invoke(method, CLIENT, json("{\"code\":-101,\"message\":\"账号未登录\"}"), "测试"));
        expectFailure("缺少状态码", () -> invoke(method, CLIENT, json("{\"data\":{}}"), "测试"));
        expectFailure("未返回有效数据", () -> invoke(method, CLIENT, json("{\"code\":0,\"data\":null}"), "测试"));
        JSONObject payload = (JSONObject) invoke(method, CLIENT, json("{\"code\":0,\"result\":{\"quality\":80}}"), "测试");
        check(payload.optInt("quality") == 80, "Successful payload returned");
    }

    @SuppressWarnings("unchecked")
    private static List<Quality> parse(JSONObject data) throws Exception {
        Method method = BiliClient.class.getDeclaredMethod("parseQualities", JSONObject.class);
        method.setAccessible(true);
        return (List<Quality>) invoke(method, CLIENT, data);
    }

    private static void restrictions(JSONObject data) throws Exception {
        Method method = BiliClient.class.getDeclaredMethod("checkPlaybackRestrictions", JSONObject.class);
        method.setAccessible(true);
        invoke(method, null, data);
    }

    private static String login(JSONObject data) throws Exception {
        Method method = BiliClient.class.getDeclaredMethod("loginName", JSONObject.class);
        method.setAccessible(true);
        return (String) invoke(method, CLIENT, data);
    }

    private static URL trusted(String address) throws Exception {
        Method method = BiliClient.class.getDeclaredMethod("trustedUrl", String.class);
        method.setAccessible(true);
        return (URL) invoke(method, null, address);
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) throws Exception {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new AssertionError(cause);
        }
    }

    private static JSONObject json(String value) { return new JSONObject(value); }

    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }

    private static void expectFailure(String expectedText, Throwing action) throws Exception {
        assertions++;
        try { action.run(); }
        catch (IOException | IllegalArgumentException error) {
            if (error.getMessage() != null && error.getMessage().contains(expectedText)) return;
            throw new AssertionError("Expected error containing '" + expectedText + "', got: " + error, error);
        }
        throw new AssertionError("Expected failure containing '" + expectedText + "'");
    }

    private interface Throwing { void run() throws Exception; }
}
