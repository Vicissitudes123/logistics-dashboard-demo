package com.animeshelf.app;

import static org.junit.Assert.*;

import android.app.Activity;
import android.view.Display;
import android.view.SurfaceView;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.ui.PlayerView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@androidx.media3.common.util.UnstableApi
public class VideoDiagnosticsTest {
    @Test public void missingMetadataDoesNotBecomeHdrJustBecauseItsLabelSaysHdr() {
        Format unknown = new Format.Builder().setSampleMimeType("video/hevc").build();
        assertEquals(VideoDiagnostics.DynamicRange.UNKNOWN, VideoDiagnostics.dynamicRange(unknown));
        String text = describe(unknown, "HDR 真彩 HEVC");
        assertTrue(text.contains("实际视频轨：未知"));
        assertTrue(text.contains("分辨率：未知"));
        assertTrue(text.contains("视频码率（媒体元数据）：未知"));
        assertTrue(text.contains("位深：未知"));
        assertFalse(text.contains("10 bit"));
    }

    @Test public void pqAndHlgAreClassifiedFromActualTransferFunction() {
        assertEquals(VideoDiagnostics.DynamicRange.HDR_PQ, VideoDiagnostics.dynamicRange(format(C.COLOR_TRANSFER_ST2084)));
        assertEquals(VideoDiagnostics.DynamicRange.HDR_HLG, VideoDiagnostics.dynamicRange(format(C.COLOR_TRANSFER_HLG)));
        String text = describe(format(C.COLOR_TRANSFER_ST2084), "HDR");
        assertTrue(text.contains("实际视频轨：HDR · PQ"));
        assertTrue(text.contains("本页未测量实际输出"));
        assertTrue(text.contains("位深：未知"));
    }

    @Test public void sdrAndLinearAreNotUpgradedByWideGamutOrHdrDisplay() {
        assertEquals(VideoDiagnostics.DynamicRange.SDR, VideoDiagnostics.dynamicRange(format(C.COLOR_TRANSFER_SDR)));
        assertEquals(VideoDiagnostics.DynamicRange.UNKNOWN, VideoDiagnostics.dynamicRange(format(C.COLOR_TRANSFER_LINEAR)));
        Format wide = new Format.Builder().setColorInfo(new ColorInfo.Builder()
                .setColorSpace(C.COLOR_SPACE_BT2020).setLumaBitdepth(10).setChromaBitdepth(10).build()).build();
        assertEquals(VideoDiagnostics.DynamicRange.UNKNOWN, VideoDiagnostics.dynamicRange(wide));
        String text = describe(format(C.COLOR_TRANSFER_SDR), "HDR");
        assertTrue(text.contains("实际视频轨：SDR"));
        assertTrue(text.contains("屏幕支持 HDR 不会把 SDR 片源变成 HDR"));
    }

    @Test public void dolbyVisionRequiresActualMimeOrCodecIdentifier() {
        assertEquals(VideoDiagnostics.DynamicRange.DOLBY_VISION, VideoDiagnostics.dynamicRange(
                new Format.Builder().setSampleMimeType("video/dolby-vision").build()));
        assertEquals(VideoDiagnostics.DynamicRange.DOLBY_VISION, VideoDiagnostics.dynamicRange(
                new Format.Builder().setCodecs("hvc1.2.4.L153.B0, dvh1.08.06").build()));
        assertEquals(VideoDiagnostics.DynamicRange.UNKNOWN, VideoDiagnostics.dynamicRange(
                new Format.Builder().setCodecs("hvc1.2.4.L153.B0").setLabel("Dolby Vision").build()));
    }

    @Test public void bitrateAndBitdepthAreReportedOnlyWhenKnown() {
        Format actual = new Format.Builder().setSampleMimeType("video/hevc").setCodecs("hvc1.2.4.L153.B0")
                .setWidth(3840).setHeight(2160).setFrameRate(23.976f).setAverageBitrate(8_500_000)
                .setPeakBitrate(12_000_000).setColorInfo(new ColorInfo.Builder()
                        .setColorTransfer(C.COLOR_TRANSFER_ST2084).setLumaBitdepth(10).setChromaBitdepth(10).build()).build();
        String text = describe(actual, "4K HDR");
        assertTrue(text.contains("3840 × 2160"));
        assertTrue(text.contains("HEVC / H.265 · hvc1.2.4.L153.B0"));
        assertTrue(text.contains("8.50 Mbps（平均）"));
        assertTrue(text.contains("位深：10 bit"));
        assertTrue(text.contains("23.98 fps"));
        assertFalse(text.contains("12.00 Mbps（平均）"));
    }

    @Test public void peakBitrateIsNotMislabelledAsAverageAndPartialDepthStaysPartial() {
        Format actual = new Format.Builder().setPeakBitrate(700_000).setColorInfo(new ColorInfo.Builder()
                .setLumaBitdepth(10).build()).build();
        String text = describe(actual, "本地");
        assertTrue(text.contains("700 kbps（峰值）"));
        assertTrue(text.contains("亮度 10 bit / 色度 未知"));
        assertEquals("未知", VideoDiagnostics.formatBitrate(0));
    }

    @Test public void textureOutputAndMissingDisplayRemainExplicitlyUnverified() {
        String text = VideoDiagnostics.describe(format(C.COLOR_TRANSFER_HLG), null, "TextureView", "HDR");
        assertTrue(text.contains("屏幕报告的 HDR 支持：未知"));
        assertTrue(text.contains("可能映射为 SDR"));
        assertTrue(text.contains("尚未确认真正 HDR 输出"));
        assertEquals("未报告 HDR 支持", VideoDiagnostics.displayHdrSupport(new int[0]));
        assertEquals("HDR10 / HLG", VideoDiagnostics.displayHdrSupport(new int[]{2, 3, 2}));
    }

    @Test public void noTrackYetIsSafeAndDoesNotLeakControlCharactersFromLabel() {
        String text = VideoDiagnostics.describe(null, (int[]) null, "未知", "HDR\n分辨率：伪造");
        assertTrue(text.contains("等待播放器读取"));
        assertTrue(text.contains("所选档位：HDR 分辨率：伪造\n实际视频轨"));
        assertTrue(text.contains("视频码率（媒体元数据）：未知"));
    }

    @Test public void playerLayoutUsesSurfaceViewAndKeepsPictureScaleIndependentOfControls() {
        org.robolectric.android.controller.ActivityController<Activity> controller =
                Robolectric.buildActivity(Activity.class).setup();
        try {
            Activity activity = controller.get();
            PlayerView player = (PlayerView) activity.getLayoutInflater().inflate(R.layout.player_surface, null, false);
            assertTrue(player.getVideoSurfaceView() instanceof SurfaceView);
            player.getVideoSurfaceView().setScaleX(1.5f);
            player.getVideoSurfaceView().setScaleY(1.5f);
            assertEquals(1.5f, player.getVideoSurfaceView().getScaleX(), 0f);
            assertEquals(1f, player.getScaleX(), 0f);
            String text = VideoDiagnostics.describe(format(C.COLOR_TRANSFER_ST2084), (Display) null,
                    player.getVideoSurfaceView(), "HDR");
            assertTrue(text.contains("视频显示方式：SurfaceView"));
            assertTrue(text.contains("屏幕支持不等于 HDR 已生效"));
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private static Format format(int transfer) {
        return new Format.Builder().setSampleMimeType("video/hevc")
                .setColorInfo(new ColorInfo.Builder().setColorTransfer(transfer).build()).build();
    }

    private static String describe(Format format, String label) {
        return VideoDiagnostics.describe(format, new int[]{Display.HdrCapabilities.HDR_TYPE_HDR10}, "SurfaceView", label);
    }
}
