package com.moulberry.flashback.state;

import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraPosition;
import com.moulberry.flashback.keyframe.handler.KeyframeHandler;
import com.moulberry.flashback.keyframe.impl.CameraKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraSwitchKeyframe;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import org.joml.Vector3d;

/**
 * Exercises which camera animates the view. This is the behaviour that must stay order-independent:
 * with a switch in force, only the selected camera's tracks may apply.
 */
public class EvaluationTest {

    private static int failures = 0;

    public static void main(String[] args) {
        withSwitchOnlyActiveCameraApplies();
        withoutSwitchLegacyBehaviourRemains();
        disabledSwitchFallsBack();

        if (failures > 0) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("All evaluation tests passed");
    }

    private static void check(String what, boolean condition) {
        if (!condition) {
            failures += 1;
            System.out.println("FAIL: " + what);
        } else {
            System.out.println("ok:   " + what);
        }
    }

    private static class RecordingHandler implements KeyframeHandler {
        Vector3d position;

        @Override
        public boolean supportsKeyframeChange(Class<? extends KeyframeChange> clazz) {
            return clazz == KeyframeChangeCameraPosition.class;
        }

        @Override
        public boolean alwaysApplyLastKeyframe() {
            return true;
        }

        @Override
        public void applyCameraPosition(Vector3d position, double yaw, double pitch, double roll) {
            this.position = position;
        }
    }

    private static KeyframeTrack positionTrack(double x) {
        KeyframeTrack track = new KeyframeTrack(CameraKeyframeType.INSTANCE);
        track.keyframesByTick.put(0, new CameraKeyframe(new Vector3d(x, 0, 0), 0, 0, 0));
        return track;
    }

    /** Builds a scene with camera B listed first, to prove order does not decide the viewpoint. */
    private static EditorState twoCameraState(boolean withSwitch) {
        EditorState state = new EditorState();
        long stamp = state.acquireWrite();
        EditorScene scene;
        try {
            scene = state.getCurrentScene(stamp);
        } finally {
            state.release(stamp);
        }

        EditorCamera cameraA = new EditorCamera("A", EditorCamera.Kind.FREE);
        EditorCamera cameraB = new EditorCamera("B", EditorCamera.Kind.FREE);
        scene.cameras.add(cameraA);
        scene.cameras.add(cameraB);

        KeyframeTrack trackB = positionTrack(2);
        trackB.cameraId = cameraB.id;
        KeyframeTrack trackA = positionTrack(1);
        trackA.cameraId = cameraA.id;

        // B's row is deliberately before A's.
        scene.keyframeTracks.add(trackB);
        scene.keyframeTracks.add(trackA);

        if (withSwitch) {
            KeyframeTrack switchTrack = scene.findOrCreateCameraSwitchTrack();
            switchTrack.keyframesByTick.put(0, new CameraSwitchKeyframe(cameraA.id));
            switchTrack.keyframesByTick.put(50, new CameraSwitchKeyframe(cameraB.id));
        }
        return state;
    }

    private static void withSwitchOnlyActiveCameraApplies() {
        EditorState state = twoCameraState(true);

        RecordingHandler handler = new RecordingHandler();
        state.applyKeyframes(handler, 10);
        check("before the cut, camera A animates the view", handler.position != null && handler.position.x == 1.0);

        handler = new RecordingHandler();
        state.applyKeyframes(handler, 60);
        check("after the cut, camera B animates the view", handler.position != null && handler.position.x == 2.0);

        handler = new RecordingHandler();
        state.applyKeyframes(handler, 50);
        check("at the cut tick the new camera is already in force", handler.position != null && handler.position.x == 2.0);
    }

    private static void withoutSwitchLegacyBehaviourRemains() {
        EditorState state = twoCameraState(false);
        RecordingHandler handler = new RecordingHandler();
        state.applyKeyframes(handler, 10);
        check("no switch means camera tracks still apply", handler.position != null);
    }

    private static void disabledSwitchFallsBack() {
        EditorState state = twoCameraState(true);
        long stamp = state.acquireWrite();
        try {
            state.getCurrentScene(stamp).cameraSwitchTrack().enabled = false;
        } finally {
            state.release(stamp);
        }
        RecordingHandler handler = new RecordingHandler();
        state.applyKeyframes(handler, 60);
        check("a disabled switch lane stops deciding the viewpoint", handler.position != null && handler.position.x == 2.0);
    }
}
