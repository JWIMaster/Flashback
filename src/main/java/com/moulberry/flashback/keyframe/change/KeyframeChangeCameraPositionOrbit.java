package com.moulberry.flashback.keyframe.change;

import com.moulberry.flashback.Interpolation;
import com.moulberry.flashback.keyframe.handler.KeyframeHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import java.util.UUID;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

public record KeyframeChangeCameraPositionOrbit(Vector3d center, double distance, double yaw, double pitch,
                                               boolean centreOnTarget, UUID target, boolean smoothFollow,
                                               double lagSeconds) implements KeyframeChange {

    /** How far behind its subject a lagging orbit trails, in replay ticks. */
    private static final double TICKS_PER_SECOND = 20.0;

    public KeyframeChangeCameraPositionOrbit(Vector3d center, double distance, double yaw, double pitch) {
        this(center, distance, yaw, pitch, false, null, false, 0.0);
    }

    public KeyframeChangeCameraPositionOrbit(Vector3d center, double distance, double yaw, double pitch, boolean centreOnTarget) {
        this(center, distance, yaw, pitch, centreOnTarget, null, false, 0.0);
    }

    public KeyframeChangeCameraPositionOrbit(Vector3d center, double distance, double yaw, double pitch,
                                             boolean centreOnTarget, UUID target) {
        this(center, distance, yaw, pitch, centreOnTarget, target, false, 0.0);
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
            if (this.target != null && minecraft != null && minecraft.level != null) {
                Entity subject = minecraft.level.getEntities().get(this.target);
                if (subject != null && subject != player) {
                    float partialTick = minecraft.deltaTracker.getGameTimeDeltaPartialTick(true);
                    Vec3 eye = subject.getEyePosition(partialTick);
                    centre = new Vector3d(eye.x, eye.y, eye.z);
                    if (this.smoothFollow) {
                        // Lagging follows a point the subject has already left, so the camera trails
                        // them and eases back into place instead of turning with every twitch.
                        float now = minecraft.level.getGameTime() + partialTick;
                        OrbitFollowDelay.record(this.target, now, centre);
                        Vector3d delayed = OrbitFollowDelay.sample(this.target,
                            (float) (now - Math.max(0.0, this.lagSeconds) * TICKS_PER_SECOND));
                        if (delayed != null) {
                            centre = delayed;
                        }
                    }
                }
            } else if (this.target == null) {
                Vector3d followed = keyframeHandler.followedPosition();
                if (followed != null) {
                    centre = followed;
                }
            }
            // Never use the local player as the centre: it IS the replay camera.
            // If the selected player is temporarily absent, hold the saved point.
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
            this.centreOnTarget,
            amount < 0.5 ? this.target : other.target,
            this.smoothFollow,
            this.lagSeconds
        );
    }
}
