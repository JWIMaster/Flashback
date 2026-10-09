package com.moulberry.flashback.keyframe.change;

import com.moulberry.flashback.keyframe.handler.KeyframeHandler;
import org.joml.Vector3d;

/**
 * Moves the camera without touching where it is looking.
 *
 * <p>Cameras are objects whose position and rotation are independent properties, so a position
 * track and a rotation track have to be able to animate the same camera at the same time. This
 * change carries only the position; the handler reads the rotation it is not setting from the live
 * camera, which is what lets the two tracks be evaluated in either order in the same frame and
 * still produce the same view.
 */
public record KeyframeChangeCameraPositionOnly(Vector3d position) implements KeyframeChange {

    @Override
    public void apply(KeyframeHandler keyframeHandler) {
        keyframeHandler.applyCameraPositionOnly(this.position);
    }

    @Override
    public KeyframeChange interpolate(KeyframeChange to, double amount) {
        KeyframeChangeCameraPositionOnly other = (KeyframeChangeCameraPositionOnly) to;
        return new KeyframeChangeCameraPositionOnly(this.position.lerp(other.position, amount, new Vector3d()));
    }

}
