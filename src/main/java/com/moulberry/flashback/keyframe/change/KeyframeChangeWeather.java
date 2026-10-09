package com.moulberry.flashback.keyframe.change;

import com.moulberry.flashback.combo_options.WeatherOverride;
import com.moulberry.flashback.keyframe.handler.KeyframeHandler;

/**
 * Sets the weather the editor draws, as one of the discrete {@link WeatherOverride} states.
 *
 * <p>Weather is chosen, not measured: there is no half-raining, so this never blends. Interpolating
 * from clear to a thunderstorm steps at the midpoint exactly as a spectate change does, which is what
 * makes a weather cut a cut rather than a smear through states that do not exist.
 */
public record KeyframeChangeWeather(WeatherOverride mode) implements KeyframeChange {

    @Override
    public void apply(KeyframeHandler keyframeHandler) {
        keyframeHandler.applyWeather(this.mode);
    }

    /** A weather state is discrete: no intermediate value exists, so one side is picked. */
    @Override
    public KeyframeChange interpolate(KeyframeChange to, double amount) {
        if (!(to instanceof KeyframeChangeWeather other)) {
            return this;
        }
        return amount < 0.5 ? this : other;
    }

}
