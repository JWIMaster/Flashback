package com.moulberry.flashback.visuals;

import net.minecraft.util.Mth;

/**
 * Computes the weather column "curtain" orientations.
 *
 * <p>Each rain/snow column is a billboard that must face the camera. Vanilla precomputes that
 * orientation once, for a 32x32 lattice of <em>integer</em> block offsets from the camera's block,
 * and indexes it with the column's offset from {@code Mth.floor(camera)}. The orientation is
 * therefore constant within a block and jumps to the next lattice direction whenever the camera
 * crosses a block boundary. Because the texture's U coordinate spans the quad exactly once, that
 * jump sweeps the snow texture sideways in world space - the snow appears to flow, jump to a
 * different part of the texture, flow, and jump again, but only while the camera is moving.</p>
 *
 * <p>{@link #fill} reproduces vanilla's table exactly when the camera sits at the centre of its
 * block, and otherwise refines it with the camera's true fractional position so the orientation
 * changes continuously.</p>
 */
public final class WeatherColumnOrientation {

    private static final float MIN_LENGTH = 0.25f;

    /** How far inside the grid boundary a column has faded out completely. */
    public static final float EDGE_FADE_WIDTH = 1.5f;

    private WeatherColumnOrientation() {}

    /**
     * Opacity multiplier that hides a column before the grid drops it.
     *
     * <p>The drawn columns are the square around {@code Mth.floor(camera)}, so a column leaves the
     * grid when its true offset along either axis reaches {@code radius - 0.5} (and up to
     * {@code radius + 0.5} depending on where the camera is inside its block). Vanilla keeps the
     * alpha at its maximum there, so that ring appears and disappears at full opacity as the
     * camera crosses block boundaries. Fading the outermost band to zero makes both the arrival
     * and the departure invisible.</p>
     *
     * @param edgeDistance largest horizontal axis offset of the column from the camera
     */
    public static float edgeFade(float radius, float edgeDistance) {
        return Mth.clamp((radius - 0.5f - edgeDistance) / EDGE_FADE_WIDTH, 0.0f, 1.0f);
    }

    /** Vanilla's orientation for a column at integer offset ({@code dx},{@code dz}) from the camera block. */
    public static float vanillaSizeX(int dx, int dz) {
        float length = Mth.length(dx, dz);
        return -dz / length;
    }

    public static float vanillaSizeZ(int dx, int dz) {
        float length = Mth.length(dx, dz);
        return dx / length;
    }

    /**
     * Rewrites a 32x32 orientation table for the given fractional camera position.
     *
     * @param sizeX       table written as {@code sizeX[(offsetZ + 16) * 32 + offsetX + 16]}
     * @param sizeZ       companion table
     * @param fractionX   {@code camera.x - floor(camera.x)}
     * @param fractionZ   {@code camera.z - floor(camera.z)}
     */
    public static void fill(float[] sizeX, float[] sizeZ, float fractionX, float fractionZ) {
        for (int i = 0; i < 32; i++) {
            // Matches vanilla's Mth.length(j - 16, i - 16) with camera-relative offsets.
            float offsetZ = i - 16 + 0.5f - fractionZ;
            int row = i * 32;
            for (int j = 0; j < 32; j++) {
                float offsetX = j - 16 + 0.5f - fractionX;
                int index = row + j;
                float length = Mth.length(offsetX, offsetZ);
                if (length < MIN_LENGTH) {
                    // The camera is inside this column. Nothing sensible to face, and the
                    // direction would swing wildly, so keep it flat and let the near plane hide it.
                    sizeX[index] = 1.0f;
                    sizeZ[index] = 0.0f;
                } else {
                    sizeX[index] = -offsetZ / length;
                    sizeZ[index] = offsetX / length;
                }
            }
        }
    }

}
