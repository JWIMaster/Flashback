package com.moulberry.flashback.editor.ui.timeline;

import com.moulberry.flashback.keyframe.impl.CameraKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraSwitchKeyframe;
import com.moulberry.flashback.keyframe.impl.FOVKeyframe;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraOrbitKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraSwitchKeyframeType;
import com.moulberry.flashback.keyframe.types.FOVKeyframeType;
import com.moulberry.flashback.state.EditorCamera;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.KeyframeTrack;
import org.joml.Vector3d;

import java.util.List;

/**
 * Row order, geometry and reordering rules. These are the things the UI must agree with itself
 * about, and they are all pure so they can be checked without a game client.
 */
public class LayoutTest {

    private static final TimelineLayout.Metrics METRICS = new TimelineLayout.Metrics(22, 17, 32, 6);

    private static int failures = 0;

    public static void main(String[] args) {
        rowsIncludeSectionsOnlyWhenUseful();
        rowGeometryIsConsistent();
        reorderStaysWithinAGroup();
        reorderComputesTheWantedOrder();
        cameraReorderComputesTheWantedOrder();
        collapsedCameraHidesItsRows();
        selectionIsIdentityBased();
        newCamerasGoAfterTheSwitchLane();
        rowHeightsAreHonoured();
        panelWidthStaysUsableAtAnyWindowSize();
        manyRowsStayReachable();
        deletingACameraExtendsItsPredecessor();
        shotsTileTheTimeline();
        youCanCutBackToACamera();
        shotsPreviewADrag();
        cuttingBackAndForthIsStable();

        if (failures > 0) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("All timeline layout tests passed");
    }

    private static void check(String what, boolean condition) {
        if (!condition) {
            failures += 1;
            System.out.println("FAIL: " + what);
        } else {
            System.out.println("ok:   " + what);
        }
    }

    private static KeyframeTrack track(com.moulberry.flashback.keyframe.KeyframeType<?> type) {
        return new KeyframeTrack(type);
    }

    private static EditorCamera camera(String name, EditorScene scene) {
        EditorCamera camera = new EditorCamera(name, EditorCamera.Kind.FREE);
        scene.cameras.add(camera);
        return camera;
    }

    private static EditorScene sceneWithoutACutAtZero() {
        EditorScene scene = new EditorScene("Scene 1");
        EditorCamera a = camera("A", scene);
        camera("B", scene);
        KeyframeTrack cuts = scene.findOrCreateCameraSwitchTrack();
        cuts.keyframesByTick.put(500, new CameraSwitchKeyframe(a.id));
        return scene;
    }

    private static EditorScene sceneWithCutAtZero() {
        EditorScene scene = new EditorScene("Scene 1");
        EditorCamera a = camera("A", scene);
        camera("B", scene);
        KeyframeTrack cuts = scene.findOrCreateCameraSwitchTrack();
        cuts.keyframesByTick.put(0, new CameraSwitchKeyframe(a.id));
        return scene;
    }

    private static EditorScene sceneWithTwoCamerasAndSceneTracks() {
        EditorScene scene = new EditorScene("Scene 1");
        KeyframeTrack switchTrack = new KeyframeTrack(CameraSwitchKeyframeType.INSTANCE);
        scene.keyframeTracks.add(switchTrack);
        switchTrack.keyframesByTick.put(0, new CameraSwitchKeyframe(null));

        EditorCamera a = camera("A", scene);
        EditorCamera b = camera("B", scene);

        KeyframeTrack aPos = track(CameraKeyframeType.INSTANCE);
        aPos.cameraId = a.id;
        aPos.keyframesByTick.put(0, new CameraKeyframe(new Vector3d(1, 0, 0), 0, 0, 0));
        KeyframeTrack aOrbit = track(CameraOrbitKeyframeType.INSTANCE);
        aOrbit.cameraId = a.id;
        KeyframeTrack bPos = track(CameraKeyframeType.INSTANCE);
        bPos.cameraId = b.id;
        bPos.keyframesByTick.put(0, new CameraKeyframe(new Vector3d(2, 0, 0), 0, 0, 0));
        KeyframeTrack timeOfDay = track(FOVKeyframeType.INSTANCE);
        timeOfDay.keyframesByTick.put(0, new FOVKeyframe(70));

        scene.keyframeTracks.add(aPos);
        scene.keyframeTracks.add(aOrbit);
        scene.keyframeTracks.add(bPos);
        scene.keyframeTracks.add(timeOfDay);
        return scene;
    }

    private static void rowsIncludeSectionsOnlyWhenUseful() {
        EditorScene scene = sceneWithTwoCamerasAndSceneTracks();
        TimelineLayout layout = TimelineLayout.build(scene, METRICS);

        check("switch lane is first", layout.row(0) instanceof TimelineRow.Track t && KeyframeTrack.isCameraSwitch(t.track()));
        check("cameras are introduced by a section", layout.row(1) instanceof TimelineRow.Section s
            && s.kind() == TimelineRow.Section.Kind.CAMERAS);
        check("camera A has a group row", layout.row(2) instanceof TimelineRow.CameraGroup g && "A".equals(g.camera().name));
        check("camera A's tracks follow it", layout.row(3) instanceof TimelineRow.Track t && t.owner() != null);
        check("scene tracks are introduced by a section", layout.rows().stream()
            .anyMatch(r -> r instanceof TimelineRow.Section s && s.kind() == TimelineRow.Section.Kind.SCENE));

        // A timeline with only scene tracks needs no headings at all.
        EditorScene simple = new EditorScene("Scene 2");
        KeyframeTrack fov = track(FOVKeyframeType.INSTANCE);
        simple.keyframeTracks.add(fov);
        TimelineLayout simpleLayout = TimelineLayout.build(simple, METRICS);
        check("one kind of content has no section heading",
            simpleLayout.size() == 1 && !(simpleLayout.row(0) instanceof TimelineRow.Section));
    }

    private static void rowGeometryIsConsistent() {
        EditorScene scene = sceneWithTwoCamerasAndSceneTracks();
        TimelineLayout layout = TimelineLayout.build(scene, METRICS);
        float contentY = 100;

        boolean consistent = true;
        for (int i = 0; i < layout.size(); i++) {
            float top = layout.rowTop(contentY, i);
            if (layout.rowAt(top + 1, contentY) != i) {
                consistent = false;
            }
        }
        check("every row is hit-testable at its own position", consistent);
        check("a position past the last row is not a row", layout.rowAt(layout.bottom(contentY) + 1, contentY) == -1);
        check("a position above the first row is not a row", layout.rowAt(contentY, contentY) == -1);
    }

    private static void reorderStaysWithinAGroup() {
        EditorScene scene = sceneWithTwoCamerasAndSceneTracks();
        TimelineLayout layout = TimelineLayout.build(scene, METRICS);

        int aPosRow = layout.rowOfTrack(scene.keyframeTracks.get(1));
        List<Integer> group = layout.reorderGroup(aPosRow);
        boolean allSameOwner = true;
        for (int row : group) {
            TimelineRow candidate = layout.row(row);
            if (!(candidate instanceof TimelineRow.Track t) || t.owner() == null
                    || !t.owner().id.equals(((TimelineRow.Track) layout.row(aPosRow)).owner().id)) {
                allSameOwner = false;
            }
        }
        check("a camera's track only reorders among that camera's tracks", allSameOwner);

        int switchRow = layout.rowOfTrack(scene.cameraSwitchTrack());
        check("the switch lane does not reorder", layout.reorderGroup(switchRow).isEmpty());

        int cameraRow = -1;
        for (int i = 0; i < layout.size(); i++) {
            if (layout.row(i) instanceof TimelineRow.CameraGroup) {
                cameraRow = i;
                break;
            }
        }
        check("a camera group reorders among camera groups", layout.reorderGroup(cameraRow).size() == 2);
    }

    private static void reorderComputesTheWantedOrder() {
        EditorScene scene = sceneWithTwoCamerasAndSceneTracks();
        TimelineLayout layout = TimelineLayout.build(scene, METRICS);

        KeyframeTrack aPos = scene.keyframeTracks.get(1);
        KeyframeTrack aOrbit = scene.keyframeTracks.get(2);
        int row = layout.rowOfTrack(aPos);

        check("dropping where it already is changes nothing", !layout.wouldMove(row, 0) && !layout.wouldMove(row, 1));

        List<KeyframeTrack> order = layout.trackOrderAfterMove(row, 2);
        check("dropping after its sibling swaps them", order.size() == 2
            && order.get(0) == aOrbit && order.get(1) == aPos);

        int[] slots = layout.trackSlotsOf(row);
        check("only the group's own slots are permuted", slots.length == 2 && slots[0] == 1 && slots[1] == 2);
    }

    private static void cameraReorderComputesTheWantedOrder() {
        EditorScene scene = sceneWithTwoCamerasAndSceneTracks();
        TimelineLayout layout = TimelineLayout.build(scene, METRICS);

        int firstCameraRow = -1;
        int secondCameraRow = -1;
        for (int i = 0; i < layout.size(); i++) {
            if (layout.row(i) instanceof TimelineRow.CameraGroup) {
                if (firstCameraRow < 0) {
                    firstCameraRow = i;
                } else if (secondCameraRow < 0) {
                    secondCameraRow = i;
                }
            }
        }

        List<EditorCamera> order = layout.cameraOrderAfterMove(firstCameraRow, 2);
        check("dragging a camera down moves it after the other",
            order.size() == 2 && "B".equals(order.get(0).name) && "A".equals(order.get(1).name));

        check("a camera drop onto itself changes nothing", !layout.wouldMove(secondCameraRow, 1));
    }

    private static void collapsedCameraHidesItsRows() {
        EditorScene scene = sceneWithTwoCamerasAndSceneTracks();
        scene.cameras.get(0).collapsed = true;
        TimelineLayout layout = TimelineLayout.build(scene, METRICS);

        int aTracks = 0;
        for (TimelineRow row : layout.rows()) {
            if (row instanceof TimelineRow.Track t && t.owner() != null && "A".equals(t.owner().name)) {
                aTracks += 1;
            }
        }
        check("a collapsed camera shows no child rows", aTracks == 0);
        check("its own row is still shown", layout.rows().stream()
            .anyMatch(r -> r instanceof TimelineRow.CameraGroup g && "A".equals(g.camera().name)));
    }

    /** Shots are clips: they cover the whole timeline, in order, with no gaps or overlaps. */
    private static void shotsTileTheTimeline() {
        EditorScene scene = sceneWithTwoCamerasAndSceneTracks();
        EditorCamera a = scene.cameras.get(0);
        EditorCamera b = scene.cameras.get(1);
        KeyframeTrack cuts = scene.findOrCreateCameraSwitchTrack();
        cuts.keyframesByTick.put(200, new CameraSwitchKeyframe(b.id));

        java.util.List<EditorScene.Shot> shots = scene.shots(1000);
        check("the timeline is covered by shots with no gap",
            shots.get(0).startTick() == 0 && shots.get(shots.size() - 1).endTick() == 1000);
        boolean contiguous = true;
        for (int i = 1; i < shots.size(); i++) {
            if (shots.get(i).startTick() != shots.get(i - 1).endTick()) {
                contiguous = false;
            }
        }
        check("each shot starts where the last one ended", contiguous);
        check("the cut is a boundary between two shots",
            shots.get(0).camera() == a && shots.get(0).endTick() == 200
                && shots.get(1).camera() == b && shots.get(1).startTick() == 200);

        check("a shot knows which cut starts it", shots.get(1).cutTick() == 200);
        // This fixture has a cut on the first tick, so that cut starts the first shot.
        check("a cut on the first tick starts the first shot", shots.get(0).cutTick() == 0);
        check("a shot with no cut of its own reports none", sceneWithoutACutAtZero().shots(100).get(0).cutTick() == -1);
        check("a cut on the first tick starts the first shot rather than making an empty one",
            sceneWithCutAtZero().shots(100).get(0).startTick() == 0);
    }

    /** The reported bug: once you had cut into a camera there was no way back. */
    private static void youCanCutBackToACamera() {
        EditorScene scene = sceneWithTwoCamerasAndSceneTracks();
        EditorCamera a = scene.cameras.get(0);
        EditorCamera b = scene.cameras.get(1);
        KeyframeTrack cuts = scene.findOrCreateCameraSwitchTrack();

        // Cut into B, then back to A, then to B again.
        cuts.keyframesByTick.put(100, new CameraSwitchKeyframe(b.id));
        cuts.keyframesByTick.put(200, new CameraSwitchKeyframe(a.id));
        cuts.keyframesByTick.put(300, new CameraSwitchKeyframe(b.id));

        java.util.List<EditorScene.Shot> shots = scene.shots(400);
        check("four shots tile the timeline", shots.size() == 4);
        check("cutting back is a shot of its own",
            shots.get(0).camera() == a && shots.get(1).camera() == b
                && shots.get(2).camera() == a && shots.get(3).camera() == b);
        check("the live camera is correct after cutting back",
            scene.resolveCameraAt(150) == b && scene.resolveCameraAt(250) == a && scene.resolveCameraAt(350) == b);
        check("a camera can appear as many shots as you like",
            shots.stream().filter(shot -> shot.camera() == b).count() == 2);
    }

    /** While a boundary or a whole shot is dragged, the lane shows the result before it is committed. */
    private static void shotsPreviewADrag() {
        EditorScene scene = sceneWithTwoCamerasAndSceneTracks();
        EditorCamera a = scene.cameras.get(0);
        EditorCamera b = scene.cameras.get(1);
        KeyframeTrack cuts = scene.findOrCreateCameraSwitchTrack();
        cuts.keyframesByTick.put(100, new CameraSwitchKeyframe(b.id));
        cuts.keyframesByTick.put(300, new CameraSwitchKeyframe(a.id));

        java.util.List<EditorScene.Shot> moved = scene.shots(400, tick -> tick == 100 ? 150 : tick);
        check("moving a boundary previews both affected shots",
            moved.get(0).endTick() == 150 && moved.get(1).startTick() == 150);
        check("the scene itself is untouched by a preview", cuts.keyframesByTick.containsKey(100));
        check("the previewed camera order is unchanged",
            moved.get(0).camera() == a && moved.get(1).camera() == b && moved.get(2).camera() == a);

        // Sliding a shot moves both of its boundaries together.
        java.util.List<EditorScene.Shot> slid = scene.shots(400, tick -> tick == 100 || tick == 300 ? tick + 50 : tick);
        check("sliding keeps the shot's length", slid.get(1).endTick() - slid.get(1).startTick() == 200);
        check("the neighbours stretch to take up the difference",
            slid.get(0).endTick() == 150 && slid.get(2).startTick() == 350);
    }

    /** Cutting back and forth repeatedly must stay consistent however the cuts are ordered. */
    private static void cuttingBackAndForthIsStable() {
        EditorScene scene = sceneWithTwoCamerasAndSceneTracks();
        EditorCamera a = scene.cameras.get(0);
        EditorCamera b = scene.cameras.get(1);
        KeyframeTrack cuts = scene.findOrCreateCameraSwitchTrack();
        // Insert them out of order: the model must not care.
        cuts.keyframesByTick.put(300, new CameraSwitchKeyframe(a.id));
        cuts.keyframesByTick.put(100, new CameraSwitchKeyframe(b.id));
        cuts.keyframesByTick.put(200, new CameraSwitchKeyframe(a.id));

        java.util.List<EditorScene.Shot> shots = scene.shots(400);
        boolean ascending = true;
        for (int i = 1; i < shots.size(); i++) {
            if (shots.get(i).startTick() < shots.get(i - 1).startTick()) {
                ascending = false;
            }
        }
        check("shots come out in time order however the cuts were added", ascending);
        check("no shot has zero length",
            shots.stream().allMatch(shot -> shot.endTick() > shot.startTick()));
    }

    /** Removing a camera must not make the programme jump to an unrelated viewpoint. */
    private static void deletingACameraExtendsItsPredecessor() {
        EditorScene scene = new EditorScene("Scene 1");
        EditorCamera a = camera("A", scene);
        EditorCamera b = camera("B", scene);
        EditorCamera c = camera("C", scene);
        KeyframeTrack cuts = scene.findOrCreateCameraSwitchTrack();
        cuts.keyframesByTick.put(0, new CameraSwitchKeyframe(a.id));
        cuts.keyframesByTick.put(50, new CameraSwitchKeyframe(b.id));
        cuts.keyframesByTick.put(100, new CameraSwitchKeyframe(c.id));

        scene.cameras.remove(b);
        scene.retargetOrphanedSwitches();

        check("the orphaned cut takes the previous camera",
            ((CameraSwitchKeyframe) cuts.keyframesByTick.get(50)).cameraId.equals(a.id));
        check("the following cut is untouched",
            ((CameraSwitchKeyframe) cuts.keyframesByTick.get(100)).cameraId.equals(c.id));
        check("the camera after the merge is still the one cut to afterwards",
            scene.resolveCameraAt(60) == a && scene.resolveCameraAt(120) == c);
    }

    /** The cut lane is deliberately taller and sections deliberately compact. */
    private static void rowHeightsAreHonoured() {
        EditorScene scene = sceneWithTwoCamerasAndSceneTracks();
        TimelineLayout layout = TimelineLayout.build(scene, METRICS);

        int cutsRow = layout.rowOfTrack(scene.cameraSwitchTrack());
        check("the cut lane is taller than a normal lane", layout.rowHeight(cutsRow) == METRICS.cutsLaneHeight());
        check("a normal lane uses the row height", layout.rowHeight(layout.rowOfTrack(scene.keyframeTracks.get(1)))
            == METRICS.rowHeight());

        boolean sectionsCompact = true;
        for (int i = 0; i < layout.size(); i++) {
            if (layout.row(i) instanceof TimelineRow.Section && layout.rowHeight(i) != METRICS.sectionHeight()) {
                sectionsCompact = false;
            }
        }
        check("section headings are compact", sectionsCompact);

        boolean ordered = true;
        for (int i = 1; i < layout.size(); i++) {
            if (layout.rowTop(0, i) <= layout.rowTop(0, i - 1) + layout.rowHeight(i - 1) - 0.01f) {
                ordered = false;
            }
        }
        check("rows follow one another without gaps or overlaps", ordered);
        check("the layout ends exactly after the last row",
            Math.abs(layout.bottom(0) - (layout.rowTop(0, layout.size() - 1) + layout.rowHeight(layout.size() - 1))) < 0.01f);
    }

    /** The row list must never crowd out the canvas, whatever the window or GUI scale. */
    private static void panelWidthStaysUsableAtAnyWindowSize() {
        check("default width is used when nothing is stored",
            TimelineLayout.panelWidth(0, 1000, 1, 140) == 250);
        check("a stored width is honoured", TimelineLayout.panelWidth(320, 1000, 1, 140) == 320);
        check("a tiny window still leaves room for the canvas",
            TimelineLayout.panelWidth(0, 400, 1, 140) <= 400 - 140);
        check("the panel never collapses below a readable column",
            TimelineLayout.panelWidth(0, 200, 1, 140) >= 170);
        check("a huge stored width is capped", TimelineLayout.panelWidth(10000, 2000, 1, 140) <= 420);
        check("GUI scale scales the panel with the interface",
            TimelineLayout.panelWidth(0, 4000, 3, 420) == 750);
    }

    /** With a lot of content every row is still reachable and the geometry stays sane. */
    private static void manyRowsStayReachable() {
        EditorScene scene = new EditorScene("Big");
        KeyframeTrack cuts = new KeyframeTrack(CameraSwitchKeyframeType.INSTANCE);
        scene.keyframeTracks.add(cuts);
        for (int i = 0; i < 40; i++) {
            EditorCamera camera = camera("Cam " + i, scene);
            KeyframeTrack track = track(CameraKeyframeType.INSTANCE);
            track.cameraId = camera.id;
            scene.keyframeTracks.add(track);
        }
        TimelineLayout layout = TimelineLayout.build(scene, METRICS);
        int groups = 0;
        int cameraTracks = 0;
        for (TimelineRow row : layout.rows()) {
            if (row instanceof TimelineRow.CameraGroup) {
                groups += 1;
            } else if (row instanceof TimelineRow.Track track && track.owner() != null) {
                cameraTracks += 1;
            }
        }
        check("every camera has a group row with its tracks beneath it", groups == 40 && cameraTracks == 40);
        check("one section heading covers all of them", layout.rows().stream()
            .filter(r -> r instanceof TimelineRow.Section s2 && s2.kind() == TimelineRow.Section.Kind.CAMERAS).count() == 1);

        boolean allReachable = true;
        for (int i = 0; i < layout.size(); i++) {
            if (layout.rowAt(layout.rowTop(0, i) + 1, 0) != i) {
                allReachable = false;
            }
        }
        check("every row is hit-testable", allReachable);

        // A camera's tracks only ever reorder among themselves even in a big scene.
        int someCameraRow = -1;
        for (int i = 0; i < layout.size(); i++) {
            if (layout.row(i) instanceof TimelineRow.CameraGroup) {
                someCameraRow = i;
                break;
            }
        }
        check("camera groups still reorder only among groups", layout.reorderGroup(someCameraRow).size() == 40);
    }

    /** A camera's rows sit below the switch lane, which is the lane that reads the timeline. */
    private static void newCamerasGoAfterTheSwitchLane() {
        EditorScene scene = new EditorScene("Scene 1");
        EditorCamera camera = new EditorCamera("A", EditorCamera.Kind.FREE);
        scene.cameras.add(camera);
        KeyframeTrack switchTrack = scene.findOrCreateCameraSwitchTrack();
        switchTrack.keyframesByTick.put(0, new CameraSwitchKeyframe(camera.id));

        int index = scene.insertionIndexForTrackOf(camera);
        check("a camera's first row is inserted after the switch lane", index == 1);
        check("the switch lane is still first", KeyframeTrack.isCameraSwitch(scene.keyframeTracks.get(0)));
    }

    private static void selectionIsIdentityBased() {
        EditorScene scene = sceneWithTwoCamerasAndSceneTracks();
        TimelineSelection selection = new TimelineSelection();
        KeyframeTrack aPos = scene.keyframeTracks.get(1);
        KeyframeTrack bPos = scene.keyframeTracks.get(3);
        bPos.keyframesByTick.put(5, new CameraKeyframe(new Vector3d(2, 0, 0), 0, 0, 0));

        selection.replace(aPos, 0);
        selection.add(bPos, 5);
        check("selection holds both references", selection.count() == 2);
        check("lookup is by track identity", selection.contains(aPos, 0) && !selection.contains(bPos, 0));
        check("the primary is the last added", selection.primary() != null && selection.primary().tick() == 5);

        // Reordering the track list must not change what is selected.
        scene.moveTrack(1, 3);
        selection.prune(scene);
        check("reordering the list keeps the selection", selection.count() == 2 && selection.contains(aPos, 0));

        // Deleting the keyframe drops just that reference.
        aPos.keyframesByTick.remove(0);
        selection.prune(scene);
        check("a deleted keyframe leaves the selection", selection.count() == 1 && selection.contains(bPos, 5));

        // Removing the track drops the rest of it.
        scene.keyframeTracks.remove(bPos);
        selection.prune(scene);
        check("a deleted track leaves the selection", selection.isEmpty() && selection.primary() == null);

        selection.replace(aPos, 0);
        selection.toggle(aPos, 0);
        check("toggling the only selection clears it", selection.isEmpty());
    }
}
