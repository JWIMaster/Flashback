package com.moulberry.flashback.keyframe.handler;

import com.moulberry.flashback.keyframe.change.*;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
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
        Minecraft minecraft = this.minecraft;
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        // Resolve the target on the client so the camera changes on this exact frame. During an export
        // the client and server are stepped by hand and the packet the server sends back may not be
        // handled until a later frame, which is why this cannot rely on the server alone.
        Entity clientTarget = player;
        if (target != null && minecraft.level != null) {
            Entity entity = minecraft.level.getEntities().get(target);
            if (entity != null) {
                clientTarget = entity;
            }
        }

        if (minecraft.getCameraEntity() != clientTarget) {
            minecraft.setCameraEntity(clientTarget);
        }

        // Tell the server as well, using the requested target rather than what the client managed to
        // resolve. The server is the side that can keep retrying, and it remembers who is being
        // spectated so that it re-sends the camera whenever the recorded entity is removed or replaced -
        // which happens constantly while a replay ticks, and is what stopped the camera going stale.
        ReplayServer replayServer = Flashback.getReplayServer();
        if (replayServer != null) {
            replayServer.requestSpectate(target);
        }
    }

    /**
     * Leaves any spectated entity, both on this client and on the replay server. Idempotent, and cheap
     * enough to call every tick.
     */
    public void stopSpectating() {
        Minecraft minecraft = this.minecraft;
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        if (minecraft.getCameraEntity() != player) {
            minecraft.setCameraEntity(player);
        }

        ReplayServer replayServer = Flashback.getReplayServer();
        if (replayServer != null) {
            replayServer.requestSpectate(null);
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
            this.stopSpectating();

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
