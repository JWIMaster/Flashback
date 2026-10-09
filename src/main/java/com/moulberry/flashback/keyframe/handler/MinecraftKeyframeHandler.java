package com.moulberry.flashback.keyframe.handler;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.combo_options.WeatherOverride;
import com.moulberry.flashback.keyframe.change.*;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.Set;
import java.util.UUID;

public class MinecraftKeyframeHandler implements KeyframeHandler {

    private final Minecraft minecraft;
    /**
     * Where the camera is following, remembered from this pass's entity-tracking keyframes.
     *
     * <p>A class rather than a record because an apply pass has state: an orbit centred on its subject
     * needs to know where that subject is, and the tracking keyframes in the same pass are what say.
     */
    private Vector3d followedPosition;

    public MinecraftKeyframeHandler(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    public Minecraft minecraft() {
        return this.minecraft;
    }

    @Override
    public void setFollowedPosition(Vector3d position) {
        this.followedPosition = position;
    }

    @Override
    public Vector3d followedPosition() {
        return this.followedPosition;
    }

    @Override
    public Vector3d subjectPosition() {
        // The local player is the subject; the camera entity is the fallback, because during replay
        // playback the local player is not always present while the camera entity always is.
        LocalPlayer player = this.minecraft.player;
        if (player != null) {
            return eyeOf(player);
        }
        Entity camera = this.minecraft.getCameraEntity();
        return camera == null ? null : eyeOf(camera);
    }

    private static Vector3d eyeOf(Entity entity) {
        Vec3 eye = entity.getEyePosition();
        return new Vector3d(eye.x, eye.y, eye.z);
    }

    private static final Set<Class<? extends KeyframeChange>> supportedChanges = Set.of(
            KeyframeChangeCameraPosition.class, KeyframeChangeCameraPositionOnly.class,
            KeyframeChangeCameraRotationOnly.class, KeyframeChangeCameraPositionOrbit.class,
            KeyframeChangeTrackEntity.class,
            KeyframeChangeFov.class, KeyframeChangeCameraFov.class, KeyframeChangeTimeOfDay.class,
            KeyframeChangeCameraShake.class,
            KeyframeChangeCameraSwitch.class, KeyframeChangeSpectate.class, KeyframeChangeWeather.class
    );

    @Override
    public Minecraft getMinecraft() {
        return this.minecraft;
    }

    @Override
    public boolean alwaysApplyLastKeyframe() {
        // A tick past a track's last keyframe produces no interpolated value, so without this the
        // client simply stops applying that track. This handler is the only one that applies camera,
        // orbit, entity-tracking, fov, shake and spectate changes, so a track with a single keyframe
        // - a spectate camera, typically - would never take effect once the playhead moved past it.
        // Holding the last keyframe is what the replay server already does, so this also makes the
        // preview agree with playback.
        return true;
    }

    @Override
    public boolean supportsKeyframeChange(Class<? extends KeyframeChange> clazz) {
        return supportedChanges.contains(clazz);
    }

    @Override
    public void applyCameraPosition(Vector3d position, double yaw, double pitch, double roll) {
        LocalPlayer player = this.minecraft.player;
        if (player != null) {
            // A positioned camera is not following anyone.
            this.stopSpectating();

            player.snapTo(position.x, position.y, position.z, (float) yaw, (float) pitch);
            player.getInterpolation().cancel();

            this.applyRollOverride(roll);

            player.setDeltaMovement(Vec3.ZERO);
        }
    }

    /**
     * Moves the player to a position and leaves where it is looking alone.
     *
     * <p>A camera's position and rotation are separate tracks now, and both are applied on the same
     * frame. This is the same prelude as {@link #applyCameraPosition} - a positioned camera is not
     * spectating, its interpolation is cancelled and its movement is zeroed - but the yaw and pitch
     * it snaps to are the player's current ones, and the roll override is deliberately not touched:
     * a position keyframe must not be able to change the camera's roll, because roll belongs to the
     * rotation track the user may be animating in the same frame.
     */
    @Override
    public void applyCameraPositionOnly(Vector3d position) {
        LocalPlayer player = this.minecraft.player;
        if (player != null) {
            this.stopSpectating();

            player.snapTo(position.x, position.y, position.z, player.getYRot(), player.getXRot());
            player.getInterpolation().cancel();

            player.setDeltaMovement(Vec3.ZERO);
        }
    }

    /**
     * Turns the player to these angles where it already is.
     *
     * <p>The counterpart to {@link #applyCameraPositionOnly}: the x/y/z snapped to are the player's
     * current ones, so the rotation track cannot move the camera and a position track applied in the
     * same frame survives whichever order the two are evaluated in. Roll is part of the rotation, so
     * it is written exactly as {@link #applyCameraPosition} writes it.
     */
    @Override
    public void applyCameraRotationOnly(double yaw, double pitch, double roll) {
        LocalPlayer player = this.minecraft.player;
        if (player != null) {
            this.stopSpectating();

            player.snapTo(player.getX(), player.getY(), player.getZ(), (float) yaw, (float) pitch);
            player.getInterpolation().cancel();

            this.applyRollOverride(roll);

            player.setDeltaMovement(Vec3.ZERO);
        }
    }

    /**
     * The roll the editor renders with, as an override because the game has no roll angle.
     *
     * <p>A roll of essentially zero clears the override rather than setting it to zero, which is the
     * state the rest of the editor treats as "level".
     */
    private void applyRollOverride(double roll) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null) {
            editorState.replayVisuals.cameraVisuals().setRoll(roll);
        }
    }

    @Override
    public void beginCameraFrame() {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null) editorState.replayVisuals.beginCameraFrame();
    }

    @Override
    public void applyFov(float fov) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null) {
            editorState.replayVisuals.cameraVisuals().setFov(fov);
        }
    }

    @Override
    public void applyTimeOfDay(int timeOfDay) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null) {
            editorState.replayVisuals.overrideTimeOfDay = timeOfDay;
        }
    }

    /**
     * Sets the weather the client draws.
     *
     * <p>The override is read straight off the editor state by the level and precipitation mixins, so
     * writing it here is what makes a weather keyframe visible on this frame. It is applied to the
     * client only: the replay server's own weather comes from the recorded packets, and this is a
     * look applied on top of it. Like {@link #applyTimeOfDay(int)} this is a view setting rather than
     * an edit, so it does not mark the project dirty.
     */
    @Override
    public void applyWeather(WeatherOverride mode) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null) {
            editorState.replayVisuals.overrideWeatherMode = mode;
        }
    }

    @Override
    public void applyCameraShake(float frequencyX, float amplitudeX, float frequencyY, float amplitudeY) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null) {
            editorState.replayVisuals.cameraVisuals().setCameraShake(frequencyX, amplitudeX, frequencyY, amplitudeY);
        }
    }

    /**
     * Points the camera at a player's first-person view, or back at the replay's own viewpoint when
     * the target is null.
     *
     * <p>Two sides have to agree here, and they are updated together:
     *
     * <ul>
     *   <li>The client camera is set directly, so the change takes effect on this exact frame. During
     *       an export the client and server are stepped by hand and a packet from the server might
     *       not be handled until a later frame, so waiting for it would render the wrong viewpoint.</li>
     *   <li>The replay server is told the intended target. It is the side that can keep retrying,
     *       because a replay destroys and recreates entities as it streams and the entity the camera
     *       currently points at can be discarded at any moment.</li>
     * </ul>
     */
    @Override
    public void applySpectate(@Nullable UUID target) {
        Minecraft minecraft = this.minecraft;

        // Resolve on the client so the change lands this frame. An unknown UUID is left as "not
        // spectating" rather than throwing, because a recorded player may not exist in this replay.
        Entity clientTarget = null;
        if (target != null) {
            clientTarget = resolveClientEntity(minecraft, target);
        }
        if (clientTarget == null) {
            // Back to the replay's own viewpoint. The local player is the usual answer, but it is not
            // guaranteed to be present while a replay is being scrubbed, so this must not be the only
            // route: when it is missing the camera entity already is that viewpoint.
            clientTarget = minecraft.player != null ? minecraft.player : minecraft.getCameraEntity();
        }

        if (clientTarget != null && minecraft.getCameraEntity() != clientTarget) {
            minecraft.setCameraEntity(clientTarget);
        }

        ReplayServer replayServer = Flashback.getReplayServer();
        if (replayServer != null) {
            // Hand over the requested target, not what the client managed to resolve, so the server
            // keeps the intention and can pick the entity up as soon as it exists.
            replayServer.setSpectateTarget(target);
        }
    }

    /**
     * Leaves any spectated entity, on this client and on the replay server. Idempotent, and cheap
     * enough to call every frame.
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
            replayServer.setSpectateTarget(null);
        }
    }

    @Nullable
    private static Entity resolveClientEntity(Minecraft minecraft, UUID uuid) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            return null;
        }
        Entity entity = level.getEntities().get(uuid);
        if (entity == null || entity.isRemoved()) {
            return null;
        }
        return entity;
    }
}
