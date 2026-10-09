package com.moulberry.flashback.keyframe.impl;

import com.google.common.collect.Maps;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraPositionOrbit;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import com.moulberry.flashback.keyframe.types.CameraOrbitKeyframeType;
import com.moulberry.flashback.spline.CatmullRom;
import com.moulberry.flashback.spline.Hermite;
import net.minecraft.client.resources.language.I18n;
import org.joml.Vector3d;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import com.moulberry.flashback.state.CameraSourceDisplay;
import com.moulberry.flashback.keyframe.types.SpectateKeyframeType;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.type.ImBoolean;

import java.util.function.Consumer;

public class CameraOrbitKeyframe extends Keyframe {

    public Vector3d center;
    public float distance;
    public float yaw;
    public float pitch;
    /**
     * Whether the orbit is centred on whoever the camera is following, rather than on the stored
     * point. Absent from older projects, where an orbit was always around a fixed point.
     */
    public boolean centreOnTarget;
    public UUID target;
    /**
     * Whether the camera trails the subject instead of being glued to them. Absent from older
     * projects, where an orbit always followed exactly.
     */
    public boolean smoothFollow;
    /** How far behind the subject a lagging orbit trails, in seconds. */
    public float lagSeconds = 0.35f;

    public CameraOrbitKeyframe(Vector3d center, float distance, float yaw, float pitch) {
        this(center, distance, yaw, pitch, InterpolationType.getDefault());
    }

    public CameraOrbitKeyframe(Vector3d center, float distance, float yaw, float pitch, InterpolationType interpolationType) {
        this(center, distance, yaw, pitch, interpolationType, false);
    }

    public CameraOrbitKeyframe(Vector3d center, float distance, float yaw, float pitch,
                               InterpolationType interpolationType, boolean centreOnTarget) {
        this(center, distance, yaw, pitch, interpolationType, centreOnTarget, null);
    }

    public CameraOrbitKeyframe(Vector3d center, float distance, float yaw, float pitch,
                               InterpolationType interpolationType, boolean centreOnTarget, UUID target) {
        this(center, distance, yaw, pitch, interpolationType, centreOnTarget, target, false, 0.35f);
    }

    public CameraOrbitKeyframe(Vector3d center, float distance, float yaw, float pitch,
                               InterpolationType interpolationType, boolean centreOnTarget, UUID target,
                               boolean smoothFollow, float lagSeconds) {
        this.target = target;
        this.smoothFollow = smoothFollow;
        this.lagSeconds = lagSeconds;
        this.center = center;
        this.distance = distance;
        this.yaw = yaw;
        this.pitch = pitch;
        this.centreOnTarget = centreOnTarget;
        this.interpolationType(interpolationType);
    }

    @Override
    public KeyframeType<?> keyframeType() {
        return CameraOrbitKeyframeType.INSTANCE;
    }

    @Override
    public Keyframe copy() {
        return new CameraOrbitKeyframe(new Vector3d(this.center), this.distance, this.yaw, this.pitch,
            this.interpolationType(), this.centreOnTarget, this.target, this.smoothFollow, this.lagSeconds);
    }

    @Override
    public void renderEditKeyframe(Consumer<Consumer<Keyframe>> update) {
        ImBoolean followsTarget = new ImBoolean(this.centreOnTarget);
        if (ImGui.checkbox(I18n.get("flashback.orbit_centre_on_player"), followsTarget)) {
            boolean value = followsTarget.get();
            update.accept(keyframe -> ((CameraOrbitKeyframe) keyframe).centreOnTarget = value);
        }
        ImGuiHelper.tooltip(I18n.get("flashback.orbit_centre_on_player_hint"));
        if (this.centreOnTarget) {
            if (ImGui.beginCombo(I18n.get("flashback.keyframe.spectate"),
                this.target == null ? I18n.get("flashback.no_players_available") : CameraSourceDisplay.describePlayer(this.target))) {
                for (Player subject : SpectateKeyframeType.availablePlayers()) {
                    if (subject != Minecraft.getInstance().player &&
                        ImGui.selectable(subject.getName().getString() + "##orbit_edit_" + subject.getUUID(), subject.getUUID().equals(this.target))) {
                        UUID selected = subject.getUUID();
                        update.accept(keyframe -> ((CameraOrbitKeyframe) keyframe).target = selected);
                    }
                }
                ImGui.endCombo();
            }

            ImBoolean lagsBehind = new ImBoolean(this.smoothFollow);
            if (ImGui.checkbox(I18n.get("flashback.orbit_lag_behind"), lagsBehind)) {
                boolean value = lagsBehind.get();
                update.accept(keyframe -> ((CameraOrbitKeyframe) keyframe).smoothFollow = value);
            }
            ImGuiHelper.tooltip(I18n.get("flashback.orbit_lag_behind_hint"));
            if (lagsBehind.get()) {
                float[] lag = new float[]{this.lagSeconds};
                if (ImGuiHelper.inputFloat(I18n.get("flashback.orbit_lag_seconds"), lag)) {
                    float value = Math.max(0.0f, Math.min(2.0f, lag[0]));
                    if (value != this.lagSeconds) {
                        update.accept(keyframe -> ((CameraOrbitKeyframe) keyframe).lagSeconds = value);
                    }
                }
            }
        }

        float[] center = new float[]{(float) this.center.x, (float) this.center.y, (float) this.center.z};
        if (!this.centreOnTarget && ImGuiHelper.inputFloat(I18n.get("flashback.position"), center)) {
            if (center[0] != this.center.x) {
                update.accept(keyframe -> ((CameraOrbitKeyframe)keyframe).center.x = center[0]);
            }
            if (center[1] != this.center.y) {
                update.accept(keyframe -> ((CameraOrbitKeyframe)keyframe).center.y = center[1]);
            }
            if (center[2] != this.center.z) {
                update.accept(keyframe -> ((CameraOrbitKeyframe)keyframe).center.z = center[2]);
            }
        }
        float[] input = new float[]{this.distance};
        if (ImGuiHelper.inputFloat(I18n.get("flashback.distance"), input)) {
            if (input[0] != this.distance) {
                update.accept(keyframe -> ((CameraOrbitKeyframe)keyframe).distance = input[0]);
            }
        }
        input[0] = this.yaw;
        if (ImGuiHelper.inputFloat(I18n.get("flashback.yaw"), input)) {
            if (input[0] != this.yaw) {
                update.accept(keyframe -> ((CameraOrbitKeyframe)keyframe).yaw = input[0]);
            }
        }
        input[0] = this.pitch;
        if (ImGuiHelper.inputFloat(I18n.get("flashback.pitch"), input)) {
            if (input[0] != this.pitch) {
                update.accept(keyframe -> ((CameraOrbitKeyframe)keyframe).pitch = input[0]);
            }
        }
    }

    private static KeyframeChangeCameraPositionOrbit createChangeFrom(Vector3d center, float distance, float yaw,
                                                                    float pitch, boolean centreOnTarget, UUID target,
                                                                    boolean smoothFollow, float lagSeconds) {
        return new KeyframeChangeCameraPositionOrbit(center, distance, yaw, pitch, centreOnTarget, target,
            smoothFollow, lagSeconds);
    }

    @Override
    public KeyframeChange createChange() {
        return createChangeFrom(this.center, this.distance, this.yaw, this.pitch, this.centreOnTarget, this.target,
            this.smoothFollow, this.lagSeconds);
    }

    @Override
    public KeyframeChange createSmoothInterpolatedChange(Keyframe p1, Keyframe p2, Keyframe p3, float t0, float t1, float t2, float t3, float amount) {
        float time1 = t1 - t0;
        float time2 = t2 - t0;
        float time3 = t3 - t0;

        Vector3d position = CatmullRom.position(this.center,
                ((CameraOrbitKeyframe)p1).center, ((CameraOrbitKeyframe)p2).center,
                ((CameraOrbitKeyframe)p3).center, time1, time2, time3, amount);

        float distance = CatmullRom.value(this.distance, ((CameraOrbitKeyframe)p1).distance, ((CameraOrbitKeyframe)p2).distance,
                ((CameraOrbitKeyframe)p3).distance, time1, time2, time3, amount);

        // Note: we don't use CatmullRom#degrees because we want to allow multiple rotations in a single orbit
        float yaw = CatmullRom.value(this.yaw, ((CameraOrbitKeyframe)p1).yaw, ((CameraOrbitKeyframe)p2).yaw,
                ((CameraOrbitKeyframe)p3).yaw, time1, time2, time3, amount);
        float pitch = CatmullRom.value(this.pitch, ((CameraOrbitKeyframe)p1).pitch, ((CameraOrbitKeyframe)p2).pitch,
                ((CameraOrbitKeyframe)p3).pitch, time1, time2, time3, amount);

        return createChangeFrom(position, distance, yaw, pitch, this.centreOnTarget, this.target,
            this.smoothFollow, this.lagSeconds);
    }

    @Override
    public KeyframeChange createHermiteInterpolatedChange(Map<Float, Keyframe> keyframes, float amount) {
        Vector3d position = Hermite.position(Maps.transformValues(keyframes, k -> ((CameraOrbitKeyframe)k).center), amount);
        double distance = Hermite.value(Maps.transformValues(keyframes, k -> (double) ((CameraOrbitKeyframe)k).distance), amount);

        // Note: we don't use Hermite#degrees because we want to allow multiple rotations in a single orbit
        double yaw = Hermite.value(Maps.transformValues(keyframes, k -> (double) ((CameraOrbitKeyframe)k).yaw), amount);
        double pitch = Hermite.value(Maps.transformValues(keyframes, k -> (double) ((CameraOrbitKeyframe)k).pitch), amount);

        return createChangeFrom(position, (float) distance, (float) yaw, (float) pitch, this.centreOnTarget, this.target,
            this.smoothFollow, this.lagSeconds);
    }

    public static class TypeAdapter implements JsonSerializer<CameraOrbitKeyframe>, JsonDeserializer<CameraOrbitKeyframe> {
        @Override
        public CameraOrbitKeyframe deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject jsonObject = json.getAsJsonObject();
            Vector3d center = context.deserialize(jsonObject.get("center"), Vector3d.class);
            float distance = jsonObject.get("distance").getAsFloat();
            float yaw = jsonObject.get("yaw").getAsFloat();
            float pitch = jsonObject.get("pitch").getAsFloat();
            InterpolationType interpolationType = context.deserialize(jsonObject.get("interpolation_type"), InterpolationType.class);
            boolean centreOnTarget = jsonObject.has("centre_on_target") && jsonObject.get("centre_on_target").getAsBoolean();
            UUID target = jsonObject.has("target") ? UUID.fromString(jsonObject.get("target").getAsString()) : null;
            boolean smoothFollow = jsonObject.has("lag_behind") && jsonObject.get("lag_behind").getAsBoolean();
            float lagSeconds = jsonObject.has("lag_seconds") ? jsonObject.get("lag_seconds").getAsFloat() : 0.35f;
            return new CameraOrbitKeyframe(center, distance, yaw, pitch, interpolationType, centreOnTarget, target,
                smoothFollow, lagSeconds);
        }

        @Override
        public JsonElement serialize(CameraOrbitKeyframe src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject jsonObject = new JsonObject();
            jsonObject.add("center", context.serialize(src.center));
            jsonObject.addProperty("distance", src.distance);
            jsonObject.addProperty("yaw", src.yaw);
            jsonObject.addProperty("pitch", src.pitch);
            if (src.centreOnTarget) {
                // Only written when set, so projects that predate it stay byte-for-byte familiar.
                jsonObject.addProperty("centre_on_target", true);
            }
            if (src.target != null) {
                jsonObject.addProperty("target", src.target.toString());
            }
            if (src.smoothFollow) {
                jsonObject.addProperty("lag_behind", true);
                jsonObject.addProperty("lag_seconds", src.lagSeconds);
            }
            jsonObject.addProperty("type", "camera_orbit");
            jsonObject.add("interpolation_type", context.serialize(src.interpolationType()));
            return jsonObject;
        }
    }

}
