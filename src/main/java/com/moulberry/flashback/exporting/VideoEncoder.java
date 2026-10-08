package com.moulberry.flashback.exporting;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.combo_options.PixelDepth;
import com.moulberry.flashback.combo_options.ProResProfile;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Chooses which encoder actually encodes a codec, preferring hardware acceleration but verifying
 * that the combination genuinely works before committing to it.
 *
 * <p>The verification step matters: several encoders here advertise pixel formats they then refuse
 * to encode. {@code prores_videotoolbox} lists {@code bgra} but rejects it at open time, and
 * {@code prores_ks} lists no 12-bit format at all. Trusting the advertised list is what previously
 * produced a hard export failure, so every candidate is confirmed with a real
 * {@code avcodec_open2()} before use.
 */
public class VideoEncoder {

    private record UsabilityKey(String encoder, String pixelFormat, boolean twelveBit) {}

    private static final Map<UsabilityKey, Boolean> usabilityCache = new HashMap<>();

    /**
     * The ProRes profile implied by a resolved bit depth. Used to label the effective format when
     * the profile was left on automatic.
     */
    public static ProResProfile profileForDepth(@Nullable PixelDepth depth) {
        if (depth == PixelDepth.BIT_12) {
            return ProResProfile.XQ;
        }
        return ProResProfile.P4444;
    }

    /** True when the encoder drives dedicated media hardware rather than the CPU. */
    public static boolean isHardwareEncoder(String encoder) {
        return isHardwareAccelerated(encoder);
    }

    /** Hardware-accelerated encoders we know how to drive, in preference order. */
    private static boolean isHardwareAccelerated(String encoder) {
        return encoder.endsWith("_videotoolbox")
            || encoder.endsWith("_nvenc")
            || encoder.endsWith("_amf")
            || encoder.endsWith("_qsv")
            || encoder.endsWith("_vaapi")
            || encoder.endsWith("_vulkan");
    }

    /**
     * Returns true if the encoder can genuinely be opened with this pixel format. Results are
     * cached because opening a codec context is not free and this runs from the UI thread.
     */
    public static boolean canUse(String encoder, String pixelFormat, boolean twelveBit) {
        if (encoder == null || pixelFormat == null) {
            return false;
        }

        UsabilityKey key = new UsabilityKey(encoder, pixelFormat, twelveBit);
        Boolean cached = usabilityCache.get(key);
        if (cached != null) {
            return cached;
        }

        boolean usable = probe(encoder, pixelFormat, twelveBit);
        usabilityCache.put(key, usable);
        return usable;
    }

    private static boolean probe(String encoder, String pixelFormat, boolean twelveBit) {
        try (AVCodec codec = avcodec.avcodec_find_encoder_by_name(encoder)) {
            if (codec == null || codec.isNull()) {
                return false;
            }

            int pixelFormatId = avutil.av_get_pix_fmt(pixelFormat);
            if (pixelFormatId == avutil.AV_PIX_FMT_NONE) {
                return false;
            }

            AVCodecContext context = avcodec.avcodec_alloc_context3(codec);
            if (context == null || context.isNull()) {
                return false;
            }

            try {
                context.codec_id(codec.id());
                context.codec_type(avutil.AVMEDIA_TYPE_VIDEO);
                context.width(1920);
                context.height(1080);
                context.pix_fmt(pixelFormatId);

                // 12-bit on ProRes needs a profile that allows it; without this a 12-bit request
                // silently negotiates down to 10-bit.
                if (twelveBit && codec.id() == avcodec.AV_CODEC_ID_PRORES) {
                    context.profile(twelveBit ? avcodec.AV_PROFILE_PRORES_XQ : avcodec.AV_PROFILE_PRORES_4444);
                }

                var frameRate = avutil.av_d2q(60.0, 1001000);
                context.time_base(avutil.av_inv_q(frameRate));

                AVDictionary options = new AVDictionary(null);
                try {
                    return avcodec.avcodec_open2(context, codec, options) >= 0;
                } finally {
                    avutil.av_dict_free(options);
                }
            } finally {
                avcodec.avcodec_free_context(context);
            }
        } catch (Throwable t) {
            return false;
        }
    }

    public record Selection(@Nullable String encoder, @Nullable PixelDepth depth, @Nullable String pixelFormat, boolean hardware) {
        public boolean isValid() {
            return this.encoder != null && this.pixelFormat != null;
        }
    }

    /**
     * Selects an encoder and pixel format for an explicit ProRes profile.
     *
     * <p>Profiles are the only thing that changes ProRes file size, and not every encoder honours
     * them: the macOS VideoToolbox encoder applies all six, while FFmpeg's software
     * {@code prores_ks} ignores the profile option and derives its profile from the pixel format,
     * reaching only 422 HQ and 4444. Hardware is therefore strongly preferred, and the pixel format
     * is chosen to encode the same profile on whichever encoder is actually used.
     */
    public static Selection selectProResWithProfile(String[] candidateEncoders, ProResProfile profile) {
        if (candidateEncoders == null || candidateEncoders.length == 0) {
            return new Selection(null, null, null, false);
        }

        java.util.List<String> ordered = new java.util.ArrayList<>();
        for (String encoder : candidateEncoders) {
            if (isHardwareAccelerated(encoder)) {
                ordered.add(encoder);
            }
        }
        for (String encoder : candidateEncoders) {
            if (!isHardwareAccelerated(encoder)) {
                ordered.add(encoder);
            }
        }

        String hardwareFormat = profile.isTwelveBit() ? "p416le" : "p410le";
        PixelDepth depth = profile.pixelDepth();

        // Hardware first: it honours the requested profile exactly.
        for (String encoder : ordered) {
            if (!isHardwareAccelerated(encoder)) {
                continue;
            }
            if (canUse(encoder, hardwareFormat, profile.isTwelveBit())) {
                Flashback.LOGGER.info("Using hardware encoder {} for ProRes {} ({})",
                    encoder, profile.text(), hardwareFormat);
                return new Selection(encoder, depth, hardwareFormat, true);
            }
        }

        // Software fallback. prores_ks ignores the profile, so only 422 HQ and 4444 are reachable,
        // and only through the pixel format that maps to them.
        if (profile.softwareSupported()) {
            String softwareFormat = profile.softwarePixelFormat();
            for (String encoder : ordered) {
                if (isHardwareAccelerated(encoder)) {
                    continue;
                }
                if (canUse(encoder, softwareFormat, profile.isTwelveBit())) {
                    Flashback.LOGGER.info("Using software encoder {} for ProRes {} ({})",
                        encoder, profile.text(), softwareFormat);
                    return new Selection(encoder, depth, softwareFormat, false);
                }
            }
        } else {
            Flashback.LOGGER.warn("ProRes {} has no hardware encoder available and cannot be produced in software; "
                + "falling back to 422 HQ", profile.text());
            for (String encoder : ordered) {
                if (!isHardwareAccelerated(encoder) && canUse(encoder, "yuv422p10le", false)) {
                    return new Selection(encoder, PixelDepth.BIT_10, "yuv422p10le", false);
                }
            }
        }

        Flashback.LOGGER.warn("No encoder could produce ProRes {}, falling back to {}", profile.text(), candidateEncoders[0]);
        return new Selection(candidateEncoders[0], depth, null, isHardwareAccelerated(candidateEncoders[0]));
    }

    /**
     * Selects an encoder for a specific, explicitly chosen bit depth. Unlike
     * {@link #select(String[], PixelDepth)} this will not silently upgrade to a different depth -
     * if the requested one is unavailable the resulting format is null and the caller can fall back
     * to the generic selection.
     */
    public static Selection selectWithDepth(String[] candidateEncoders, PixelDepth requestedDepth) {
        if (candidateEncoders == null || candidateEncoders.length == 0) {
            return new Selection(null, null, null, false);
        }

        java.util.List<String> ordered = new java.util.ArrayList<>();
        for (String encoder : candidateEncoders) {
            if (isHardwareAccelerated(encoder)) {
                ordered.add(encoder);
            }
        }
        for (String encoder : candidateEncoders) {
            if (!isHardwareAccelerated(encoder)) {
                ordered.add(encoder);
            }
        }

        if (requestedDepth == null) {
            return select(candidateEncoders, null);
        }

        String softwareFallbackEncoder = null;
        for (String encoder : ordered) {
            for (String pixelFormat : requestedDepth.pixelFormatNames()) {
                if (canUse(encoder, pixelFormat, requestedDepth.isTwelveBit())) {
                    boolean hardware = isHardwareAccelerated(encoder);
                    if (hardware) {
                        Flashback.LOGGER.info("Using hardware encoder {} with {} ({})",
                            encoder, pixelFormat, requestedDepth.text());
                        return new Selection(encoder, requestedDepth, pixelFormat, true);
                    }
                    if (softwareFallbackEncoder == null) {
                        softwareFallbackEncoder = encoder;
                    }
                }
            }
            if (softwareFallbackEncoder != null) {
                Flashback.LOGGER.info("Using software encoder {} with {} ({})",
                    softwareFallbackEncoder, requestedDepth.pixelFormatNames()[0], requestedDepth.text());
                return new Selection(softwareFallbackEncoder, requestedDepth,
                    requestedDepth.pixelFormatNames()[0], false);
            }
        }

        Flashback.LOGGER.warn("No encoder could provide {} output", requestedDepth.text());
        return new Selection(null, null, null, false);
    }

    /**
     * The bit depths worth offering for these encoders, ascending. 8-bit and 10-bit are offered
     * whenever the build can encode them at all (both are always available in practice); 12-bit
     * only when something can genuinely produce it, which today means the hardware ProRes encoder
     * with 4444 XQ.
     */
    public static PixelDepth[] selectableDepths(String[] candidateEncoders) {
        java.util.List<PixelDepth> selectable = new java.util.ArrayList<>();
        for (PixelDepth depth : new PixelDepth[]{PixelDepth.BIT_8, PixelDepth.BIT_10}) {
            selectable.add(depth);
        }
        for (PixelDepth depth : new PixelDepth[]{PixelDepth.BIT_12}) {
            for (String pixelFormat : depth.pixelFormatNames()) {
                boolean usable = false;
                for (String encoder : candidateEncoders) {
                    if (canUse(encoder, pixelFormat, true)) {
                        usable = true;
                        break;
                    }
                }
                if (usable) {
                    selectable.add(depth);
                    break;
                }
            }
        }
        return selectable.toArray(new PixelDepth[0]);
    }

    /**
     * The depths that any of these encoders can genuinely deliver, so the UI never offers a choice
     * that would silently fall back to 8-bit.
     */
    public static PixelDepth[] availableDepths(String[] candidateEncoders) {
        if (candidateEncoders == null || candidateEncoders.length == 0) {
            return new PixelDepth[0];
        }

        java.util.List<PixelDepth> usable = new java.util.ArrayList<>();
        for (PixelDepth depth : PixelDepth.values()) {
            boolean available = false;
            for (String encoder : candidateEncoders) {
                for (String pixelFormat : depth.pixelFormatNames()) {
                    if (canUse(encoder, pixelFormat, depth.isTwelveBit())) {
                        available = true;
                        break;
                    }
                }
                if (available) {
                    break;
                }
            }
            if (available) {
                usable.add(depth);
            }
        }
        return usable.toArray(new PixelDepth[0]);
    }

    /**
     * Picks the encoder to use for a codec and depth.
     *
     * <p>Hardware encoders are tried first, then software. A candidate is only accepted if it can
     * actually open at the requested depth, so this returns a working combination or a best-effort
     * software fallback rather than something that will fail later mid-export.
     *
     * @param candidateEncoders encoders for the codec, in preference order
     * @param requestedDepth    the depth the user asked for, or null for the codec default
     */
    public static Selection select(String[] candidateEncoders, @Nullable PixelDepth requestedDepth) {
        if (candidateEncoders == null || candidateEncoders.length == 0) {
            return new Selection(null, null, null, false);
        }

        // Hardware first, then software, each preserving the caller's relative order.
        java.util.List<String> ordered = new java.util.ArrayList<>();
        for (String encoder : candidateEncoders) {
            if (isHardwareAccelerated(encoder)) {
                ordered.add(encoder);
            }
        }
        for (String encoder : candidateEncoders) {
            if (!isHardwareAccelerated(encoder)) {
                ordered.add(encoder);
            }
        }

        String softwareFallback = null;
        PixelDepth softwareFallbackDepth = null;
        String softwareFallbackPixelFormat = null;
        boolean softwareFallbackValid = false;

        for (String encoder : ordered) {
            boolean hardware = isHardwareAccelerated(encoder);

            // Try the requested depth, then any lower depth this encoder can actually deliver.
            for (PixelDepth depth = requestedDepth; depth != null; depth = depth.fallbackDepth()) {
                for (String pixelFormat : depth.pixelFormatNames()) {
                    if (canUse(encoder, pixelFormat, depth.isTwelveBit())) {
                        if (hardware) {
                            Flashback.LOGGER.info("Using hardware encoder {} with {}{}",
                                encoder, pixelFormat, depth == requestedDepth ? "" : " (" + depth.text() + " fallback)");
                            return new Selection(encoder, depth, pixelFormat, true);
                        }
                        if (!softwareFallbackValid) {
                            softwareFallback = encoder;
                            softwareFallbackDepth = depth;
                            softwareFallbackPixelFormat = pixelFormat;
                            softwareFallbackValid = true;
                        }
                        break;
                    }
                }
            }
        }

        if (softwareFallbackValid) {
            Flashback.LOGGER.info("Using software encoder {} with {}", softwareFallback, softwareFallbackPixelFormat);
            return new Selection(softwareFallback, softwareFallbackDepth, softwareFallbackPixelFormat, false);
        }

        // Nothing at any depth worked. Let the existing selection path decide rather than failing
        // here; the recorder's own open-failure retry remains the last line of defence.
        Flashback.LOGGER.warn("No encoder for this codec could be verified; falling back to {}", candidateEncoders[0]);
        return new Selection(candidateEncoders[0], requestedDepth, null, isHardwareAccelerated(candidateEncoders[0]));
    }

}
