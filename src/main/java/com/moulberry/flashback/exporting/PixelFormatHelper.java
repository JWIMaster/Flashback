package com.moulberry.flashback.exporting;

import com.moulberry.flashback.Flashback;
import it.unimi.dsi.fastutil.ints.Int2BooleanMap;
import it.unimi.dsi.fastutil.ints.Int2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public class PixelFormatHelper {

    private record BestFormatKey(String codec, int srcPixelFormat, boolean transparent, @Nullable String preferredPixelFormat) {}
    private static final Map<BestFormatKey, Integer> bestPixelFormats = new HashMap<>();

    public static int getBestPixelFormat(String codecName, int srcPixelFormat, boolean transparent, @Nullable String preferredPixelFormatName) {
        int preferred = preferredPixelFormatName != null
            ? pixelFormatByName(preferredPixelFormatName)
            : avutil.AV_PIX_FMT_NONE;

        BestFormatKey key = new BestFormatKey(codecName, srcPixelFormat, transparent, preferredPixelFormatName);

        if (bestPixelFormats.containsKey(key)) {
            return bestPixelFormats.get(key);
        }

        int bestPixelFormat = calculateBestPixelFormat(codecName, srcPixelFormat, transparent, preferred);

        if (bestPixelFormat == avutil.AV_PIX_FMT_NONE) {
            throw new RuntimeException("Unable to determine best alternate pixel format for " + codecName);
        }
        if (bestPixelFormat != avutil.AV_PIX_FMT_YUV420P) {
            Flashback.LOGGER.info("Chose to use pixel format {} for codec {} with transparent={}, srcPixelFormat={}, preferred={}",
                pixelFormatToString(bestPixelFormat), codecName, transparent, pixelFormatToString(srcPixelFormat), preferredPixelFormatName);
        }

        bestPixelFormats.put(key, bestPixelFormat);
        return bestPixelFormat;
    }

    private static int pixelFormatByName(String name) {
        try {
            return avutil.av_get_pix_fmt(name);
        } catch (Throwable t) {
            return avutil.AV_PIX_FMT_NONE;
        }
    }

    private static int calculateBestPixelFormat(String codecName, int srcPixelFormat, boolean transparent, int preferredPixelFormat) {
        try (AVCodec codec = avcodec.avcodec_find_encoder_by_name(codecName)) {
            IntList supportedFormats = new IntArrayList();

            IntPointer pixFmts = codec.pix_fmts();

            if (pixFmts == null) {
                Flashback.LOGGER.info("Encoder {} does not provide a list of supported pixel formats, falling back to YUV420P", codecName);
                return avutil.AV_PIX_FMT_YUV420P;
            }

            int index = 0;
            while (true) {
                int pixFmt = pixFmts.get(index);
                if (pixFmt == -1) {
                    break;
                }

                if (!transparent && preferredPixelFormat == avutil.AV_PIX_FMT_NONE && pixFmt == avutil.AV_PIX_FMT_YUV420P) {
                    return avutil.AV_PIX_FMT_YUV420P;
                }

                supportedFormats.add(pixFmt);
                index += 1;
            }

            // An explicitly chosen pixel format wins, provided the encoder really offers it. It was
            // verified by opening the encoder before being written into the export settings.
            if (preferredPixelFormat != avutil.AV_PIX_FMT_NONE && supportedFormats.contains(preferredPixelFormat)) {
                Flashback.LOGGER.info("Using selected pixel format {} for encoder {}",
                    pixelFormatToString(preferredPixelFormat), codecName);
                return preferredPixelFormat;
            }

            supportedFormats.add(avutil.AV_PIX_FMT_NONE);

            if (transparent) {
                int format = avcodec.avcodec_find_best_pix_fmt_of_list(supportedFormats.toIntArray(), srcPixelFormat, 1, new int[1]);
                if (format != avutil.AV_PIX_FMT_NONE) {
                    return format;
                }
            }

            return avcodec.avcodec_find_best_pix_fmt_of_list(supportedFormats.toIntArray(), srcPixelFormat, 0, new int[1]);
        }
    }

    /** Overload for callers with no explicit pixel-format preference. */
    public static int getBestPixelFormat(String codecName, int srcPixelFormat, boolean transparent) {
        return getBestPixelFormat(codecName, srcPixelFormat, transparent, null);
    }

    /**
     * Whether {@code encoderName} advertises {@code pixelFormat}. Used to pick a 10-bit format for
     * HDR, where the candidate list spans encoders that report different families.
     */
    public static boolean supportsPixelFormat(String encoderName, int pixelFormat) {
        try (AVCodec codec = avcodec.avcodec_find_encoder_by_name(encoderName)) {
            if (codec == null || codec.isNull() || codec.pix_fmts() == null) {
                return false;
            }
            for (int i = 0; ; i++) {
                int format = codec.pix_fmts().get(i);
                if (format == -1) {
                    return false;
                }
                if (format == pixelFormat) {
                    return true;
                }
            }
        }
    }

    // Pixel format supports transparency
    private static final Int2BooleanMap pixelFormatSupportsTransparency = new Int2BooleanOpenHashMap();

    public static boolean doesPixelFormatSupportTransparency(int pixelFormat) {
        if (pixelFormatSupportsTransparency.containsKey(pixelFormat)) {
            return pixelFormatSupportsTransparency.get(pixelFormat);
        }

        boolean supports = calculateDoesPixelFormatSupportTransparency(pixelFormat);
        pixelFormatSupportsTransparency.put(pixelFormat, supports);
        return supports;
    }

    private static boolean calculateDoesPixelFormatSupportTransparency(int pixelFormat) {
        try (var descriptor = avutil.av_pix_fmt_desc_get(pixelFormat)) {
            if (descriptor == null || descriptor.isNull()) {
                return false;
            }

            return (descriptor.flags() & avutil.AV_PIX_FMT_FLAG_ALPHA) != 0;
        }
    }

    // Pixel format names
    private static final Int2ObjectMap<String> pixelFormatNames = new Int2ObjectOpenHashMap<>();

    public static String pixelFormatToString(int pixelFormat) {
        if (pixelFormatNames.containsKey(pixelFormat)) {
            return pixelFormatNames.get(pixelFormat);
        }

        String name = pixelFormatToStringInner(pixelFormat);
        pixelFormatNames.put(pixelFormat, name);
        return name;
    }

    private static String pixelFormatToStringInner(int pixelFormat) {
        try (BytePointer name = avutil.av_get_pix_fmt_name(pixelFormat)) {
            return name.getString();
        } catch (Throwable ignored) {}
        return "UNKNOWN(" + pixelFormat + ")";
    }

    public static boolean isYuvFormat(int pixelFormat) {
        try (var descriptor = avutil.av_pix_fmt_desc_get(pixelFormat)) {
            if (descriptor == null || descriptor.isNull()) {
                throw new RuntimeException();
            }

            return (descriptor.flags() & avutil.AV_PIX_FMT_FLAG_RGB) == 0 && descriptor.nb_components() >= 2;
        }
    }

}
