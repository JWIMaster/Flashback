package uitest;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.editor.ui.windows.TimelineWindow;
import com.moulberry.flashback.keyframe.impl.CameraKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraOrbitKeyframe;
import com.moulberry.flashback.keyframe.types.CameraOrbitKeyframeType;
import com.moulberry.flashback.keyframe.impl.CameraSwitchKeyframe;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.record.FlashbackMeta;
import com.moulberry.flashback.state.EditorCamera;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.flashback.state.KeyframeTrack;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.flag.ImGuiCond;
import org.joml.Vector3d;

import java.util.UUID;

/**
 * Renders and drives the real timeline window offscreen.
 *
 * <p>Scenarios are named so a run can reproduce one situation: a handful of cameras and cuts, a
 * crowded project, a narrow window. The point is to be able to look at the interface and to click it
 * without loading a replay in the game.
 */
public final class TimelineScenario {

    private static final float WINDOW_X = 0;
    private static final float WINDOW_Y = 300;
    // Geometry read off a render, relative to the window, so the driver clicks what is on screen.
    private static final float BAND_OFFSET_Y = 84;    // middle of the cut lane, from the window top
    private static final float BAND_Y = WINDOW_Y + BAND_OFFSET_Y;
    private static final float CUT_1_2 = 367;         // boundary between Camera 1 and Camera 2
    private static final float CUT_2_3 = 487;         // boundary between Camera 2 and Camera 3
    private static final float SHOT_2_MIDDLE = 427;   // middle of Camera 2's shot
    private static final float BLADE_X = 187;         // the cut button on the cut lane
    private static final float CAMERAS_HEADING_Y = 103;

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "three-cameras";

        FlashbackMeta metadata = new FlashbackMeta();
        metadata.replayIdentifier = UUID.randomUUID();
        metadata.name = "harness";
        metadata.totalTicks = 400;
        ReplayServer server = new ReplayServer(metadata);
        Flashback.setReplayServer(server);

        EditorState state = new EditorState();
        boolean panned = scenario.equals("pan-time") || scenario.equals("pan-horizontal");
        state.zoomMin = panned ? 0.3 : 0;
        state.zoomMax = panned ? 0.7 : 1;
        EditorScene scene = state.currentSceneOrNull();
        build(scene, scenario);

        EditorStateManager.injected = state;
        server.setTotalTicks(400);
        server.setReplayTick(320);

        Harness harness = new Harness(854, 480, 2f);
        ReplayUI.uiScale = 1f;
        harness.setBeforeFrame(() -> {
            // Menus open downwards, so leave room below for them in scenarios that open one.
            ImGui.setNextWindowPos(WINDOW_X, windowY(scenario), ImGuiCond.Always);
            ImGui.setNextWindowSize(854, windowHeight(scenario), ImGuiCond.Always);
        });
        harness.setUi(TimelineWindow::render);

        drive(harness, scenario);
        harness.renderer().save("devtools/uitest/out/" + scenario + ".png");
        report(scene, server);
        System.out.println("wrote " + scenario + ".png (" + harness.lastCmdLists() + " lists, "
            + harness.lastVertices() + " vertices, " + harness.frames() + " frames, "
            + harness.viewportWindows() + " viewport windows)");
    }

    /** What the scene looks like now, so a driven interaction can be checked and not just seen. */
    private static void report(EditorScene scene, ReplayServer server) {
        System.out.println("[state] replayTick: " + server.getReplayTick()
            + " keyframesReapplied: " + server.forceApplyKeyframes.get());
        System.out.println("[state] rowScroll: " + String.format("%.1f",
            com.moulberry.flashback.editor.ui.windows.TimelineWindow.debugRowScroll()));
        System.out.println("[state] zoom: " + String.format("%.4f..%.4f",
            com.moulberry.flashback.state.EditorStateManager.injected.zoomMin,
            com.moulberry.flashback.state.EditorStateManager.injected.zoomMax));
        KeyframeTrack cuts = scene.cameraSwitchTrack();
        StringBuilder line = new StringBuilder();
        for (var entry : cuts.keyframesByTick.entrySet()) {
            Object value = entry.getValue();
            String camera = value instanceof CameraSwitchKeyframe cut
                ? scene.displayNameOf(scene.resolveCamera(cut.cameraId)) : "?";
            line.append(entry.getKey()).append("=").append(camera).append(" ");
        }
        System.out.println("[state] cuts: " + line.toString().trim());
        StringBuilder shots = new StringBuilder();
        for (EditorScene.Shot shot : scene.shots(server.getTotalReplayTicks())) {
            shots.append(scene.displayNameOf(shot.camera())).append("[")
                .append(shot.startTick()).append("..").append(shot.endTick()).append("] ");
        }
        System.out.println("[state] shots: " + shots.toString().trim());
    }

    /**
     * Holds a modifier down the way a keyboard would.
     *
     * <p>Keybinds read ImGui's own key state, so a scenario has to press the real key rather than set
     * a flag on a stand-in.
     */
    private static void holdModifier(Harness harness, int key, boolean down) {
        harness.key(key, down);
        harness.frame();
    }

    /** Where the window sits for this scenario; menus need room below them. */
    private static float windowY(String scenario) {
        if (scenario.equals("rows")) {
            return 20;
        }
        return scenario.endsWith("menu") ? 120 : WINDOW_Y;
    }

    /** How tall the window is; the row list only shows its structure when it is given room. */
    private static float windowHeight(String scenario) {
        return scenario.equals("rows") ? 440 : 180;
    }

    private static float bandY(String scenario) {
        return windowY(scenario) + BAND_OFFSET_Y;
    }

    /** Clicks, drags and keys, so the run exercises the interaction and not only the drawing. */
    private static void drive(Harness harness, String scenario) {
        float bandY = bandY(scenario);
        for (int i = 0; i < 3; i++) {
            harness.frame();
        }

        if (scenario.equals("hover-band")) {
            harness.moveMouse(SHOT_2_MIDDLE, bandY);
            harness.frame();
            harness.frame();
        } else if (scenario.equals("drag-edge") || scenario.equals("drag-edge-snapped")) {
            // Grab the boundary between the second and third shots and move it.
            float to = scenario.endsWith("snapped") ? 560 : 590;
            harness.drag(CUT_2_3, bandY, to, bandY, 8, 0);
            harness.frame();
            harness.frame();
        } else if (scenario.equals("drag-body")) {
            // Grab the middle of the second shot and slide it.
            harness.drag(SHOT_2_MIDDLE, bandY, SHOT_2_MIDDLE + 60, BAND_Y, 8, 0);
            harness.frame();
            harness.frame();
        } else if (scenario.equals("select-shot")) {
            harness.click(SHOT_2_MIDDLE, bandY);
            harness.frame();
        } else if (scenario.equals("shot-menu")) {
            harness.click(SHOT_2_MIDDLE, bandY, 1);
            harness.frame();
            harness.frame();
        } else if (scenario.equals("cut-menu")) {
            // The blade on the cut lane, which is how a cut is added at the playhead.
            harness.click(BLADE_X, bandY);
            harness.frame();
        } else if (scenario.equals("scroll-down") || scenario.equals("scroll-up")) {
            // Wheel over the middle of the row list: it should scroll the rows, not the timeline.
            float direction = scenario.equals("scroll-down") ? -1 : 1;
            harness.moveMouse(200, bandY + 40);
            harness.frame();
            for (int i = 0; i < 12; i++) {
                harness.scroll(direction, 0);
                harness.frame();
            }
            harness.frame();
        } else if (scenario.equals("pan-time")) {
            // Shift and the wheel move along the replay instead.
            harness.moveMouse(600, bandY + 40);
            harness.frame();
            holdModifier(harness, imgui.moulberry90.flag.ImGuiKey.ModShift, true);
            for (int i = 0; i < 6; i++) {
                harness.scroll(-1, 0);
                harness.frame();
            }
            holdModifier(harness, imgui.moulberry90.flag.ImGuiKey.ModShift, false);
            harness.frame();
        } else if (scenario.equals("pan-horizontal")) {
            // A trackpad's sideways gesture should pan the timeline the way the fingers travel.
            harness.moveMouse(600, bandY + 40);
            harness.frame();
            for (int i = 0; i < 8; i++) {
                harness.scroll(0, 1);
                harness.frame();
            }
        } else if (scenario.equals("zoom-time") || scenario.equals("zoom-cmd") || scenario.equals("zoom-ctrl")) {
            int modifier = scenario.equals("zoom-cmd")
                ? imgui.moulberry90.flag.ImGuiKey.ModSuper : imgui.moulberry90.flag.ImGuiKey.ModCtrl;
            harness.moveMouse(600, bandY + 40);
            harness.frame();
            // Either command key must zoom: the platform's and the one the user actually presses.
            holdModifier(harness, modifier, true);
            for (int i = 0; i < 4; i++) {
                // Scrolling up zooms in, which is the direction that can change anything from the
                // fully zoomed-out start.
                harness.scroll(1, 0);
                harness.frame();
            }
            holdModifier(harness, imgui.moulberry90.flag.ImGuiKey.ModSuper, false);
            harness.frame();
        } else if (scenario.equals("delete-shot")) {
            harness.click(SHOT_2_MIDDLE, bandY);
            harness.frame();
            harness.key(imgui.moulberry90.flag.ImGuiKey.Delete, true);
            harness.frame();
            harness.key(imgui.moulberry90.flag.ImGuiKey.Delete, false);
            harness.frame();
        } else if (scenario.equals("scrub")) {
            // Dragging the playhead must ask for the keyframes to be reapplied, or the camera keeps
            // showing wherever it was before the scrub.
            // The ruler strip sits above the rows; scrubbing is a drag along it.
            float rulerY = bandY - 38;
            harness.drag(700, rulerY, 1100, rulerY, 6, 0);
            harness.frame();
        } else if (scenario.equals("collapsed")) {
            // Collapse-all on the cameras heading.
            harness.click(70, windowY(scenario) + CAMERAS_HEADING_Y);
            harness.frame();
        }
    }

    private static void build(EditorScene scene, String scenario) {
        KeyframeTrack cuts = scene.findOrCreateCameraSwitchTrack();
        cuts.keyframesByTick.put(0, new CameraSwitchKeyframe(null));

        EditorCamera camera1 = addCamera(scene, "Camera 1", 0, 0);
        EditorCamera camera2 = addCamera(scene, "Camera 2", 100, 100);
        EditorCamera camera3 = addCamera(scene, "Camera 3", 300, 300);
        addCamera(scene, "Orbit 1", EditorScene.class == null ? 0 : 0, 0, EditorCamera.Kind.ORBIT);
        addSpectate(scene, "Spectate");

        if (!scenario.equals("one-camera")) {
            cuts.keyframesByTick.put(80, new CameraSwitchKeyframe(camera2.id));
            cuts.keyframesByTick.put(160, new CameraSwitchKeyframe(camera3.id));
        }
        if (scenario.equals("scroll-down") || scenario.equals("scroll-up")) {
            // Enough cameras that the list cannot fit, so scrolling has something to do.
            for (int i = 4; i <= 12; i++) {
                addCamera(scene, "Camera " + i, i * 25, i * 25);
            }
        }
        if (scenario.equals("crowded")) {
            for (int i = 4; i <= 14; i++) {
                addCamera(scene, "Camera " + i, i * 25, i * 25);
            }
        }
        if (scenario.equals("empty")) {
            scene.keyframeTracks.clear();
            scene.cameras.clear();
            scene.findOrCreateCameraSwitchTrack();
        }
        camera1.collapsed = scenario.equals("collapsed");
    }

    private static EditorCamera addCamera(EditorScene scene, String name, int keyframeTick, int cutTick) {
        return addCamera(scene, name, keyframeTick, cutTick, EditorCamera.Kind.FREE);
    }

    private static EditorCamera addCamera(EditorScene scene, String name, int keyframeTick, int cutTick,
                                          EditorCamera.Kind kind) {
        EditorCamera camera = new EditorCamera(name, kind);
        scene.cameras.add(camera);
        KeyframeTrack track = new KeyframeTrack(kind == EditorCamera.Kind.ORBIT
            ? CameraOrbitKeyframeType.INSTANCE : CameraKeyframeType.INSTANCE);
        track.cameraId = camera.id;
        if (kind == EditorCamera.Kind.ORBIT) {
            track.keyframesByTick.put(keyframeTick, new CameraOrbitKeyframe(new Vector3d(0, 64, 0), 8f, 0f, 0f));
        } else {
            track.keyframesByTick.put(keyframeTick, new CameraKeyframe(new Vector3d(0, 64, 0), 0, 0, 0));
        }
        scene.keyframeTracks.add(track);
        return camera;
    }

    private static void addSpectate(EditorScene scene, String name) {
        EditorCamera camera = new EditorCamera(name, EditorCamera.Kind.SPECTATE);
        scene.cameras.add(camera);
    }
}
