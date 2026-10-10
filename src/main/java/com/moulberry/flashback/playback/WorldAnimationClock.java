package com.moulberry.flashback.playback;

/**
 * Client world-tick animation clock, unaffected by packet corrections between ticks.
 *
 * <p>The animations it drives (the rain/snow columns) are periodic, so their absolute phase is
 * arbitrary: continuity matters, matching the recorded world clock does not. It therefore never
 * rebases after the first sample. Rebasing on seeks would restart the scroll pattern, which is
 * far more noticeable than letting the snow keep drifting through the rewind.</p>
 */
public final class WorldAnimationClock {
    private long tick;
    private boolean initialized;

    public long sample(long gameTime) {
        if (!this.initialized) {
            this.tick = gameTime;
            this.initialized = true;
        }
        return this.tick;
    }

    public void tick(long gameTimeBeforeTick) {
        this.sample(gameTimeBeforeTick);
        this.tick++;
    }

    public void reset() {
        this.initialized = false;
    }
}
