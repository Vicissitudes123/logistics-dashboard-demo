package com.animeshelf.app;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.HttpDataSource;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLException;

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
            detail.append('\n').append(host).append(" · ").append(reason(failures.get(i)));
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
        String reason = reason(error);
        if (!"网络读取失败".equals(reason)) return reason + "，请检查网络后重试。";
        for (Throwable cause : causes) {
            if (cause instanceof PlaybackException) {
                String code = ((PlaybackException) cause).getErrorCodeName();
                if (code.startsWith("ERROR_CODE_DECOD")) return "设备无法解码这一格式，请尝试 AVC 或其他清晰度。\n" + code;
                return "播放未能继续，请重新选择这一集或检查本地文件。\n" + code;
            }
        }
        return "请求未能完成，请重试。未取得具体 HTTP 状态。";
    }

    private static String reason(Throwable error) {
        List<Throwable> causes = causes(error);
        for (Throwable cause : causes) if (cause instanceof HttpDataSource.InvalidResponseCodeException)
            return httpReason(((HttpDataSource.InvalidResponseCodeException) cause).responseCode);
        for (Throwable cause : causes) {
            if (cause instanceof UnknownHostException) return "域名解析失败";
            if (cause instanceof SocketTimeoutException) return "连接或读取超时";
            if (cause instanceof SSLException) return "HTTPS 连接失败";
            if (cause instanceof ConnectException) return "无法连接服务器";
        }
        return "网络读取失败";
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
