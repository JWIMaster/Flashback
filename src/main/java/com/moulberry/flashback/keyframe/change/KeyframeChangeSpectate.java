package com.moulberry.flashback.keyframe.change;

import com.moulberry.flashback.keyframe.handler.KeyframeHandler;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Points the camera at a player's first-person view, or back to the replay player when the target
 * is null.
 */
public record KeyframeChangeSpectate(@Nullable UUID target) implements KeyframeChange {

    @Override
    public void apply(KeyframeHandler keyframeHandler) {
        keyframeHandler.applySpectate(this.target);
    }

    /** A viewpoint change is discrete: no intermediate value exists. */
    @Override
    public KeyframeChange interpolate(KeyframeChange to, double amount) {
        if (!(to instanceof KeyframeChangeSpectate other)) {
            return this;
        }
        return amount < 0.5 ? this : other;
    }

}
