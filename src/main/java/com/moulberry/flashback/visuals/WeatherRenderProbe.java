package com.moulberry.flashback.visuals;

import com.moulberry.flashback.Flashback;

/**
 * Measures the rain/snow geometry that is actually submitted for drawing.
 *
 * <p>Every other theory about this bug was tested by reasoning about the renderer; this records the
 * result instead. The totals are alpha weighted, so a column that is fully faded out (or off the
 * edge of the draw region) contributes nothing: if the visible snow is continuous, the weighted
 * world position and texture phase barely move, and if something jumps, the log says which of the
 * three quantities moved and by how much.</p>
 *
 * <ul>
 *   <li>world position - the geometry moved or the visible column set changed
 *   <li>texture phase - the snow texture jumped to a different part of the image
 *   <li>density - the snow thickened or thinned
 * </ul>
 */
public final class WeatherRenderProbe {

    private static final double POSITION_EPSILON = 0.05;
    private static final double PHASE_EPSILON = 0.05;
    private static final double DENSITY_EPSILON = 0.5;
    private static final int MAX_REPORTS = 20;

    private static boolean activated;
    private static boolean primed;
    private static int reports;
    private static int drawReports;

    private static double cameraX;
    private static double cameraY;
    private static double cameraZ;

    private static float vertexX;
    private static float vertexY;
    private static float vertexZ;
    private static float vertexU;
    private static float vertexV;

    private static double weight;
    private static double sumWorldX;
    private static double sumWorldZ;
    private static double sumAlpha;
    private static double sumU;
    private static double sumV;

    private static double meanWorldX;
    private static double meanWorldZ;
    private static double meanU;
    private static double lastAlphaMass;

    private WeatherRenderProbe() {}

    public static void reset() {
        activated = false;
        primed = false;
        reports = 0;
        drawReports = 0;
    }

    public static void begin(double x, double y, double z) {
        cameraX = x;
        cameraY = y;
        cameraZ = z;
        weight = 0.0;
        sumWorldX = 0.0;
        sumWorldZ = 0.0;
        sumAlpha = 0.0;
        sumU = 0.0;
        sumV = 0.0;
    }

    /**
     * Compares the camera the snow was built against with the camera the frame is drawn with. The
     * snow's vertices are camera relative, so any difference here slides the whole snow field
     * against a world that is drawn in absolute coordinates.
     */
    public static void draw(double liveX, double liveY, double liveZ,
                            double matrixX, double matrixY, double matrixZ) {
        if (!Flashback.getConfig().internal.recordGuiEvents) {
            return;
        }
        double liveDelta = Math.abs(liveX - cameraX) + Math.abs(liveY - cameraY) + Math.abs(liveZ - cameraZ);
        double matrixDelta = Math.abs(matrixX) + Math.abs(matrixY) + Math.abs(matrixZ);
        if (liveDelta > 0.02 || matrixDelta > 0.02) {
            if (drawReports++ < MAX_REPORTS) {
                Flashback.LOGGER.info("Weather camera mismatch: extracted ({}, {}, {}), live moved {} blocks, modelview translation ({}, {}, {})",
                    round(cameraX), round(cameraY), round(cameraZ), round(liveDelta),
                    round(matrixX), round(matrixY), round(matrixZ));
            }
        }
    }

    public static void vertex(float x, float y, float z) {
        vertexX = x;
        vertexY = y;
        vertexZ = z;
    }

    public static void uv(float u, float v) {
        vertexU = u;
        vertexV = v;
    }

    public static void color(int argb) {
        float alpha = ((argb >>> 24) & 0xFF) / 255.0f;
        if (alpha <= 0.0f) {
            return;
        }
        weight += alpha;
        sumAlpha += alpha;
        sumWorldX += alpha * (cameraX + vertexX);
        sumWorldZ += alpha * (cameraZ + vertexZ);
        sumU += alpha * vertexU;
        sumV += alpha * vertexV;
    }

    public static void end(int columns, int radius) {
        if (!Flashback.getConfig().internal.recordGuiEvents) {
            primed = false;
            return;
        }
        if (weight <= 0.0) {
            return;
        }

        double worldX = sumWorldX / weight;
        double worldZ = sumWorldZ / weight;
        double phase = sumU / weight;
        double alphaMass = sumAlpha;

        if (!activated) {
            activated = true;
            Flashback.LOGGER.info("Weather render probe active: {} columns, radius {}, alpha mass {}, phase {}",
                columns, radius, alphaMass, phase);
        }

        if (primed) {
            double moved = Math.abs(worldX - meanWorldX) + Math.abs(worldZ - meanWorldZ);
            double phaseJump = Math.abs(phase - meanU);
            double densityJump = Math.abs(alphaMass - lastAlphaMass);

            if (moved > POSITION_EPSILON || phaseJump > PHASE_EPSILON || densityJump > DENSITY_EPSILON) {
                if (reports++ < MAX_REPORTS) {
                    Flashback.LOGGER.info("Weather jump: position {} blocks, texture phase {}, density {}, columns {} (camera block {}, {})",
                        round(moved), round(phaseJump), round(densityJump), columns,
                        (int) Math.floor(cameraX), (int) Math.floor(cameraZ));
                }
            }
        }

        primed = true;
        meanWorldX = worldX;
        meanWorldZ = worldZ;
        meanU = phase;
        lastAlphaMass = alphaMass;
    }

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

}
