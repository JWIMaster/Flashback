package com.moulberry.flashback.keyframe;

import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraPositionOrbit;
import com.moulberry.flashback.keyframe.handler.KeyframeHandler;
import com.moulberry.flashback.keyframe.impl.CameraOrbitKeyframe;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import org.joml.Vector3d;

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

        // With nothing tracked, the subject is the player: this is the case the user hit, and it is
        // what makes an orbit camera turn around someone who walks about.
        Recorder playerOnly = new Recorder();
        playerOnly.subject = subject;
        CameraOrbitKeyframe onPlayer = new CameraOrbitKeyframe(fixedPoint, 10f, 0f, 0f,
            InterpolationType.getDefault(), true);
        onPlayer.createChange().apply(playerOnly);
        check("with nothing tracked, the orbit centres on the player",
            Math.abs(playerOnly.position.z - (subject.z - 10)) < 0.001
                && Math.abs(playerOnly.position.x - subject.x) < 0.001);

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
        KeyframeChange interpolated = following.createChange().interpolate(anchored.createChange(), 0.5);
        check("interpolating keeps the choice", ((KeyframeChangeCameraPositionOrbit) interpolated).centreOnTarget());

        if (failures > 0) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("All orbit tests passed");
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
