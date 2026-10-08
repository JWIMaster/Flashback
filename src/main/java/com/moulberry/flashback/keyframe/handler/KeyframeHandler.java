package com.moulberry.flashback.keyframe.handler;

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

    default void applyCameraPosition(Vector3d position, double yaw, double pitch, double roll) {
    }

    default void applyFov(float fov) {
    }

    default void applyTickrate(float tickrate) {
    }

    default void applyFreeze(boolean frozen, int frozenDelay) {
    }

    default void applyTimeOfDay(int timeOfDay) {
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
