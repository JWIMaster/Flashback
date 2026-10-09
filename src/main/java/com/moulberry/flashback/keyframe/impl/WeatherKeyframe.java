package com.moulberry.flashback.keyframe.impl;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.moulberry.flashback.combo_options.WeatherOverride;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeWeather;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import com.moulberry.flashback.keyframe.types.WeatherKeyframeType;
import imgui.moulberry90.ImGui;
import net.minecraft.client.resources.language.I18n;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A weather state that takes effect from this tick onwards.
 *
 * <p>Weather is one of the discrete {@link WeatherOverride} states rather than a number, so this
 * keyframe stores the state itself. It is a scene track: weather describes the world, so it keeps
 * applying when the camera cuts.
 */
public class WeatherKeyframe extends Keyframe {

    public WeatherOverride mode;

    public WeatherKeyframe(WeatherOverride mode) {
        this(mode, InterpolationType.getDefault());
    }

    public WeatherKeyframe(WeatherOverride mode, InterpolationType interpolationType) {
        this.mode = mode;
        this.interpolationType(interpolationType);
    }

    @Override
    public KeyframeType<?> keyframeType() {
        return WeatherKeyframeType.INSTANCE;
    }

    @Override
    public Keyframe copy() {
        return new WeatherKeyframe(this.mode, this.interpolationType());
    }

    @Override
    public void renderEditKeyframe(Consumer<Consumer<Keyframe>> update) {
        ImGui.setNextItemWidth(160);
        WeatherOverride selected = ImGuiHelper.enumCombo(I18n.get("flashback.weather"), this.mode);
        if (selected != this.mode) {
            update.accept(keyframe -> ((WeatherKeyframe) keyframe).mode = selected);
        }
    }

    @Override
    public KeyframeChange createChange() {
        return new KeyframeChangeWeather(this.mode);
    }

    /**
     * A weather state has no interpolated form, so a smooth span simply starts from this keyframe.
     *
     * <p>The step between the two ends of a span comes from
     * {@link KeyframeChangeWeather#interpolate}, which picks a side.
     */
    @Override
    public KeyframeChange createSmoothInterpolatedChange(Keyframe p1, Keyframe p2, Keyframe p3, float t0, float t1, float t2, float t3, float amount) {
        return this.createChange();
    }

    /** As above: there is nothing to blend between two weather states. */
    @Override
    public KeyframeChange createHermiteInterpolatedChange(Map<Float, Keyframe> keyframes, float amount) {
        return this.createChange();
    }

    public static class TypeAdapter implements com.google.gson.JsonSerializer<WeatherKeyframe>,
            com.google.gson.JsonDeserializer<WeatherKeyframe> {

        @Override
        public WeatherKeyframe deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject jsonObject = json.getAsJsonObject();
            WeatherOverride mode = context.deserialize(jsonObject.get("mode"), WeatherOverride.class);
            InterpolationType interpolationType = context.deserialize(jsonObject.get("interpolation_type"), InterpolationType.class);
            return new WeatherKeyframe(mode, interpolationType);
        }

        @Override
        public JsonElement serialize(WeatherKeyframe src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject jsonObject = new JsonObject();
            jsonObject.add("mode", context.serialize(src.mode));
            jsonObject.addProperty("type", "weather");
            jsonObject.add("interpolation_type", context.serialize(src.interpolationType()));
            return jsonObject;
        }
    }

}
