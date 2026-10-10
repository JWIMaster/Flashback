package com.moulberry.flashback.playback;

import java.util.ArrayList;
import java.util.List;

/** Bounded, in-memory frame capture. Only enabled alongside the existing diagnostic GUI recorder. */
public final class PlaybackTimingTrace {
    private static final long DURATION_NANOS = 15_000_000_000L;
    private static final int MAX_SAMPLES = 8192;
    private final List<Sample> samples = new ArrayList<>();
    private long startNanos;
    private boolean finished;

    public record Sample(long nanos, long clientTick, long gameTime, float partialTick,
                         double replayTick, boolean paused, boolean frozen, boolean runsNormally,
                         int entityId, int entityTick, double x, double y, double z,
                         double oldX, double oldY, double oldZ, int frameLimit, String throttleReason,
                         long animationTime, double cameraX, double cameraY, double cameraZ,
                         float cameraYaw, float cameraPitch, float cameraPartial, int cameraEntityId, boolean accurateCameraData) {}

    /** Returns true once, when ready to serialize on a background executor. */
    public boolean add(Sample sample) {
        if (this.finished) return false;
        if (this.samples.isEmpty()) this.startNanos = sample.nanos();
        this.samples.add(sample);
        if (sample.nanos() - this.startNanos < DURATION_NANOS && this.samples.size() < MAX_SAMPLES) {
            return false;
        }
        return this.finish();
    }

    /** Flush a shorter playback segment on pause/disconnect; an empty capture creates no file. */
    public boolean finish() {
        if (this.finished || this.samples.isEmpty()) return false;
        this.finished = true;
        return true;
    }

    public boolean finished() {
        return this.finished;
    }

    /** Call only after add returns true; no further samples can mutate this capture. */
    public String toCsv() {
        if (!this.finished) throw new IllegalStateException("Timing capture is still running");
        StringBuilder csv = new StringBuilder("elapsed_nanos,client_tick,game_time,partial_tick,replay_tick,paused,frozen,runs_normally,entity_id,entity_tick,x,y,z,old_x,old_y,old_z,frame_limit,throttle_reason,animation_time,camera_x,camera_y,camera_z,camera_yaw,camera_pitch,camera_partial,camera_entity_id,accurate_camera_data\n");
        for (Sample s : this.samples) {
            csv.append(s.nanos() - this.startNanos).append(',').append(s.clientTick()).append(',')
                .append(s.gameTime()).append(',').append(s.partialTick()).append(',').append(s.replayTick()).append(',')
                .append(s.paused()).append(',').append(s.frozen()).append(',').append(s.runsNormally()).append(',')
                .append(s.entityId()).append(',').append(s.entityTick()).append(',')
                .append(s.x()).append(',').append(s.y()).append(',').append(s.z()).append(',')
                .append(s.oldX()).append(',').append(s.oldY()).append(',').append(s.oldZ()).append(',')
                .append(s.frameLimit()).append(',').append(s.throttleReason()).append(',')
                .append(s.animationTime()).append(',').append(s.cameraX()).append(',').append(s.cameraY()).append(',')
                .append(s.cameraZ()).append(',').append(s.cameraYaw()).append(',').append(s.cameraPitch()).append(',')
                .append(s.cameraPartial()).append(',').append(s.cameraEntityId()).append(',').append(s.accurateCameraData()).append('\n');
        }
        this.samples.clear();
        return csv.toString();
    }
}
