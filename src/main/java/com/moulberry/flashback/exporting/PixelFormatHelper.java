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

import java.util.HashMap;
import java.util.Map;

public class PixelFormatHelper {

    public static int getBestPixelFormat(String codecName, int srcPixelFormat, boolean transparent) {
        return getBestPixelFormat(codecName, srcPixelFormat, transparent, new int[0]);
    }

    /**
     * Picks the pixel format to encode in, preferring one of {@code preferredFormats} when the
     * encoder advertises it.
     *
     * <p>Preferences matter for codecs whose profile decides the chroma layout: ProRes 4444 needs a
     * 4:4:4 format, while the 422 variants do not, and picking the cheapest advertised format would
     * silently produce a different variant than the one chosen.
     */
    public static int getBestPixelFormat(String codecName, int srcPixelFormat, boolean transparent, int[] preferredFormats) {
        BestFormatKey key = new BestFormatKey(codecName, srcPixelFormat, transparent, java.util.Arrays.hashCode(preferredFormats));

        if (bestPixelFormats.containsKey(key)) {
            return bestPixelFormats.get(key);
        }

        int bestPixelFormat = calculateBestPixelFormat(codecName, srcPixelFormat, transparent, preferredFormats);

        if (bestPixelFormat == avutil.AV_PIX_FMT_NONE) {
            throw new RuntimeException("Unable to determine best alternate pixel format for " + codecName);
        }
        if (bestPixelFormat != avutil.AV_PIX_FMT_YUV420P) {
            Flashback.LOGGER.info("Chose to use pixel format {} for codec {} with transparent={} and srcPixelFormat={}",
                pixelFormatToString(bestPixelFormat), codecName, transparent, pixelFormatToString(srcPixelFormat));
        }

        bestPixelFormats.put(key, bestPixelFormat);
        return bestPixelFormat;
    }

    private record BestFormatKey(String codec, int srcPixelFormat, boolean transparent, int preferredHash) {}
    private static final Map<BestFormatKey, Integer> bestPixelFormats = new HashMap<>();

    private static int calculateBestPixelFormat(String codecName, int srcPixelFormat, boolean transparent, int[] preferredFormats) {
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

                if (!transparent && preferredFormats.length == 0 && pixFmt == avutil.AV_PIX_FMT_YUV420P) {
                    return avutil.AV_PIX_FMT_YUV420P;
                }

                supportedFormats.add(pixFmt);
                index += 1;
            }

            supportedFormats.add(avutil.AV_PIX_FMT_NONE);

            // A preferred format wins if the encoder really advertises it.
            for (int preferred : preferredFormats) {
                if (supportedFormats.contains(preferred)) {
                    return preferred;
                }
            }

            if (transparent) {
                int format = avcodec.avcodec_find_best_pix_fmt_of_list(supportedFormats.toIntArray(), srcPixelFormat, 1, new int[1]);
                if (format != avutil.AV_PIX_FMT_NONE) {
                    return format;
                }
            }

            return avcodec.avcodec_find_best_pix_fmt_of_list(supportedFormats.toIntArray(), srcPixelFormat, 0, new int[1]);
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

    /**
     * Whether a pixel format is full-range by definition (the deprecated {@code yuvj*} JPEG family).
     *
     * <p>These formats have no way to signal a limited range, so the conversion has to be told to
     * produce full-range samples or the output comes out washed out.
     */
    public static boolean isFullRange(int pixelFormat) {
        String name = pixelFormatToString(pixelFormat);
        return name != null && name.startsWith("yuvj");
    }

}
