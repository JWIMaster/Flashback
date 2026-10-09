package com.moulberry.flashback.keyframe;

import com.moulberry.flashback.combo_options.WeatherOverride;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraPositionOrbit;
import com.moulberry.flashback.keyframe.change.KeyframeChangeWeather;
import com.moulberry.flashback.keyframe.change.OrbitFollowDelay;
import com.moulberry.flashback.keyframe.handler.KeyframeHandler;
import com.moulberry.flashback.keyframe.impl.CameraOrbitKeyframe;
import com.moulberry.flashback.keyframe.impl.WeatherKeyframe;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import org.joml.Vector3d;
import java.util.UUID;

/**
 * The orbit camera's two meanings: around a fixed point, or around the subject it follows.
 *
 * <p>"Centred on the player" is the difference between a camera that turns around someone and one
 * that turns around wherever they happened to be standing when the keyframe was made, so it is worth
 * pinning down mechanically.
 */
public class OrbitTest {

    private static int failures = 0;

    /** Records what the camera was told to do, and pretends to be following a subject. */
    private static final class Recorder implements KeyframeHandler {
        Vector3d followed;
        Vector3d position;
        double yaw;
        double pitch;

        @Override
        public boolean supportsKeyframeChange(Class<? extends KeyframeChange> clazz) {
            return clazz == KeyframeChangeCameraPositionOrbit.class;
        }

        Vector3d subject;

        @Override
        public Vector3d followedPosition() {
            return this.followed;
        }

        @Override
        public Vector3d subjectPosition() {
            return this.subject;
        }

        @Override
        public void applyCameraPosition(Vector3d position, double yaw, double pitch, double roll) {
            this.position = position;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    public static void main(String[] args) {
        Vector3d fixedPoint = new Vector3d(0, 64, 0);
        Vector3d subject = new Vector3d(100, 70, -50);

        Recorder recorder = new Recorder();
        recorder.followed = subject;

        // Distance 10 behind, no yaw or pitch, means directly before the centre on the z axis.
        CameraOrbitKeyframe following = new CameraOrbitKeyframe(fixedPoint, 10f, 0f, 0f,
            InterpolationType.getDefault(), true);
        following.createChange().apply(recorder);
        check("following the subject orbits the subject, not the stored point",
            recorder.position.z == subject.z - 10 && Math.abs(recorder.position.x - subject.x) < 0.001);
        check("following ignores the stored centre entirely", recorder.position.z != fixedPoint.z - 10);

        // Yaw turns the camera around the subject, which is the control the user asked for: it must
        // keep the distance and swing to the side, whatever the sign convention happens to be.
        CameraOrbitKeyframe turned = new CameraOrbitKeyframe(fixedPoint, 10f, 90f, 0f,
            InterpolationType.getDefault(), true);
        turned.createChange().apply(recorder);
        double turnedDistance = Math.sqrt(recorder.position.distanceSquared(subject));
        check("yaw keeps the camera at its distance from the subject",
            Math.abs(turnedDistance - 10) < 0.001);
        check("yaw swings the camera to the side of the subject",
            Math.abs(Math.abs(recorder.position.x - subject.x) - 10) < 0.001
                && Math.abs(recorder.position.z - subject.z) < 0.001);

        // Without the flag it is the old behaviour, which is what existing projects must keep.
        CameraOrbitKeyframe anchored = new CameraOrbitKeyframe(fixedPoint, 10f, 0f, 0f,
            InterpolationType.getDefault(), false);
        anchored.createChange().apply(recorder);
        check("a fixed orbit still orbits its stored point",
            Math.abs(recorder.position.z - (fixedPoint.z - 10)) < 0.001
                && Math.abs(recorder.position.x - fixedPoint.x) < 0.001);

        // The local player is the moving camera, not the subject. Centring on it every
        // frame sent the camera backwards by the distance on every update.
        Recorder playerOnly = new Recorder();
        playerOnly.subject = subject;
        CameraOrbitKeyframe onPlayer = new CameraOrbitKeyframe(fixedPoint, 10f, 0f, 0f,
            InterpolationType.getDefault(), true);
        onPlayer.createChange().apply(playerOnly);
        check("without a selected or tracked subject, orbit does not chase its own camera",
            Math.abs(playerOnly.position.z - (fixedPoint.z - 10)) < 0.001
                && Math.abs(playerOnly.position.x - fixedPoint.x) < 0.001);

        // And when even the player cannot be found, it must keep the stored point rather than
        // collapsing to the world origin.
        Recorder nothingKnown = new Recorder();
        CameraOrbitKeyframe orphan = new CameraOrbitKeyframe(new Vector3d(12, 70, -3), 10f, 0f, 0f,
            InterpolationType.getDefault(), true);
        orphan.createChange().apply(nothingKnown);
        check("with no subject at all the stored point is kept",
            Math.abs(nothingKnown.position.z - (-13)) < 0.001
                && Math.abs(nothingKnown.position.x - 12) < 0.001);

        // Distance is a plain control in both modes.
        CameraOrbitKeyframe far = new CameraOrbitKeyframe(fixedPoint, 25f, 0f, 0f,
            InterpolationType.getDefault(), false);
        far.createChange().apply(recorder);
        check("distance sets how far out the camera sits",
            Math.abs(recorder.position.z - (fixedPoint.z - 25)) < 0.001);

        // Copying and interpolating must carry the choice, or the camera would silently anchor.
        CameraOrbitKeyframe copy = (CameraOrbitKeyframe) following.copy();
        check("copying keeps the choice", copy.centreOnTarget);
        UUID playerId = UUID.randomUUID();
        CameraOrbitKeyframe selected = new CameraOrbitKeyframe(fixedPoint, 10f, 0f, 0f,
            InterpolationType.getDefault(), true, playerId);
        check("copying keeps the selected player", playerId.equals(((CameraOrbitKeyframe) selected.copy()).target));
        check("applying keeps the selected player", playerId.equals(((KeyframeChangeCameraPositionOrbit) selected.createChange()).target()));
        KeyframeChange interpolated = following.createChange().interpolate(anchored.createChange(), 0.5);
        check("interpolating keeps the choice", ((KeyframeChangeCameraPositionOrbit) interpolated).centreOnTarget());

        // Weather is a state, not a number. A span from clear to a thunderstorm must step at its
        // midpoint rather than pass through states that were never chosen.
        KeyframeChange clear = new WeatherKeyframe(WeatherOverride.CLEAR).createChange();
        KeyframeChange storm = new WeatherKeyframe(WeatherOverride.THUNDERING).createChange();
        check("weather before the midpoint is still the left state",
            ((KeyframeChangeWeather) clear.interpolate(storm, 0.49)).mode() == WeatherOverride.CLEAR);
        check("weather after the midpoint is the right state",
            ((KeyframeChangeWeather) clear.interpolate(storm, 0.51)).mode() == WeatherOverride.THUNDERING);
        check("weather at the midpoint has already stepped",
            ((KeyframeChangeWeather) clear.interpolate(storm, 0.5)).mode() == WeatherOverride.THUNDERING);
        KeyframeChange somethingElse = new KeyframeChange() {
            @Override
            public void apply(KeyframeHandler handler) {
            }

            @Override
            public KeyframeChange interpolate(KeyframeChange to, double amount) {
                return this;
            }
        };
        check("interpolating to a different kind of change keeps this one",
            clear.interpolate(somethingElse, 0.9) == clear);
        check("a weather keyframe keeps its state when copied",
            ((WeatherKeyframe) new WeatherKeyframe(WeatherOverride.SNOWING).copy()).mode == WeatherOverride.SNOWING);

        lagChecks();

        if (failures > 0) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("All orbit tests passed");
    }

    /**
     * The lag behind option: the camera aims where its subject was, a set number of ticks ago.
     *
     * <p>Movement here is one block per tick on the x axis, so a sample's x is the tick it describes.
     */
    private static void lagChecks() {
        UUID subject = UUID.randomUUID();
        OrbitFollowDelay.clear();
        for (int tick = 0; tick <= 40; tick++) {
            OrbitFollowDelay.record(subject, tick, new Vector3d(tick, 64, 0));
        }

        Vector3d delayed = OrbitFollowDelay.sample(subject, 40 - 10);
        check("lagging aims ten ticks back", delayed != null && Math.abs(delayed.x - 30) < 0.001);

        Vector3d between = OrbitFollowDelay.sample(subject, 30.5f);
        check("lagging interpolates between recorded ticks", between != null && Math.abs(between.x - 30.5) < 0.001);

        Vector3d ahead = OrbitFollowDelay.sample(subject, 100);
        check("lagging cannot aim into the future", ahead != null && Math.abs(ahead.x - 40) < 0.001);

        // At the very start there is no history to lag into, so the oldest known position is used and
        // the delay eases in rather than the camera snapping.
        OrbitFollowDelay.clear();
        OrbitFollowDelay.record(subject, 500, new Vector3d(5, 64, 0));
        Vector3d firstFrame = OrbitFollowDelay.sample(subject, 500 - 10);
        check("lagging starts from the oldest known position", firstFrame != null && Math.abs(firstFrame.x - 5) < 0.001);

        // Changing subject, or seeking, must not drag the old position along.
        UUID other = UUID.randomUUID();
        OrbitFollowDelay.record(other, 501, new Vector3d(9, 70, 0));
        Vector3d afterSubjectChange = OrbitFollowDelay.sample(other, 501);
        check("a new subject forgets the old one", afterSubjectChange != null && Math.abs(afterSubjectChange.x - 9) < 0.001);
        check("the old subject has no history left", OrbitFollowDelay.sample(subject, 500) == null);

        OrbitFollowDelay.clear();
        OrbitFollowDelay.record(other, 600, new Vector3d(1, 64, 0));
        OrbitFollowDelay.record(other, 700, new Vector3d(2, 64, 0));
        Vector3d afterSeek = OrbitFollowDelay.sample(other, 700 - 10);
        check("a seek drops history from before it", afterSeek != null && Math.abs(afterSeek.x - 2) < 0.001);
        OrbitFollowDelay.clear();
    }

    private static void check(String what, boolean condition) {
        if (condition) {
            System.out.println("ok:   " + what);
        } else {
            failures += 1;
            System.out.println("FAIL: " + what);
        }
    }
}
