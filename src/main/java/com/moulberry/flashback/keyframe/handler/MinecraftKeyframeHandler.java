package com.moulberry.flashback.keyframe.handler;

import com.moulberry.flashback.keyframe.change.*;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.Set;

public record MinecraftKeyframeHandler(Minecraft minecraft) implements KeyframeHandler {

    private static final Set<Class<? extends KeyframeChange>> supportedChanges = Set.of(
            KeyframeChangeCameraPosition.class, KeyframeChangeCameraPositionOrbit.class, KeyframeChangeTrackEntity.class,
            KeyframeChangeFov.class, KeyframeChangeTimeOfDay.class, KeyframeChangeCameraShake.class,
            KeyframeChangeCameraSwitch.class, KeyframeChangeSpectate.class
    );

    @Override
    public Minecraft getMinecraft() {
        return this.minecraft;
    }

    @Override
    public boolean supportsKeyframeChange(Class<? extends KeyframeChange> clazz) {
        return supportedChanges.contains(clazz);
    }

    @Override
    public void applySpectate(java.util.UUID target) {
        LocalPlayer player = this.minecraft.player;
        if (player == null) {
            return;
        }
        if (target == null) {
            // Back to the replay's own viewpoint.
            if (this.minecraft.getCameraEntity() != player) {
                this.minecraft.setCameraEntity(player);
            }
            return;
        }
        if (this.minecraft.level == null) {
            return;
        }
        // An unknown UUID (a player who left the recording) must not throw mid-export.
        net.minecraft.world.entity.Entity entity = this.minecraft.level.getEntities().get(target);
        if (entity != null) {
            this.minecraft.setCameraEntity(entity);
        }
    }

    @Override
    public void applyCameraPosition(Vector3d position, double yaw, double pitch, double roll) {
        LocalPlayer player = this.minecraft.player;
        if (player != null) {
            // Leave any spectated entity directly rather than by sending the /spectate command.
            // The command needs a server round-trip, and an export advances the server by hand on a
            // frozen tick, so the command was often never processed - which is why switching from a
            // spectated player back to a camera did not take effect.
            if (this.minecraft.getCameraEntity() != this.minecraft.player) {
                this.minecraft.setCameraEntity(this.minecraft.player);
            }

            player.snapTo(position.x, position.y, position.z, (float) yaw, (float) pitch);
            player.getInterpolation().cancel();

            EditorState editorState = EditorStateManager.getCurrent();
            if (editorState != null) {
                if (roll > -0.01 && roll < 0.01) {
                    editorState.replayVisuals.overrideRoll = false;
                    editorState.replayVisuals.overrideRollAmount = 0.0f;
                } else {
                    editorState.replayVisuals.overrideRoll = true;
                    editorState.replayVisuals.overrideRollAmount = (float) roll;
                }
            }

            player.setDeltaMovement(Vec3.ZERO);
        }
    }

    @Override
    public void applyFov(float fov) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null) {
            editorState.replayVisuals.setFov(fov);
        }
    }

    @Override
    public void applyTimeOfDay(int timeOfDay) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null) {
            editorState.replayVisuals.overrideTimeOfDay = timeOfDay;
        }
    }

    @Override
    public void applyCameraShake(float frequencyX, float amplitudeX, float frequencyY, float amplitudeY) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null) {
            editorState.replayVisuals.setCameraShake(frequencyX, amplitudeX, frequencyY, amplitudeY);
        }
    }
}
