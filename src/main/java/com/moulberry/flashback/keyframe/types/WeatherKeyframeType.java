package com.moulberry.flashback.keyframe.types;

import com.moulberry.flashback.combo_options.WeatherOverride;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeWeather;
import com.moulberry.flashback.keyframe.impl.WeatherKeyframe;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import imgui.moulberry90.ImGui;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;

/**
 * The animation lane for the weather: keyframes say which environmental state is shown.
 *
 * <p>Weather is a discrete state rather than a number, so the lane does not offer interpolation
 * types: a span from clear to a thunderstorm steps at its midpoint, and letting the user pick an
 * easing curve would imply a blend that does not exist.
 */
public class WeatherKeyframeType implements KeyframeType<WeatherKeyframe> {

    public static final WeatherKeyframeType INSTANCE = new WeatherKeyframeType();

    private WeatherKeyframeType() {
    }

    @Override
    public Class<? extends KeyframeChange> keyframeChangeType() {
        return KeyframeChangeWeather.class;
    }

    @Override
    public @Nullable String icon() {
        return "\ue2bd";
    }

    @Override
    public String name() {
        return I18n.get("flashback.keyframe.weather");
    }

    @Override
    public String id() {
        return "WEATHER";
    }

    /** Which weather is shown is discrete, so there is nothing to interpolate. */
    @Override
    public boolean allowChangingInterpolationType() {
        return false;
    }

    @Override
    public @Nullable WeatherKeyframe createDirect() {
        return null;
    }

    private static final WeatherOverride[] ALL_MODES = WeatherOverride.values();

    @Override
    public KeyframeCreatePopup<WeatherKeyframe> createPopup() {
        // Start from whatever the editor is already showing, so the popup opens on the state the user
        // is looking at rather than on an arbitrary one.
        WeatherOverride[] modeInput = new WeatherOverride[]{WeatherOverride.CLEAR};
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null && editorState.replayVisuals.overrideWeatherMode != null) {
            modeInput[0] = editorState.replayVisuals.overrideWeatherMode;
        }

        return () -> {
            // The explicit-values overload rebuilds its labels each frame, so a language change shows
            // up here without waiting for the shared combo cache to be cleared.
            modeInput[0] = ImGuiHelper.enumCombo(I18n.get("flashback.weather"), modeInput[0], ALL_MODES);

            if (ImGui.button(I18n.get("flashback.add")) || ReplayUI.consumeConfirm()) {
                return new WeatherKeyframe(modeInput[0]);
            }
            ImGui.sameLine();
            if (ImGui.button(I18n.get("gui.cancel")) || ReplayUI.consumeCancel()) {
                ImGui.closeCurrentPopup();
            }
            return null;
        };
    }

}
