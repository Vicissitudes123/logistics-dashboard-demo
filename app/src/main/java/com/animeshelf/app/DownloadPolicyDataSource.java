package com.animeshelf.app;

import android.net.Uri;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** A final per-request guard used only for offline downloads, never for online playback. */
@UnstableApi
public final class DownloadPolicyDataSource implements DataSource {
    public static final class PolicyBlockedException extends InterruptedIOException {
        private PolicyBlockedException() { super("下载已暂停，等待允许的网络或手动继续"); }
    }
    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstream;
        private final BooleanSupplier allowed;
        private final Runnable blocked;
        public Factory(DataSource.Factory upstream, BooleanSupplier allowed, Runnable blocked) {
            this.upstream = upstream; this.allowed = allowed; this.blocked = blocked;
        }
        @Override public DataSource createDataSource() {
            return new DownloadPolicyDataSource(upstream.createDataSource(), allowed, blocked);
        }
    }
    private final DataSource upstream;
    private final BooleanSupplier allowed;
    private final Runnable blocked;
    DownloadPolicyDataSource(DataSource upstream, BooleanSupplier allowed, Runnable blocked) {
        this.upstream = upstream; this.allowed = allowed; this.blocked = blocked;
    }
    private void checkPolicy() throws PolicyBlockedException {
        if (!allowed.getAsBoolean()) { blocked.run(); throw new PolicyBlockedException(); }
    }
    @Override public long open(DataSpec dataSpec) throws IOException { checkPolicy(); return upstream.open(dataSpec); }
    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        checkPolicy();
        return upstream.read(buffer, offset, length);
    }
    @Override public void addTransferListener(TransferListener listener) { upstream.addTransferListener(listener); }
    @Override public Uri getUri() { return upstream.getUri(); }
    @Override public Map<String, List<String>> getResponseHeaders() { return upstream.getResponseHeaders(); }
    @Override public void close() throws IOException { upstream.close(); }
}
