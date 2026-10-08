package com.moulberry.flashback.utils;

/**
 * Stand-in for the game's raw input reads, which go through SDL and a native library the harness
 * does not load.
 *
 * <p>The harness sets these flags to hold modifiers down, which is how a drag can be driven with and
 * without snapping.
 */
public class InputHelper {

    public static boolean shiftDown = false;
    public static boolean ctrlDown = false;
    public static boolean altDown = false;

    public static boolean isShiftDownRaw() {
        return shiftDown;
    }

    public static boolean isCtrlDownRaw() {
        return ctrlDown;
    }

    public static boolean isAltDownRaw() {
        return altDown;
    }
}
