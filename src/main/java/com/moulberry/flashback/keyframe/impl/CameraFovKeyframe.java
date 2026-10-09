package com.moulberry.flashback.keyframe.impl;

import com.google.common.collect.Maps;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.moulberry.flashback.Utils;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraFov;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import com.moulberry.flashback.keyframe.types.CameraFovKeyframeType;
import com.moulberry.flashback.spline.CatmullRom;
import com.moulberry.flashback.spline.Hermite;
import imgui.moulberry90.ImGui;
import net.minecraft.client.resources.language.I18n;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A field of view animated by a camera rather than by the scene.
 *
 * <p>It applies through the same handler method as the scene-wide {@link FOVKeyframe} - the editor
 * has one FOV to render - but it is a different keyframe and a different change, so it lives on its
 * own track and does not collide with the scene lane.
 */
public class CameraFovKeyframe extends Keyframe {

    public float fov;

    public CameraFovKeyframe(float fov) {
        this(fov, InterpolationType.getDefault());
    }

    public CameraFovKeyframe(float fov, InterpolationType interpolationType) {
        this.fov = fov;
        this.interpolationType(interpolationType);
    }

    @Override
    public KeyframeType<?> keyframeType() {
        return CameraFovKeyframeType.INSTANCE;
    }

    @Override
    public Keyframe copy() {
        return new CameraFovKeyframe(this.fov, this.interpolationType());
    }

    @Override
    public void renderEditKeyframe(Consumer<Consumer<Keyframe>> update) {
        ImGui.setNextItemWidth(160);
        float[] input = new float[]{this.fov};
        if (ImGui.sliderFloat(I18n.get("flashback.fov"), input, 1.0f, 110.0f, "%.1f") && this.fov != input[0]) {
            update.accept(keyframe -> ((CameraFovKeyframe) keyframe).fov = input[0]);
        }
    }

    @Override
    public KeyframeChange createChange() {
        return new KeyframeChangeCameraFov(this.fov);
    }

    @Override
    public KeyframeChange createSmoothInterpolatedChange(Keyframe p1, Keyframe p2, Keyframe p3, float t0, float t1, float t2, float t3, float amount) {
        float time1 = t1 - t0;
        float time2 = t2 - t0;
        float time3 = t3 - t0;

        float f0 = Utils.fovToFocalLength(this.fov);
        float f1 = Utils.fovToFocalLength(((CameraFovKeyframe) p1).fov);
        float f2 = Utils.fovToFocalLength(((CameraFovKeyframe) p2).fov);
        float f3 = Utils.fovToFocalLength(((CameraFovKeyframe) p3).fov);

        float focalLength = CatmullRom.value(f0, f1, f2, f3, time1, time2, time3, amount);

        return new KeyframeChangeCameraFov(Utils.focalLengthToFov(focalLength));
    }

    @Override
    public KeyframeChange createHermiteInterpolatedChange(Map<Float, Keyframe> keyframes, float amount) {
        float focalLength = (float) Hermite.value(Maps.transformValues(keyframes, k -> (double) Utils.fovToFocalLength(((CameraFovKeyframe) k).fov)), amount);
        return new KeyframeChangeCameraFov(Utils.focalLengthToFov(focalLength));
    }

    public static class TypeAdapter implements JsonSerializer<CameraFovKeyframe>, JsonDeserializer<CameraFovKeyframe> {
        @Override
        public CameraFovKeyframe deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject jsonObject = json.getAsJsonObject();
            float fov = jsonObject.has("fov") ? jsonObject.get("fov").getAsFloat() : 70.0f;
            InterpolationType interpolationType = context.deserialize(jsonObject.get("interpolation_type"), InterpolationType.class);
            return new CameraFovKeyframe(fov, interpolationType);
        }

        @Override
        public JsonElement serialize(CameraFovKeyframe src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("fov", src.fov);
            jsonObject.addProperty("type", "camera_fov");
            jsonObject.add("interpolation_type", context.serialize(src.interpolationType()));
            return jsonObject;
        }
    }

}
