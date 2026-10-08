package com.moulberry.flashback.keyframe.change;

import com.moulberry.flashback.Interpolation;
import com.moulberry.flashback.keyframe.handler.KeyframeHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

public record KeyframeChangeCameraPositionOrbit(Vector3d center, double distance, double yaw, double pitch,
                                               boolean centreOnTarget) implements KeyframeChange {

    public KeyframeChangeCameraPositionOrbit(Vector3d center, double distance, double yaw, double pitch) {
        this(center, distance, yaw, pitch, false);
    }

    @Override
    public void apply(KeyframeHandler keyframeHandler) {
        // An orbit centred on what the camera is following keeps its subject framed wherever they go,
        // which is what makes an orbit camera useful for a moving player rather than a fixed point.
        // Ask the handler for its client rather than the singleton, so this works for any handler
        // and cannot trip over a client that is not there.
        Minecraft minecraft = keyframeHandler.getMinecraft();
        LocalPlayer player = minecraft == null ? null : minecraft.player;

        Vector3d centre = this.center;
        if (this.centreOnTarget) {
            Vector3d followed = keyframeHandler.followedPosition();
            if (followed != null) {
                centre = followed;
            } else {
                // Nothing is being tracked, so the subject is the player themselves.
                Vector3d subject = keyframeHandler.subjectPosition();
                if (subject != null) {
                    centre = subject;
                }
            }
        }

        float pitchRadians = (float) Math.toRadians(this.pitch);
        float yawRadians = (float) Math.toRadians(-this.yaw);
        float cosYaw = Mth.cos(yawRadians);
        float sinYaw = Mth.sin(yawRadians);
        float cosPitch = Mth.cos(pitchRadians);
        float sinPitch = Mth.sin(pitchRadians);

        Vector3d look = new Vector3d(sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
        Vector3d cameraPosition = new Vector3d(centre).sub(look.mul(this.distance));
        if (player != null) {
            cameraPosition.y -= player.getEyeHeight();
        }
        keyframeHandler.applyCameraPosition(cameraPosition, this.yaw, this.pitch, 0.0f);
    }

    @Override
    public KeyframeChange interpolate(KeyframeChange to, double amount) {
        KeyframeChangeCameraPositionOrbit other = (KeyframeChangeCameraPositionOrbit) to;
        return new KeyframeChangeCameraPositionOrbit(
            this.center.lerp(other.center, amount, new Vector3d()),
            Interpolation.linear(this.distance, other.distance, amount),
            Interpolation.linear(this.yaw, other.yaw, amount),
            Interpolation.linear(this.pitch, other.pitch, amount),
            this.centreOnTarget
        );
    }
}
