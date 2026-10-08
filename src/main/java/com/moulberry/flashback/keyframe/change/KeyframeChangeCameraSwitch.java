package com.moulberry.flashback.keyframe.change;

import com.moulberry.flashback.keyframe.CameraSource;
import com.moulberry.flashback.keyframe.handler.KeyframeHandler;

/**
 * Names the timeline source that should be output from this tick onwards. A hard cut.
 *
 * <p>Deliberately inert on its own: applying it would require knowing whether the source is a camera
 * or a spectate object, which only the editor state knows. The editor resolves and performs the
 * switch, so this type only carries the selection.
 */
public record KeyframeChangeCameraSwitch(CameraSource source) implements KeyframeChange {

    @Override
    public void apply(KeyframeHandler keyframeHandler) {
        // Resolution happens in EditorState, which knows what the source id refers to.
    }

    /** A cut is discrete, so there is no intermediate viewpoint. */
    @Override
    public KeyframeChange interpolate(KeyframeChange to, double amount) {
        if (!(to instanceof KeyframeChangeCameraSwitch other)) {
            return this;
        }
        return amount < 0.5 ? this : other;
    }

}
