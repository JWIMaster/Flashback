package com.moulberry.flashback.combo_options;

import org.bytedeco.ffmpeg.global.avcodec;

/**
 * Which Apple ProRes variant to encode.
 *
 * <p>ProRes has no bitrate control: the profile is the only quality knob, and it also decides the
 * chroma layout (422 for the standard variants, 4444 when an alpha channel is needed). Presenting a
 * bitrate for ProRes would be misleading, so this replaces it.
 *
 * <p>The numeric values are FFmpeg's {@code AV_PROFILE_PRORES_*} codes, which is what the encoders
 * expect for their {@code profile} option.
 */
public enum ProResProfile implements ComboOption {

    PROXY("ProRes 422 Proxy", avcodec.AV_PROFILE_PRORES_PROXY, false),
    LT("ProRes 422 LT", avcodec.AV_PROFILE_PRORES_LT, false),
    STANDARD("ProRes 422", avcodec.AV_PROFILE_PRORES_STANDARD, false),
    HQ("ProRes 422 HQ", avcodec.AV_PROFILE_PRORES_HQ, false),
    P4444("ProRes 4444", avcodec.AV_PROFILE_PRORES_4444, true),
    P4444XQ("ProRes 4444 XQ", avcodec.AV_PROFILE_PRORES_XQ, true);

    private final String text;
    private final int profile;
    private final boolean supportsAlpha;

    ProResProfile(String text, int profile, boolean supportsAlpha) {
        this.text = text;
        this.profile = profile;
        this.supportsAlpha = supportsAlpha;
    }

    @Override
    public String text() {
        return this.text;
    }

    public int profile() {
        return this.profile;
    }

    /** Only the 4444 variants carry an alpha channel. */
    public boolean supportsAlpha() {
        return this.supportsAlpha;
    }

    public static ProResProfile fromProfile(int profile) {
        for (ProResProfile value : values()) {
            if (value.profile == profile) {
                return value;
            }
        }
        return null;
    }

}
