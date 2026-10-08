package com.moulberry.flashback.state;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads project JSON in the shape stock 0.43.6 wrote (no cameras, no schema version) to prove old
 * projects open safely. This is the exact crash that was reported: EditorScene had no no-argument
 * constructor, so Gson allocated it without running field initialisers and "cameras" came back null.
 */
public class LoadCompatTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        // In game these happen during mod initialisation; the test does them itself so the real
        // deserialisation path is exercised.
        com.moulberry.flashback.keyframe.KeyframeRegistry.register(
            com.moulberry.flashback.keyframe.types.CameraKeyframeType.INSTANCE);
        com.moulberry.flashback.keyframe.KeyframeRegistry.register(
            com.moulberry.flashback.keyframe.types.CameraSwitchKeyframeType.INSTANCE);
        com.moulberry.flashback.keyframe.KeyframeRegistry.register(
            com.moulberry.flashback.keyframe.types.SpectateKeyframeType.INSTANCE);

        loadsOldProjectWithoutCameras();
        loadsProjectWithoutSchemaVersionAndWithCameraTracks();
        toleratesNullCollections();
        toleratesUnknownCameraIdInSwitch();
        saveStampsVersionAndDoesNotRemigrate();
        repairsStateWronglyStampedAsCurrent();

        if (failures > 0) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("All load-compatibility tests passed");
    }

    private static void check(String what, boolean condition) {
        if (!condition) {
            failures += 1;
            System.out.println("FAIL: " + what);
        } else {
            System.out.println("ok:   " + what);
        }
    }

    private static EditorState load(String json) throws Exception {
        Path path = Files.createTempFile("flashback-test", ".json");
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

    /** Exactly what the crash report showed: a scene with no camera tracks and no "cameras" field. */
    private static void loadsOldProjectWithoutCameras() throws Exception {
        String json = """
            {"scenes":[{"name":"Scene 1","keyframeTracks":[],"exportStartTicks":-1,"exportEndTicks":-1,
            "history":{"entries":[],"position":0}}],"sceneIndex":0,"zoomMin":0.0,"zoomMax":1.0}""";

        EditorState state = load(json);
        long stamp = state.acquireRead();
        EditorScene scene;
        try {
            scene = state.getCurrentScene(stamp);
        } finally {
            state.release(stamp);
        }

        check("scene loaded", scene != null);
        check("cameras repaired to an empty list", scene.cameras != null && scene.cameras.isEmpty());
        check("tracks repaired to an empty list", scene.keyframeTracks != null);
        check("resolving a camera does not crash", scene.resolveCameraAt(0) == null);
        check("playback evaluation does not crash", applyWithoutCrash(state));

        // Upgrade and re-save must round-trip.
        state.migrateSchema();
        check("schema upgraded", state.schemaVersion == EditorState.CURRENT_SCHEMA_VERSION);
    }

    private static void loadsProjectWithoutSchemaVersionAndWithCameraTracks() throws Exception {
        // An old project that did animate its camera: migration must give it a camera and a switch.
        String json = """
            {"scenes":[{"name":"Scene 1","keyframeTracks":[
              {"keyframeType":"CAMERA","enabled":true,"keyframesByTick":{"0":{"type":"camera","position":[1.0,2.0,3.0],"yaw":0.0,"pitch":0.0,"roll":0.0,"interpolation_type":"SMOOTH"}},"customColour":0}
            ],"exportStartTicks":-1,"exportEndTicks":-1,"history":{"entries":[],"position":0}}],"sceneIndex":0}""";

        EditorState state = load(json);
        long stamp = state.acquireRead();
        EditorScene scene;
        try {
            scene = state.getCurrentScene(stamp);
        } finally {
            state.release(stamp);
        }

        check("one camera created for the legacy camera track", scene.cameras.size() == 1);
        check("legacy camera track is owned", scene.keyframeTracks.get(1).cameraId != null);
        check("switch lane created", scene.cameraSwitchTrack() != null);
        check("switch selects the migrated camera", scene.resolveCameraAt(0) == scene.cameras.get(0));
        check("keyframes survived migration", scene.keyframeTracks.get(1).keyframesByTick.size() == 1);
    }

    private static void toleratesNullCollections() throws Exception {
        // A scene whose collections are literally null in the file: JSON null must not crash either.
        String json = """
            {"scenes":[{"name":"Scene 1","keyframeTracks":null,"cameras":null,
            "exportStartTicks":-1,"exportEndTicks":-1,"history":{"entries":[],"position":0}}],"sceneIndex":0}""";

        EditorState state = load(json);
        long stamp = state.acquireRead();
        EditorScene scene;
        try {
            scene = state.getCurrentScene(stamp);
        } finally {
            state.release(stamp);
        }
        check("null collections repaired", scene.cameras != null && scene.keyframeTracks != null);
        check("evaluation safe with null collections", applyWithoutCrash(state));
    }

    private static void toleratesUnknownCameraIdInSwitch() throws Exception {
        String json = """
            {"scenes":[{"name":"Scene 1","cameras":[{"id":"11111111-1111-1111-1111-111111111111","name":"A","kind":"FREE"}],
            "keyframeTracks":[{"keyframeType":"CAMERA_SWITCH","enabled":true,
            "keyframesByTick":{"0":{"type":"camera_switch","camera_id":"22222222-2222-2222-2222-222222222222","interpolation_type":"SMOOTH"}},"customColour":0}],
            "exportStartTicks":-1,"exportEndTicks":-1,"history":{"entries":[],"position":0}}],"sceneIndex":0}""";

        EditorState state = load(json);
        long stamp = state.acquireRead();
        EditorScene scene;
        try {
            scene = state.getCurrentScene(stamp);
        } finally {
            state.release(stamp);
        }
        check("a switch to a missing camera falls back", scene.resolveCameraAt(0) == scene.cameras.get(0));
        check("evaluation safe with a dangling switch", applyWithoutCrash(state));
    }

    /** Full lifecycle: load an old project, save the upgraded form, load it again. */
    private static void saveStampsVersionAndDoesNotRemigrate() throws Exception {
        String json = """
            {"scenes":[{"name":"Scene 1","keyframeTracks":[
              {"keyframeType":"CAMERA","enabled":true,"keyframesByTick":{"0":{"type":"camera","position":[1.0,2.0,3.0],"yaw":0.0,"pitch":0.0,"roll":0.0,"interpolation_type":"SMOOTH"}},"customColour":0}
            ],"exportStartTicks":-1,"exportEndTicks":-1,"history":{"entries":[],"position":0}}],"sceneIndex":0}""";

        Path first = Files.createTempFile("flashback-test", ".json");
        Path second = Files.createTempFile("flashback-test", ".json");
        try {
            Files.writeString(first, json);
            EditorState loaded = EditorState.load(first);
            check("loading migrates the legacy project without being asked",
                loaded.schemaVersion == EditorState.CURRENT_SCHEMA_VERSION);
            check("migrating again reports no change", !loaded.migrateSchema());
            loaded.save(second);

            EditorState reloaded = EditorState.load(second);
            check("saved project records the current version",
                reloaded.schemaVersion == EditorState.CURRENT_SCHEMA_VERSION);
            check("reloading does not migrate again", !reloaded.migrateSchema());

            long stamp = reloaded.acquireRead();
            EditorScene scene;
            try {
                scene = reloaded.getCurrentScene(stamp);
            } finally {
                reloaded.release(stamp);
            }
            check("upgraded project kept its camera", scene.cameras.size() == 1);
            check("upgraded project kept its keyframes", scene.keyframeTracks.get(1).keyframesByTick.size() == 1);
            check("upgraded project kept its switch", scene.resolveCameraAt(0) == scene.cameras.get(0));
        } finally {
            Files.deleteIfExists(first);
            Files.deleteIfExists(second);
        }
    }

    /**
     * A project can record the current schema version without having been migrated - a build that
     * stamped the version before the migration worked would do exactly that. Detection must catch it.
     */
    private static void repairsStateWronglyStampedAsCurrent() throws Exception {
        String json = """
            {"schemaVersion":1,"scenes":[{"name":"Scene 1","keyframeTracks":[
              {"keyframeType":"CAMERA","enabled":true,"keyframesByTick":{"0":{"type":"camera","position":[1.0,2.0,3.0],"yaw":0.0,"pitch":0.0,"roll":0.0,"interpolation_type":"SMOOTH"}},"customColour":0}
            ],"exportStartTicks":-1,"exportEndTicks":-1,"history":{"entries":[],"position":0}}],"sceneIndex":0}""";

        EditorState state = load(json);
        long stamp = state.acquireRead();
        EditorScene scene;
        try {
            scene = state.getCurrentScene(stamp);
        } finally {
            state.release(stamp);
        }
        check("a wrongly stamped project is still migrated", scene.cameras.size() == 1);
        check("its track gained an owner", scene.keyframeTracks.get(1).cameraId != null);
        check("a switch was added", scene.resolveCameraAt(0) == scene.cameras.get(0));
    }

    private static boolean applyWithoutCrash(EditorState state) {
        try {
            state.applyKeyframes(new com.moulberry.flashback.keyframe.handler.KeyframeHandler() {
                @Override
                public boolean supportsKeyframeChange(Class<? extends com.moulberry.flashback.keyframe.change.KeyframeChange> clazz) {
                    return false;
                }
            }, 0);
            return true;
        } catch (Throwable t) {
            System.out.println("      threw: " + t);
            return false;
        }
    }
}
