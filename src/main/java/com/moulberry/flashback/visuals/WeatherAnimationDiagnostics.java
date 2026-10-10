package com.moulberry.flashback.visuals;

import com.moulberry.flashback.Flashback;

/**
 * Measures exactly the values {@code WeatherEffectRenderer} animates the rain/snow columns with.
 *
 * <p>The columns are a pure function of (tick + partial), the column intensity and the camera
 * position, so a jump in one of those is the only way the weather itself can visibly jerk. The
 * values are recorded where the renderer reads them, not from the level's raw clock, so a silent
 * log is positive proof that the weather was fed a continuous clock.</p>
 */
public final class WeatherAnimationDiagnostics {

    private static final double TICK_EPSILON = 0.9;
    private static final double INTENSITY_EPSILON = 0.25;
    private static final double CAMERA_EPSILON = 2.0;
    private static final int MAX_REPORTS = 20;

    private static float scratchPartial;
    private static double scratchIntensity;
    private static double scratchX;
    private static double scratchY;
    private static double scratchZ;

    private static boolean activated;
    private static boolean primed;
    private static double lastTime;
    private static double lastIntensity;
    private static double lastX;
    private static double lastY;
    private static double lastZ;
    private static int reported;

    private WeatherAnimationDiagnostics() {}

    public static void reset() {
        activated = false;
        primed = false;
        reported = 0;
    }

    /** Captures the inputs that are only available as method arguments. */
    public static void head(float partialTick, double intensity, double cameraX, double cameraY, double cameraZ) {
        scratchPartial = partialTick;
        scratchIntensity = intensity;
        scratchX = cameraX;
        scratchY = cameraY;
        scratchZ = cameraZ;
    }

    /** Called with the tick the weather renderer is actually given, plus the raw level clock. */
    public static void used(long rawGameTime, long animationTick, boolean replay) {
        if (!Flashback.getConfig().internal.recordGuiEvents) {
            primed = false;
            return;
        }

        if (!activated) {
            activated = true;
            Flashback.LOGGER.info("Weather clock engaged: replay={} rawTick={} weatherTick={} partial={} intensity={}",
                replay, rawGameTime, animationTick, scratchPartial, scratchIntensity);
        }

        double time = animationTick + scratchPartial;
        if (primed) {
            double delta = time - lastTime;
            double intensityDelta = Math.abs(scratchIntensity - lastIntensity);
            double cameraDelta = Math.abs(scratchX - lastX) + Math.abs(scratchY - lastY) + Math.abs(scratchZ - lastZ);

            if (Math.abs(delta) > TICK_EPSILON || intensityDelta > INTENSITY_EPSILON || cameraDelta > CAMERA_EPSILON) {
                if (reported++ < MAX_REPORTS) {
                    Flashback.LOGGER.warn("Weather animation discontinuity: weatherTick {} -> {} (delta {}), rawTick {}, intensity {} -> {} (delta {}), camera moved {}",
                        lastTime, time, delta, rawGameTime, lastIntensity, scratchIntensity, intensityDelta, cameraDelta);
                }
            }
        }

        primed = true;
        lastTime = time;
        lastIntensity = scratchIntensity;
        lastX = scratchX;
        lastY = scratchY;
        lastZ = scratchZ;
    }

}
