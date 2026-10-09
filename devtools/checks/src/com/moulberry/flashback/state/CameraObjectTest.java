package com.moulberry.flashback.state;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeRegistry;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraFov;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraPosition;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraPositionOnly;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraRotationOnly;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraShake;
import com.moulberry.flashback.keyframe.change.KeyframeChangeFov;
import com.moulberry.flashback.keyframe.handler.KeyframeHandler;
import com.moulberry.flashback.keyframe.impl.CameraFovKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraPositionKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraRotationKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraShakeKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraSwitchKeyframe;
import com.moulberry.flashback.keyframe.impl.FOVKeyframe;
import com.moulberry.flashback.keyframe.types.CameraFovKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraPositionKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraRotationKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraShakeKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraSwitchKeyframeType;
import com.moulberry.flashback.keyframe.types.FOVKeyframeType;
import com.moulberry.flashback.visuals.ReplayVisuals;
import org.joml.Vector3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Cameras as objects with independently animatable properties.
 *
 * <p>The point of this suite is that position and rotation are genuinely separate tracks: applying
 * one must not disturb the other, both must be able to run in the same frame, and a camera's own
 * stored values must fill in exactly the properties nothing animates.
 */
public class CameraObjectTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        // In game these happen during mod initialisation; doing them here exercises the real
        // deserialisation path for the new keyframe types.
        KeyframeRegistry.register(CameraKeyframeType.INSTANCE);
        KeyframeRegistry.register(CameraPositionKeyframeType.INSTANCE);
        KeyframeRegistry.register(CameraRotationKeyframeType.INSTANCE);
        KeyframeRegistry.register(CameraFovKeyframeType.INSTANCE);
        KeyframeRegistry.register(CameraSwitchKeyframeType.INSTANCE);
        KeyframeRegistry.register(FOVKeyframeType.INSTANCE);
        KeyframeRegistry.register(CameraShakeKeyframeType.INSTANCE);

        positionAndRotationInterpolateIndependently();
        applyingOneChangeLeavesTheOtherPropertyAlone();
        granularTracksMergeThroughEvaluation();
        staticValuesFillOnlyUncoveredProperties();
        evaluationMatchesApplyPassBetweenAndAroundKeys();
        evaluationKeepsPropertiesIndependent();
        editingOnePropertyLeavesTheOthersUntouched();
        aKeyedDragPersistsThroughTheRelease();
        anUnkeyedDragPersistsInTheStoredValue();
        aWholeCameraKeyframeKeepsItsShapeWhenOnePropertyIsEdited();
        aCameraWithNoTracksEvaluatesToItsStoredValues();
        theInspectorLockDisciplineDoesNotBreakTheTimeline();
        aDegenerateFovOverrideIsRepairedOnLoad();
        newKeyframesRoundTripThroughJson();
        oldProjectsLoadAndEvaluate();
        emptyAndFutureTracksUseStoredValues();
        inactiveCameraStillEvaluatesItsOwnTracks();
        overlappingChangesFollowApplicationOrder();
        cameraEffectsDoNotReplaceSceneDefaults();
        allPersistedFovSourcesAreRepaired();
        suppliedSceneStampsDoNotReenterTheLock();

        if (failures > 0) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("All camera object tests passed");
    }

    private static void check(String what, boolean condition) {
        if (condition) {
            System.out.println("ok:   " + what);
        } else {
            failures += 1;
            System.out.println("FAIL: " + what);
        }
    }

    /**
     * A stand-in for the client camera: it keeps a position and an angle, and merges each granular
     * change the way {@code MinecraftKeyframeHandler} does - by reading the value it is not setting.
     */
    private static class RecordingHandler implements KeyframeHandler {
        Vector3d position = new Vector3d();
        double yaw;
        double pitch;
        double roll;
        Float fov;
        float[] shake;

        int positionApplies = 0;
        int rotationApplies = 0;

        @Override
        public boolean supportsKeyframeChange(Class<? extends KeyframeChange> clazz) {
            return clazz == KeyframeChangeCameraPosition.class
                || clazz == KeyframeChangeCameraPositionOnly.class
                || clazz == KeyframeChangeCameraRotationOnly.class
                || clazz == KeyframeChangeFov.class
                || clazz == KeyframeChangeCameraFov.class
                || clazz == KeyframeChangeCameraShake.class;
        }

        @Override
        public boolean alwaysApplyLastKeyframe() {
            return true;
        }

        @Override
        public void applyCameraPosition(Vector3d position, double yaw, double pitch, double roll) {
            this.applyCameraPositionOnly(position);
            this.applyCameraRotationOnly(yaw, pitch, roll);
        }

        @Override
        public void applyCameraPositionOnly(Vector3d position) {
            // A position change reads nothing and writes only the position: the angles are untouched.
            this.position = position;
            this.positionApplies += 1;
        }

        @Override
        public void applyCameraRotationOnly(double yaw, double pitch, double roll) {
            // The counterpart: only the angles move, the position is untouched.
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            this.rotationApplies += 1;
        }

        @Override
        public void applyFov(float fov) {
            this.fov = fov;
        }

        @Override
        public void applyCameraShake(float frequencyX, float amplitudeX, float frequencyY, float amplitudeY) {
            this.shake = new float[]{frequencyX, amplitudeX, frequencyY, amplitudeY};
        }
    }

    private static EditorScene sceneOf(EditorState state) {
        long stamp = state.acquireWrite();
        try {
            return state.getCurrentScene(stamp);
        } finally {
            state.release(stamp);
        }
    }

    /** A scene whose switch lane selects one camera from tick 0, so there is an active camera. */
    private static EditorState stateWithActiveCamera(EditorCamera camera) {
        EditorState state = new EditorState();
        EditorScene scene = sceneOf(state);
        scene.cameras.add(camera);
        KeyframeTrack switchTrack = scene.findOrCreateCameraSwitchTrack();
        switchTrack.keyframesByTick.put(0, new CameraSwitchKeyframe(camera.id));
        return state;
    }

    private static void positionAndRotationInterpolateIndependently() {
        KeyframeChangeCameraPositionOnly from = new KeyframeChangeCameraPositionOnly(new Vector3d(0, 0, 0));
        KeyframeChangeCameraPositionOnly to = new KeyframeChangeCameraPositionOnly(new Vector3d(10, 20, 30));
        KeyframeChangeCameraPositionOnly middle = (KeyframeChangeCameraPositionOnly) from.interpolate(to, 0.5);
        check("position interpolates between its two keyframes",
            middle.position().equals(new Vector3d(5, 10, 15), 0.0001));

        KeyframeChangeCameraRotationOnly turnFrom = new KeyframeChangeCameraRotationOnly(0, 0, 0);
        KeyframeChangeCameraRotationOnly turnTo = new KeyframeChangeCameraRotationOnly(90, 45, 10);
        KeyframeChangeCameraRotationOnly turnMiddle = (KeyframeChangeCameraRotationOnly) turnFrom.interpolate(turnTo, 0.5);
        check("rotation interpolates between its two keyframes",
            turnMiddle.yaw() == 45 && turnMiddle.pitch() == 22.5 && turnMiddle.roll() == 5);

        // The duplicate-change guard in EditorState keys off the change class, so these two being
        // different classes is exactly what lets both tracks apply on the same frame.
        check("the position and rotation tracks have different change types",
            CameraPositionKeyframeType.INSTANCE.keyframeChangeType() != CameraRotationKeyframeType.INSTANCE.keyframeChangeType());
        check("a position keyframe is not a rotation keyframe",
            new CameraPositionKeyframe(new Vector3d()).keyframeType() != new CameraRotationKeyframe(0, 0, 0).keyframeType());
    }

    private static void applyingOneChangeLeavesTheOtherPropertyAlone() {
        RecordingHandler handler = new RecordingHandler();
        handler.position = new Vector3d(1, 2, 3);
        handler.yaw = 10;
        handler.pitch = 20;
        handler.roll = 30;

        new KeyframeChangeCameraPositionOnly(new Vector3d(4, 5, 6)).apply(handler);
        check("a position change moves the camera", handler.position.equals(new Vector3d(4, 5, 6), 0.0001));
        check("a position change leaves yaw, pitch and roll exactly as they were",
            handler.yaw == 10 && handler.pitch == 20 && handler.roll == 30);

        new KeyframeChangeCameraRotationOnly(90, -10, 15).apply(handler);
        check("a rotation change turns the camera", handler.yaw == 90 && handler.pitch == -10 && handler.roll == 15);
        check("a rotation change leaves the position exactly where it was",
            handler.position.equals(new Vector3d(4, 5, 6), 0.0001));

        // Interpolated values must behave the same way as the keyframes they came from.
        KeyframeChangeCameraPositionOnly interpolatedPosition =
            (KeyframeChangeCameraPositionOnly) new KeyframeChangeCameraPositionOnly(new Vector3d(0, 0, 0))
                .interpolate(new KeyframeChangeCameraPositionOnly(new Vector3d(8, 0, 0)), 0.5);
        interpolatedPosition.apply(handler);
        check("an interpolated position change still leaves the rotation alone",
            handler.yaw == 90 && handler.roll == 15);

        KeyframeChangeCameraRotationOnly interpolatedRotation =
            (KeyframeChangeCameraRotationOnly) new KeyframeChangeCameraRotationOnly(90, -10, 15)
                .interpolate(new KeyframeChangeCameraRotationOnly(0, 0, 0), 0.5);
        interpolatedRotation.apply(handler);
        check("an interpolated rotation change still leaves the position alone",
            handler.position.equals(new Vector3d(4, 0, 0), 0.0001));
    }

    /**
     * End to end through {@link EditorState#applyKeyframes}: both granular tracks apply on one frame,
     * and the order the rows happen to be in does not change the resulting view.
     */
    private static void granularTracksMergeThroughEvaluation() {
        EditorCamera camera = new EditorCamera(null, EditorCamera.Kind.FREE);

        EditorState positionFirst = stateWithActiveCamera(camera);
        EditorScene scene = sceneOf(positionFirst);
        KeyframeTrack positionTrack = new KeyframeTrack(CameraPositionKeyframeType.INSTANCE);
        positionTrack.cameraId = camera.id;
        positionTrack.keyframesByTick.put(0, new CameraPositionKeyframe(new Vector3d(12, 64, -8)));
        KeyframeTrack rotationTrack = new KeyframeTrack(CameraRotationKeyframeType.INSTANCE);
        rotationTrack.cameraId = camera.id;
        rotationTrack.keyframesByTick.put(0, new CameraRotationKeyframe(35, 12, 4));
        scene.keyframeTracks.add(positionTrack);
        scene.keyframeTracks.add(rotationTrack);

        RecordingHandler handler = new RecordingHandler();
        positionFirst.applyKeyframes(handler, 0);
        check("both granular tracks apply on the same frame",
            handler.position.equals(new Vector3d(12, 64, -8), 0.0001)
                && handler.yaw == 35 && handler.pitch == 12 && handler.roll == 4);

        // The same two tracks the other way round in the row list.
        EditorState rotationFirst = stateWithActiveCamera(camera);
        scene = sceneOf(rotationFirst);
        KeyframeTrack rotationTrackFirst = new KeyframeTrack(CameraRotationKeyframeType.INSTANCE);
        rotationTrackFirst.cameraId = camera.id;
        rotationTrackFirst.keyframesByTick.put(0, new CameraRotationKeyframe(35, 12, 4));
        KeyframeTrack positionTrackSecond = new KeyframeTrack(CameraPositionKeyframeType.INSTANCE);
        positionTrackSecond.cameraId = camera.id;
        positionTrackSecond.keyframesByTick.put(0, new CameraPositionKeyframe(new Vector3d(12, 64, -8)));
        scene.keyframeTracks.add(rotationTrackFirst);
        scene.keyframeTracks.add(positionTrackSecond);

        RecordingHandler reversed = new RecordingHandler();
        rotationFirst.applyKeyframes(reversed, 0);
        check("the two tracks merge to the same view whichever order they are evaluated in",
            reversed.position.equals(handler.position, 0.0001)
                && reversed.yaw == handler.yaw && reversed.pitch == handler.pitch && reversed.roll == handler.roll);

        // An interpolated frame between two keyframes: the position moves without the angles moving,
        // and the angles turn without the position moving.
        KeyframeTrack between = new KeyframeTrack(CameraPositionKeyframeType.INSTANCE);
        between.cameraId = camera.id;
        between.keyframesByTick.put(0, new CameraPositionKeyframe(new Vector3d(0, 64, 0)));
        between.keyframesByTick.put(10, new CameraPositionKeyframe(new Vector3d(10, 64, 0)));
        check("a position track interpolates a fresh position change",
            between.createKeyframeChange(5, null) instanceof KeyframeChangeCameraPositionOnly);
    }

    /**
     * The static-value rule: a camera's own values are applied only for the properties no enabled
     * track that applies to it animates.
     */
    private static void staticValuesFillOnlyUncoveredProperties() {
        EditorCamera camera = new EditorCamera(null, EditorCamera.Kind.FREE);
        camera.x = 10;
        camera.y = 64;
        camera.z = -5;
        camera.yaw = 90;
        camera.pitch = 12;
        camera.roll = 3;
        camera.fov = 95;
        camera.overrideCameraShake = true;
        camera.cameraShakeXFrequency = 2.0f;
        camera.cameraShakeXAmplitude = 0.5f;
        camera.cameraShakeYFrequency = 3.0f;
        camera.cameraShakeYAmplitude = 0.25f;

        EditorState state = stateWithActiveCamera(camera);
        EditorScene scene = sceneOf(state);

        RecordingHandler handler = new RecordingHandler();
        state.applyKeyframes(handler, 0);
        check("with no tracks at all the camera's own position is applied",
            handler.positionApplies == 1 && handler.position.equals(new Vector3d(10, 64, -5), 0.0001));
        check("with no tracks at all the camera's own rotation is applied",
            handler.rotationApplies == 1 && handler.yaw == 90 && handler.pitch == 12 && handler.roll == 3);
        check("with no tracks at all the camera's own fov is applied", handler.fov != null && handler.fov == 95.0f);
        check("with no tracks at all the camera's own shake is applied",
            handler.shake != null && handler.shake[0] == 2.0f && handler.shake[1] == 0.5f
                && handler.shake[2] == 3.0f && handler.shake[3] == 0.25f);

        // A camera-owned position track covers position, and only position.
        KeyframeTrack positionTrack = new KeyframeTrack(CameraPositionKeyframeType.INSTANCE);
        positionTrack.cameraId = camera.id;
        positionTrack.keyframesByTick.put(0, new CameraPositionKeyframe(new Vector3d(100, 70, 100)));
        scene.keyframeTracks.add(positionTrack);

        handler = new RecordingHandler();
        state.applyKeyframes(handler, 0);
        // The count matters as much as the value: since static values run first, a covered property
        // would still end up looking right if the track merely overwrote it. One position write means
        // the camera's own position really was skipped.
        check("a covered position comes from its track, not the camera's stored value",
            handler.positionApplies == 1 && handler.position.equals(new Vector3d(100, 70, 100), 0.0001));
        check("the still-uncovered rotation is the camera's own",
            handler.rotationApplies == 1 && handler.yaw == 90 && handler.pitch == 12 && handler.roll == 3);
        check("the still-uncovered fov is the camera's own", handler.fov != null && handler.fov == 95.0f);

        // Disabling the track stops it covering the property.
        positionTrack.enabled = false;
        handler = new RecordingHandler();
        state.applyKeyframes(handler, 0);
        check("a disabled track stops covering position, so the camera's own value returns",
            handler.positionApplies == 1 && handler.position.equals(new Vector3d(10, 64, -5), 0.0001));
        positionTrack.enabled = true;

        // Another camera's track does not apply to this one, so it does not cover anything.
        positionTrack.cameraId = UUID.randomUUID();
        handler = new RecordingHandler();
        state.applyKeyframes(handler, 0);
        check("another camera's track does not cover this camera's position",
            handler.positionApplies == 1 && handler.position.equals(new Vector3d(10, 64, -5), 0.0001));
        positionTrack.cameraId = camera.id;

        // A whole-camera keyframe covers position and rotation together. The granular position track
        // is disabled for this, so a position write count of one means the whole keyframe covered the
        // property rather than the camera's own value being applied underneath it.
        positionTrack.enabled = false;
        KeyframeTrack wholeTrack = new KeyframeTrack(CameraKeyframeType.INSTANCE);
        wholeTrack.cameraId = camera.id;
        wholeTrack.keyframesByTick.put(0, new CameraKeyframe(new Vector3d(7, 7, 7), 7, 7, 7));
        scene.keyframeTracks.add(wholeTrack);
        handler = new RecordingHandler();
        state.applyKeyframes(handler, 0);
        check("a whole-camera keyframe covers position",
            handler.positionApplies == 1 && handler.position.equals(new Vector3d(7, 7, 7), 0.0001));
        check("a whole-camera keyframe covers rotation",
            handler.rotationApplies == 1 && handler.yaw == 7 && handler.pitch == 7 && handler.roll == 7);
        check("a whole-camera keyframe does not cover fov, which it cannot animate",
            handler.fov != null && handler.fov == 95.0f);
        scene.keyframeTracks.remove(wholeTrack);
        positionTrack.enabled = true;

        // The scene-wide FOV lane and shake lane still win over the camera's own values.
        KeyframeTrack sceneFov = new KeyframeTrack(FOVKeyframeType.INSTANCE);
        sceneFov.cameraId = null;
        sceneFov.keyframesByTick.put(0, new FOVKeyframe(45));
        scene.keyframeTracks.add(sceneFov);
        KeyframeTrack sceneShake = new KeyframeTrack(CameraShakeKeyframeType.INSTANCE);
        sceneShake.cameraId = null;
        sceneShake.keyframesByTick.put(0, new CameraShakeKeyframe(1, 0.1f, 1, 0.1f, false));
        scene.keyframeTracks.add(sceneShake);

        handler = new RecordingHandler();
        state.applyKeyframes(handler, 0);
        check("the scene FOV track covers the camera's own fov", handler.fov != null && handler.fov == 45.0f);
        check("the scene shake track covers the camera's own shake",
            handler.shake != null && handler.shake[1] == 0.1f);

        // Only free cameras apply their own values: an orbit camera's position comes from its orbit.
        EditorCamera orbit = new EditorCamera(null, EditorCamera.Kind.ORBIT);
        orbit.x = 1;
        orbit.y = 2;
        orbit.z = 3;
        orbit.yaw = 45;
        orbit.fov = 30;
        EditorState orbitState = stateWithActiveCamera(orbit);
        RecordingHandler orbitHandler = new RecordingHandler();
        orbitState.applyKeyframes(orbitHandler, 0);
        check("an orbit camera does not apply stored values over its own machinery",
            orbitHandler.positionApplies == 0 && orbitHandler.rotationApplies == 0 && orbitHandler.fov == null);
    }

    /** The new keyframe types have to survive the JSON path in both directions. */
    private static void newKeyframesRoundTripThroughJson() throws Exception {
        Path file = Files.createTempFile("flashback-camera-object", ".json");
        try {
            EditorState state = new EditorState();
            EditorScene scene = sceneOf(state);
            EditorCamera camera = new EditorCamera("A", EditorCamera.Kind.FREE);
            camera.x = 1.5;
            camera.y = 64.25;
            camera.z = -3.75;
            camera.yaw = 12.5f;
            camera.pitch = -4.5f;
            camera.roll = 6.5f;
            camera.fov = 82.5f;
            camera.overrideCameraShake = true;
            camera.cameraShakeXFrequency = 1.5f;
            camera.cameraShakeXAmplitude = 0.75f;
            camera.cameraShakeYFrequency = 2.5f;
            camera.cameraShakeYAmplitude = 0.25f;
            scene.cameras.add(camera);

            KeyframeTrack positionTrack = new KeyframeTrack(CameraPositionKeyframeType.INSTANCE);
            positionTrack.cameraId = camera.id;
            positionTrack.keyframesByTick.put(0, new CameraPositionKeyframe(new Vector3d(1, 2, 3)));
            scene.keyframeTracks.add(positionTrack);

            KeyframeTrack rotationTrack = new KeyframeTrack(CameraRotationKeyframeType.INSTANCE);
            rotationTrack.cameraId = camera.id;
            rotationTrack.keyframesByTick.put(0, new CameraRotationKeyframe(10, 20, 30));
            scene.keyframeTracks.add(rotationTrack);

            KeyframeTrack fovTrack = new KeyframeTrack(CameraFovKeyframeType.INSTANCE);
            fovTrack.cameraId = camera.id;
            fovTrack.keyframesByTick.put(0, new CameraFovKeyframe(75));
            scene.keyframeTracks.add(fovTrack);

            state.save(file);
            EditorState loaded = EditorState.load(file);
            check("a project with the new keyframe types loads", loaded != null);

            EditorScene loadedScene = sceneOf(loaded);
            check("the camera's own values survive a round trip",
                loadedScene.cameras.get(0).x == 1.5
                    && loadedScene.cameras.get(0).fov == 82.5f
                    && loadedScene.cameras.get(0).overrideCameraShake
                    && loadedScene.cameras.get(0).cameraShakeYAmplitude == 0.25f);
            check("a position keyframe round-trips as a position keyframe",
                loadedScene.keyframeTracks.get(0).keyframesByTick.get(0) instanceof CameraPositionKeyframe);
            check("a rotation keyframe round-trips as a rotation keyframe",
                loadedScene.keyframeTracks.get(1).keyframesByTick.get(0) instanceof CameraRotationKeyframe);
            check("a camera fov keyframe round-trips as a camera fov keyframe",
                loadedScene.keyframeTracks.get(2).keyframesByTick.get(0) instanceof CameraFovKeyframe);

            RecordingHandler handler = new RecordingHandler();
            loaded.applyKeyframes(handler, 0);
            check("a reloaded project still merges its granular tracks",
                handler.position.equals(new Vector3d(1, 2, 3), 0.0001)
                    && handler.yaw == 10 && handler.roll == 30
                    && handler.fov != null && handler.fov == 75.0f);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** A project written before cameras had their own values must load and evaluate safely. */
    private static void oldProjectsLoadAndEvaluate() throws Exception {
        // Shape schema 1 wrote: a camera, a legacy whole-camera track it owns and a switch, with none
        // of the camera's own value fields present.
        String json = """
            {"schemaVersion":1,"scenes":[{"name":"Scene 1","cameras":[{"id":"11111111-1111-1111-1111-111111111111","name":"A","kind":"FREE"}],
            "keyframeTracks":[
              {"keyframeType":"CAMERA","enabled":true,"cameraId":"11111111-1111-1111-1111-111111111111","keyframesByTick":{"0":{"type":"camera","position":[1.0,2.0,3.0],"yaw":10.0,"pitch":20.0,"roll":5.0,"interpolation_type":"SMOOTH"}},"customColour":0},
              {"keyframeType":"CAMERA_SWITCH","enabled":true,"keyframesByTick":{"0":{"type":"camera_switch","camera_id":"11111111-1111-1111-1111-111111111111","interpolation_type":"SMOOTH"}},"customColour":0}
            ],"exportStartTicks":-1,"exportEndTicks":-1,"history":{"entries":[],"position":0}}],"sceneIndex":0}""";

        EditorState state = load(json);
        check("an old project loads", state != null);

        EditorScene scene = sceneOf(state);
        EditorCamera camera = scene.cameras.get(0);
        check("the camera's own values are seeded from its keyframes, so it does not jump",
            camera.x == 1.0 && camera.y == 2.0 && camera.z == 3.0
                && camera.yaw == 10.0f && camera.pitch == 20.0f && camera.roll == 5.0f);
        check("an unset fov stays unset rather than inventing a value", camera.fov < 0);
        check("shake stays off when the project never had it", !camera.overrideCameraShake);
        check("loading stamped the current schema", state.schemaVersion == EditorState.CURRENT_SCHEMA_VERSION);
        check("migrating again reports no change", !state.migrateSchema());

        RecordingHandler handler = new RecordingHandler();
        state.applyKeyframes(handler, 0);
        check("an old project's camera track still applies",
            handler.position.equals(new Vector3d(1, 2, 3), 0.0001)
                && handler.yaw == 10 && handler.pitch == 20 && handler.roll == 5);

        // The seeding is what makes disabling the legacy track keep the camera where it was rather
        // than dropping it on the world origin.
        scene.keyframeTracks.get(0).enabled = false;
        handler = new RecordingHandler();
        state.applyKeyframes(handler, 0);
        check("with the track disabled the camera keeps the position it was seeded with",
            handler.position.equals(new Vector3d(1, 2, 3), 0.0001)
                && handler.yaw == 10 && handler.pitch == 20);

        // A project with no cameras at all, like a project written before cameras existed.
        String noCameras = """
            {"scenes":[{"name":"Scene 1","keyframeTracks":[],"exportStartTicks":-1,"exportEndTicks":-1,
            "history":{"entries":[],"position":0}}],"sceneIndex":0,"zoomMin":0.0,"zoomMax":1.0}""";
        EditorState bare = load(noCameras);
        check("a project with no cameras still loads", bare != null);
        RecordingHandler bareHandler = new RecordingHandler();
        bare.applyKeyframes(bareHandler, 0);
        check("a project with no cameras evaluates without applying anything",
            bareHandler.positionApplies == 0 && bareHandler.rotationApplies == 0 && bareHandler.fov == null);
    }

    /**
     * What the camera evaluates to is what the apply pass would apply.
     *
     * <p>This is the property the inspector depends on: it shows evaluated values, so if the two ever
     * disagreed the panel would be lying about the shot. The same edit is therefore run through both
     * paths and their answers compared, at a keyframe, between two, before the first, and past the
     * last - where the held last keyframe is what both must produce.
     */
    private static void evaluationMatchesApplyPassBetweenAndAroundKeys() {
        EditorCamera camera = new EditorCamera(null, EditorCamera.Kind.FREE);
        camera.x = 3;
        camera.y = 65;
        camera.z = 7;
        camera.yaw = 1;
        camera.pitch = 2;
        camera.roll = 3;
        camera.fov = 80;

        EditorState state = stateWithActiveCamera(camera);
        EditorScene scene = sceneOf(state);
        KeyframeTrack positionTrack = new KeyframeTrack(CameraPositionKeyframeType.INSTANCE);
        positionTrack.cameraId = camera.id;
        positionTrack.keyframesByTick.put(0, new CameraPositionKeyframe(new Vector3d(0, 64, 0)));
        positionTrack.keyframesByTick.put(10, new CameraPositionKeyframe(new Vector3d(10, 64, 20)));
        scene.keyframeTracks.add(positionTrack);

        KeyframeTrack rotationTrack = new KeyframeTrack(CameraRotationKeyframeType.INSTANCE);
        rotationTrack.cameraId = camera.id;
        rotationTrack.keyframesByTick.put(0, new CameraRotationKeyframe(0, 0, 0));
        rotationTrack.keyframesByTick.put(10, new CameraRotationKeyframe(90, 0, 0));
        scene.keyframeTracks.add(rotationTrack);

        for (int tick : new int[]{0, 5, 10, 20}) {
            EditorState.CameraEvaluation evaluated = state.evaluateCameraAt(scene, camera, tick);

            // The apply pass, as the client camera's handler runs it.
            RecordingHandler handler = new RecordingHandler();
            state.applyKeyframes(handler, tick);

            check("tick " + tick + ": the evaluated position is what the apply pass applies",
                evaluated.position().equals(handler.position, 0.0001));
            check("tick " + tick + ": the evaluated angles are what the apply pass applies",
                evaluated.yaw() == handler.yaw && evaluated.pitch() == handler.pitch && evaluated.roll() == handler.roll);
        }

        // The concrete numbers, so a change in interpolation would be caught here rather than only
        // by the two paths happening to agree with each other.
        EditorState.CameraEvaluation middle = state.evaluateCameraAt(scene, camera, 5);
        check("between two position keyframes the position is interpolated",
            middle.position().equals(new Vector3d(5, 64, 10), 0.0001));
        check("between two rotation keyframes the angles are interpolated",
            middle.yaw() == 45 && middle.pitch() == 0 && middle.roll() == 0);

        EditorState.CameraEvaluation first = state.evaluateCameraAt(scene, camera, 0);
        check("at the first keyframe the first keyframe's value is used",
            first.position().equals(new Vector3d(0, 64, 0), 0.0001) && first.yaw() == 0);
        check("an evaluated property is reported as driven", first.isDriven(EditorState.CameraProperty.POSITION));

        EditorState.CameraEvaluation last = state.evaluateCameraAt(scene, camera, 10);
        check("at the last keyframe the last keyframe's value is used",
            last.position().equals(new Vector3d(10, 64, 20), 0.0001) && last.yaw() == 90);

        EditorState.CameraEvaluation after = state.evaluateCameraAt(scene, camera, 20);
        check("past the last keyframe the last keyframe is held, not the stored value",
            after.position().equals(new Vector3d(10, 64, 20), 0.0001) && after.yaw() == 90);
    }

    /**
     * A camera's properties are independent tracks, and the evaluation has to say so: evaluating a
     * position must not claim rotation is animated, or the inspector would offer to edit a keyframe
     * for a property nothing is animating.
     */
    private static void evaluationKeepsPropertiesIndependent() {
        EditorCamera camera = new EditorCamera(null, EditorCamera.Kind.FREE);
        camera.yaw = 12;
        camera.fov = 70;
        camera.overrideCameraShake = true;

        EditorState state = stateWithActiveCamera(camera);
        EditorScene scene = sceneOf(state);
        KeyframeTrack positionTrack = new KeyframeTrack(CameraPositionKeyframeType.INSTANCE);
        positionTrack.cameraId = camera.id;
        positionTrack.keyframesByTick.put(0, new CameraPositionKeyframe(new Vector3d(1, 2, 3)));
        scene.keyframeTracks.add(positionTrack);

        EditorState.CameraEvaluation onlyPosition = state.evaluateCameraAt(scene, camera, 0);
        check("evaluating position does not report rotation as animated",
            onlyPosition.isDriven(EditorState.CameraProperty.POSITION)
                && !onlyPosition.isDriven(EditorState.CameraProperty.ROTATION));
        check("evaluating position does not report fov or shake as animated",
            !onlyPosition.isDriven(EditorState.CameraProperty.FOV) && !onlyPosition.isDriven(EditorState.CameraProperty.SHAKE));
        check("an uncovered rotation falls back to the camera's stored angles",
            onlyPosition.yaw() == 12);
        check("an uncovered fov falls back to the camera's stored fov", onlyPosition.fov() == 70.0f);

        // A whole-camera keyframe covers both, and a rotation track on its own covers only rotation.
        KeyframeTrack rotationTrack = new KeyframeTrack(CameraRotationKeyframeType.INSTANCE);
        rotationTrack.cameraId = camera.id;
        rotationTrack.keyframesByTick.put(0, new CameraRotationKeyframe(35, 0, 0));
        scene.keyframeTracks.add(rotationTrack);

        EditorState.CameraEvaluation both = state.evaluateCameraAt(scene, camera, 0);
        check("with both granular tracks, both properties are driven",
            both.isDriven(EditorState.CameraProperty.POSITION) && both.isDriven(EditorState.CameraProperty.ROTATION));
        check("with both granular tracks, fov stays stored", !both.isDriven(EditorState.CameraProperty.FOV) && both.fov() == 70.0f);
    }

    /**
     * Editing one property's keyframe must leave the others byte-identical - the tracks are separate,
     * so a position edit cannot rewrite a rotation keyframe, or vice versa.
     */
    private static void editingOnePropertyLeavesTheOthersUntouched() {
        EditorCamera camera = new EditorCamera(null, EditorCamera.Kind.FREE);

        EditorState state = stateWithActiveCamera(camera);
        EditorScene scene = sceneOf(state);
        KeyframeTrack positionTrack = new KeyframeTrack(CameraPositionKeyframeType.INSTANCE);
        positionTrack.cameraId = camera.id;
        positionTrack.keyframesByTick.put(5, new CameraPositionKeyframe(new Vector3d(1, 2, 3)));
        scene.keyframeTracks.add(positionTrack);

        KeyframeTrack rotationTrack = new KeyframeTrack(CameraRotationKeyframeType.INSTANCE);
        rotationTrack.cameraId = camera.id;
        rotationTrack.keyframesByTick.put(5, new CameraRotationKeyframe(10, 20, 30));
        scene.keyframeTracks.add(rotationTrack);

        KeyframeTrack fovTrack = new KeyframeTrack(CameraFovKeyframeType.INSTANCE);
        fovTrack.cameraId = camera.id;
        fovTrack.keyframesByTick.put(5, new CameraFovKeyframe(80));
        scene.keyframeTracks.add(fovTrack);

        // The exact keyframe objects, so "untouched" means untouched rather than merely equal.
        Keyframe rotationKeyframe = rotationTrack.keyframesByTick.get(5);
        Keyframe fovKeyframe = fovTrack.keyframesByTick.get(5);

        EditorSceneHistoryEntry entry = new EditorSceneHistoryEntry(
            List.of(new EditorSceneHistoryAction.SetKeyframe(CameraPositionKeyframeType.INSTANCE,
                scene.trackIndexOf(positionTrack), 5, positionTrack.keyframesByTick.get(5).copy())),
            List.of(new EditorSceneHistoryAction.SetKeyframe(CameraPositionKeyframeType.INSTANCE,
                scene.trackIndexOf(positionTrack), 5, new CameraPositionKeyframe(new Vector3d(9, 9, 9)))),
            "camera position drag");
        scene.push(entry, state);

        check("editing the position keyframe changed the position keyframe",
            positionTrack.keyframesByTick.get(5) instanceof CameraPositionKeyframe moved
                && moved.position.equals(new Vector3d(9, 9, 9), 0.0001));
        check("editing the position keyframe left the rotation keyframe object alone",
            rotationTrack.keyframesByTick.get(5) == rotationKeyframe);
        check("editing the position keyframe left the fov keyframe object alone",
            fovTrack.keyframesByTick.get(5) == fovKeyframe);
        check("only one keyframe still exists on the position track",
            positionTrack.keyframesByTick.size() == 1 && positionTrack.keyframesByTick.containsKey(5));
        // The other properties still evaluate to exactly their own keyframes: a position edit must
        // not disturb what the rotation and fov tracks produce at the same tick.
        EditorState.CameraEvaluation afterEdit = state.evaluateCameraAt(scene, camera, 5);
        check("editing position leaves the rotation the rotation track produces untouched",
            afterEdit.yaw() == 10 && afterEdit.pitch() == 20 && afterEdit.roll() == 30);
        check("editing position leaves the fov the fov track produces untouched", afterEdit.fov() == 80.0f);
        check("editing position does not add or remove keyframes on the other tracks",
            rotationTrack.keyframesByTick.size() == 1 && fovTrack.keyframesByTick.size() == 1);
    }

    /**
     * A keyed drag must survive being released.
     *
     * <p>The release is the dangerous moment: the history entry is pushed with the redo list holding
     * the keyframe to end up with, and pushing APPLIES the redo. If that keyframe is built from the
     * camera's stored fields - which a keyed drag never writes - the release writes the pre-drag value
     * back and the field visibly snaps home. This walks the path the inspector uses: a base keyframe,
     * the live per-frame replacement, the release push, then a fresh evaluation.
     */
    private static void aKeyedDragPersistsThroughTheRelease() {
        EditorCamera camera = new EditorCamera(null, EditorCamera.Kind.FREE);
        camera.yaw = 20;

        EditorState state = stateWithActiveCamera(camera);
        EditorScene scene = sceneOf(state);
        KeyframeTrack positionTrack = new KeyframeTrack(CameraPositionKeyframeType.INSTANCE);
        positionTrack.cameraId = camera.id;
        positionTrack.keyframesByTick.put(5, new CameraPositionKeyframe(new Vector3d(1, 2, 3)));
        scene.keyframeTracks.add(positionTrack);

        KeyframeTrack rotationTrack = new KeyframeTrack(CameraRotationKeyframeType.INSTANCE);
        rotationTrack.cameraId = camera.id;
        rotationTrack.keyframesByTick.put(5, new CameraRotationKeyframe(10, 20, 30));
        scene.keyframeTracks.add(rotationTrack);
        Keyframe rotationKeyframe = rotationTrack.keyframesByTick.get(5);

        Keyframe before = positionTrack.keyframesByTick.get(5);
        float[] dragged = {7, 8, 9};
        int trackIndex = scene.trackIndexOf(positionTrack);

        // One live frame, exactly as performDrag does it: a fresh keyframe from the drag's numbers.
        positionTrack.keyframesByTick.put(5, draggedPosition(before, dragged));
        check("a live keyed frame shows the dragged value before release",
            state.evaluateCameraAt(scene, camera, 5).position().equals(new Vector3d(7, 8, 9), 0.0001));

        // The release, exactly as finishDrag does it: one history entry, pre-drag copy in the undo
        // list and the final keyframe in the redo list - which push() applies.
        scene.push(new EditorSceneHistoryEntry(
            List.of(new EditorSceneHistoryAction.SetKeyframe(positionTrack.keyframeType, trackIndex, 5, before.copy())),
            List.of(new EditorSceneHistoryAction.SetKeyframe(positionTrack.keyframeType, trackIndex, 5,
                draggedPosition(before, dragged))),
            "camera position drag"), state);

        check("a released keyed drag still evaluates to the dragged value",
            state.evaluateCameraAt(scene, camera, 5).position().equals(new Vector3d(7, 8, 9), 0.0001));
        check("the released keyed drag left exactly one keyframe on the track",
            positionTrack.keyframesByTick.size() == 1 && positionTrack.keyframesByTick.containsKey(5));
        check("the released keyed drag did not write the camera's stored position",
            camera.x == 0 && camera.y == 0 && camera.z == 0);
        check("the released keyed drag left the other property's keyframe object untouched",
            rotationTrack.keyframesByTick.get(5) == rotationKeyframe);
        check("the released keyed drag left the other property's values untouched",
            state.evaluateCameraAt(scene, camera, 5).yaw() == 10
                && state.evaluateCameraAt(scene, camera, 5).pitch() == 20
                && state.evaluateCameraAt(scene, camera, 5).roll() == 30);

        // Undo restores the pre-drag value and redo the dragged one - the gesture is one step.
        scene.undo(state, ignored -> { });
        check("undo restores the pre-drag value", state.evaluateCameraAt(scene, camera, 5).position()
            .equals(new Vector3d(1, 2, 3), 0.0001));
        scene.redo(state, ignored -> { });
        check("redo puts the dragged value back", state.evaluateCameraAt(scene, camera, 5).position()
            .equals(new Vector3d(7, 8, 9), 0.0001));

        // Before the first keyframe there is nothing to interpolate or hold, so the property falls
        // back to the camera's stored value - the same rule the apply pass uses. The dragged value is
        // what the tick it was made at answers with, and past the last keyframe it is held.
        check("before the first keyframe the stored value is the fallback",
            state.evaluateCameraAt(scene, camera, 0).position().equals(new Vector3d(0, 0, 0), 0.0001));
        check("past the last keyframe the dragged value is held",
            state.evaluateCameraAt(scene, camera, 10).position().equals(new Vector3d(7, 8, 9), 0.0001));
    }

    /**
     * An unkeyed drag writes the camera's stored value and that value stays: it is the fallback the
     * evaluation answers with while no track covers the property.
     */
    private static void anUnkeyedDragPersistsInTheStoredValue() {
        EditorCamera camera = new EditorCamera(null, EditorCamera.Kind.FREE);
        camera.yaw = 20;

        EditorState state = stateWithActiveCamera(camera);
        EditorScene scene = sceneOf(state);
        check("an unkeyed camera evaluates to its stored position",
            state.evaluateCameraAt(scene, camera, 5).position().equals(new Vector3d(0, 0, 0), 0.0001));

        // The unkeyed drag path: performDrag writes the stored fields and nothing else.
        camera.x = 4.5;
        camera.y = -1.25;
        camera.z = 30.0;

        check("an unkeyed drag's stored value is what evaluation answers with",
            state.evaluateCameraAt(scene, camera, 5).position().equals(new Vector3d(4.5, -1.25, 30.0), 0.0001));
        check("an unkeyed drag adds no camera keyframes at all",
            scene.keyframeTracks.stream().noneMatch(track -> track.cameraId != null));
        check("an unkeyed drag leaves other properties' stored values alone", camera.yaw == 20);
        check("an unkeyed drag leaves other properties uncovered",
            !state.evaluateCameraAt(scene, camera, 5).isDriven(EditorState.CameraProperty.ROTATION));
    }

    /** The keyframe a position drag leaves behind, built the way the inspector builds it. */
    private static Keyframe draggedPosition(Keyframe base, float[] values) {
        return new CameraPositionKeyframe(new Vector3d(values[0], values[1], values[2]), base.interpolationType());
    }

    /**
     * A whole-camera keyframe holds position and rotation together, so an edit to one of its
     * properties has to keep it a whole-camera keyframe: replacing it with a position-only keyframe
     * would put a change of the wrong class on a track whose type expects both, and would silently
     * drop where the camera was looking.
     */
    private static void aWholeCameraKeyframeKeepsItsShapeWhenOnePropertyIsEdited() {
        EditorCamera camera = new EditorCamera(null, EditorCamera.Kind.FREE);
        camera.yaw = 77;
        camera.pitch = 21;
        camera.roll = 4;

        EditorState state = stateWithActiveCamera(camera);
        EditorScene scene = sceneOf(state);
        KeyframeTrack wholeTrack = new KeyframeTrack(CameraKeyframeType.INSTANCE);
        wholeTrack.cameraId = camera.id;
        wholeTrack.keyframesByTick.put(0, new CameraKeyframe(new Vector3d(1, 2, 3), 30, 10, 5));
        scene.keyframeTracks.add(wholeTrack);

        Keyframe before = wholeTrack.keyframesByTick.get(0);
        check("the whole-camera keyframe is the type its track expects",
            before instanceof CameraKeyframe && wholeTrack.keyframeType.keyframeChangeType()
                == KeyframeChangeCameraPosition.class);

        // What the inspector's merge produces for a position edit: new position, everything else as
        // the keyframe already had it.
        CameraKeyframe merged = new CameraKeyframe(new Vector3d(camera.x, camera.y, camera.z),
            camera.yaw, camera.pitch, camera.roll, before.interpolationType());
        scene.setKeyframe(scene.trackIndexOf(wholeTrack), 0, merged);

        Keyframe after = wholeTrack.keyframesByTick.get(0);
        check("editing the position of a whole-camera keyframe keeps it a whole-camera keyframe",
            after instanceof CameraKeyframe);
        CameraKeyframe afterCamera = (CameraKeyframe) after;
        check("the edited whole-camera keyframe carries the camera's new position",
            afterCamera.position.equals(new Vector3d(0, 0, 0), 0.0001));
        check("the edited whole-camera keyframe carries the camera's angles",
            afterCamera.yaw == 77 && afterCamera.pitch == 21 && afterCamera.roll == 4);

        // And the evaluation still answers for both properties from that one keyframe.
        EditorState.CameraEvaluation evaluated = state.evaluateCameraAt(scene, camera, 0);
        check("a whole-camera keyframe drives both position and rotation",
            evaluated.isDriven(EditorState.CameraProperty.POSITION)
                && evaluated.isDriven(EditorState.CameraProperty.ROTATION));
        check("the evaluated values are the whole-camera keyframe's",
            evaluated.position().equals(new Vector3d(0, 0, 0), 0.0001)
                && evaluated.yaw() == 77 && evaluated.pitch() == 21 && evaluated.roll() == 4);
    }

    /** A camera with no tracks at all evaluates to its stored values, and drives nothing. */
    private static void aCameraWithNoTracksEvaluatesToItsStoredValues() {
        EditorCamera camera = new EditorCamera(null, EditorCamera.Kind.FREE);
        camera.x = 4.5;
        camera.y = 70.25;
        camera.z = -8.75;
        camera.yaw = 33.5f;
        camera.pitch = -12.25f;
        camera.roll = 5.5f;
        camera.fov = 91.5f;
        camera.overrideCameraShake = true;
        camera.cameraShakeXFrequency = 1.25f;
        camera.cameraShakeXAmplitude = 0.5f;
        camera.cameraShakeYFrequency = 2.5f;
        camera.cameraShakeYAmplitude = 0.125f;

        EditorState state = stateWithActiveCamera(camera);
        EditorScene scene = sceneOf(state);
        EditorState.CameraEvaluation evaluated = state.evaluateCameraAt(scene, camera, 0);

        check("a camera with no tracks evaluates to its stored position",
            evaluated.position().equals(new Vector3d(4.5, 70.25, -8.75), 0.0001));
        check("a camera with no tracks evaluates to its stored angles",
            evaluated.yaw() == 33.5 && evaluated.pitch() == -12.25 && evaluated.roll() == 5.5);
        check("a camera with no tracks evaluates to its stored fov", evaluated.fov() == 91.5f);
        check("a camera with no tracks evaluates to its stored shake",
            evaluated.shakeXFrequency() == 1.25f && evaluated.shakeXAmplitude() == 0.5f
                && evaluated.shakeYFrequency() == 2.5f && evaluated.shakeYAmplitude() == 0.125f);
        check("a camera with no tracks drives nothing",
            evaluated.driven().isEmpty());
        check("an unset fov stays at its -1 sentinel", new EditorState().evaluateCameraAt(scene,
            new EditorCamera("bare", EditorCamera.Kind.FREE), 0).fov() == -1.0f);
    }

    /**
     * The lock sequence the inspector uses must not break the timeline's own sequence.
     *
     * <p>The regression this guards: the inspector used to call a helper that wrote the TIMELINE's
     * static stamp, so on the timeline's next frame it believed it already held a write stamp, mutated
     * unlocked, and released a stamp that had already been released - StampedLock then throws
     * IllegalMonitorStateException from inside the render loop. Here the timeline-style and
     * inspector-style sequences are run in the order they really happen, and a read afterwards has to
     * still work. The statics themselves are asserted untouched by reflection, and the source-level
     * rule (the inspector never mentions the timeline's stamp) is checked in ContainerGuiCheck.
     */
    private static void theInspectorLockDisciplineDoesNotBreakTheTimeline() throws Exception {
        EditorState state = new EditorState();

        // A timeline frame: a read stamp held for the whole frame, released in a finally.
        long stamp = state.acquireRead();
        try {
            state.getCurrentScene(stamp);
        } finally {
            state.release(stamp);
        }

        // The inspector's read phase: copy what the frame needs, then let go before drawing.
        EditorScene scene;
        stamp = state.acquireRead();
        try {
            scene = state.getCurrentScene(stamp);
            state.evaluateCameraAt(scene, new EditorCamera("probe", EditorCamera.Kind.FREE), 0);
        } finally {
            state.release(stamp);
        }

        // An inspector mutation: one short write stamp, its own acquire and release.
        EditorCamera camera = new EditorCamera("probe", EditorCamera.Kind.FREE);
        stamp = state.acquireWrite();
        try {
            state.getCurrentScene(stamp).cameras.add(camera);
        } finally {
            state.release(stamp);
        }

        // The timeline's next frame must be able to read again.
        stamp = state.acquireRead();
        try {
            check("a read stamp still works after the inspector's read/write sequence",
                state.getCurrentScene(stamp).cameraById(camera.id) == camera);
        } finally {
            state.release(stamp);
        }

        // And the timeline's own statics are not something the inspector may have written: they are
        // private, so this is read by reflection, and they stay at their initial values.
        Class<?> timeline = Class.forName("com.moulberry.flashback.editor.ui.windows.TimelineWindow");
        java.lang.reflect.Field sceneStamp = timeline.getDeclaredField("sceneStamp");
        java.lang.reflect.Field sceneStampIsWrite = timeline.getDeclaredField("sceneStampIsWrite");
        sceneStamp.setAccessible(true);
        sceneStampIsWrite.setAccessible(true);
        check("the timeline's scene stamp is not left set by anything the inspector did",
            sceneStamp.getLong(null) == 0L);
        check("the timeline does not believe it holds a write stamp",
            !sceneStampIsWrite.getBoolean(null));
    }

    /**
     * A project saved with a degenerate fov override renders again after a plain reopen.
     *
     * <p>The real project that prompted this has {@code "overrideFov": true,
     * "overrideFovAmount": 1.4e-45} - {@link Float#MIN_VALUE}, written by a drag field whose lower
     * bound was that constant, which clamps every negative value up to about zero. An override of
     * about zero degrees is a degenerate projection, so nothing in the world draws. The repair has to
     * happen on load, because the user cannot be asked to hand-edit the file.
     */
    private static void aDegenerateFovOverrideIsRepairedOnLoad() throws Exception {
        float expectedDefault = Flashback.getConfig().internal.defaultOverrideFov;
        check("the configured default fov is itself a usable angle", expectedDefault >= 1.0f);

        // Exactly the damaged shape: override on, amount at Float.MIN_VALUE.
        ReplayVisuals damaged = new ReplayVisuals();
        damaged.overrideFov = true;
        damaged.overrideFovAmount = Float.MIN_VALUE;

        String json = "{\"schemaVersion\":" + EditorState.CURRENT_SCHEMA_VERSION
            + ",\"scenes\":[],\"sceneIndex\":0,\"replayVisuals\":{\"overrideFov\":true,\"overrideFovAmount\":"
            + Float.MIN_VALUE + "}}";
        EditorState repaired = load(json);
        check("a project with a MIN_VALUE fov override loads", repaired != null);
        check("the MIN_VALUE fov override is repaired to the configured default",
            repaired.replayVisuals.overrideFovAmount == expectedDefault);
        check("the repaired override is still switched on", repaired.replayVisuals.overrideFov);
        check("the repaired fov is not degenerate",
            repaired.replayVisuals.overrideFovAmount >= 1.0f
                && Float.isFinite(repaired.replayVisuals.overrideFovAmount));

        // The same for a negative amount with the override on: that is the sentinel leaking through,
        // not an angle.
        String negative = "{\"schemaVersion\":" + EditorState.CURRENT_SCHEMA_VERSION
            + ",\"scenes\":[],\"sceneIndex\":0,\"replayVisuals\":{\"overrideFov\":true,\"overrideFovAmount\":-1.0}}";
        EditorState negativeState = load(negative);
        check("a negative fov override with the override on is repaired",
            negativeState != null && negativeState.replayVisuals.overrideFovAmount == expectedDefault);

        // Every value below a usable angle, including zero and the smallest positive float.
        check("the shared fov rule treats every degenerate value the same",
            EditorState.saneOverrideFov(Float.MIN_VALUE) == expectedDefault
                && EditorState.saneOverrideFov(0.0f) == expectedDefault
                && EditorState.saneOverrideFov(-1.0f) == expectedDefault
                && EditorState.saneOverrideFov(Float.NaN) == expectedDefault
                && EditorState.saneOverrideFov(Float.NEGATIVE_INFINITY) == expectedDefault);
        check("the shared fov rule leaves a usable angle alone", EditorState.saneOverrideFov(90.0f) == 90.0f);

        // An override that is OFF keeps its sentinel, so a project that never used it is not rewritten.
        ReplayVisuals off = new ReplayVisuals();
        off.overrideFov = false;
        off.overrideFovAmount = -1.0f;
        check("an unused override keeps its -1 sentinel", off.overrideFovAmount == -1.0f);

        String offJson = "{\"schemaVersion\":" + EditorState.CURRENT_SCHEMA_VERSION
            + ",\"scenes\":[],\"sceneIndex\":0,\"replayVisuals\":{\"overrideFov\":false,\"overrideFovAmount\":-1.0}}";
        EditorState offState = load(offJson);
        check("an override that is off is not rewritten on load",
            offState != null && offState.replayVisuals.overrideFovAmount == -1.0f);

        // Non-finite values in the other visual overrides a drag field can reach are repaired too.
        String nonFinite = "{\"schemaVersion\":" + EditorState.CURRENT_SCHEMA_VERSION
            + ",\"scenes\":[],\"sceneIndex\":0,\"replayVisuals\":{\"overrideFog\":true,"
            + "\"overrideFogStart\":\"NaN\",\"overrideFogEnd\":-5.0,\"overrideRollAmount\":\"Infinity\"}}";
        EditorState nonFiniteState = load(nonFinite);
        check("a non-finite fog start is repaired", nonFiniteState != null
            && Float.isFinite(nonFiniteState.replayVisuals.overrideFogStart)
            && nonFiniteState.replayVisuals.overrideFogStart >= 0);
        check("a negative fog end is repaired", nonFiniteState != null
            && nonFiniteState.replayVisuals.overrideFogEnd >= 1.0f);
        check("a non-finite roll is repaired", nonFiniteState != null
            && Float.isFinite(nonFiniteState.replayVisuals.overrideRollAmount));
        check("the damaged value in the sample project would not have been a usable angle",
            damaged.overrideFovAmount < 1.0f);
    }

    private static void emptyAndFutureTracksUseStoredValues() {
        EditorCamera camera = new EditorCamera("fallback", EditorCamera.Kind.FREE);
        camera.x = 10; camera.yaw = 30; camera.fov = 80;
        camera.overrideCameraShake = true; camera.cameraShakeXAmplitude = 0.25f;
        EditorState state = stateWithActiveCamera(camera);
        EditorScene scene = sceneOf(state);
        KeyframeTrack position = new KeyframeTrack(CameraPositionKeyframeType.INSTANCE);
        position.cameraId = camera.id;
        KeyframeTrack rotation = new KeyframeTrack(CameraRotationKeyframeType.INSTANCE);
        rotation.cameraId = camera.id;
        KeyframeTrack fov = new KeyframeTrack(CameraFovKeyframeType.INSTANCE);
        fov.cameraId = camera.id;
        KeyframeTrack shake = new KeyframeTrack(CameraShakeKeyframeType.INSTANCE);
        scene.keyframeTracks.addAll(List.of(position, rotation, fov, shake));
        RecordingHandler handler = new RecordingHandler();
        handler.position = new Vector3d(999, 0, 0); handler.yaw = 999; handler.fov = 999f;
        for (boolean future : new boolean[]{false, true}) {
            if (future) {
                position.keyframesByTick.put(20, new CameraPositionKeyframe(new Vector3d(20, 0, 0)));
                rotation.keyframesByTick.put(20, new CameraRotationKeyframe(60, 0, 0));
                fov.keyframesByTick.put(20, new CameraFovKeyframe(90));
                shake.keyframesByTick.put(20, new CameraShakeKeyframe(1, 1, 1, 1, false));
            }
            state.applyKeyframes(handler, 10);
            EditorState.CameraEvaluation value = state.evaluateCameraAt(scene, camera, 10);
            check((future ? "before first keys" : "empty tracks") + ": stored position replaces previous camera",
                handler.position.x == 10 && value.position().x == 10 && !value.isDriven(EditorState.CameraProperty.POSITION));
            check((future ? "before first keys" : "empty tracks") + ": stored lens and effects apply",
                handler.yaw == 30 && handler.fov == 80 && handler.shake[1] == 0.25f
                    && value.yaw() == 30 && value.fov() == 80 && value.shakeXAmplitude() == 0.25f);
        }
    }

    private static void inactiveCameraStillEvaluatesItsOwnTracks() {
        EditorCamera output = new EditorCamera("output", EditorCamera.Kind.FREE);
        EditorCamera selected = new EditorCamera("selected", EditorCamera.Kind.FREE);
        selected.x = 1;
        EditorState state = stateWithActiveCamera(output);
        EditorScene scene = sceneOf(state);
        scene.cameras.add(selected);
        KeyframeTrack track = new KeyframeTrack(CameraPositionKeyframeType.INSTANCE);
        track.cameraId = selected.id;
        track.keyframesByTick.put(0, new CameraPositionKeyframe(new Vector3d(42, 0, 0)));
        scene.keyframeTracks.add(track);
        EditorState.CameraEvaluation value = state.evaluateCameraAt(scene, selected, 5);
        check("inactive selected camera evaluates its own held animation",
            value.position().x == 42 && value.isDriven(EditorState.CameraProperty.POSITION));
        RecordingHandler handler = new RecordingHandler();
        state.applyKeyframes(handler, 5);
        check("evaluating inactive camera does not change the output camera", handler.position.x == output.x);
    }

    private static void overlappingChangesFollowApplicationOrder() {
        for (boolean reverse : new boolean[]{false, true}) {
            EditorCamera camera = new EditorCamera("order", EditorCamera.Kind.FREE);
            EditorState state = stateWithActiveCamera(camera);
            EditorScene scene = sceneOf(state);
            KeyframeTrack whole = new KeyframeTrack(CameraKeyframeType.INSTANCE);
            whole.cameraId = camera.id;
            whole.keyframesByTick.put(0, new CameraKeyframe(new Vector3d(20, 0, 0), 20, 0, 0));
            KeyframeTrack position = new KeyframeTrack(CameraPositionKeyframeType.INSTANCE);
            position.cameraId = camera.id;
            position.keyframesByTick.put(0, new CameraPositionKeyframe(new Vector3d(10, 0, 0)));
            KeyframeTrack rotation = new KeyframeTrack(CameraRotationKeyframeType.INSTANCE);
            rotation.cameraId = camera.id;
            rotation.keyframesByTick.put(0, new CameraRotationKeyframe(10, 0, 0));
            scene.keyframeTracks.addAll(reverse ? List.of(whole, position, rotation) : List.of(position, rotation, whole));
            KeyframeTrack sceneFov = new KeyframeTrack(FOVKeyframeType.INSTANCE);
            sceneFov.keyframesByTick.put(0, new FOVKeyframe(45));
            KeyframeTrack cameraFov = new KeyframeTrack(CameraFovKeyframeType.INSTANCE);
            cameraFov.cameraId = camera.id;
            cameraFov.keyframesByTick.put(0, new CameraFovKeyframe(90));
            scene.keyframeTracks.addAll(reverse ? List.of(cameraFov, sceneFov) : List.of(sceneFov, cameraFov));
            for (int tick : new int[]{0, 5}) {
                RecordingHandler handler = new RecordingHandler();
                state.applyKeyframes(handler, tick);
                EditorState.CameraEvaluation value = state.evaluateCameraAt(scene, camera, tick);
                check("row order " + reverse + " at " + tick + ": whole and granular changes agree",
                    handler.position.x == (reverse ? 10 : 20) && value.position().equals(handler.position, 0.0001)
                        && handler.yaw == (reverse ? 10 : 20) && value.yaw() == handler.yaw);
                check("row order " + reverse + " at " + tick + ": distinct FOV classes agree",
                    handler.fov == (reverse ? 45 : 90) && value.fov() == handler.fov);
            }
            // A held class applies after a live class, even when the held track appeared first.
            sceneFov.keyframesByTick.put(10, new FOVKeyframe(45));
            RecordingHandler handler = new RecordingHandler();
            state.applyKeyframes(handler, 5);
            check("held camera FOV follows live scene FOV in both paths",
                handler.fov == 90 && state.evaluateCameraAt(scene, camera, 5).fov() == 90);
        }
    }

    private static void cameraEffectsDoNotReplaceSceneDefaults() {
        EditorCamera a = new EditorCamera("A", EditorCamera.Kind.FREE);
        a.fov = 100; a.overrideCameraShake = true; a.cameraShakeXAmplitude = 2;
        EditorCamera b = new EditorCamera("B", EditorCamera.Kind.FREE);
        EditorState state = stateWithActiveCamera(a);
        EditorScene scene = sceneOf(state);
        scene.cameras.add(b);
        scene.cameraSwitchTrack().keyframesByTick.put(10, new CameraSwitchKeyframe(b.id));
        ReplayVisuals base = state.replayVisuals;
        base.overrideFov = true; base.overrideFovAmount = 75;
        base.overrideCameraShake = false;
        RecordingHandler handler = new RecordingHandler() {
            @Override public void beginCameraFrame() { base.beginCameraFrame(); }
            @Override public void applyFov(float value) { base.cameraVisuals().setFov(value); }
            @Override public void applyCameraShake(float xf, float xa, float yf, float ya) {
                base.cameraVisuals().setCameraShake(xf, xa, yf, ya);
            }
        };
        state.applyKeyframes(handler, 0);
        check("A renders its own lens and shake", base.cameraVisuals().overrideFovAmount == 100
            && base.cameraVisuals().overrideCameraShake && base.cameraVisuals().cameraShakeXAmplitude == 2);
        check("A does not overwrite persisted scene defaults", base.overrideFovAmount == 75 && !base.overrideCameraShake);
        state.applyKeyframes(handler, 10);
        check("B restores scene lens and does not inherit A's shake", base.cameraVisuals().overrideFovAmount == 75
            && !base.cameraVisuals().overrideCameraShake);
        base.setFov(82);
        check("unanimated lens follows a scene edit even while paused", base.cameraVisuals().overrideFovAmount == 82);
        base.cameraVisuals().setRoll(25);
        base.beginCameraFrame();
        check("roll from another camera is not retained on a fresh pass", !base.cameraVisuals().overrideRoll);
        base.overrideFov = false; base.overrideFovAmount = -1;
        state.applyKeyframes(handler, 0);
        state.applyKeyframes(handler, 10);
        check("an unset B restores the game's FOV rather than A's override", !base.cameraVisuals().overrideFov);
        String json = com.moulberry.flashback.FlashbackGson.COMPRESSED.toJson(base);
        check("evaluated camera effects are not persisted", !json.contains("evaluatedCamera")
            && base.overrideFovAmount == -1 && !base.overrideCameraShake);
    }

    private static void allPersistedFovSourcesAreRepaired() throws Exception {
        float fallback = EditorState.saneOverrideFov(0);
        String cameraId = "11111111-1111-1111-1111-111111111111";
        for (String invalid : new String[]{"0", "1.4e-45", "-5", "180", "360", "\"NaN\"", "\"Infinity\""}) {
            String json = "{\"schemaVersion\":2,\"scenes\":[{\"name\":\"S\",\"cameras\":[{\"id\":\"" + cameraId
                + "\",\"kind\":\"FREE\",\"fov\":" + invalid + "}],\"keyframeTracks\":["
                + "{\"keyframeType\":\"CAMERA_SWITCH\",\"enabled\":true,\"keyframesByTick\":{\"0\":{\"type\":\"camera_switch\",\"camera_id\":\"" + cameraId + "\",\"interpolation_type\":\"SMOOTH\"}}},"
                + "{\"keyframeType\":\"CAMERA_FOV\",\"enabled\":true,\"cameraId\":\"" + cameraId + "\",\"keyframesByTick\":{\"0\":{\"type\":\"camera_fov\",\"fov\":" + invalid + ",\"interpolation_type\":\"SMOOTH\"}}},"
                + "{\"keyframeType\":\"FOV\",\"enabled\":true,\"keyframesByTick\":{\"0\":{\"type\":\"fov\",\"fov\":" + invalid + ",\"interpolation_type\":\"SMOOTH\"}}}]}],"
                + "\"replayVisuals\":{\"overrideFov\":true,\"overrideFovAmount\":" + invalid + "}}";
            EditorState state = load(json);
            EditorScene scene = sceneOf(state);
            check("persisted camera FOV " + invalid + " is repaired", scene.cameras.get(0).fov == fallback);
            check("persisted FOV keys " + invalid + " are repaired",
                ((CameraFovKeyframe)scene.keyframeTracks.get(1).keyframesByTick.get(0)).fov == fallback
                    && ((FOVKeyframe)scene.keyframeTracks.get(2).keyframesByTick.get(0)).fov == fallback);
            RecordingHandler handler = new RecordingHandler();
            state.applyKeyframes(handler, 0);
            check("first application cannot undo repair for " + invalid, handler.fov == fallback
                && state.evaluateCameraAt(scene, scene.cameras.get(0), 0).fov() == fallback);
        }
        ReplayVisuals visuals = new ReplayVisuals();
        for (float invalid : new float[]{0, Float.MIN_VALUE, -1, 180, Float.NaN, Float.POSITIVE_INFINITY}) {
            visuals.setFov(invalid);
            check("runtime FOV setter rejects " + invalid, visuals.overrideFovAmount == fallback);
        }
        EditorCamera unset = new EditorCamera("unset", EditorCamera.Kind.FREE);
        EditorState state = stateWithActiveCamera(unset);
        state.migrateSchema();
        check("static FOV sentinel remains unset", unset.fov == -1);
        KeyframeTrack animated = new KeyframeTrack(CameraFovKeyframeType.INSTANCE);
        animated.cameraId = unset.id;
        animated.keyframesByTick.put(0, new CameraFovKeyframe(-1));
        sceneOf(state).keyframeTracks.add(animated);
        state.migrateSchema();
        check("animated FOV cannot use the static sentinel", ((CameraFovKeyframe)animated.keyframesByTick.get(0)).fov == fallback);
    }

    private static void suppliedSceneStampsDoNotReenterTheLock() {
        EditorState state = stateWithActiveCamera(new EditorCamera("lock", EditorCamera.Kind.FREE));
        for (boolean write : new boolean[]{false, true}) {
            long stamp = write ? state.acquireWrite() : state.acquireRead();
            try {
                state.applyKeyframes(new RecordingHandler(), 0, stamp);
                check("supplied " + (write ? "write" : "read") + " stamp remains owned after application",
                    state.getCurrentScene(stamp) != null);
            } finally { state.release(stamp); }
        }
    }

    private static EditorState load(String json) throws Exception {
        Path path = Files.createTempFile("flashback-camera-object-test", ".json");
        Files.writeString(path, json);
        try {
            EditorState state = EditorState.load(path);
            if (state == null) {
                throw new IllegalStateException("EditorState.load returned null");
            }
            return state;
        } finally {
            Files.deleteIfExists(path);
        }
    }

}
