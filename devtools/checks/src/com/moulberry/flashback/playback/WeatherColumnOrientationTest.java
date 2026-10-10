package com.moulberry.flashback.playback;

import com.moulberry.flashback.visuals.WeatherColumnOrientation;

/**
 * Proves the weather column orientations are (a) identical to vanilla when the camera is at the
 * centre of its block, and (b) continuous in the camera's fractional position, so the snow
 * texture no longer sweeps to a new spot every time the camera crosses a block boundary.
 */
public class WeatherColumnOrientationTest {

    private static final float[] SIZE_X = new float[1024];
    private static final float[] SIZE_Z = new float[1024];

    public static void main(String[] args) {
        matchesVanillaAtBlockCentre();
        continuousWhileTheCameraMoves();
        vanillaQuantisationExplainsTheVisibleJump();
        valuesStayUsable();
        edgeFadeCoversEveryDropPosition();
        ringSwapIsInvisibleOnceFaded();
        System.out.println("All weather column orientation checks passed");
    }

    /** The grid drops a column when its true axis offset reaches (radius - 0.5) .. (radius + 0.5). */
    private static void edgeFadeCoversEveryDropPosition() {
        float radius = 10.0f;
        check(WeatherColumnOrientation.edgeFade(radius, radius - 0.5f) == 0.0f,
            "a column at the closest possible drop position must already be invisible");
        check(WeatherColumnOrientation.edgeFade(radius, radius) == 0.0f, "and so must one at the boundary");
        check(WeatherColumnOrientation.edgeFade(radius, radius + 0.5f) == 0.0f, "and one just past it");
        check(WeatherColumnOrientation.edgeFade(radius, radius - 2.0f) > 0.99f, "columns well inside stay fully opaque");

        float previous = 2.0f;
        for (int step = 0; step <= 100; step++) {
            float edge = step / 100.0f * (radius + 1.0f);
            float fade = WeatherColumnOrientation.edgeFade(radius, edge);
            check(fade >= 0.0f && fade <= 1.0f, "fade stays within 0..1");
            check(fade <= previous + 1.0e-6f, "fade must not increase with distance");
            previous = fade;
        }
    }

    /**
     * The visible failure and its cure, as one invariant: the opacity of a fixed world column must
     * not change when the camera crosses a block boundary, because that is the moment the grid
     * swaps a ring. Vanilla jumps by most of a column's opacity, the faded field does not.
     */
    private static void ringSwapIsInvisibleOnceFaded() {
        float radius = 10.0f;
        float before = 100.999f;
        float after = 101.001f;

        float worstVanilla = 0.0f;
        float worstFaded = 0.0f;
        for (int x = 88; x <= 112; x++) {
            for (int z = 88; z <= 112; z++) {
                worstVanilla = Math.max(worstVanilla,
                    Math.abs(columnAlpha(x, z, after, 100.0f, radius, false) - columnAlpha(x, z, before, 100.0f, radius, false)));
                worstFaded = Math.max(worstFaded,
                    Math.abs(columnAlpha(x, z, after, 100.0f, radius, true) - columnAlpha(x, z, before, 100.0f, radius, true)));
            }
        }

        check(worstVanilla > 0.5f,
            "vanilla really does add or drop a column at full opacity when the camera crosses a block, was " + worstVanilla);
        check(worstFaded < 0.02f,
            "with the fade the visible snow field is continuous across the block boundary, was " + worstFaded);
    }

    /** Snow's alpha in vanilla: Mth.lerp(min(1, d^2/r^2), 0.5, 0.8) * intensity(1.0). */
    private static float columnAlpha(int x, int z, float camX, float camZ, float radius, boolean fade) {
        float dx = (x + 0.5f) - camX;
        float dz = (z + 0.5f) - camZ;
        if (Math.abs(x - (int) Math.floor(camX)) > radius || Math.abs(z - (int) Math.floor(camZ)) > radius) {
            return 0.0f; // outside the drawn grid
        }
        float alpha = 0.5f + 0.3f * Math.min(1.0f, (dx * dx + dz * dz) / (radius * radius));
        if (fade) {
            alpha *= WeatherColumnOrientation.edgeFade(radius, Math.max(Math.abs(dx), Math.abs(dz)));
        }
        return alpha;
    }

    /** Vanilla's table is built from integer offsets, i.e. as if the camera were at its block centre. */
    private static void matchesVanillaAtBlockCentre() {
        WeatherColumnOrientation.fill(SIZE_X, SIZE_Z, 0.5f, 0.5f);
        int compared = 0;
        for (int dz = -15; dz <= 15; dz++) {
            for (int dx = -15; dx <= 15; dx++) {
                if (dx == 0 && dz == 0) {
                    continue; // vanilla divides by zero here; the fill() guard replaces it
                }
                int index = (dz + 16) * 32 + (dx + 16);
                check(close(SIZE_X[index], WeatherColumnOrientation.vanillaSizeX(dx, dz)),
                    "sizeX must match vanilla's lattice value at offset " + dx + "," + dz);
                check(close(SIZE_Z[index], WeatherColumnOrientation.vanillaSizeZ(dx, dz)),
                    "sizeZ must match vanilla's lattice value at offset " + dx + "," + dz);
                compared++;
            }
        }
        check(compared == 31 * 31 - 1, "the whole usable lattice was compared");
    }

    /**
     * Sweeping the fractional camera position must never make a curtain jump. Vanilla's value for
     * a fixed column is constant until the block boundary and then jumps, which is the visible pop.
     */
    private static void continuousWhileTheCameraMoves() {
        float worstSmooth = 0.0f;
        float worstVanilla = 0.0f;

        for (int dz = -6; dz <= 6; dz++) {
            for (int dx = -6; dx <= 6; dx++) {
                if (Math.abs(dx) < 2 && Math.abs(dz) < 2) {
                    continue; // directly under the camera; hidden by the near plane
                }
                float previousX = Float.NaN;
                float previousZ = Float.NaN;
                for (int step = 0; step <= 200; step++) {
                    float fraction = step / 200.0f;
                    WeatherColumnOrientation.fill(SIZE_X, SIZE_Z, fraction, fraction * 0.5f);
                    int index = (dz + 16) * 32 + (dx + 16);
                    float currentX = SIZE_X[index];
                    float currentZ = SIZE_Z[index];
                    if (step > 0) {
                        worstSmooth = Math.max(worstSmooth, angleBetween(previousX, previousZ, currentX, currentZ));
                    }
                    previousX = currentX;
                    previousZ = currentZ;
                }

                // The quantised behaviour this replaces: constant within a block, then a jump.
                if (Math.abs(dx) <= 4 && Math.abs(dz) <= 4) {
                    float beforeX = WeatherColumnOrientation.vanillaSizeX(dx, dz);
                    float beforeZ = WeatherColumnOrientation.vanillaSizeZ(dx, dz);
                    float afterX = WeatherColumnOrientation.vanillaSizeX(dx + 1, dz);
                    float afterZ = WeatherColumnOrientation.vanillaSizeZ(dx + 1, dz);
                    worstVanilla = Math.max(worstVanilla, angleBetween(beforeX, beforeZ, afterX, afterZ));
                }
            }
        }

        check(worstSmooth < 6.0f, "smooth orientation never jumps more than 6 degrees per step, was " + worstSmooth);
        check(worstVanilla > 12.0f, "vanilla's quantised orientation does jump noticeably, was " + worstVanilla);
    }

    /** Documents the failure being fixed: the vanilla step is what the player sees as a pop. */
    private static void vanillaQuantisationExplainsTheVisibleJump() {
        // Axis-aligned offsets are exact in vanilla, so the visible snaps come from the off-axis
        // ones: (1,1) means "one block diagonally", (2,1) is one block further out.
        float near = angleBetween(
            WeatherColumnOrientation.vanillaSizeX(1, 1), WeatherColumnOrientation.vanillaSizeZ(1, 1),
            WeatherColumnOrientation.vanillaSizeX(2, 1), WeatherColumnOrientation.vanillaSizeZ(2, 1));
        check(near > 12.0f, "an off-axis column two blocks out snaps by more than ten degrees, was " + near);

        // The texture is mapped once across the quad, so rotating it by that much slides the
        // visible snow pattern a large fraction of a block.
        float movedX = WeatherColumnOrientation.vanillaSizeX(1, 1) - WeatherColumnOrientation.vanillaSizeX(2, 1);
        float movedZ = WeatherColumnOrientation.vanillaSizeZ(1, 1) - WeatherColumnOrientation.vanillaSizeZ(2, 1);
        check(Math.hypot(movedX, movedZ) > 0.25f,
            "the quad end points move by a large fraction of a block, was " + Math.hypot(movedX, movedZ));
    }

    private static void valuesStayUsable() {
        boolean anyDeleted = false;
        for (int step = 0; step < 100; step++) {
            float fraction = step / 100.0f;
            WeatherColumnOrientation.fill(SIZE_X, SIZE_Z, fraction, 1.0f - fraction);
            for (int index = 0; index < SIZE_X.length; index++) {
                if (!Float.isFinite(SIZE_X[index]) || !Float.isFinite(SIZE_Z[index])) {
                    anyDeleted = true;
                }
                float length = (float) Math.sqrt(SIZE_X[index] * SIZE_X[index] + SIZE_Z[index] * SIZE_Z[index]);
                check(Math.abs(length - 1.0f) < 1.0e-4f, "orientation stays a unit vector");
            }
        }
        check(!anyDeleted, "no NaN or infinite orientations anywhere in the table");
    }

    private static float angleBetween(float ax, float az, float bx, float bz) {
        float dot = Math.max(-1.0f, Math.min(1.0f, ax * bx + az * bz));
        return (float) Math.toDegrees(Math.acos(dot));
    }

    private static boolean close(float a, float b) {
        return Math.abs(a - b) < 1.0e-5f;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

}
