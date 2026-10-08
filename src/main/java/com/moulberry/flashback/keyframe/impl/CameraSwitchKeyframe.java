package com.moulberry.flashback.keyframe.impl;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.moulberry.flashback.keyframe.CameraSource;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraSwitch;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import com.moulberry.flashback.keyframe.types.CameraSwitchKeyframeType;
import imgui.moulberry90.ImGui;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;

/**
 * Marks which viewpoint becomes active at this tick.
 *
 * <p>This is a discrete cut, not an animated value, so it does not interpolate: the camera in force
 * between two switches is simply the most recent one at or before the current tick.
 */
public class CameraSwitchKeyframe extends Keyframe {

    public final CameraSource source;

    public CameraSwitchKeyframe(CameraSource source) {
        this(source, InterpolationType.getDefault());
    }

    public CameraSwitchKeyframe(CameraSource source, InterpolationType interpolationType) {
        this.source = source;
        this.interpolationType(interpolationType);
    }

    @Override
    public KeyframeType<?> keyframeType() {
        return CameraSwitchKeyframeType.INSTANCE;
    }

    @Override
    public Keyframe copy() {
        return new CameraSwitchKeyframe(this.source, this.interpolationType());
    }

    @Override
    public void renderEditKeyframe(java.util.function.Consumer<java.util.function.Consumer<Keyframe>> update) {
        ImGui.textUnformatted(
            com.moulberry.flashback.state.CameraSourceDisplay.describeSource(this.source.sourceId()));
    }

    @Override
    public KeyframeChange createChange() {
        return new KeyframeChangeCameraSwitch(this.source);
    }

    @Override
    public KeyframeChange createSmoothInterpolatedChange(Keyframe p1, Keyframe p2, Keyframe p3, float t0, float t1, float t2, float t3, float amount) {
        // A cut has no meaningful intermediate value: hold the earlier source.
        return this.createChange();
    }

    @Override
    public KeyframeChange createHermiteInterpolatedChange(Map<Float, Keyframe> keyframes, float tick) {
        return this.createChange();
    }

    public static class TypeAdapter implements com.google.gson.JsonSerializer<CameraSwitchKeyframe>,
            com.google.gson.JsonDeserializer<CameraSwitchKeyframe> {

        @Override
        public CameraSwitchKeyframe deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject jsonObject = json.getAsJsonObject();
            InterpolationType interpolationType = context.deserialize(jsonObject.get("interpolation_type"), InterpolationType.class);

            // "source_id" is the current form. The older "camera_id" / "spectate_target" pair is
            // still read so switch keyframes written before sources were unified keep working.
            UUID sourceId = null;
            if (jsonObject.has("source_id") && !jsonObject.get("source_id").isJsonNull()) {
                sourceId = UUID.fromString(jsonObject.get("source_id").getAsString());
            } else if (jsonObject.has("camera_id") && !jsonObject.get("camera_id").isJsonNull()) {
                sourceId = UUID.fromString(jsonObject.get("camera_id").getAsString());
            }

            return new CameraSwitchKeyframe(sourceId == null ? null : CameraSource.of(sourceId), interpolationType);
        }

        @Override
        public JsonElement serialize(CameraSwitchKeyframe src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject jsonObject = new JsonObject();
            if (src.source != null && src.source.sourceId() != null) {
                jsonObject.addProperty("source_id", src.source.sourceId().toString());
            }
            jsonObject.addProperty("type", "camera_switch");
            jsonObject.add("interpolation_type", context.serialize(src.interpolationType()));
            return jsonObject;
        }
    }

}
