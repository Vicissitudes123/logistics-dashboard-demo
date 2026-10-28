package com.animeshelf.app;

import android.view.Display;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reports the current input track and display capabilities, never a measured HDR output state. */
@UnstableApi
public final class VideoDiagnostics {
    private VideoDiagnostics() { }

    public enum DynamicRange { DOLBY_VISION, HDR_PQ, HDR_HLG, SDR, UNKNOWN }

    /** Read on the player's application thread, using ExoPlayer.getVideoFormat(). */
    public static String describe(Format format, Display display, View surface, String sourceLabel) {
        int[] hdrTypes = null;
        if (display != null) {
            try {
                Display.HdrCapabilities capabilities = display.getHdrCapabilities();
                if (capabilities != null) hdrTypes = capabilities.getSupportedHdrTypes();
            } catch (RuntimeException ignored) {
                // A detached/external display can disappear while its information is being shown.
            }
        }
        String surfaceType = surface instanceof SurfaceView ? "SurfaceView"
                : surface instanceof TextureView ? "TextureView" : "未知";
        return describe(format, hdrTypes, surfaceType, sourceLabel);
    }

    static String describe(Format format, int[] hdrTypes, String surfaceType, String sourceLabel) {
        ColorInfo color = format == null ? null : format.colorInfo;
        DynamicRange range = dynamicRange(format);
        StringBuilder text = new StringBuilder();
        text.append("所选档位：").append(oneLine(sourceLabel, "本地视频 / 未提供"));
        text.append("\n实际视频轨：").append(rangeName(range));
        if (format == null) text.append("（等待播放器读取）");
        text.append("\n分辨率：").append(resolution(format));
        text.append("\n编码：").append(codec(format));
        text.append("\n视频码率（媒体元数据）：").append(bitrate(format));
        text.append("\n帧率：").append(format != null && format.frameRate > 0 && Float.isFinite(format.frameRate)
                ? String.format(Locale.ROOT, "%.2f fps", format.frameRate) : "未知");
        text.append("\n色彩传递：").append(transferName(color == null ? Format.NO_VALUE : color.colorTransfer));
        text.append("\n色彩空间：").append(colorSpaceName(color == null ? Format.NO_VALUE : color.colorSpace));
        text.append("\n位深：").append(bitdepth(color));
        text.append("\n屏幕报告的 HDR 支持：").append(displayHdrSupport(hdrTypes));
        text.append("\n视频显示方式：").append(surfaceType);
        text.append("\n\n").append(outputNote(range, surfaceType));
        return text.toString();
    }

    /** The quality label, resolution, bit depth and BT.2020 alone do not prove HDR. */
    public static DynamicRange dynamicRange(Format format) {
        if (format == null) return DynamicRange.UNKNOWN;
        if ("video/dolby-vision".equalsIgnoreCase(format.sampleMimeType) || hasDolbyCodec(format.codecs))
            return DynamicRange.DOLBY_VISION;
        if (format.colorInfo == null) return DynamicRange.UNKNOWN;
        switch (format.colorInfo.colorTransfer) {
            case C.COLOR_TRANSFER_ST2084: return DynamicRange.HDR_PQ;
            case C.COLOR_TRANSFER_HLG: return DynamicRange.HDR_HLG;
            case C.COLOR_TRANSFER_SDR:
            case C.COLOR_TRANSFER_SRGB:
            case C.COLOR_TRANSFER_GAMMA_2_2: return DynamicRange.SDR;
            default: return DynamicRange.UNKNOWN;
        }
    }

    private static boolean hasDolbyCodec(String codecs) {
        if (codecs == null) return false;
        for (String value : codecs.toLowerCase(Locale.ROOT).split(",")) {
            String token = value.trim();
            if (token.startsWith("dvhe.") || token.startsWith("dvh1.")
                    || token.startsWith("dvav.") || token.startsWith("dva1.") || token.startsWith("dav1.")) return true;
        }
        return false;
    }

    private static String rangeName(DynamicRange range) {
        switch (range) {
            case DOLBY_VISION: return "Dolby Vision";
            case HDR_PQ: return "HDR · PQ / ST 2084";
            case HDR_HLG: return "HDR · HLG";
            case SDR: return "SDR";
            default: return "未知（不能仅凭档位标签判断 HDR）";
        }
    }

    private static String outputNote(DynamicRange range, String surfaceType) {
        if (range == DynamicRange.SDR) return "当前视频轨报告为 SDR。屏幕支持 HDR 不会把 SDR 片源变成 HDR。";
        if (range == DynamicRange.UNKNOWN) return "尚不能确认片源是否为 HDR。屏幕支持列表只表示能力，不表示已经输出 HDR。";
        if ("TextureView".equals(surfaceType))
            return "已识别 HDR 片源，但 TextureView 的 HDR 支持有限，可能映射为 SDR。尚未确认真正 HDR 输出。";
        return "已识别 HDR 片源。真正 HDR 输出仍取决于解码器、屏幕和系统；本页未测量实际输出，屏幕支持不等于 HDR 已生效。";
    }

    private static String resolution(Format format) {
        if (format == null || format.width <= 0 || format.height <= 0) return "未知";
        String value = format.width + " × " + format.height;
        return format.rotationDegrees == 90 || format.rotationDegrees == 270
                ? value + "（旋转 " + format.rotationDegrees + "°）" : value;
    }

    private static String codec(Format format) {
        if (format == null) return "未知";
        String mime = format.sampleMimeType;
        String name;
        if ("video/hevc".equals(mime)) name = "HEVC / H.265";
        else if ("video/avc".equals(mime)) name = "AVC / H.264";
        else if ("video/av01".equals(mime)) name = "AV1";
        else if ("video/x-vnd.on2.vp9".equals(mime)) name = "VP9";
        else if ("video/dolby-vision".equals(mime)) name = "Dolby Vision";
        else name = oneLine(mime, "未知");
        return format.codecs == null || format.codecs.trim().isEmpty()
                ? name : name + " · " + oneLine(format.codecs, "未知");
    }

    private static String bitrate(Format format) {
        if (format == null) return "未知";
        if (format.averageBitrate > 0) return formatBitrate(format.averageBitrate) + "（平均）";
        if (format.peakBitrate > 0) return formatBitrate(format.peakBitrate) + "（峰值）";
        return "未知";
    }

    /** For explicitly labelled platform metadata too; this is not a throughput measurement. */
    public static String formatBitrate(long bitsPerSecond) {
        if (bitsPerSecond <= 0) return "未知";
        return bitsPerSecond >= 1_000_000
                ? String.format(Locale.ROOT, "%.2f Mbps", bitsPerSecond / 1_000_000d)
                : String.format(Locale.ROOT, "%.0f kbps", bitsPerSecond / 1_000d);
    }

    private static String bitdepth(ColorInfo color) {
        if (color == null) return "未知";
        int luma = color.lumaBitdepth, chroma = color.chromaBitdepth;
        if (luma <= 0 && chroma <= 0) return "未知";
        if (luma > 0 && luma == chroma) return luma + " bit";
        return "亮度 " + (luma > 0 ? luma + " bit" : "未知")
                + " / 色度 " + (chroma > 0 ? chroma + " bit" : "未知");
    }

    private static String transferName(int transfer) {
        switch (transfer) {
            case C.COLOR_TRANSFER_ST2084: return "PQ / ST 2084";
            case C.COLOR_TRANSFER_HLG: return "HLG";
            case C.COLOR_TRANSFER_SDR: return "SDR / SMPTE 170M";
            case C.COLOR_TRANSFER_SRGB: return "sRGB";
            case C.COLOR_TRANSFER_GAMMA_2_2: return "Gamma 2.2";
            case C.COLOR_TRANSFER_LINEAR: return "Linear（单凭此项无法确认 HDR）";
            default: return transfer == Format.NO_VALUE ? "未知" : "未识别（" + transfer + "）";
        }
    }

    private static String colorSpaceName(int space) {
        switch (space) {
            case C.COLOR_SPACE_BT601: return "BT.601";
            case C.COLOR_SPACE_BT709: return "BT.709";
            case C.COLOR_SPACE_BT2020: return "BT.2020";
            default: return space == Format.NO_VALUE ? "未知" : "未识别（" + space + "）";
        }
    }

    static String displayHdrSupport(int[] types) {
        if (types == null) return "未知";
        if (types.length == 0) return "未报告 HDR 支持";
        List<String> names = new ArrayList<>();
        for (int type : types) {
            String name;
            switch (type) {
                case Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION: name = "Dolby Vision"; break;
                case Display.HdrCapabilities.HDR_TYPE_HDR10: name = "HDR10"; break;
                case Display.HdrCapabilities.HDR_TYPE_HLG: name = "HLG"; break;
                case Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS: name = "HDR10+"; break;
                default: name = "未识别类型 " + type;
            }
            if (!names.contains(name)) names.add(name);
        }
        return String.join(" / ", names);
    }

    private static String oneLine(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        String clean = value.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ").trim();
        return clean.length() > 160 ? clean.substring(0, 160) + "…" : clean;
    }
}
