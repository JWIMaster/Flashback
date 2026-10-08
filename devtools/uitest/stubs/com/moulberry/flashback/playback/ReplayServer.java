package com.moulberry.flashback.playback;

import com.moulberry.flashback.record.FlashbackMeta;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A replay the harness can steer: the editor only ever asks a replay how long it is and where the
 * playhead is, so that is all this provides.
 */
public class ReplayServer {

    public volatile int jumpToTick = -1;
    public volatile boolean replayPaused = true;
    public final AtomicBoolean forceApplyKeyframes = new AtomicBoolean(false);

    private final FlashbackMeta metadata;
    private int replayTick;
    private int totalTicks = 400;

    public ReplayServer(FlashbackMeta metadata) {
        this.metadata = metadata;
        this.metadata.totalTicks = this.totalTicks;
    }

    public FlashbackMeta getMetadata() {
        return this.metadata;
    }

    public int getReplayTick() {
        return this.replayTick;
    }

    public int getTotalReplayTicks() {
        return this.totalTicks;
    }

    public void setReplayTick(int tick) {
        this.replayTick = Math.max(0, Math.min(this.totalTicks, tick));
    }

    public void setTotalTicks(int ticks) {
        this.totalTicks = ticks;
        this.metadata.totalTicks = ticks;
    }

    public void goToReplayTick(int tick) {
        this.setReplayTick(tick);
    }

    public float getDesiredTickRate(boolean replay) {
        return 20f;
    }

    public void setDesiredTickRate(float rate, boolean replay) {
    }
}
