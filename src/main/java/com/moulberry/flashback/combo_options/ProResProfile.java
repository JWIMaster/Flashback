package com.moulberry.flashback.combo_options;

import org.bytedeco.ffmpeg.global.avcodec;

/**
 * ProRes profiles, from smallest to largest.
 *
 * <p>ProRes is constant-quality, and its bitrate is fixed per profile for a given resolution and
 * framerate. Unlike H.264/HEVC there is <em>no</em> bitrate or quality knob: the profile is the
 * only thing that changes file size. At 4K the difference is dramatic - ProRes 422 HQ runs around
 * 1.3 Gbps, so a half-minute clip is multiple gigabytes, while LT is roughly half that and Proxy
 * about a fifth.
 *
 * <p>Profiles can only be selected on encoders that actually honour them. The macOS VideoToolbox
 * encoder applies any of these; FFmpeg's software {@code prores_ks} derives its profile purely
 * from the pixel format and ignores the requested profile entirely.
 */
public enum ProResProfile implements ComboOption {

    /** Automatic: the encoder picks the profile, preserving the previous behaviour. */
    AUTO("Automatic", -1, null, false),
    PROXY("422 Proxy", avcodec.AV_PROFILE_PRORES_PROXY, "yuv422p10le", false),
    LT("422 LT", avcodec.AV_PROFILE_PRORES_LT, "yuv422p10le", false),
    STANDARD("422 Standard", avcodec.AV_PROFILE_PRORES_STANDARD, "yuv422p10le", false),
    HQ("422 HQ", avcodec.AV_PROFILE_PRORES_HQ, "yuv422p10le", false),
    P4444("4444", avcodec.AV_PROFILE_PRORES_4444, "yuv444p10le", false),
    XQ("4444 XQ", avcodec.AV_PROFILE_PRORES_XQ, "yuv444p12le", true);

    private final String text;
    private final int profileId;
    private final String softwarePixelFormat;
    private final boolean twelveBit;

    ProResProfile(String text, int profileId, String softwarePixelFormat, boolean twelveBit) {
        this.text = text;
        this.profileId = profileId;
        this.softwarePixelFormat = softwarePixelFormat;
        this.twelveBit = twelveBit;
    }

    @Override
    public String text() {
        return this.text;
    }

    /**
     * Whether this entry means "let the encoder decide". A {@code null} profile is represented by
     * this constant in the menus, so the user can return to automatic selection.
     */
    public boolean isCodecDefault() {
        return this == AUTO;
    }

    /** Resolves a menu selection back to a profile, mapping {@link #AUTO} to null. */
    public static @org.jetbrains.annotations.Nullable ProResProfile fromSelection(ProResProfile selection) {
        return selection == null || selection.isCodecDefault() ? null : selection;
    }

    /**
     * Menu entries: automatic selection, then every explicit profile that either has hardware
     * support or can be produced by the software encoder.
     */
    public static ProResProfile[] menuEntries(boolean hardwareAvailable) {
        java.util.List<ProResProfile> entries = new java.util.ArrayList<>();
        entries.add(AUTO);
        for (ProResProfile profile : values()) {
            if (profile.isCodecDefault()) {
                continue;
            }
            if (hardwareAvailable || profile.softwareSupported()) {
                entries.add(profile);
            }
        }
        return entries.toArray(new ProResProfile[0]);
    }

    /** The FFmpeg profile constant, or -1 for {@link #AUTO} (let the encoder decide). */
    public int profileId() {
        return this.profileId;
    }

    /** Whether this profile carries 12-bit data (only 4444 XQ does). */
    public boolean isTwelveBit() {
        return this.twelveBit;
    }

    /** True when this entry is a real profile rather than the automatic option. */
    public boolean isExplicit() {
        return this != AUTO;
    }

    /** The output bit depth this profile is able to deliver. */
    public PixelDepth pixelDepth() {
        return this.twelveBit ? PixelDepth.BIT_12 : PixelDepth.BIT_10;
    }

    /**
     * The pixel format that selects this profile on an encoder which ignores the profile option
     * (FFmpeg's software {@code prores_ks}). Only 422 HQ and 4444 are reachable that way.
     */
    public String softwarePixelFormat() {
        return this.softwarePixelFormat;
    }

    /** True when the software encoder can produce this profile at all. */
    public boolean softwareSupported() {
        return this == HQ || this == P4444;
    }

    /**
     * Approximate data rate relative to ProRes 422 HQ, for showing the user what a profile costs.
     * These are the standard Apple ProRes target rates.
     */
    public double relativeDataRate() {
        return switch (this) {
            case AUTO -> 1.00;
            case PROXY -> 0.20;
            case LT -> 0.47;
            case STANDARD -> 0.66;
            case HQ -> 1.00;
            case P4444 -> 1.17;
            case XQ -> 1.76;
        };
    }

}
