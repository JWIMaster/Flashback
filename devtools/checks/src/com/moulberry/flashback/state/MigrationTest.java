package com.moulberry.flashback.state;

import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.impl.CameraSwitchKeyframe;
import com.moulberry.flashback.keyframe.impl.FOVKeyframe;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import com.moulberry.flashback.keyframe.types.FOVKeyframeType;
import com.moulberry.flashback.keyframe.types.TimelapseKeyframeType;

import java.util.UUID;

/**
 * Exercises the pre-camera -> camera schema migration without a game client, since migration is the
 * part of this change that must not corrupt existing projects.
 */
public class MigrationTest {

    private static int failures = 0;

    public static void main(String[] args) {
        migratesFlatScene();
        isIdempotent();
        leavesSceneWithNoCameraTracksAlone();
        keepsSceneTracksSceneWide();
        switchResolvesToMigratedCamera();
        resolvesUnknownSwitchToFirstCamera();
        beforeFirstSwitchUsesFirstCamera();

        if (failures > 0) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("All migration tests passed");
    }

    private static void check(String what, boolean condition) {
        if (!condition) {
            failures += 1;
            System.out.println("FAIL: " + what);
        } else {
            System.out.println("ok:   " + what);
        }
    }

    private static EditorScene legacyScene() {
        EditorScene scene = new EditorScene("Scene 1");
        // A pre-camera project: camera position + fov + timelapse as flat scene tracks.
        KeyframeTrack cameraTrack = new KeyframeTrack(CameraKeyframeType.INSTANCE);
        // The payloads are deliberately simple keyframes: migration works from the track *type*,
        // and using Minecraft-independent keyframes keeps this testable off the game client.
        cameraTrack.keyframesByTick.put(0, new FOVKeyframe(70));
        cameraTrack.keyframesByTick.put(20, new FOVKeyframe(80));

        KeyframeTrack fovTrack = new KeyframeTrack(FOVKeyframeType.INSTANCE);
        fovTrack.keyframesByTick.put(0, new FOVKeyframe(70));

        KeyframeTrack timelapseTrack = new KeyframeTrack(TimelapseKeyframeType.INSTANCE);
        timelapseTrack.keyframesByTick.put(0, new FOVKeyframe(70));
        timelapseTrack.keyframesByTick.put(100, new FOVKeyframe(70));

        scene.keyframeTracks.add(cameraTrack);
        scene.keyframeTracks.add(fovTrack);
        scene.keyframeTracks.add(timelapseTrack);
        return scene;
    }

    private static void migratesFlatScene() {
        EditorScene scene = legacyScene();
        EditorState.migrateSceneToCameraSchema(scene);

        check("one camera created for one viewpoint", scene.cameras.size() == 1);
        check("migrated camera is a free camera", scene.cameras.get(0).kind == EditorCamera.Kind.FREE);
        check("camera-switch lane created", scene.cameraSwitchTrack() != null);
        check("switch is first so it reads before what it selects", scene.keyframeTracks.get(0) == scene.cameraSwitchTrack());
        check("switch at tick 0 selects the camera", scene.resolveCameraAt(0) == scene.cameras.get(0));
        check("switch still selects the camera later", scene.resolveCameraAt(500) == scene.cameras.get(0));

        KeyframeTrack cameraTrack = scene.keyframeTracks.get(1);
        check("camera position track tagged with the camera",
            scene.cameras.get(0).id.equals(cameraTrack.cameraId));
        check("camera keyframes preserved", cameraTrack.keyframesByTick.size() == 2);
        check("fov stays scene-wide so it is not tied to one camera", scene.keyframeTracks.get(2).cameraId == null);
        check("timelapse track preserved", scene.keyframeTracks.get(3).keyframesByTick.size() == 2);
    }

    private static void isIdempotent() {
        EditorScene scene = legacyScene();
        EditorState.migrateSceneToCameraSchema(scene);
        UUID cameraId = scene.cameras.get(0).id;
        int tracks = scene.keyframeTracks.size();
        int cameras = scene.cameras.size();
        int switches = scene.cameraSwitchTrack().keyframesByTick.size();

        EditorState.migrateSceneToCameraSchema(scene);

        check("second migration adds no camera", scene.cameras.size() == cameras);
        check("second migration adds no track", scene.keyframeTracks.size() == tracks);
        check("second migration keeps the same camera", cameraId.equals(scene.cameras.get(0).id));
        check("second migration adds no switch", scene.cameraSwitchTrack().keyframesByTick.size() == switches);
    }

    private static void leavesSceneWithNoCameraTracksAlone() {
        EditorScene scene = new EditorScene("Scene 2");
        scene.keyframeTracks.add(new KeyframeTrack(TimelapseKeyframeType.INSTANCE));
        EditorState.migrateSceneToCameraSchema(scene);

        check("no camera invented for a scene with no camera tracks", scene.cameras.isEmpty());
        check("no switch lane invented either", scene.cameraSwitchTrack() == null);
        check("nothing is evaluated as a camera", scene.resolveCameraAt(0) == null);
    }

    private static void keepsSceneTracksSceneWide() {
        EditorScene scene = legacyScene();
        EditorState.migrateSceneToCameraSchema(scene);

        boolean timelapseIsSceneWide = false;
        for (KeyframeTrack track : scene.keyframeTracks) {
            if (track.keyframeType == TimelapseKeyframeType.INSTANCE) {
                timelapseIsSceneWide = track.cameraId == null;
            }
        }
        check("a timelapse stays scene-wide so it is not lost when the camera is", timelapseIsSceneWide);
    }

    private static void switchResolvesToMigratedCamera() {
        EditorScene scene = legacyScene();
        EditorState.migrateSceneToCameraSchema(scene);

        KeyframeTrack switchTrack = scene.cameraSwitchTrack();
        Keyframe switchKeyframe = switchTrack.keyframesByTick.get(0);
        check("switch names the migrated camera",
            switchKeyframe instanceof CameraSwitchKeyframe cs && scene.cameras.get(0).id.equals(cs.cameraId));
        check("unknown switch target falls back to the first camera",
            scene.resolveCamera(UUID.randomUUID()) == scene.cameras.get(0));
    }

    private static void beforeFirstSwitchUsesFirstCamera() {
        EditorScene scene = legacyScene();
        EditorState.migrateSceneToCameraSchema(scene);
        // An empty switch lane still means the scene is camera-driven, so the first camera is output
        // rather than every camera's tracks being applied at once.
        scene.cameraSwitchTrack().keyframesByTick.clear();
        check("an empty switch lane outputs the first camera",
            scene.resolveCameraAt(30) == scene.cameras.get(0));
    }

    private static void resolvesUnknownSwitchToFirstCamera() {
        EditorScene scene = legacyScene();
        EditorState.migrateSceneToCameraSchema(scene);
        scene.findOrCreateCameraSwitchTrack().keyframesByTick.put(50, new CameraSwitchKeyframe(UUID.randomUUID()));

        check("a switch to a deleted camera still resolves",
            scene.resolveCameraAt(60) == scene.cameras.get(0));
    }
}
