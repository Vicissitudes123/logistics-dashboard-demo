package com.animeshelf.app;

import static org.junit.Assert.*;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import java.io.IOException;
import java.io.EOFException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Checks the information users can safely copy out of a failed real network request. */
@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class MediaErrorsTest {
    private static final String PRIVATE = "private-canary";

    private static HttpDataSource.InvalidResponseCodeException http(int status, String host) {
        Map<String, String> requestHeaders = new LinkedHashMap<>();
        requestHeaders.put("Cookie", "SESSDATA=" + PRIVATE + "-cookie");
        requestHeaders.put("Authorization", "Bearer " + PRIVATE + "-authorization");
        DataSpec spec = new DataSpec.Builder()
            .setUri("https://" + host + "/" + PRIVATE + "-path.m4s?token=" + PRIVATE + "-query")
            .setHttpRequestHeaders(requestHeaders).build();
        Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
        responseHeaders.put("Set-Cookie", Collections.singletonList(PRIVATE + "-response-header"));
        return new HttpDataSource.InvalidResponseCodeException(status, PRIVATE + "-response-message",
            new IOException(PRIVATE + "-cause"), responseHeaders, spec,
            (PRIVATE + "-response-body").getBytes(StandardCharsets.UTF_8));
    }

    private static void assertSafe(String description) {
        assertNotNull(description);
        assertFalse(description, description.contains(PRIVATE));
        assertFalse(description, description.contains("SESSDATA"));
        assertFalse(description, description.contains("Cookie"));
        assertFalse(description, description.contains("Authorization"));
        assertFalse(description, description.contains("https://"));
        assertFalse(description, description.contains("token="));
    }

    @Test public void nestedHttpFailureKeepsStatusAndHostWithoutRequestOrResponseSecrets() {
        PlaybackException error = new PlaybackException(PRIVATE + "-playback-message",
            new IOException(PRIVATE + "-wrapper", http(403, "cdn.example.test")),
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS);
        String description = MediaErrors.describe(error);
        assertTrue(description, description.contains("HTTP 403"));
        assertTrue(description, description.contains("cdn.example.test"));
        assertFalse(description, description.contains("无法解码"));
        assertSafe(description);
    }

    @Test public void specificHttpStatusesSurviveInsteadOfACommonBadStatusLabel() {
        for (int code : new int[]{401, 404, 410, 416, 429, 500, 503}) {
            String description = MediaErrors.describe(http(code, "media.example.test"));
            assertTrue(description, description.contains("HTTP " + code));
            assertSafe(description);
        }
    }

    @Test public void aggregatePreservesTrackAndEachAttemptReasonButRejectsRawHostInput() {
        IOException aggregate = MediaErrors.transportFailure("音轨",
            Arrays.asList(http(403, "first.example.test"), new SocketTimeoutException(PRIVATE)),
            Arrays.asList("first.example.test", "https://second.example.test/path?token=" + PRIVATE));
        String description = MediaErrors.describe(new IOException(PRIVATE, aggregate));
        assertTrue(description, description.contains("音轨请求失败"));
        assertTrue(description, description.contains("2 个地址"));
        assertTrue(description, description.contains("first.example.test"));
        assertTrue(description, description.contains("HTTP 403"));
        assertTrue(description, description.contains("未知主机"));
        assertTrue(description, description.contains("超时"));
        assertSafe(description);
    }

    @Test public void untrustedTrackLabelAndHostNeverEnterDiagnostic() {
        String description = MediaErrors.describe(MediaErrors.transportFailure(
            "Cookie: " + PRIVATE, Collections.singletonList(new IOException(PRIVATE)),
            Collections.singletonList("cdn.example.test\nCookie: " + PRIVATE)));
        assertTrue(description, description.startsWith("媒体请求失败"));
        assertTrue(description, description.contains("未知主机"));
        assertSafe(description);
    }

    @Test public void nestedNetworkCausesHaveUsefulTypedDescriptionsWithoutRawMessages() {
        Throwable[] causes = {new UnknownHostException(PRIVATE), new SocketTimeoutException(PRIVATE),
            new SSLHandshakeException(PRIVATE), new ConnectException(PRIVATE)};
        String[] expected = {"域名解析失败", "超时", "HTTPS 连接失败", "无法连接服务器"};
        for (int i = 0; i < causes.length; i++) {
            String description = MediaErrors.describe(new IOException(PRIVATE, causes[i]));
            assertTrue(description, description.contains(expected[i]));
            assertSafe(description);
        }
    }

    @Test public void decoderFailureIsDistinctFromHttpFailure() {
        String description = MediaErrors.describe(new PlaybackException(PRIVATE,
            new IOException(PRIVATE), PlaybackException.ERROR_CODE_DECODING_FAILED));
        assertTrue(description, description.contains("设备无法解码"));
        assertTrue(description, description.contains("AVC"));
        assertFalse(description, description.contains("HTTP 403"));
        assertSafe(description);
    }

    @Test public void unspecifiedIoPreservesEarlyEofTypeWithoutGuessingMissingResolution() {
        PlaybackException error = new PlaybackException(PRIVATE,
            new IOException(PRIVATE, new EOFException("https://private.example/?token=" + PRIVATE)),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        String description = MediaErrors.describe(error);
        assertTrue(description, description.contains("提前结束"));
        assertTrue(description, description.contains("EOFException"));
        assertTrue(description, description.contains("ERROR_CODE_IO_UNSPECIFIED"));
        assertTrue(description, description.contains("不能据此判断清晰度缺失"));
        assertSafe(description);
    }

    @Test public void nonHttpTransportFailuresKeepTrackHostAndExceptionType() {
        Throwable[] failures = {new SocketException(PRIVATE), new EOFException(PRIVATE),
            new ProtocolException(PRIVATE), new IOException(PRIVATE)};
        String[] descriptions = {"网络连接异常", "提前结束", "响应协议异常", "网络读取失败"};
        for (int i = 0; i < failures.length; i++) {
            String description = MediaErrors.describe(MediaErrors.transportFailure("音轨",
                Collections.singletonList(new IOException(PRIVATE, failures[i])),
                Collections.singletonList("audio.example.test")));
            assertTrue(description, description.contains("音轨"));
            assertTrue(description, description.contains("audio.example.test"));
            assertTrue(description, description.contains(failures[i].getClass().getSimpleName()));
            assertTrue(description, description.contains(descriptions[i]));
            assertSafe(description);
        }
    }

    @Test public void certificateFailureIsDistinguishedFromGenericTlsHandshake() {
        SSLHandshakeException handshake = new SSLHandshakeException(PRIVATE);
        handshake.initCause(new CertificateException(PRIVATE));
        String description = MediaErrors.describe(MediaErrors.transportFailure("视频",
            Collections.singletonList(handshake), Collections.singletonList("tls.example.test")));
        assertTrue(description, description.contains("证书或主机身份校验失败"));
        assertTrue(description, description.contains("SSLHandshakeException"));
        assertTrue(description, description.contains("tls.example.test"));
        assertSafe(description);
    }

    @Test public void unknownErrorsDoNotExposeTheirMessageOrSuppressedExceptions() {
        IOException error = new IOException("https://private.example.test/?token=" + PRIVATE);
        error.addSuppressed(new IOException("Cookie: " + PRIVATE));
        String description = MediaErrors.describe(error);
        assertTrue(description, description.contains("未取得具体 HTTP 状态"));
        assertSafe(description);
        assertSafe(MediaErrors.describe(null));
    }

    @Test public void cyclicCauseChainTerminatesWithoutExposingMessages() {
        IOException first = new IOException(PRIVATE + "-first");
        IOException second = new IOException(PRIVATE + "-second");
        first.initCause(second); second.initCause(first);
        assertSafe(MediaErrors.describe(first));
    }

    @Test public void locallyGeneratedClientExplanationIsPreservedThroughWrappers() throws Exception {
        try {
            new BiliClient("").resolve("");
            fail("Empty input must produce a local validation error");
        } catch (BiliClient.ClientException safeError) {
            String description = MediaErrors.describe(new IOException(PRIVATE, safeError));
            assertEquals(safeError.getMessage(), description);
            assertTrue(description, description.contains("请粘贴"));
            assertSafe(description);
        }
    }
}
