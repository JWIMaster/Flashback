package com.moulberry.flashback.keyframe.handler;

import com.moulberry.flashback.combo_options.WeatherOverride;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import net.minecraft.client.Minecraft;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

public interface KeyframeHandler {
    boolean supportsKeyframeChange(Class<? extends KeyframeChange> clazz);

    default Minecraft getMinecraft() {
        return null;
    }

    default boolean alwaysApplyLastKeyframe() {
        return false;
    }

    /** Starts a fresh camera render pass without changing persisted scene defaults. */
    default void beginCameraFrame() {
    }

    /**
     * Where the camera is currently following, if anything.
     *
     * <p>An orbit that is centred on its subject asks for this, so it can turn around whatever the
     * camera is following rather than around a point fixed when the keyframe was made.
     */
    default void setFollowedPosition(Vector3d position) {
    }

    default Vector3d followedPosition() {
        return null;
    }

    /**
     * The player being filmed, when nothing more specific is being followed.
     *
     * <p>An orbit camera turns around its subject, and its subject is normally the player. Taking that
     * from the handler keeps the change free of any dependency on a game client being present.
     */
    default Vector3d subjectPosition() {
        return null;
    }

    default void applyCameraPosition(Vector3d position, double yaw, double pitch, double roll) {
    }

    /**
     * Moves the camera to a position while keeping the rotation it already has.
     *
     * <p>A camera's position and rotation are independent properties with their own tracks, and both
     * tracks are applied in the same frame. A handler therefore has to read the value it is not
     * setting from the live camera: this one takes the current yaw and pitch and leaves roll alone
     * entirely, so a position keyframe cannot turn the camera and cannot disturb a rotation
     * keyframe's roll.
     */
    default void applyCameraPositionOnly(Vector3d position) {
    }

    /**
     * Turns the camera to these angles while keeping the position it already has.
     *
     * <p>The counterpart to {@link #applyCameraPositionOnly}: the handler reads the current x/y/z
     * from the live camera and writes only the angles, so a rotation keyframe cannot move the camera
     * and the two kinds of track can be applied in either order.
     */
    default void applyCameraRotationOnly(double yaw, double pitch, double roll) {
    }

    default void applyFov(float fov) {
    }

    default void applyTickrate(float tickrate) {
    }

    default void applyFreeze(boolean frozen, int frozenDelay) {
    }

    default void applyTimeOfDay(int timeOfDay) {
    }

    /**
     * Sets the weather state the editor draws, or clears any override.
     *
     * <p>Weather is an environmental state rather than anything the replay server simulates, so this
     * defaults to doing nothing and only a handler that owns the client's rendering needs it.
     */
    default void applyWeather(WeatherOverride mode) {
    }

    default void applyCameraShake(float frequencyX, float amplitudeX, float frequencyY, float amplitudeY) {
    }

    /**
     * Points the camera at a player's first-person view, or back to the replay's own viewpoint when
     * the target is null.
     *
     * <p>Who is being spectated is a property of the source being output, so this is applied by the
     * editor when a spectate source becomes active rather than by a keyframe of its own.
     */
    default void applySpectate(@org.jetbrains.annotations.Nullable java.util.UUID target) {
    }

}
