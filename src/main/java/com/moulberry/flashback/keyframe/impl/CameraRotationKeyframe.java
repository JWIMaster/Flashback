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
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraRotationOnly;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import com.moulberry.flashback.keyframe.types.CameraRotationKeyframeType;
import com.moulberry.flashback.spline.CatmullRom;
import com.moulberry.flashback.spline.Hermite;
import net.minecraft.client.resources.language.I18n;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Where the camera is looking, on its own track.
 *
 * <p>Carries no position, so evaluating this track cannot move the camera: it only sets yaw, pitch
 * and roll, which is what makes a rotation animation independent of a position animation on the
 * same camera.
 */
public class CameraRotationKeyframe extends Keyframe {

    public float yaw;
    public float pitch;
    public float roll;

    public CameraRotationKeyframe(float yaw, float pitch, float roll) {
        this(yaw, pitch, roll, InterpolationType.getDefault());
    }

    public CameraRotationKeyframe(float yaw, float pitch, float roll, InterpolationType interpolationType) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
        this.interpolationType(interpolationType);
    }

    @Override
    public KeyframeType<?> keyframeType() {
        return CameraRotationKeyframeType.INSTANCE;
    }

    @Override
    public Keyframe copy() {
        return new CameraRotationKeyframe(this.yaw, this.pitch, this.roll, this.interpolationType());
    }

    @Override
    public void renderEditKeyframe(Consumer<Consumer<Keyframe>> update) {
        float[] input = new float[]{this.yaw};
        if (ImGuiHelper.inputFloat(I18n.get("flashback.yaw"), input) && input[0] != this.yaw) {
            update.accept(keyframe -> ((CameraRotationKeyframe) keyframe).yaw = input[0]);
        }
        input[0] = this.pitch;
        if (ImGuiHelper.inputFloat(I18n.get("flashback.pitch"), input) && input[0] != this.pitch) {
            update.accept(keyframe -> ((CameraRotationKeyframe) keyframe).pitch = input[0]);
        }
        input[0] = this.roll;
        if (ImGuiHelper.inputFloat(I18n.get("flashback.roll"), input) && input[0] != this.roll) {
            update.accept(keyframe -> ((CameraRotationKeyframe) keyframe).roll = input[0]);
        }
    }

    @Override
    public KeyframeChange createChange() {
        return new KeyframeChangeCameraRotationOnly(this.yaw, this.pitch, this.roll);
    }

    @Override
    public KeyframeChange createSmoothInterpolatedChange(Keyframe p1, Keyframe p2, Keyframe p3, float t0, float t1, float t2, float t3, float amount) {
        float time1 = t1 - t0;
        float time2 = t2 - t0;
        float time3 = t3 - t0;

        float yaw = CatmullRom.degrees(this.yaw,
                ((CameraRotationKeyframe) p1).yaw, ((CameraRotationKeyframe) p2).yaw,
                ((CameraRotationKeyframe) p3).yaw, time1, time2, time3, amount);
        float pitch = CatmullRom.degrees(this.pitch,
                ((CameraRotationKeyframe) p1).pitch, ((CameraRotationKeyframe) p2).pitch,
                ((CameraRotationKeyframe) p3).pitch, time1, time2, time3, amount);
        float roll = CatmullRom.degrees(this.roll,
                ((CameraRotationKeyframe) p1).roll, ((CameraRotationKeyframe) p2).roll,
                ((CameraRotationKeyframe) p3).roll, time1, time2, time3, amount);

        return new KeyframeChangeCameraRotationOnly(yaw, pitch, roll);
    }

    @Override
    public KeyframeChange createHermiteInterpolatedChange(Map<Float, Keyframe> keyframes, float amount) {
        double yaw = Hermite.degrees(Maps.transformValues(keyframes, k -> (double) ((CameraRotationKeyframe) k).yaw), amount);
        double pitch = Hermite.degrees(Maps.transformValues(keyframes, k -> (double) ((CameraRotationKeyframe) k).pitch), amount);
        double roll = Hermite.degrees(Maps.transformValues(keyframes, k -> (double) ((CameraRotationKeyframe) k).roll), amount);

        return new KeyframeChangeCameraRotationOnly(yaw, pitch, roll);
    }

    public static class TypeAdapter implements JsonSerializer<CameraRotationKeyframe>, JsonDeserializer<CameraRotationKeyframe> {
        @Override
        public CameraRotationKeyframe deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject jsonObject = json.getAsJsonObject();
            float yaw = jsonObject.has("yaw") ? jsonObject.get("yaw").getAsFloat() : 0.0f;
            float pitch = jsonObject.has("pitch") ? jsonObject.get("pitch").getAsFloat() : 0.0f;
            float roll = jsonObject.has("roll") ? jsonObject.get("roll").getAsFloat() : 0.0f;
            InterpolationType interpolationType = context.deserialize(jsonObject.get("interpolation_type"), InterpolationType.class);
            return new CameraRotationKeyframe(yaw, pitch, roll, interpolationType);
        }

        @Override
        public JsonElement serialize(CameraRotationKeyframe src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("yaw", src.yaw);
            jsonObject.addProperty("pitch", src.pitch);
            jsonObject.addProperty("roll", src.roll);
            jsonObject.addProperty("type", "camera_rotation");
            jsonObject.add("interpolation_type", context.serialize(src.interpolationType()));
            return jsonObject;
        }
    }

}
