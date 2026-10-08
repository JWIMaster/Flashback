package com.moulberry.flashback.combo_options;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.IntPointer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Output bit depth for encoders that support more than one, currently used by ProRes
 * (10-bit 422 HQ / 4444, 12-bit 4444 XQ).
 *
 * <p>Intended pixel formats are expressed as an ordered list of preferences. The first entry
 * that the chosen encoder actually offers wins; if none are available we fall through to the
 * normal "best available" selection, so a codec/depth combination can never hard-fail here.
 */
public enum PixelDepth implements ComboOption {

    // Preference order is most-capable first. The hardware ProRes encoder (VideoToolbox) only
    // accepts Apple's own formats (p410le/p416le); the software prores_ks only accepts the planar
    // yuv ones. Listing both lets a single preference cover hardware and software.
    BIT_8("8-bit", "nv12", "yuv420p"),
    BIT_10("10-bit", "yuv444p10le", "yuv422p10le", "yuva444p10le", "p410le", "p010le", "yuv420p10le"),
    BIT_12("12-bit", "p416le");

    private final String text;
    private final String[] preferredPixelFormats;

    PixelDepth(String text, String... preferredPixelFormats) {
        this.text = text;
        this.preferredPixelFormats = preferredPixelFormats;
    }

    @Override
    public String text() {
        return this.text;
    }

    /** Bits per channel this depth represents. */
    public int bitDepth() {
        return this == BIT_8 ? 8 : (this == BIT_12 ? 12 : 10);
    }

    /** True when this depth is 12-bit. */
    public boolean isTwelveBit() {
        return this == BIT_12;
    }

    /** True when this depth is the 8-bit baseline. */
    public boolean isEightBit() {
        return this == BIT_8;
    }

    /** The pixel format names this depth is willing to use, in preference order. */
    public String[] pixelFormatNames() {
        return this.preferredPixelFormats.clone();
    }

    /**
     * Resolves this depth to a concrete FFmpeg pixel format supported by the given encoder,
     * or {@link avutil#AV_PIX_FMT_NONE} if none of the preferred formats are available.
     */
    public int resolvePixelFormat(String encoderName) {
        int[] supported = supportedPixelFormats(encoderName);
        if (supported.length == 0) {
            return avutil.AV_PIX_FMT_NONE;
        }

        for (String name : this.preferredPixelFormats) {
            int pixelFormat = pixelFormatByName(name);
            if (pixelFormat == avutil.AV_PIX_FMT_NONE) {
                continue;
            }
            for (int candidate : supported) {
                if (candidate == pixelFormat) {
                    return pixelFormat;
                }
            }
        }

        return avutil.AV_PIX_FMT_NONE;
    }

    /**
     * The preferred pixel formats for this depth that the given encoder actually supports,
     * in preference order. Used to populate the UI without offering unsupported choices.
     */
    public String[] availablePixelFormats(String encoderName) {
        int[] supported = supportedPixelFormats(encoderName);
        if (supported.length == 0) {
            return new String[0];
        }

        List<String> available = new ArrayList<>();
        for (String name : this.preferredPixelFormats) {
            int pixelFormat = pixelFormatByName(name);
            if (pixelFormat == avutil.AV_PIX_FMT_NONE) {
                continue;
            }
            for (int candidate : supported) {
                if (candidate == pixelFormat) {
                    available.add(name);
                    break;
                }
            }
        }
        return available.toArray(new String[0]);
    }

    /**
     * Resolves a pixel format by name, returning {@link avutil#AV_PIX_FMT_NONE} if unknown.
     */
    private static int pixelFormatByName(String name) {
        try {
            return avutil.av_get_pix_fmt(name);
        } catch (Throwable t) {
            return avutil.AV_PIX_FMT_NONE;
        }
    }

    /**
     * Caches the pixel formats an encoder advertises. An empty array means "unknown or none",
     * in which case the caller falls back to the generic selection logic.
     */
    private static final java.util.Map<String, int[]> supportedPixelFormatsCache = new java.util.HashMap<>();

    private static int[] supportedPixelFormats(String encoderName) {
        int[] cached = supportedPixelFormatsCache.get(encoderName);
        if (cached != null) {
            return cached;
        }

        int[] result = new int[0];
        try (AVCodec codec = avcodec.avcodec_find_encoder_by_name(encoderName)) {
            if (codec != null && !codec.isNull()) {
                IntPointer pixelFormats = codec.pix_fmts();
                if (pixelFormats != null) {
                    List<Integer> formats = new ArrayList<>();
                    for (int index = 0; ; index++) {
                        int pixelFormat = pixelFormats.get(index);
                        if (pixelFormat == -1) {
                            break;
                        }
                        formats.add(pixelFormat);
                    }

                    result = new int[formats.size()];
                    for (int i = 0; i < result.length; i++) {
                        result[i] = formats.get(i);
                    }
                }
            }
        } catch (Throwable t) {
            result = new int[0];
        }

        supportedPixelFormatsCache.put(encoderName, result);
        return result;
    }

    /**
     * The depths that are actually usable with the given encoder, so the UI never offers a
     * choice that would silently fall back to 8-bit.
     */
    public static PixelDepth[] findSupportedDepths(String encoderName) {
        List<PixelDepth> supported = new ArrayList<>();
        for (PixelDepth depth : values()) {
            if (depth.resolvePixelFormat(encoderName) != avutil.AV_PIX_FMT_NONE) {
                supported.add(depth);
            }
        }
        return supported.toArray(new PixelDepth[0]);
    }

    /**
     * The depth to try if this one is unavailable on the chosen encoder. Only ever degrades to a
     * lower depth: asking for 12-bit on a 10-bit-only encoder gives 10-bit, never the reverse.
     */
    public @Nullable PixelDepth fallbackDepth() {
        int lower = this.ordinal() - 1;
        if (lower < 0) {
            return null;
        }
        return values()[lower];
    }

}
