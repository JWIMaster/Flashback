package com.moulberry.flashback.playback;

import com.moulberry.flashback.visuals.WeatherTextureCoordinates;

public final class WeatherTextureCoordinatesTest {
    public static void main(String[] args) {
        float phase = 0.375f;
        float worldY = 64.5f;
        check(Math.abs(sample(54, 74, worldY, phase, false)
            - sample(55, 75, worldY, phase, false)) == 0.5f,
            "vanilla moves the texture half a repeat at a camera Y block crossing");

        for (int radius : new int[]{3, 5, 10, 15}) {
            for (int ground : new int[]{-64, 60, 64, 68}) {
                for (int cameraY = 62; cameraY <= 66; cameraY++) {
                    int bottom = Math.max(cameraY - radius, ground);
                    int top = Math.max(cameraY + radius, ground);
                    if (bottom == top || worldY < bottom || worldY > top) continue;
                    float actual = sample(bottom, top, worldY, phase, true);
                    check(Math.abs(actual - (-worldY * 0.25f + phase)) < 0.00001f,
                        "V at a world point must not depend on camera height, radius, or ground clipping");
                }
            }
        }
        // Changing animation phase must still move the texture in the same direction and amount.
        check(Math.abs(sample(54, 74, worldY, phase + 0.125f, true)
            - sample(54, 74, worldY, phase, true) - 0.125f) < 0.00001f,
            "the correction retains animation and its direction");
        System.out.println("All weather texture anchoring checks passed");
    }

    private static float sample(int bottom, int top, float y, float phase, boolean anchored) {
        float topV = bottom * 0.25f + phase;
        float bottomV = top * 0.25f + phase;
        if (anchored) {
            topV = WeatherTextureCoordinates.anchor(topV, bottom, top);
            bottomV = WeatherTextureCoordinates.anchor(bottomV, bottom, top);
        }
        float fraction = (y - bottom) / (top - bottom);
        return bottomV + fraction * (topV - bottomV);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
