package com.moulberry.flashback.keyframe.change;

import com.moulberry.flashback.Interpolation;
import com.moulberry.flashback.keyframe.handler.KeyframeHandler;

/**
 * Turns the camera without moving it.
 *
 * <p>The counterpart to {@link KeyframeChangeCameraPositionOnly}: a rotation track animates yaw,
 * pitch and roll while the camera stays wherever the position track (or the live camera) put it.
 * The handler reads the position it is not setting from the live camera, so a position track and a
 * rotation track can both be evaluated in the same frame in either order.
 */
public record KeyframeChangeCameraRotationOnly(double yaw, double pitch, double roll) implements KeyframeChange {

    @Override
    public void apply(KeyframeHandler keyframeHandler) {
        keyframeHandler.applyCameraRotationOnly(this.yaw, this.pitch, this.roll);
    }

    @Override
    public KeyframeChange interpolate(KeyframeChange to, double amount) {
        KeyframeChangeCameraRotationOnly other = (KeyframeChangeCameraRotationOnly) to;
        return new KeyframeChangeCameraRotationOnly(
            Interpolation.linearAngle(this.yaw, other.yaw, amount),
            Interpolation.linearAngle(this.pitch, other.pitch, amount),
            Interpolation.linearAngle(this.roll, other.roll, amount)
        );
    }

}
