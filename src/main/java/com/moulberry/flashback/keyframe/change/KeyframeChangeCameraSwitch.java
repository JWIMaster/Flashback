package com.moulberry.flashback.keyframe.change;

import com.moulberry.flashback.keyframe.handler.KeyframeHandler;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Switches which camera is being output from this tick onwards. A hard cut.
 *
 * <p>Deliberately inert on its own: this keyframe only carries a camera id, and applying a camera
 * means applying that camera's own tracks, which only the editor state can enumerate. The editor
 * resolves the switch and applies the camera it names.
 */
public record KeyframeChangeCameraSwitch(@Nullable UUID cameraId) implements KeyframeChange {

    @Override
    public void apply(KeyframeHandler keyframeHandler) {
        // Resolution happens in EditorState, which knows the scene's cameras.
    }

    /** A cut is discrete, so there is no intermediate camera. */
    @Override
    public KeyframeChange interpolate(KeyframeChange to, double amount) {
        if (!(to instanceof KeyframeChangeCameraSwitch other)) {
            return this;
        }
        return amount < 0.5 ? this : other;
    }

}
