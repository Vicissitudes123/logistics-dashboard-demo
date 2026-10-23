package com.animeshelf.app;

import com.animeshelf.app.Models.Episode;
import com.animeshelf.app.Models.Quality;
import com.animeshelf.app.Models.Series;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bilibili's existing web responses; no proxy, DRM decryption or privilege bypass. */
public final class BiliClient {
    /** Safe, locally authored explanation. Transport exceptions retain their original types. */
    public static final class ClientException extends IOException {
        private static final long serialVersionUID = 1L;

        private ClientException(String message) { super(message); }
    }

    /** Preserve the existing API/login identity independently of the media request policy. */
    public static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36";
    /** Web playurl responses produce PC media URLs; their CDN requests use a desktop browser. */
    public static final String MEDIA_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    public static final String REFERER = "https://www.bilibili.com/";
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final Pattern SHARE_URL = Pattern.compile("https?://[^\\s<>\\\"'，。；）】]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern BVID = Pattern.compile("(?i)(BV[0-9a-z]{10})(?![0-9a-z])");
    private static final Pattern EPISODE_ID = Pattern.compile("(?i)(?:^|/)(ep[0-9]+)(?:$|[/?#])");
    private static final Pattern SEASON_ID = Pattern.compile("(?i)(?:^|/)(ss[0-9]+)(?:$|[/?#])");
    private final String cookie;

    /** Pass the cookie returned by Android CookieManager for https://api.bilibili.com. */
    public BiliClient(String cookie) {
        this.cookie = cookie == null ? "" : cookie;
        if (this.cookie.indexOf('\r') >= 0 || this.cookie.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("登录状态格式无效，请重新登录");
        }
    }

    /** Empty means explicitly logged out. Request/verification errors are not login results. */
    public String verifyLogin() throws Exception {
        return loginName(getJson("https://api.bilibili.com/x/web-interface/nav"));
    }

    private String loginName(JSONObject response) throws IOException {
        if (response.optInt("code", Integer.MIN_VALUE) == -101) return "";
        JSONObject info = checkedPayload(response, "验证 B 站登录状态失败");
        if (!info.has("isLogin") || info.isNull("isLogin")) {
            throw new ClientException("登录验证接口未返回明确的登录状态，请稍后重试");
        }
        Object loggedIn = info.opt("isLogin");
        if (!(loggedIn instanceof Boolean)) {
            throw new ClientException("登录验证接口返回了无法识别的登录状态，请稍后重试");
        }
        if (!((Boolean) loggedIn)) return "";
        String name = info.optString("uname", "").replaceAll("[\\p{Cntrl}\\p{Cf}]", "").trim();
        if (name.isEmpty()) return "B站账号";
        int length = name.codePointCount(0, name.length());
        return length > 80 ? name.substring(0, name.offsetByCodePoints(0, 80)) : name;
    }

    /** Accepts a B站 share message, an HTTPS link, ep/ss ID, or BV ID. Run off the UI thread. */
    public Series resolve(String input) throws Exception {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) throw new ClientException("请粘贴 B 站番剧、单集或 BV 视频链接");
        Matcher share = SHARE_URL.matcher(value);
        if (share.find()) {
            URL url = trustedUrl(stripTrailingPunctuation(share.group()));
            if (isShortHost(url.getHost())) url = followShortLink(url);
            value = url.toExternalForm();
        } else if (value.matches("(?i)^(ep|ss)[0-9]+$") || value.matches("(?i)^BV[0-9a-z]{10}$")) {
            // Explicit IDs are safe to place into our fixed API paths.
        } else {
            throw new ClientException("链接格式不支持；请使用 bilibili.com、b23.tv 链接或 ep / ss / BV 编号");
        }

        Matcher episodeMatch = EPISODE_ID.matcher(value);
        if (episodeMatch.find()) return loadSeason("ep_id=" + episodeMatch.group(1).substring(2));
        Matcher seasonMatch = SEASON_ID.matcher(value);
        if (seasonMatch.find()) return loadSeason("season_id=" + seasonMatch.group(1).substring(2));
        Matcher videoMatch = BVID.matcher(value);
        if (videoMatch.find()) {
            String bvid = "BV" + videoMatch.group(1).substring(2);
            return loadVideo(bvid);
        }
        throw new ClientException("未找到可识别的番剧或视频编号，请从视频播放页复制分享链接");
    }

    /** Fetch only a missing cover for an old catalogue entry; never replaces its metadata or episodes. */
    public String resolveCover(Series series) throws Exception {
        String endpoint = coverEndpoint(series);
        if (endpoint.isEmpty()) return "";
        JSONObject info = checkedPayload(getJson(endpoint), "获取封面失败");
        return coverFromInfo(info);
    }

    private static String coverEndpoint(Series series) {
        if (series == null) return "";
        if (series.id != null && series.id.matches("ss[1-9][0-9]{0,18}"))
            return "https://api.bilibili.com/pgc/view/web/season?season_id=" + series.id.substring(2);
        if (series.id != null && series.id.matches("BV[0-9A-Za-z]{10}"))
            return "https://api.bilibili.com/x/web-interface/view?bvid=" + series.id;
        if (series.episodes != null) for (Episode episode : series.episodes) {
            if (episode == null || (episode.localUri != null && !episode.localUri.isEmpty())) continue;
            if (episode.bangumi && episode.id != null && episode.id.matches("ep[1-9][0-9]{0,18}"))
                return "https://api.bilibili.com/pgc/view/web/season?ep_id=" + episode.id.substring(2);
            if (episode.bvid != null && episode.bvid.matches("BV[0-9A-Za-z]{10}"))
                return "https://api.bilibili.com/x/web-interface/view?bvid=" + episode.bvid;
            if (episode.aid > 0)
                return "https://api.bilibili.com/x/web-interface/view?aid=" + episode.aid;
        }
        return "";
    }

    private static String coverFromInfo(JSONObject info) {
        for (String field : new String[]{"cover", "pic", "horizontal_cover", "square_cover"}) {
            String cover = info.optString(field, "");
            if (cover.startsWith("//")) cover = "https:" + cover;
            else if (cover.regionMatches(true, 0, "http://", 0, 7)) cover = "https://" + cover.substring(7);
            cover = Models.safeCoverUrl(cover);
            if (!cover.isEmpty()) return cover;
        }
        return "";
    }

    /** Returns only actual non-DRM media streams available to the current session. */
    public List<Quality> qualities(Episode episode) throws Exception {
        if (episode == null) throw new ClientException("缺少集数信息");
        if (!episode.localUri.isEmpty()) throw new ClientException("本地视频无需获取在线清晰度");
        String base;
        if (episode.bangumi) {
            if (!episode.id.matches("ep[0-9]+")) throw new ClientException("番剧集数编号无效");
            base = "https://api.bilibili.com/pgc/player/web/playurl?ep_id=" + episode.id.substring(2);
            if (episode.cid > 0) base += "&cid=" + episode.cid;
        } else {
            if (episode.cid <= 0 || (episode.aid <= 0 && !episode.bvid.matches("BV[0-9A-Za-z]{10}"))) {
                throw new ClientException("视频分集信息不完整，请重新导入链接");
            }
            base = "https://api.bilibili.com/x/player/playurl?cid=" + episode.cid;
            base += episode.aid > 0 ? "&avid=" + episode.aid : "&bvid=" + encode(episode.bvid);
        }
        JSONObject response = getJson(base + "&qn=127&fnval=4048&fnver=0&fourk=1");
        JSONObject info = checkedPayload(response, "获取视频清晰度失败");
        JSONObject videoInfo = info.optJSONObject("video_info");
        if (videoInfo != null) {
            checkPlaybackRestrictions(info);
            info = videoInfo;
        }
        checkPlaybackRestrictions(info);
        return parseQualities(info);
    }

    /** Renew expiring signed URLs without silently changing the selected format. */
    public Quality refresh(Episode episode, String qualityId) throws Exception {
        if (qualityId == null || qualityId.isEmpty()) throw new ClientException("缺少已选择的清晰度");
        return requireQuality(qualities(episode), qualityId);
    }

    private static Quality requireQuality(List<Quality> available, String qualityId) throws IOException {
        for (Quality quality : available) {
            if (qualityId.equals(quality.id)) return quality;
        }
        // Do not copy API-provided labels or the requested ID into a copyable diagnostic.
        Set<String> formats = new LinkedHashSet<>();
        for (Quality quality : available) {
            int codec = inferCodec(quality.codecs);
            if (codec == 0 && quality.id.matches("[0-9]+:(7|12|13):[0-9]+"))
                codec = Integer.parseInt(quality.id.split(":")[1]);
            formats.add(resolutionLabel(quality.qn, quality.height) + " · " + codecLabel(codec, ""));
            if (formats.size() == 12) break;
        }
        String choices = formats.isEmpty() ? "无可用档位" : String.join("、", formats);
        throw new ClientException("原清晰度或编码已不可用，本次实际可用：" + choices + "。请重新选择清晰度");
    }

    private Series loadSeason(String query) throws Exception {
        JSONObject info = checkedPayload(getJson("https://api.bilibili.com/pgc/view/web/season?" + query), "获取番剧信息失败");
        long seasonId = info.optLong("season_id", 0);
        if (seasonId <= 0) throw new ClientException("番剧接口未返回有效的季编号");
        Series series = new Series();
        series.id = "ss" + seasonId;
        series.title = nonEmpty(info.optString("title"), "未命名番剧");
        series.coverUrl = coverFromInfo(info);
        Set<String> seen = new HashSet<>();
        addEpisodes(series, info.optJSONArray("episodes"), seen);
        // Some season types store the main episodes in a section instead.
        if (series.episodes.isEmpty()) {
            JSONObject main = info.optJSONObject("main_section");
            if (main != null) addEpisodes(series, main.optJSONArray("episodes"), seen);
            JSONArray sections = info.optJSONArray("section");
            if (sections != null) {
                for (int i = 0; i < sections.length(); i++) {
                    JSONObject section = sections.optJSONObject(i);
                    if (section != null) addEpisodes(series, section.optJSONArray("episodes"), seen);
                }
            }
        }
        if (series.episodes.isEmpty()) throw new ClientException("该番剧暂未返回可选集数，可能未上线或当前地区不可用");
        return series;
    }

    private void addEpisodes(Series series, JSONArray items, Set<String> seen) {
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            long epId = item.optLong("id", item.optLong("ep_id", 0));
            if (epId <= 0 || !seen.add("ep" + epId)) continue;
            Episode episode = new Episode();
            episode.id = "ep" + epId;
            episode.bangumi = true;
            episode.bvid = item.optString("bvid", "");
            episode.aid = item.optLong("aid", 0);
            episode.cid = item.optLong("cid", 0);
            episode.seriesId = series.id;
            episode.seriesTitle = series.title;
            episode.index = series.episodes.size() + 1;
            String shortTitle = item.optString("title", "").trim();
            String longTitle = item.optString("long_title", "").trim();
            String prefix = shortTitle.matches("[0-9]+(?:\\.[0-9]+)?") ? "第 " + shortTitle + " 集" : shortTitle;
            episode.title = nonEmpty(prefix, "第 " + episode.index + " 集") + (longTitle.isEmpty() ? "" : " · " + longTitle);
            series.episodes.add(episode);
        }
    }

    private Series loadVideo(String bvid) throws Exception {
        JSONObject info = checkedPayload(getJson("https://api.bilibili.com/x/web-interface/view?bvid=" + encode(bvid)), "获取视频信息失败");
        String redirect = info.optString("redirect_url", "");
        if (!redirect.isEmpty()) {
            URL url = trustedUrl(redirect);
            Matcher match = EPISODE_ID.matcher(url.toExternalForm());
            if (match.find()) return loadSeason("ep_id=" + match.group(1).substring(2));
        }
        Series series = new Series();
        series.id = nonEmpty(info.optString("bvid"), bvid);
        series.title = nonEmpty(info.optString("title"), bvid);
        series.coverUrl = coverFromInfo(info);
        JSONArray pages = info.optJSONArray("pages");
        if (pages == null || pages.length() == 0) throw new ClientException("视频接口未返回分集信息");
        for (int i = 0; i < pages.length(); i++) {
            JSONObject page = pages.optJSONObject(i);
            if (page == null || page.optLong("cid", 0) <= 0) continue;
            Episode episode = new Episode();
            episode.cid = page.optLong("cid");
            episode.id = series.id + ":" + episode.cid;
            episode.bvid = series.id;
            episode.aid = info.optLong("aid", 0);
            episode.seriesId = series.id;
            episode.seriesTitle = series.title;
            episode.index = page.optInt("page", i + 1);
            episode.title = nonEmpty(page.optString("part"), "第 " + episode.index + " 集");
            series.episodes.add(episode);
        }
        if (series.episodes.isEmpty()) throw new ClientException("没有找到可播放的视频分集");
        return series;
    }

    private List<Quality> parseQualities(JSONObject info) throws IOException {
        List<Quality> result = new ArrayList<>();
        Map<Integer, String> labels = new LinkedHashMap<>();
        JSONArray supportFormats = info.optJSONArray("support_formats");
        if (supportFormats != null) {
            for (int i = 0; i < supportFormats.length(); i++) {
                JSONObject format = supportFormats.optJSONObject(i);
                if (format != null) labels.put(format.optInt("quality"), nonEmpty(format.optString("new_description"), format.optString("display_desc")));
            }
        }
        JSONArray accepted = info.optJSONArray("accept_quality");
        JSONArray descriptions = info.optJSONArray("accept_description");
        if (accepted != null && descriptions != null) {
            for (int i = 0; i < Math.min(accepted.length(), descriptions.length()); i++) {
                int qn = accepted.optInt(i);
                if (!labels.containsKey(qn) || labels.get(qn).isEmpty()) labels.put(qn, descriptions.optString(i));
            }
        }

        JSONObject dash = info.optJSONObject("dash");
        boolean sawDrm = false;
        boolean missingAudio = false;
        boolean missingQuality = false;
        if (dash != null) {
            JSONArray audioItems = dash.optJSONArray("audio");
            JSONObject audio = null;
            long bestBandwidth = -1;
            if (audioItems != null) {
                for (int i = 0; i < audioItems.length(); i++) {
                    JSONObject item = audioItems.optJSONObject(i);
                    if (item == null) continue;
                    if (isDrm(item)) { sawDrm = true; continue; }
                    if (mediaUrls(item).isEmpty()) continue;
                    // The ordinary DASH audio array is AAC; avoid optional Dolby/FLAC tracks.
                    long bandwidth = item.optLong("bandwidth", 0);
                    if (audio == null || bandwidth > bestBandwidth) { audio = item; bestBandwidth = bandwidth; }
                }
            }
            JSONArray videoItems = dash.optJSONArray("video");
            if (videoItems != null) {
                Map<String, Quality> seen = new LinkedHashMap<>();
                for (int i = 0; i < videoItems.length(); i++) {
                    JSONObject video = videoItems.optJSONObject(i);
                    if (video == null) continue;
                    if (isDrm(video)) { sawDrm = true; continue; }
                    ArrayList<String> videoUrls = mediaUrls(video);
                    if (videoUrls.isEmpty()) continue;
                    if (audio == null) { missingAudio = true; continue; }
                    // The top-level quality describes a response/default selection, not every
                    // DASH representation. Never label an unidentified stream with that value.
                    int qn = video.optInt("id", 0);
                    if (qn <= 0) { missingQuality = true; continue; }
                    int codec = video.optInt("codecid", inferCodec(video.optString("codecs")));
                    int audioId = audio.optInt("id", 0);
                    Quality quality = new Quality();
                    quality.qn = qn;
                    quality.width = Math.max(0, video.optInt("width", 0));
                    quality.height = Math.max(0, video.optInt("height", 0));
                    quality.bandwidth = Math.max(0, video.optLong("bandwidth", 0));
                    quality.codecs = video.optString("codecs", "");
                    quality.id = qn + ":" + codec + ":" + audioId;
                    Quality existing = seen.get(quality.id);
                    if (existing != null) {
                        // The same qn/codec can describe a different encoded resource.
                        // Only merge duplicate representations of the same path; a
                        // cache continuation must never combine distinct media files.
                        if (!URI.create(existing.videoUrl).getRawPath()
                                .equals(URI.create(videoUrls.get(0)).getRawPath())) continue;
                        for (String candidate : videoUrls) {
                            if (!existing.videoUrls.contains(candidate)) existing.videoUrls.add(candidate);
                        }
                        continue;
                    }
                    seen.put(quality.id, quality);
                    String resolution = nonEmpty(labels.get(qn), resolutionLabel(qn, video.optInt("height", 0)));
                    quality.label = resolution + " · " + codecLabel(codec, video.optString("codecs"));
                    quality.videoUrls = videoUrls;
                    quality.videoUrl = videoUrls.get(0);
                    quality.audioUrls = mediaUrls(audio);
                    quality.audioUrl = quality.audioUrls.get(0);
                    addMediaHeaders(quality);
                    result.add(quality);
                }
            }
        }

        // A single progressive file includes sound. Multiple legacy FLV chunks need a
        // different playback model and must not be advertised as a complete episode.
        JSONArray durl = info.optJSONArray("durl");
        if (result.isEmpty() && durl != null && durl.length() == 1) {
            JSONObject file = durl.optJSONObject(0);
            if (file != null && !isDrm(file)) {
                ArrayList<String> urls = mediaUrls(file);
                if (!urls.isEmpty()) {
                    Quality quality = new Quality();
                    quality.qn = info.optInt("quality", 0);
                    quality.id = quality.qn + ":progressive:0";
                    quality.label = nonEmpty(labels.get(quality.qn), resolutionLabel(quality.qn, 0)) + " · 原始文件";
                    quality.videoUrls = urls;
                    quality.videoUrl = urls.get(0);
                    addMediaHeaders(quality);
                    result.add(quality);
                }
            } else if (file != null) sawDrm = true;
        }
        if (result.isEmpty()) {
            if (sawDrm) throw new ClientException("当前返回的媒体含 DRM 加密，首版不支持此内容的离线下载");
            if (missingAudio) throw new ClientException("接口仅返回画面，未取得可用音轨；请检查登录状态后重试");
            if (missingQuality) throw new ClientException("接口返回的视频缺少实际清晰度标识，请重新解析");
            if (durl != null && durl.length() > 1) throw new ClientException("该视频采用多段旧格式，首版暂不支持完整下载");
            throw new ClientException("接口未返回可用媒体流。可能涉及账号权限、地区限制、接口变化或加密；不能仅凭此错误确定原因");
        }
        result.sort(Comparator.comparingInt((Quality q) -> q.qn).reversed()
                .thenComparingInt(q -> q.id.contains(":7:") ? 0 : 1).thenComparing(q -> q.id));
        return result;
    }

    private static void checkPlaybackRestrictions(JSONObject info) throws IOException {
        if (isDrm(info)) throw new ClientException("当前媒体标记为 DRM 加密，首版不支持此内容的离线下载");
        if (truthy(info.opt("is_preview"))) throw new ClientException("当前账号仅获得试看内容，请在 B 站确认正片观看权限");
        for (String key : new String[]{"business", "play_view_business", "play_view_business_info"}) {
            JSONObject business = info.optJSONObject(key);
            if (business != null) {
                if (isDrm(business)) throw new ClientException("当前媒体标记为 DRM 加密，首版不支持此内容的离线下载");
                if (truthy(business.opt("is_preview"))) throw new ClientException("当前账号仅获得试看内容，请在 B 站确认正片观看权限");
            }
        }
    }

    private static boolean isDrm(JSONObject item) {
        if (truthy(item.opt("is_drm")) || truthy(item.opt("drm_tech_type")) || truthy(item.opt("drmTechType"))) return true;
        for (String key : new String[]{"widevine_pssh", "widevinePssh", "playready_pssh", "fairplay_key", "drm_kid"}) {
            Object value = item.opt(key);
            if (value != null && value != JSONObject.NULL && !String.valueOf(value).isEmpty()) return true;
        }
        return false;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return value != null && ("true".equalsIgnoreCase(String.valueOf(value)) || String.valueOf(value).matches("[1-9][0-9]*"));
    }

    private JSONObject checkedPayload(JSONObject response, String action) throws IOException {
        if (!response.has("code")) throw new ClientException(action + "：接口响应缺少状态码");
        int code = response.optInt("code", Integer.MIN_VALUE);
        if (code != 0) {
            String hint = "";
            if (code == -101) hint = "请在工具内登录 B 站";
            else if (code == -10403 || code == 10015002) hint = "当前账号或地区没有取得该视频的播放权限";
            else if (code == -412 || code == -352) hint = "B 站要求验证或限制了请求，请稍后重试";
            else if (code == -404) hint = "该视频不存在或当前不可用";
            // Server messages are untrusted and can contain request URLs or credentials.
            throw new ClientException(action + "（" + code + "）" + (hint.isEmpty() ? "" : "。" + hint));
        }
        JSONObject info = response.optJSONObject("result");
        if (info == null) info = response.optJSONObject("data");
        if (info == null) throw new ClientException(action + "：接口未返回有效数据");
        return info;
    }

    private JSONObject getJson(String address) throws IOException {
        URL url = trustedUrl(address);
        for (int redirect = 0; redirect <= 5; redirect++) {
            if (!isBiliHost(url.getHost())) throw new ClientException("接口跳转到了非 B 站地址，已停止请求");
            HttpURLConnection connection = open(url);
            try {
                int status = connection.getResponseCode();
                if (isRedirect(status)) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new ClientException("接口跳转缺少目标地址");
                    url = trustedUrl(new URL(url, location).toExternalForm());
                    continue;
                }
                if (status != 200) throw new ClientException("B 站接口返回 HTTP " + status + (status == 412 ? "，请稍后重试或完成登录验证" : ""));
                try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (bytes.size() + count > MAX_RESPONSE_BYTES) throw new ClientException("接口响应过大，已停止读取");
                        bytes.write(buffer, 0, count);
                    }
                    try {
                        return new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                    } catch (JSONException error) {
                        throw new ClientException("B 站接口未返回 JSON 数据，可能需要登录验证或接口已变化");
                    }
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new ClientException("接口跳转次数过多");
    }

    private URL followShortLink(URL initial) throws IOException {
        URL url = initial;
        for (int redirect = 0; redirect <= 5; redirect++) {
            if (isBiliHost(url.getHost())) return url;
            HttpURLConnection connection = open(url);
            try {
                int status = connection.getResponseCode();
                if (!isRedirect(status)) throw new ClientException("短链接没有跳转到视频页，请复制完整播放链接");
                String location = connection.getHeaderField("Location");
                if (location == null) throw new ClientException("短链接缺少跳转地址");
                url = trustedUrl(new URL(url, location).toExternalForm());
            } finally {
                connection.disconnect();
            }
        }
        throw new ClientException("短链接跳转次数过多");
    }

    private HttpURLConnection open(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Referer", REFERER);
        connection.setRequestProperty("Accept", "application/json,text/plain,*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");
        // Short links and all CDN requests never receive the user's login session.
        if (isBiliHost(url.getHost()) && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        return connection;
    }

    private static URL trustedUrl(String value) throws IOException {
        URL url;
        try { url = new URL(value); } catch (Exception error) { throw new ClientException("链接地址无效"); }
        String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (!(protocol.equals("http") || protocol.equals("https")) || url.getUserInfo() != null
                || !(isBiliHost(host) || isShortHost(host))
                || (url.getPort() != -1 && url.getPort() != (protocol.equals("http") ? 80 : 443))) {
            throw new ClientException("仅接受 bilibili.com 或 b23.tv 的标准视频链接");
        }
        // Login cookies are never sent over cleartext HTTP.
        return new URL("https", host, -1, url.getFile());
    }

    private static boolean isBiliHost(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        return value.equals("bilibili.com") || value.endsWith(".bilibili.com");
    }

    private static boolean isShortHost(String host) {
        return "b23.tv".equalsIgnoreCase(host) || "www.b23.tv".equalsIgnoreCase(host);
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private static String stripTrailingPunctuation(String value) {
        return value.replaceAll("[)\\]}>，。；！？、]+$", "");
    }

    private static ArrayList<String> mediaUrls(JSONObject item) {
        ArrayList<String> raw = new ArrayList<>();
        for (String key : new String[]{"baseUrl", "base_url", "url"}) {
            Object value = item.opt(key);
            if (value instanceof String) raw.add((String) value);
        }
        for (String key : new String[]{"backupUrl", "backup_url"}) {
            Object value = item.opt(key);
            if (value instanceof JSONArray) {
                JSONArray values = (JSONArray) value;
                for (int i = 0; i < values.length(); i++) {
                    Object candidate = values.opt(i);
                    if (candidate instanceof String) raw.add((String) candidate);
                }
            } else if (value instanceof String) raw.add((String) value);
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (String value : raw) {
            String https = Quality.httpsMediaUrl(value);
            if (!https.isEmpty()) candidates.add(https);
        }
        // Prefer every exact HTTPS address before retaining the old client's HTTP
        // upgrade behavior. Never rewrite an existing HTTPS signature or CDN host.
        for (String value : raw) {
            if (value.regionMatches(true, 0, "http://", 0, 7)) {
                String https = Quality.httpsMediaUrl("https://" + value.substring(7));
                if (!https.isEmpty()) candidates.add(https);
            }
        }
        return new ArrayList<>(candidates);
    }

    private static void addMediaHeaders(Quality quality) {
        quality.headers.put("User-Agent", MEDIA_USER_AGENT);
        quality.headers.put("Referer", REFERER);
    }

    private static int inferCodec(String codecs) {
        if (codecs.startsWith("avc")) return 7;
        if (codecs.startsWith("hev") || codecs.startsWith("hvc")) return 12;
        if (codecs.startsWith("av01")) return 13;
        return 0;
    }

    private static String codecLabel(int codec, String codecs) {
        if (codec == 7) return "AVC";
        if (codec == 12) return "HEVC";
        if (codec == 13) return "AV1";
        return nonEmpty(codecs, "原始编码");
    }

    private static String resolutionLabel(int qn, int height) {
        switch (qn) {
            case 6: return "240P";
            case 16: return "360P";
            case 32: return "480P";
            case 64: return "720P";
            case 74: return "720P 60帧";
            case 80: return "1080P";
            case 112: return "1080P 高码率";
            case 116: return "1080P 60帧";
            case 120: return "4K";
            case 125: return "HDR";
            case 126: return "杜比视界";
            case 127: return "8K";
            default: return height > 0 ? height + "P" : qn > 0 ? "清晰度 " + qn : "清晰度未知";
        }
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()); }
        catch (Exception impossible) { throw new IllegalArgumentException(impossible); }
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.isEmpty() ? (fallback == null ? "" : fallback) : value;
    }
}
