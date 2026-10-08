package com.moulberry.flashback.keyframe.change;

import com.moulberry.flashback.keyframe.handler.KeyframeHandler;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Makes the viewpoint follow a player's first-person view, or return to the replay's own viewpoint
 * when the target is null.
 *
 * <p>Which player is watched is discrete, so this never interpolates: cutting from one player to
 * another is a cut, not a blend that passes through an undefined player.
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
