package com.animeshelf.app;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.HttpDataSource;
import java.io.IOException;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.security.cert.CertPathValidatorException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

/** Diagnostics that never include signed URLs, response bodies or account credentials. */
@UnstableApi
public final class MediaErrors {
    private MediaErrors() {}

    private static final class TransportFailure extends IOException {
        final String detail;
        TransportFailure(String detail, Throwable cause) { super(detail, cause); this.detail = detail; }
    }

    public static IOException transportFailure(String label, List<Throwable> failures, List<String> hosts) {
        String track = "视频".equals(label) || "音轨".equals(label) ? label : "媒体";
        StringBuilder detail = new StringBuilder(track).append("请求失败");
        int count = Math.min(5, failures.size());
        if (count > 1) detail.append("（已尝试 ").append(count).append(" 个地址）");
        for (int i = 0; i < count; i++) {
            String host = i < hosts.size() ? safeHost(hosts.get(i)) : "未知主机";
            detail.append('\n').append(host).append(" · ").append(typedReason(failures.get(i)));
        }
        detail.append("\n可先刷新地址重试；若仍失败，请复制这些详情反馈。");
        return new TransportFailure(detail.toString(), failures.isEmpty() ? null : failures.get(failures.size() - 1));
    }

    public static String describe(Throwable error) {
        List<Throwable> causes = causes(error);
        for (Throwable cause : causes) if (cause instanceof TransportFailure) return ((TransportFailure) cause).detail;
        for (Throwable cause : causes) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                HttpDataSource.InvalidResponseCodeException http = (HttpDataSource.InvalidResponseCodeException) cause;
                String host = http.dataSpec == null ? "未知主机" : safeHost(http.dataSpec.uri.getHost());
                return "媒体请求失败\n" + host + " · " + httpReason(http.responseCode) + "\n请刷新地址重试。";
            }
        }
        // Only BiliClient can construct this type. Its messages are fixed local business
        // explanations (plus numeric status codes), never upstream text or request URLs.
        for (Throwable cause : causes) {
            if (cause instanceof BiliClient.ClientException) return cause.getMessage();
        }
        String playbackCode = "";
        for (Throwable cause : causes) {
            if (cause instanceof PlaybackException) {
                String code = ((PlaybackException) cause).getErrorCodeName();
                if (code.startsWith("ERROR_CODE_DECOD")) return "设备无法解码这一格式，请尝试 AVC 或其他清晰度。\n" + code;
                if (code.matches("ERROR_CODE_[A-Z0-9_]+")) playbackCode = code;
                break;
            }
        }
        String host = "";
        for (Throwable cause : causes) {
            if (cause instanceof HttpDataSource.HttpDataSourceException) {
                HttpDataSource.HttpDataSourceException http = (HttpDataSource.HttpDataSourceException) cause;
                if (http.dataSpec != null) host = safeHost(http.dataSpec.uri.getHost());
                break;
            }
        }
        String type = ioType(error);
        if (!type.isEmpty()) {
            return "媒体读取失败\n" + (host.isEmpty() ? "" : host + " · ") + typedReason(error)
                + "\n未取得具体 HTTP 状态；不能据此判断清晰度缺失。请刷新地址或重试。"
                + (playbackCode.isEmpty() ? "" : "\n" + playbackCode);
        }
        return "请求未能完成，请重试。未取得具体 HTTP 状态。"
            + (playbackCode.isEmpty() ? "" : "\n" + playbackCode);
    }

    private static String typedReason(Throwable error) {
        String detail = reason(error);
        if (detail.startsWith("HTTP ")) return detail;
        String type = ioType(error);
        return detail + (type.isEmpty() ? "" : "（" + type + "）");
    }

    private static String reason(Throwable error) {
        List<Throwable> causes = causes(error);
        for (Throwable cause : causes) if (cause instanceof HttpDataSource.InvalidResponseCodeException)
            return httpReason(((HttpDataSource.InvalidResponseCodeException) cause).responseCode);
        for (Throwable cause : causes) {
            if (cause instanceof CertificateException || cause instanceof CertPathValidatorException
                || cause instanceof SSLPeerUnverifiedException) return "HTTPS 连接失败：证书或主机身份校验失败";
        }
        for (int i = causes.size() - 1; i >= 0; i--) {
            Throwable cause = causes.get(i);
            if (cause instanceof UnknownHostException) return "域名解析失败";
            if (cause instanceof SocketTimeoutException) return "连接或读取超时";
            if (cause instanceof SSLHandshakeException) return "HTTPS 连接失败：TLS 握手失败";
            if (cause instanceof SSLException) return "HTTPS 连接失败";
            if (cause instanceof ConnectException) return "无法连接服务器";
            if (cause instanceof NoRouteToHostException) return "无法到达媒体服务器";
            if (cause instanceof EOFException) return "媒体数据提前结束，未读到完整内容";
            if (cause instanceof SocketException) return "网络连接异常或中断";
            if (cause instanceof ProtocolException) return "媒体服务器响应协议异常";
            if (cause instanceof InterruptedIOException) return "媒体读取被中断";
            if (cause instanceof FileNotFoundException) return "媒体或缓存文件无法打开";
            if (cause instanceof ParserException) return "媒体容器或数据解析失败";
            if (cause instanceof HttpDataSource.InvalidContentTypeException) return "服务器返回了不支持的媒体内容类型";
        }
        return "网络读取失败";
    }

    private static String ioType(Throwable error) {
        String name = "";
        for (Throwable cause : causes(error)) {
            if (cause instanceof IOException) {
                // Class names are local program metadata; never use exception messages,
                // response bodies or signed request addresses to enrich this diagnostic.
                String candidate = cause.getClass().getSimpleName();
                if (candidate.matches("[A-Za-z][A-Za-z0-9_$]{0,79}")) name = candidate;
            }
        }
        return name;
    }

    private static String httpReason(int code) {
        String hint;
        switch (code) {
            case 401: case 403: hint = "服务器拒绝请求，需核对地址有效性、访问权限及网络出口"; break;
            case 404: case 410: hint = "媒体地址不存在或已失效"; break;
            case 416: hint = "请求的文件范围不可用，请重新解析"; break;
            case 429: hint = "请求过于频繁，请稍后重试"; break;
            default: hint = code >= 500 ? "媒体服务器暂时不可用" : "服务器未接受媒体请求";
        }
        return "HTTP " + code + " · " + hint;
    }

    private static String safeHost(String host) {
        if (host == null || host.length() > 253 || !host.matches("[A-Za-z0-9.\\-:\\[\\]]+")) return "未知主机";
        return host;
    }

    private static List<Throwable> causes(Throwable error) {
        ArrayList<Throwable> result = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = error; current != null && result.size() < 16 && seen.add(current); current = current.getCause())
            result.add(current);
        return result;
    }
}
