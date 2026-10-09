package com.moulberry.flashback.keyframe.change;

import com.moulberry.flashback.Interpolation;
import com.moulberry.flashback.Utils;
import com.moulberry.flashback.keyframe.handler.KeyframeHandler;

/**
 * A FOV animated by a camera rather than by the scene.
 *
 * <p>This is deliberately a separate change from {@link KeyframeChangeFov} even though it applies
 * through the same handler method: the editor tracks which change types it has already applied so a
 * second track of the same type cannot fight the first, and a camera's own FOV lane has to be able
 * to coexist with the scene-wide FOV lane.
 *
 * <p>Interpolates in focal length rather than degrees, exactly as the scene-wide FOV lane does, so a
 * camera's field of view changes at a constant visual rate instead of accelerating at the wide end.
 */
public record KeyframeChangeCameraFov(float fov) implements KeyframeChange {

    @Override
    public void apply(KeyframeHandler keyframeHandler) {
        keyframeHandler.applyFov(this.fov);
    }

    @Override
    public KeyframeChange interpolate(KeyframeChange to, double amount) {
        KeyframeChangeCameraFov other = (KeyframeChangeCameraFov) to;
        float thisFocalLength = Utils.fovToFocalLength(this.fov);
        float otherFocalLength = Utils.fovToFocalLength(other.fov);
        float focalLength = (float) Interpolation.linear(thisFocalLength, otherFocalLength, amount);

        return new KeyframeChangeCameraFov(Utils.focalLengthToFov(focalLength));
    }

}
