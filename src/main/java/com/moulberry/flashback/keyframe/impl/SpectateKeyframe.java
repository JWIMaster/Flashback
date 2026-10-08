package com.moulberry.flashback.keyframe.impl;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeSpectate;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import com.moulberry.flashback.keyframe.types.SpectateKeyframeType;
import com.moulberry.flashback.state.CameraSourceDisplay;
import imgui.moulberry90.ImGui;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;

/**
 * A viewpoint that follows a player, as an object on the timeline in its own right.
 *
 * <p>This is what the camera switch selects when you want a player's view: the track holds these
 * keyframes saying who is being watched over time, and the switch simply decides when that source
 * is the one being output.
 */
public class SpectateKeyframe extends Keyframe {

    /** The player to follow. */
    public final @Nullable UUID target;

    public SpectateKeyframe(@Nullable UUID target) {
        this(target, InterpolationType.getDefault());
    }

    public SpectateKeyframe(@Nullable UUID target, InterpolationType interpolationType) {
        this.target = target;
        this.interpolationType(interpolationType);
    }

    @Override
    public KeyframeType<?> keyframeType() {
        return SpectateKeyframeType.INSTANCE;
    }

    @Override
    public Keyframe copy() {
        return new SpectateKeyframe(this.target, this.interpolationType());
    }

    @Override
    public void renderEditKeyframe(java.util.function.Consumer<java.util.function.Consumer<Keyframe>> update) {
        ImGui.textUnformatted(CameraSourceDisplay.describeSpectate(this.target));
    }

    @Override
    public KeyframeChange createChange() {
        return new KeyframeChangeSpectate(this.target);
    }

    @Override
    public KeyframeChange createSmoothInterpolatedChange(Keyframe p1, Keyframe p2, Keyframe p3, float t0, float t1, float t2, float t3, float amount) {
        return this.createChange();
    }

    @Override
    public KeyframeChange createHermiteInterpolatedChange(Map<Float, Keyframe> keyframes, float tick) {
        return this.createChange();
    }

    public static class TypeAdapter implements com.google.gson.JsonSerializer<SpectateKeyframe>,
            com.google.gson.JsonDeserializer<SpectateKeyframe> {

        @Override
        public SpectateKeyframe deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject jsonObject = json.getAsJsonObject();
            InterpolationType interpolationType = context.deserialize(jsonObject.get("interpolation_type"), InterpolationType.class);
            UUID target = jsonObject.has("target") && !jsonObject.get("target").isJsonNull()
                ? UUID.fromString(jsonObject.get("target").getAsString())
                : null;
            return new SpectateKeyframe(target, interpolationType);
        }

        @Override
        public JsonElement serialize(SpectateKeyframe src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject jsonObject = new JsonObject();
            if (src.target != null) {
                jsonObject.addProperty("target", src.target.toString());
            }
            jsonObject.addProperty("type", "spectate");
            jsonObject.add("interpolation_type", context.serialize(src.interpolationType()));
            return jsonObject;
        }
    }

}
