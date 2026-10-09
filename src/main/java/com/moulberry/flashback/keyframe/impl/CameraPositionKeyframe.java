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
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraPositionOnly;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import com.moulberry.flashback.keyframe.types.CameraPositionKeyframeType;
import com.moulberry.flashback.spline.CatmullRom;
import com.moulberry.flashback.spline.Hermite;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3d;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Where the camera is, on its own track.
 *
 * <p>Splitting position out of {@link CameraKeyframe} is what makes position and rotation genuinely
 * independent: this keyframe carries no angles, so evaluating its track cannot change where the
 * camera is looking.
 */
public class CameraPositionKeyframe extends Keyframe {

    public final Vector3d position;

    public CameraPositionKeyframe(Vector3d position) {
        this(position, InterpolationType.getDefault());
    }

    public CameraPositionKeyframe(Vector3d position, InterpolationType interpolationType) {
        this.position = position;
        this.interpolationType(interpolationType);
    }

    @Override
    public KeyframeType<?> keyframeType() {
        return CameraPositionKeyframeType.INSTANCE;
    }

    @Override
    public Keyframe copy() {
        return new CameraPositionKeyframe(new Vector3d(this.position), this.interpolationType());
    }

    @Override
    public void renderEditKeyframe(Consumer<Consumer<Keyframe>> update) {
        float[] input = new float[]{(float) this.position.x, (float) this.position.y, (float) this.position.z};
        if (ImGuiHelper.inputFloat(I18n.get("flashback.position"), input)) {
            if (input[0] != this.position.x) {
                update.accept(keyframe -> ((CameraPositionKeyframe) keyframe).position.x = input[0]);
            }
            if (input[1] != this.position.y) {
                update.accept(keyframe -> ((CameraPositionKeyframe) keyframe).position.y = input[1]);
            }
            if (input[2] != this.position.z) {
                update.accept(keyframe -> ((CameraPositionKeyframe) keyframe).position.z = input[2]);
            }
        }
    }

    @Override
    public KeyframeChange createChange() {
        return new KeyframeChangeCameraPositionOnly(this.position);
    }

    @Override
    public KeyframeChange createSmoothInterpolatedChange(Keyframe p1, Keyframe p2, Keyframe p3, float t0, float t1, float t2, float t3, float amount) {
        float time1 = t1 - t0;
        float time2 = t2 - t0;
        float time3 = t3 - t0;

        Vector3d position = CatmullRom.position(this.position,
                ((CameraPositionKeyframe) p1).position, ((CameraPositionKeyframe) p2).position,
                ((CameraPositionKeyframe) p3).position, time1, time2, time3, amount);

        return new KeyframeChangeCameraPositionOnly(position);
    }

    @Override
    public KeyframeChange createHermiteInterpolatedChange(Map<Float, Keyframe> keyframes, float amount) {
        Vector3d position = Hermite.position(Maps.transformValues(keyframes, k -> ((CameraPositionKeyframe) k).position), amount);
        return new KeyframeChangeCameraPositionOnly(position);
    }

    public static class TypeAdapter implements JsonSerializer<CameraPositionKeyframe>, JsonDeserializer<CameraPositionKeyframe> {
        @Override
        public CameraPositionKeyframe deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject jsonObject = json.getAsJsonObject();
            Vector3d position = context.deserialize(jsonObject.get("position"), Vector3d.class);
            InterpolationType interpolationType = context.deserialize(jsonObject.get("interpolation_type"), InterpolationType.class);
            return new CameraPositionKeyframe(position, interpolationType);
        }

        @Override
        public JsonElement serialize(CameraPositionKeyframe src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject jsonObject = new JsonObject();
            jsonObject.add("position", context.serialize(src.position));
            jsonObject.addProperty("type", "camera_position");
            jsonObject.add("interpolation_type", context.serialize(src.interpolationType()));
            return jsonObject;
        }
    }

}
