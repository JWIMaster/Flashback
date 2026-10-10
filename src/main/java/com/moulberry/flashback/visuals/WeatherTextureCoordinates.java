package com.moulberry.flashback.visuals;

/** Keeps the falling weather texture attached to world height, not the camera's integer Y. */
public final class WeatherTextureCoordinates {
    private WeatherTextureCoordinates() {}

    public static float anchor(float v, int bottomY, int topY) {
        // Vanilla assigns bottomY/4 to the TOP vertex, and topY/4 to the BOTTOM.
        // At a fixed world height its interpolated V is (bottomY + topY - y)/4 + phase.
        // Moving the camera up one block moves both bounds and jumps V by half a repeat.
        // Remove the bounds-dependent origin, retaining the existing slope and fall direction.
        return (float) (v - ((double) bottomY + topY) * 0.25);
    }
}
