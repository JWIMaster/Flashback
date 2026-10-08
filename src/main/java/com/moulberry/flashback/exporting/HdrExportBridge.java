package com.moulberry.flashback.exporting;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;

/**
 * Hook for an external HDR mod to supply the colour transform used when exporting HDR.
 *
 * <p>Flashback cannot produce HDR on its own: the renderer delivers an SDR image, and PQ-encoding
 * an SDR capture is not HDR - it produces a file that claims BT.2020/PQ while carrying no more
 * luminance range than the source. So HDR export is only offered while a mod that genuinely
 * renders HDR registers a transform here. With nothing registered the option does not appear and
 * the 8-bit SDR path is used unchanged.
 *
 * <p>External mods locate this class and its nested interface <em>by name</em> through reflection,
 * so the class name, the interface name, the method signature and the static {@code register}
 * method are all part of the contract and must not be renamed or reordered.
 */
public class HdrExportBridge {

    public interface ColorTransform {
        /**
         * Renders {@code source} through the HDR colour transform (BT.2020 primaries with the PQ
         * transfer function) into a texture that reads back as 16-bit normalised RGBA, preserving
         * GL's bottom-up orientation. The returned texture stays valid until the next call.
         */
        GpuTexture transform(RenderTarget source, int width, int height);
    }

    private static volatile ColorTransform transform;

    /** Mirrors the per-export HDR checkbox; set by the export window. */
    public static volatile boolean requested;

    public static void register(ColorTransform colorTransform) {
        transform = colorTransform;
    }

    /** True when a mod has supplied a transform, i.e. the HDR option can be offered. */
    public static boolean available() {
        return transform != null;
    }

    /** True when HDR output was requested and a transform is actually registered. */
    public static boolean active() {
        return requested && transform != null;
    }

    public static ColorTransform get() {
        return transform;
    }

}
