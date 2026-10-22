package com.animeshelf.app;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Opens a track using its API-provided HTTPS mirrors, without forwarding account credentials. */
@UnstableApi
public final class MediaHttpDataSource implements DataSource {
    private static final int MAX_CANDIDATES = 5;

    public interface Resolver {
        Request resolve(DataSpec dataSpec);
    }

    public static final class Request {
        public final List<String> urls;
        public final Map<String, String> headers;
        public final String trackLabel;

        public Request(List<String> urls, Map<String, String> headers, String trackLabel) {
            this.urls = urls == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(urls));
            this.headers = headers == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            this.trackLabel = trackLabel == null || trackLabel.isEmpty() ? "媒体" : trackLabel;
        }
    }

    public static final class Factory implements DataSource.Factory {
        private final Resolver resolver;
        private final DataSource.Factory upstreamFactory;

        public Factory(Resolver resolver) {
            // Keep the media identity in the sanitized headers used by every attempt,
            // including restored downloads whose stored Quality still has the old mobile UA.
            this(resolver, new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(20000).setReadTimeoutMs(30000));
        }

        Factory(Resolver resolver, DataSource.Factory upstreamFactory) {
            this.resolver = resolver;
            this.upstreamFactory = upstreamFactory;
        }

        @Override public MediaHttpDataSource createDataSource() {
            return new MediaHttpDataSource(resolver, upstreamFactory);
        }
    }

    private final Resolver resolver;
    private final DataSource.Factory upstreamFactory;
    private final List<TransferListener> listeners = new ArrayList<>();
    private DataSource upstream;
    private String trackLabel = "媒体";
    private String openedHost = "";
    private long bytesRemaining = C.LENGTH_UNSET;

    MediaHttpDataSource(Resolver resolver, DataSource.Factory upstreamFactory) {
        this.resolver = resolver;
        this.upstreamFactory = upstreamFactory;
    }

    @Override public void addTransferListener(TransferListener listener) {
        if (listener == null) throw new NullPointerException("listener");
        if (!listeners.contains(listener)) {
            listeners.add(listener);
            if (upstream != null) upstream.addTransferListener(listener);
        }
    }

    @Override public long open(DataSpec dataSpec) throws IOException {
        if (upstream != null) throw new IllegalStateException("媒体连接尚未关闭");
        Request request = resolver == null ? null : resolver.resolve(dataSpec);
        if (request == null) {
            request = new Request(Collections.singletonList(dataSpec.uri.toString()),
                Collections.emptyMap(), "媒体");
        }
        List<String> candidates = candidates(request.urls);
        Map<String, String> headers = headers(request.headers, dataSpec.httpRequestHeaders);
        trackLabel = request.trackLabel;
        bytesRemaining = C.LENGTH_UNSET;
        List<Throwable> failures = new ArrayList<>();
        List<String> hosts = new ArrayList<>();
        for (String candidate : candidates) {
            openedHost = Uri.parse(candidate).getHost();
            upstream = upstreamFactory.createDataSource();
            for (TransferListener listener : listeners) upstream.addTransferListener(listener);
            // Retain the caller's range, body/method, flags, cache key and custom data.
            DataSpec attempt = dataSpec.buildUpon().setUri(candidate)
                .setHttpRequestHeaders(headers).build();
            try {
                long length = upstream.open(attempt);
                // For a bounded request, open() may return the requested length even when
                // the resource ends sooner. Only an unbounded request resolves its true size.
                bytesRemaining = attempt.length == C.LENGTH_UNSET ? length : C.LENGTH_UNSET;
                return length;
            } catch (IOException failure) {
                failures.add(failure);
                hosts.add(currentHost());
                try {
                    upstream.close();
                } catch (IOException ignored) {
                    // The HTTP failure is the useful diagnosis; cleanup must not prevent failover.
                } finally {
                    upstream = null;
                }
            }
        }
        openedHost = "";
        if (failures.isEmpty()) {
            failures.add(new IOException("未找到有效的 HTTPS 媒体地址，请重新解析"));
        }
        throw MediaErrors.transportFailure(trackLabel, failures, hosts);
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (upstream == null) throw new IllegalStateException("媒体连接尚未打开");
        try {
            int count = upstream.read(buffer, offset, length);
            // A truncated response can otherwise look like a clean end to the cache, with
            // the eventual parser EOF losing both the failing track and its CDN host.
            if (count == C.RESULT_END_OF_INPUT && bytesRemaining > 0)
                throw new EOFException("媒体响应在预期字节数之前结束");
            if (count > 0 && bytesRemaining != C.LENGTH_UNSET)
                bytesRemaining = Math.max(0, bytesRemaining - count);
            return count;
        } catch (IOException failure) {
            // Media3 owns read retries and the current byte offset. Reopening here could replay
            // bytes into a partially written cache span and corrupt a supposedly complete file.
            throw MediaErrors.transportFailure(trackLabel,
                Collections.singletonList(failure), Collections.singletonList(currentHost()));
        }
    }

    @Override public Uri getUri() {
        return upstream == null ? null : upstream.getUri();
    }

    @Override public Map<String, List<String>> getResponseHeaders() {
        return upstream == null ? Collections.emptyMap() : upstream.getResponseHeaders();
    }

    @Override public void close() throws IOException {
        if (upstream == null) return;
        String host = currentHost();
        try {
            upstream.close();
        } catch (IOException failure) {
            throw MediaErrors.transportFailure(trackLabel,
                Collections.singletonList(failure), Collections.singletonList(host));
        } finally {
            upstream = null;
            openedHost = "";
            bytesRemaining = C.LENGTH_UNSET;
        }
    }

    private String currentHost() {
        Uri uri = upstream == null ? null : upstream.getUri();
        String host = uri == null ? null : uri.getHost();
        return host == null || host.isEmpty() ? openedHost : host;
    }

    private static List<String> candidates(List<String> urls) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : urls) {
            if (value == null) continue;
            String candidate = value;
            try {
                URI uri = new URI(candidate);
                int port = uri.getPort();
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getHost().isEmpty() || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null
                    || (port != -1 && (port < 1 || port > 65535))) continue;
                result.add(candidate);
                if (result.size() == MAX_CANDIDATES) break;
            } catch (URISyntaxException ignored) {
                // Invalid addresses are not network attempts and are never included in errors.
            }
        }
        return new ArrayList<>(result);
    }

    private static Map<String, String> headers(Map<String, String> qualityHeaders,
                                               Map<String, String> dataSpecHeaders) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("Referer", BiliClient.REFERER);
        copyAllowedHeaders(qualityHeaders, result);
        copyAllowedHeaders(dataSpecHeaders, result);
        // Web playurl may return platform=pc CDN URLs. Mobile Android browser UAs can
        // be rejected for those URLs, even when the API request succeeded. Do not let
        // a persisted pre-update Quality or a caller's DataSpec restore that mismatch.
        result.put("User-Agent", BiliClient.MEDIA_USER_AGENT);
        return result;
    }

    private static void copyAllowedHeaders(Map<String, String> source, Map<String, String> target) {
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String name = entry.getKey(), value = entry.getValue();
            if (name == null || value == null || value.isEmpty()
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) continue;
            // This allowlist also excludes cookies, authorization and proxy credentials supplied
            // through DataSpec. DefaultHttpDataSource derives Range from DataSpec position/length.
            if (name.equalsIgnoreCase("Referer")) target.put("Referer", value);
            else if (name.equalsIgnoreCase("Origin")) target.put("Origin", value);
        }
    }
}
