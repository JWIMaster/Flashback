package com.moulberry.flashback.playback;

import com.moulberry.flashback.action.PositionAndAngle;
import com.moulberry.flashback.visuals.AccuratePositionTimeline;

import java.util.ArrayList;
import java.util.List;

public final class AccuratePositionTimelineTest {
    public static void main(String[] args) {
        monotonicAcrossLocalPartialWraps();
        missingIntervalHoldsEndpoint();
        lateArrivalSamplesCurrentTime();
        batchChoosesCorrectCurve();
        anglesTakeShortestArc();
        duplicatesReplaceAndInputsAreCopied();
        boundedCacheRetainsNewestIntervals();
        emptyAndSinglePointInputsAreSafe();
        System.out.println("All accurate position timeline checks passed");
    }

    private static List<PositionAndAngle> curve(double start, double end) {
        return List.of(pose(start), pose((start + end) / 2), pose(end));
    }

    private static PositionAndAngle pose(double x) {
        return new PositionAndAngle(x, 2 * x, -x, 0, 0);
    }

    private static void monotonicAcrossLocalPartialWraps() {
        AccuratePositionTimeline timeline = new AccuratePositionTimeline();
        for (int tick = 10; tick < 17; tick++) timeline.put(tick, curve(tick, tick + 1));
        double previous = Double.NEGATIVE_INFINITY;
        // The local partial wraps repeatedly; only the absolute replay clock selects a curve.
        for (int frame = 0; frame <= 700; frame++) {
            double time = 10 + frame / 100.0;
            PositionAndAngle sample = timeline.sample(time);
            check(sample.x() >= previous, "local partial wrap cannot rewind position at " + time);
            near(sample.x(), time, "linear curve uses absolute replay time");
            near(sample.y(), 2 * time, "y interpolates linearly");
            near(sample.z(), -time, "z interpolates linearly");
            previous = sample.x();
        }
        near(timeline.sample(9.95).x(), 10, "before-first time clamps to first pose");
    }

    private static void missingIntervalHoldsEndpoint() {
        AccuratePositionTimeline timeline = new AccuratePositionTimeline();
        timeline.put(1, curve(10, 20));
        double beforeWrap = timeline.sample(1.9).x();
        double afterWrap = timeline.sample(2.05).x();
        check(afterWrap >= beforeWrap, "missing interval must not restart the preceding curve");
        near(afterWrap, 20, "missing interval holds prior endpoint");
        near(timeline.sample(100.25).x(), 20, "expired curve holds final endpoint indefinitely");
        timeline.put(4, curve(40, 50));
        near(timeline.sample(3.95).x(), 20, "future data cannot bridge a missing interval");
        near(timeline.sample(4.5).x(), 45, "next available interval resumes its own curve");
    }

    private static void lateArrivalSamplesCurrentTime() {
        AccuratePositionTimeline timeline = new AccuratePositionTimeline();
        timeline.put(1, curve(10, 20));
        near(timeline.sample(2.65).x(), 20, "hold while current data is unavailable");
        timeline.put(2, curve(20, 30));
        near(timeline.sample(2.65).x(), 26.5, "late arrival samples current time, not curve start");
        timeline.put(0, curve(0, 10));
        near(timeline.sample(2.65).x(), 26.5, "older late arrival cannot replace the selected curve");
    }

    private static void batchChoosesCorrectCurve() {
        AccuratePositionTimeline timeline = new AccuratePositionTimeline();
        timeline.put(22, curve(220, 230));
        timeline.put(20, curve(200, 210));
        timeline.put(21, curve(210, 220));
        near(timeline.sample(20.25).x(), 202.5, "batch selects first interval");
        near(timeline.sample(21.5).x(), 215, "batch selects middle interval");
        near(timeline.sample(22.75).x(), 227.5, "batch selects last interval");
        near(timeline.sample(21).x(), 210, "exact boundary selects new interval");
    }

    private static void anglesTakeShortestArc() {
        AccuratePositionTimeline timeline = new AccuratePositionTimeline();
        timeline.put(0, List.of(new PositionAndAngle(0, 0, 0, 179, -179),
            new PositionAndAngle(0, 0, 0, -179, 179)));
        PositionAndAngle middle = timeline.sample(0.5);
        near(middle.yaw(), -180, "179 to -179 passes through wrapped 180, not zero");
        near(middle.pitch(), -180, "pitch also takes shortest arc");
        near(timeline.sample(0.25).yaw(), 179.5, "yaw approaches wrap boundary");
        near(timeline.sample(0.75).yaw(), -179.5, "yaw leaves wrap boundary");
        timeline.put(1, List.of(new PositionAndAngle(0, 0, 0, 350, -350),
            new PositionAndAngle(0, 0, 0, 10, -10)));
        near(timeline.sample(1.5).yaw(), 0, "angles outside canonical range wrap during interpolation");
        near(timeline.sample(1.5).pitch(), 0, "reverse angles wrap during interpolation");
    }

    private static void duplicatesReplaceAndInputsAreCopied() {
        AccuratePositionTimeline timeline = new AccuratePositionTimeline();
        List<PositionAndAngle> mutable = new ArrayList<>(curve(0, 10));
        timeline.put(3, mutable);
        mutable.set(1, pose(1000));
        mutable.clear();
        near(timeline.sample(3.5).x(), 5, "caller mutation cannot alter copied curve");
        timeline.put(3, curve(20, 40));
        check(timeline.size() == 1, "duplicate source tick replaces rather than appends");
        near(timeline.sample(3.5).x(), 30, "replacement is sampled immediately");
    }

    private static void boundedCacheRetainsNewestIntervals() {
        AccuratePositionTimeline timeline = new AccuratePositionTimeline();
        for (int tick = 0; tick < 12; tick++) timeline.put(tick, curve(tick, tick + 1));
        check(timeline.size() == 8, "cache is bounded to eight intervals");
        near(timeline.sample(-10).x(), 4, "oldest source ticks were evicted");
        timeline.put(-1, curve(-1, 0));
        check(timeline.size() == 8, "late old insertion cannot grow cache");
        near(timeline.sample(-10).x(), 4, "late old insertion evicts itself, not newest data");
        timeline.put(8, curve(80, 90));
        check(timeline.size() == 8, "full-cache duplicate does not evict another interval");
        near(timeline.sample(8.5).x(), 85, "full-cache replacement is retained");
        timeline.put(20, curve(20, 21));
        near(timeline.sample(-10).x(), 5, "new insertion trims earliest retained interval");
        near(timeline.sample(20.5).x(), 20.5, "newest curve remains available");
    }

    private static void emptyAndSinglePointInputsAreSafe() {
        AccuratePositionTimeline timeline = new AccuratePositionTimeline();
        check(timeline.sample(0) == null, "empty timeline has no pose");
        timeline.put(0, List.of());
        timeline.put(1, null);
        check(timeline.size() == 0, "empty/null input cannot occupy an interval");
        timeline.put(2, List.of(pose(7)));
        near(timeline.sample(1).x(), 7, "single pose clamps before its interval");
        near(timeline.sample(2.5).x(), 7, "single pose is constant inside its interval");
        near(timeline.sample(100).x(), 7, "single pose holds after its interval");
        check(timeline.sample(Double.NaN) == null, "NaN request is rejected safely");
        near(timeline.sample(Double.NEGATIVE_INFINITY).x(), 7, "negative infinity clamps first pose");
        near(timeline.sample(Double.POSITIVE_INFINITY).x(), 7, "positive infinity holds final pose");
        timeline.put(2, List.of());
        check(timeline.size() == 0 && timeline.sample(2.5) == null,
            "empty replacement drops the interval safely");
        timeline.put(Integer.MIN_VALUE, curve(0, 1));
        timeline.put(Integer.MAX_VALUE, curve(10, 11));
        near(timeline.sample((double) Integer.MIN_VALUE + 0.5).x(), 0.5, "minimum tick is sampleable");
        near(timeline.sample((double) Integer.MAX_VALUE + 0.5).x(), 10.5,
            "maximum tick interval does not overflow its endpoint");
    }

    private static void near(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < 1.0e-6,
            message + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
