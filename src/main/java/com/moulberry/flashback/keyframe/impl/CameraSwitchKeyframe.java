package com.moulberry.flashback.keyframe.impl;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraSwitch;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import com.moulberry.flashback.keyframe.types.CameraSwitchKeyframeType;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import imgui.moulberry90.ImGui;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;
import com.moulberry.flashback.state.CameraSourceDisplay;

/**
 * Marks that the camera being output changes at this tick.
 *
 * <p>This is a hard cut, not an animated value, so it does not interpolate: the camera in force
 * between two switches is simply the one named by the most recent switch at or before the current
 * tick. The keyframe only names a camera id - resolving that id, and applying that camera's own
 * tracks, belongs to the editor, so that a switch never implies anything about the camera itself.
 */
public class CameraSwitchKeyframe extends Keyframe {

    /** The camera to output, or null for "whichever camera is first" in a scene not yet set up. */
    public final @Nullable UUID cameraId;

    public CameraSwitchKeyframe(@Nullable UUID cameraId) {
        this(cameraId, InterpolationType.getDefault());
    }

    public CameraSwitchKeyframe(@Nullable UUID cameraId, InterpolationType interpolationType) {
        this.cameraId = cameraId;
        this.interpolationType(interpolationType);
    }

    @Override
    public KeyframeType<?> keyframeType() {
        return CameraSwitchKeyframeType.INSTANCE;
    }

    @Override
    public Keyframe copy() {
        return new CameraSwitchKeyframe(this.cameraId, this.interpolationType());
    }

    @Override
    public void renderEditKeyframe(java.util.function.Consumer<java.util.function.Consumer<Keyframe>> update) {
        EditorState editorState = EditorStateManager.getCurrent();
        EditorScene scene = editorState == null ? null : editorState.currentSceneOrNull();
        ImGui.textUnformatted(I18n.get("flashback.cut_to_camera",
            CameraSourceDisplay.describeCamera(scene, this.cameraId)));
    }

    @Override
    public KeyframeChange createChange() {
        return new KeyframeChangeCameraSwitch(this.cameraId);
    }

    @Override
    public KeyframeChange createSmoothInterpolatedChange(Keyframe p1, Keyframe p2, Keyframe p3, float t0, float t1, float t2, float t3, float amount) {
        // A cut has no intermediate value: hold the camera named by the earlier keyframe.
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

            UUID cameraId = null;
            if (jsonObject.has("camera_id") && !jsonObject.get("camera_id").isJsonNull()) {
                cameraId = UUID.fromString(jsonObject.get("camera_id").getAsString());
            }

            return new CameraSwitchKeyframe(cameraId, interpolationType);
        }

        @Override
        public JsonElement serialize(CameraSwitchKeyframe src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject jsonObject = new JsonObject();
            if (src.cameraId != null) {
                jsonObject.addProperty("camera_id", src.cameraId.toString());
            }
            jsonObject.addProperty("type", "camera_switch");
            jsonObject.add("interpolation_type", context.serialize(src.interpolationType()));
            return jsonObject;
        }
    }

}
