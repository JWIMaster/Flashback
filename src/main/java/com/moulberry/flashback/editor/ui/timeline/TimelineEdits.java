package com.moulberry.flashback.editor.ui.timeline;

import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.impl.CameraSwitchKeyframe;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import com.moulberry.flashback.state.EditorCamera;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorSceneHistoryAction;
import com.moulberry.flashback.state.EditorSceneHistoryEntry;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.KeyframeTrack;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every change the timeline can make to a scene, as one undoable step each.
 *
 * <p>The interaction code decides <em>what</em> the user asked for; this decides <em>how</em> that is
 * expressed in the scene and in the undo history. Keeping the two apart is what stops undo entries
 * being built ad hoc in the middle of mouse handling, and it means every edit is reversible - because
 * each one goes through here, where the undo form has to be written down.
 */
public final class TimelineEdits {

    private TimelineEdits() {
    }

    /** Creates a camera of the given kind with its first track, and cuts to it at {@code tick}. */
    public static void addCamera(EditorScene scene, EditorState state, EditorCamera.Kind kind, int tick) {
        EditorCamera camera = new EditorCamera(null, kind);
        KeyframeType<?> firstType = kind.trackTypes().get(0);
        int cameraIndex = scene.cameras.size();
        int trackIndex = scene.insertionIndexForTrackOf(camera);

        push(scene, state,
            List.of(new EditorSceneHistoryAction.RemoveTrack(firstType, trackIndex),
                    new EditorSceneHistoryAction.RemoveCamera(camera)),
            List.of(new EditorSceneHistoryAction.AddCamera(camera, cameraIndex, List.of()),
                    new EditorSceneHistoryAction.AddTrack(firstType, trackIndex, camera.id)),
            I18n.get("flashback.create_named_track", firstType.name()));

        cutToCamera(scene, state, camera, tick);
    }

    /**
     * Records that a camera becomes the output at {@code tick}.
     *
     * <p>An existing cut at the same tick is retargeted rather than stacked on top of, so repeatedly
     * cutting at one position replaces the cut instead of leaving several at the same time.
     */
    public static void cutToCamera(EditorScene scene, EditorState state, EditorCamera camera, int tick) {
        KeyframeTrack switchTrack = scene.findOrCreateCameraSwitchTrack();
        Keyframe existing = switchTrack.keyframesByTick.get(tick);
        if (existing instanceof CameraSwitchKeyframe cut && camera.id.equals(cut.cameraId)) {
            return;
        }

        int index = scene.trackIndexOf(switchTrack);
        if (index < 0) {
            return;
        }
        pushKeyframe(scene, state, switchTrack, index, tick, new CameraSwitchKeyframe(camera.id));
    }

    /** Adds one more track of the given type to a camera. */
    public static void addTrackToCamera(EditorScene scene, EditorState state, EditorCamera camera, KeyframeType<?> type) {
        if (!camera.canOwn(type) || scene.hasTrackOfType(camera, type)) {
            return;
        }
        int index = scene.insertionIndexForTrackOf(camera);
        push(scene, state,
            List.of(new EditorSceneHistoryAction.RemoveTrack(type, index)),
            List.of(new EditorSceneHistoryAction.AddTrack(type, index, camera.id)),
            I18n.get("flashback.create_named_track", type.name()));
    }

    /**
     * Removes a camera along with every track it owns.
     *
     * <p>A camera and its rows are one thing to the user, so deleting the camera cannot leave its
     * rows behind attached to nothing.
     */
    public static void deleteCamera(EditorScene scene, EditorState state, EditorCamera camera) {
        List<EditorSceneHistoryAction> undo = new ArrayList<>();
        List<EditorSceneHistoryAction> redo = new ArrayList<>();

        // Undo restores from the lowest index up; redo removes from the highest down.
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < scene.keyframeTracks.size(); i++) {
            if (camera.id.equals(scene.keyframeTracks.get(i).cameraId)) {
                indices.add(i);
            }
        }
        for (int index : indices) {
            KeyframeTrack track = scene.keyframeTracks.get(index);
            undo.add(new EditorSceneHistoryAction.RestoreTrack(track, index));
        }
        for (int i = indices.size() - 1; i >= 0; i--) {
            KeyframeTrack track = scene.keyframeTracks.get(indices.get(i));
            redo.add(new EditorSceneHistoryAction.RemoveTrack(track.keyframeType, indices.get(i)));
        }

        // Cuts that named this camera would otherwise dangle; remember them so undo puts them back.
        List<EditorSceneHistoryAction.AddCamera.CameraSwitchEdit> cutEdits = new ArrayList<>();
        KeyframeTrack switchTrack = scene.cameraSwitchTrack();
        int switchIndex = scene.trackIndexOf(switchTrack);
        if (switchTrack != null) {
            for (Map.Entry<Integer, Keyframe> entry : switchTrack.keyframesByTick.entrySet()) {
                if (entry.getValue() instanceof CameraSwitchKeyframe cut && camera.id.equals(cut.cameraId)) {
                    cutEdits.add(new EditorSceneHistoryAction.AddCamera.CameraSwitchEdit(switchIndex, entry.getKey(), camera.id));
                }
            }
        }

        // Undo runs in list order, so the camera is put back before its rows: the rows need an owner.
        undo.add(0, new EditorSceneHistoryAction.AddCamera(camera, Math.max(0, scene.cameraIndexOf(camera)), cutEdits));
        redo.add(new EditorSceneHistoryAction.RemoveCamera(camera));

        push(scene, state, undo, redo, I18n.get("flashback.delete_named_camera", scene.displayNameOf(camera)));
    }

    /** Removes a single track. */
    public static void deleteTrack(EditorScene scene, EditorState state, KeyframeTrack track) {
        int index = scene.trackIndexOf(track);
        if (index < 0) {
            return;
        }
        push(scene, state,
            List.of(new EditorSceneHistoryAction.RestoreTrack(track, index)),
            List.of(new EditorSceneHistoryAction.RemoveTrack(track.keyframeType, index)),
            I18n.get("flashback.delete_named_track", track.keyframeType.name()));
    }

    /** Removes every keyframe from a track, leaving the track in place. */
    public static void clearTrack(EditorScene scene, EditorState state, KeyframeTrack track) {
        int index = scene.trackIndexOf(track);
        if (index < 0 || track.keyframesByTick.isEmpty()) {
            return;
        }

        List<EditorSceneHistoryAction> undo = new ArrayList<>();
        List<EditorSceneHistoryAction> redo = new ArrayList<>();
        for (Map.Entry<Integer, Keyframe> entry : track.keyframesByTick.entrySet()) {
            undo.add(new EditorSceneHistoryAction.SetKeyframe(track.keyframeType, index, entry.getKey(), entry.getValue().copy()));
            redo.add(new EditorSceneHistoryAction.RemoveKeyframe(track.keyframeType, index, entry.getKey()));
        }
        push(scene, state, undo, redo, I18n.get("flashback.clear_named_track", track.keyframeType.name()));
    }

    /** Adds or replaces one keyframe on a track. */
    public static void setKeyframe(EditorScene scene, EditorState state, KeyframeTrack track, int tick, Keyframe keyframe) {
        int index = scene.trackIndexOf(track);
        if (index < 0) {
            return;
        }
        pushKeyframe(scene, state, track, index, tick, keyframe);
    }

    private static void pushKeyframe(EditorScene scene, EditorState state, KeyframeTrack track, int index, int tick, Keyframe keyframe) {
        Keyframe old = track.keyframesByTick.get(tick);
        if (old == null) {
            push(scene, state,
                List.of(new EditorSceneHistoryAction.RemoveKeyframe(track.keyframeType, index, tick)),
                List.of(new EditorSceneHistoryAction.SetKeyframe(track.keyframeType, index, tick, keyframe)),
                I18n.get("flashback.added_named_keyframe", track.keyframeType.name()));
        } else {
            push(scene, state,
                List.of(new EditorSceneHistoryAction.SetKeyframe(track.keyframeType, index, tick, old.copy())),
                List.of(new EditorSceneHistoryAction.SetKeyframe(track.keyframeType, index, tick, keyframe)),
                I18n.get("flashback.added_named_keyframe", track.keyframeType.name()));
        }
    }

    /**
     * Adds a camera-position keyframe to the camera that is being output at {@code tick}.
     *
     * <p>Extends the camera the user is looking through, rather than starting a second one, which is
     * what pressing "camera keyframe" while a camera is live is asking for.
     */
    public static void addCameraPositionKeyframe(EditorScene scene, EditorState state, EditorCamera camera, int tick) {
        KeyframeTrack track = null;
        for (KeyframeTrack candidate : scene.keyframeTracks) {
            if (camera.id.equals(candidate.cameraId) && candidate.keyframeType == CameraKeyframeType.INSTANCE) {
                track = candidate;
                break;
            }
        }

        if (track == null) {
            if (!camera.canOwn(CameraKeyframeType.INSTANCE)) {
                ReplayUI.setInfoOverlayShort(I18n.get("flashback.camera_has_no_position_track", scene.displayNameOf(camera)));
                return;
            }
            addTrackToCamera(scene, state, camera, CameraKeyframeType.INSTANCE);
            for (KeyframeTrack candidate : scene.keyframeTracks) {
                if (camera.id.equals(candidate.cameraId) && candidate.keyframeType == CameraKeyframeType.INSTANCE) {
                    track = candidate;
                    break;
                }
            }
        }

        if (track == null) {
            return;
        }
        Keyframe keyframe = CameraKeyframeType.INSTANCE.createDirect();
        if (keyframe != null) {
            setKeyframe(scene, state, track, tick, keyframe);
        }
    }

    /**
     * Moves a track within its group, as one undoable step.
     *
     * <p>Only the slots the group's tracks already occupy are permuted, so a move can never reorder a
     * track outside the group or change a camera's ownership of its rows.
     */
    public static void reorderTrackGroup(EditorScene scene, EditorState state, int[] slots, List<KeyframeTrack> newOrder) {
        if (slots.length != newOrder.size() || slots.length < 2) {
            return;
        }

        List<KeyframeTrack> oldOrder = new ArrayList<>();
        for (int slot : slots) {
            if (slot < 0 || slot >= scene.keyframeTracks.size()) {
                return;
            }
            oldOrder.add(scene.keyframeTracks.get(slot));
        }

        List<EditorSceneHistoryAction> undo = new ArrayList<>();
        List<EditorSceneHistoryAction> redo = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            redo.add(new EditorSceneHistoryAction.RemoveTrack(oldOrder.get(i).keyframeType, slots[i]));
            redo.add(new EditorSceneHistoryAction.RestoreTrack(newOrder.get(i), slots[i]));
            undo.add(new EditorSceneHistoryAction.RemoveTrack(newOrder.get(i).keyframeType, slots[i]));
            undo.add(new EditorSceneHistoryAction.RestoreTrack(oldOrder.get(i), slots[i]));
        }
        push(scene, state, undo, redo, I18n.get("flashback.reordered_rows"));
    }

    /** Moves cameras so the registry ends up in the given order, as one undoable step. */
    public static void reorderCamera(EditorScene scene, EditorState state, List<EditorCamera> newOrder) {
        if (newOrder.size() != scene.cameras.size()) {
            return;
        }

        // Walk the wanted order left to right and move whichever camera belongs there into place.
        // Each move is a single camera stepping to a new index, which is easy to reverse exactly.
        List<EditorSceneHistoryAction> redo = new ArrayList<>();
        List<EditorCamera> working = new ArrayList<>(scene.cameras);
        for (int target = 0; target < newOrder.size(); target++) {
            EditorCamera wanted = newOrder.get(target);
            int current = working.indexOf(wanted);
            if (current < 0 || current == target) {
                continue;
            }
            working.remove(current);
            working.add(target, wanted);
            redo.add(new EditorSceneHistoryAction.ReorderCamera(wanted.id, current, target));
        }

        if (redo.isEmpty()) {
            return;
        }

        List<EditorSceneHistoryAction> undo = new ArrayList<>();
        for (int i = redo.size() - 1; i >= 0; i--) {
            EditorSceneHistoryAction.ReorderCamera action = (EditorSceneHistoryAction.ReorderCamera) redo.get(i);
            undo.add(new EditorSceneHistoryAction.ReorderCamera(action.cameraId(), action.toIndex(), action.fromIndex()));
        }
        push(scene, state, undo, redo, I18n.get("flashback.reordered_rows"));
    }

    // -- Shared plumbing -------------------------------------------------------------------------

    /**
     * Applies an edit and records it, so every change the timeline makes is undoable and marks the
     * project dirty.
     */
    public static void push(EditorScene scene, EditorState state, List<EditorSceneHistoryAction> undo,
                            List<EditorSceneHistoryAction> redo, String description) {
        scene.push(new EditorSceneHistoryEntry(undo, redo, description));
        state.markDirty();
    }

    @Nullable
    public static EditorCamera cameraForEditing(EditorScene scene, int tick) {
        EditorCamera active = scene.resolveCameraAt(tick);
        if (active != null) {
            return active;
        }
        return scene.cameras.isEmpty() ? null : scene.cameras.get(0);
    }

}
