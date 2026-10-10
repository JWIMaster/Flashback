package com.moulberry.flashback.visuals;

import com.moulberry.flashback.action.PositionAndAngle;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Recorded pose curves indexed by their absolute replay-tick interval. */
public final class AccuratePositionTimeline {
    private static final int MAX_INTERVALS = 8;

    private final TreeMap<Integer, List<PositionAndAngle>> intervals = new TreeMap<>();

    /** Replaces an interval, retaining only the eight newest source ticks. */
    public void put(int sourceTick, List<PositionAndAngle> points) {
        if (points == null || points.isEmpty()) {
            intervals.remove(sourceTick);
            return;
        }
        intervals.put(sourceTick, List.copyOf(points));
        while (intervals.size() > MAX_INTERVALS) {
            intervals.pollFirstEntry();
        }
    }

    /**
     * Samples an absolute replay time. Missing/expired intervals hold the previous
     * curve's endpoint, rather than restarting that curve with a wrapped partial tick.
     * Returns null when no poses are available or the requested time is NaN.
     */
    public PositionAndAngle sample(double replayTick) {
        if (intervals.isEmpty() || Double.isNaN(replayTick)) {
            return null;
        }
        if (replayTick < intervals.firstKey()) {
            return intervals.firstEntry().getValue().getFirst();
        }

        Map.Entry<Integer, List<PositionAndAngle>> interval =
            intervals.floorEntry((int) Math.floor(replayTick));
        List<PositionAndAngle> points = interval.getValue();
        double fraction = Math.max(0.0, Math.min(1.0, replayTick - interval.getKey()));
        double index = fraction * (points.size() - 1);
        int lower = (int) Math.floor(index);
        if (lower == points.size() - 1) {
            return points.getLast();
        }

        PositionAndAngle from = points.get(lower);
        PositionAndAngle to = points.get(lower + 1);
        double amount = index - lower;
        return new PositionAndAngle(
            from.x() + (to.x() - from.x()) * amount,
            from.y() + (to.y() - from.y()) * amount,
            from.z() + (to.z() - from.z()) * amount,
            interpolateAngle(from.yaw(), to.yaw(), amount),
            interpolateAngle(from.pitch(), to.pitch(), amount)
        );
    }

    public int latestSourceTick() {
        return intervals.lastKey();
    }

    public int size() {
        return intervals.size();
    }

    private static float interpolateAngle(float from, float to, double amount) {
        return (float) wrapDegrees(from + wrapDegrees((double) to - from) * amount);
    }

    private static double wrapDegrees(double angle) {
        double wrapped = angle % 360.0;
        if (wrapped >= 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }
}
