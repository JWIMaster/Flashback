package com.moulberry.flashback.editor.ui.timeline;

import com.moulberry.flashback.state.EditorCamera;

/**
 * The timeline's colour language and shared sizes.
 *
 * <p>Each camera owns an accent colour, taken from its position among the cameras. That same colour
 * marks the camera's header row, tints its tracks, fills its spans in the cut lane and flags its
 * cuts, so a coloured band segment and the rows it belongs to are recognisably the same camera
 * without reading any text.
 *
 * <p>Everything else is neutral on purpose: the playhead, selection and hover states must be legible
 * over any accent, so they are white or near-white rather than another hue.
 */
public final class TimelineColours {

    private static final int[] CAMERA_ACCENTS = {
        0xFFFFB74D, // amber
        0xFF4FC3F7, // sky
        0xFF81C784, // green
        0xFFE57373, // red
        0xFFBA68C8, // violet
        0xFF4DB6AC, // teal
        0xFFF06292, // pink
        0xFFAED581, // lime
    };

    // -- Surfaces --
    public static final int CUTS_LANE_BACKGROUND = 0x12FFFFFF;
    public static final int ROW_HOVER = 0x14FFFFFF;
    public static final int ROW_DIVIDER = 0x14FFFFFF;
    public static final int PANEL_DIVIDER = 0x40FFFFFF;

    // -- States --
    public static final int SELECTED = 0xFFFFFFFF;
    public static final int SELECTION_GLOW = 0x40FFFFFF;
    public static final int HOVERED = 0xFFFFFFFF;
    public static final int PLAYHEAD = 0xFFFFFFFF;
    public static final int PLAYHEAD_SHADOW = 0x60000000;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_DIM = 0x80FFFFFF;
    public static final int SECTION_TEXT = 0x70FFFFFF;

    // -- Content --
    public static final int KEYFRAME_DEFAULT = 0xFFFFFFFF;
    public static final int KEYFRAME_DISABLED = 0x70FFFFFF;
    public static final int ROW_RAIL = 0x1AFFFFFF;
    public static final int ROW_RAIL_DISABLED = 0x0DFFFFFF;
    public static final int INVALID = 0xFF5F7FFF;
    public static final int DROP_INDICATOR = 0xFFFFC040;

    /** The colour identifying a camera, stable for as long as its position among the cameras is. */
    public static int cameraAccent(int cameraIndex) {
        if (cameraIndex < 0) {
            return 0xFFCCCCCC;
        }
        return CAMERA_ACCENTS[cameraIndex % CAMERA_ACCENTS.length];
    }

    /** Replaces the alpha of an ARGB colour. */
    public static int alpha(int argb, int a) {
        return (argb & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

}
