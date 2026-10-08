package com.moulberry.flashback.editor;

import com.moulberry.flashback.editor.ui.KeyframeRelativeOffsets;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.state.EditorCamera;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorSceneHistoryAction;
import com.moulberry.flashback.state.EditorSceneHistoryEntry;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.KeyframeTrack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public record SavedTrack(KeyframeType<?> type, int track, boolean copiedFromDisabled,
                         @Nullable UUID cameraId, TreeMap<Integer, Keyframe> keyframes) {

    /**
     * Backwards-compatible constructor for clipboards written before tracks recorded their owner.
     */
    public SavedTrack(KeyframeType<?> type, int track, boolean copiedFromDisabled, TreeMap<Integer, Keyframe> keyframes) {
        this(type, track, copiedFromDisabled, null, keyframes);
    }

    public int applyToScene(EditorScene editorScene, int cursorTicks, int totalTicks, KeyframeRelativeOffsets offsets) {
        if (this.keyframes == null || this.keyframes.isEmpty()) {
            return 0;
        }

        // Camera-scoped keyframes belong to a camera, so they are pasted into a track of the camera
        // they came from. Creating a plain scene-level track for them would leave camera animation
        // attached to no camera, which then applies to whichever camera happens to be output.
        if (EditorScene.isCameraScoped(this.type)) {
            EditorCamera camera = resolveCameraForPaste(editorScene, this.cameraId, cursorTicks);
            if (camera == null) {
                return 0;
            }
            KeyframeTrack existing = findTrackOfCamera(editorScene, camera, this.type);
            if (existing != null) {
                return this.applyTo(editorScene, existing, editorScene.keyframeTracks.indexOf(existing), cursorTicks, totalTicks, offsets);
            }
            int index = editorScene.insertionIndexForTrackOf(camera);
            return this.applyTo(editorScene, null, index, camera.id, cursorTicks, totalTicks, offsets);
        }

        // Try to apply directly to copied track
        if (this.track >= 0 && this.track < editorScene.keyframeTracks.size()) {
            KeyframeTrack keyframeTrack = editorScene.keyframeTracks.get(this.track);
            if ((keyframeTrack.enabled || this.copiedFromDisabled) && keyframeTrack.keyframeType == this.type) {
                return this.applyTo(editorScene, keyframeTrack, this.track, cursorTicks, totalTicks, offsets);
            }
        }

        // Try to find first eligible enabled track
        for (KeyframeTrack keyframeTrack : editorScene.keyframeTracks) {
            if (keyframeTrack.enabled && keyframeTrack.keyframeType == this.type) {
                return this.applyTo(editorScene, keyframeTrack, this.track, cursorTicks, totalTicks, offsets);
            }
        }

        // Create new track
        return this.applyTo(editorScene, null, editorScene.keyframeTracks.size(), cursorTicks, totalTicks, offsets);
    }

    /**
     * The camera that camera-scoped keyframes should be pasted into: the one they were copied from,
     * or the one being output, or a new one so the paste is not silently dropped.
     */
    @Nullable
    private static EditorCamera resolveCameraForPaste(EditorScene editorScene, @Nullable UUID copiedFrom, int cursorTicks) {
        EditorCamera camera = editorScene.cameraById(copiedFrom);
        if (camera == null) {
            camera = editorScene.resolveCameraAt(cursorTicks);
        }
        if (camera != null) {
            return camera;
        }
        if (!editorScene.cameras.isEmpty()) {
            return editorScene.cameras.get(0);
        }
        return null;
    }

    @Nullable
    private static KeyframeTrack findTrackOfCamera(EditorScene editorScene, EditorCamera camera, KeyframeType<?> type) {
        for (KeyframeTrack track : editorScene.keyframeTracks) {
            if (camera.id.equals(track.cameraId) && track.keyframeType == type) {
                return track;
            }
        }
        return null;
    }

    private int applyTo(EditorScene editorScene, @Nullable KeyframeTrack existing, int trackIndex,
                         int cursorTicks, int totalTicks, KeyframeRelativeOffsets offsets) {
        return this.applyTo(editorScene, existing, trackIndex, null, cursorTicks, totalTicks, offsets);
    }

    private int applyTo(EditorScene editorScene, @Nullable KeyframeTrack existing, int trackIndex,
                         @Nullable UUID owner, int cursorTicks, int totalTicks, KeyframeRelativeOffsets offsets) {
        List<EditorSceneHistoryAction> undo = new ArrayList<>();
        List<EditorSceneHistoryAction> redo = new ArrayList<>();

        if (existing == null) {
            undo.add(new EditorSceneHistoryAction.RemoveTrack(this.type, trackIndex));
            redo.add(new EditorSceneHistoryAction.AddTrack(this.type, trackIndex, owner));
        }

        int count = 0;
        for (Map.Entry<Integer, Keyframe> entry : this.keyframes.entrySet()) {
            int newTick = entry.getKey() + cursorTicks;
            if (newTick >= 0 && newTick <= totalTicks) {
                if (existing != null) {
                    Keyframe old = existing.keyframesByTick.get(newTick);

                    if (old != null) {
                        undo.add(new EditorSceneHistoryAction.SetKeyframe(this.type, trackIndex, newTick, old.copy()));
                    } else {
                        undo.add(new EditorSceneHistoryAction.RemoveKeyframe(this.type, trackIndex, newTick));
                    }
                }

                Keyframe newKeyframe = entry.getValue().copy();
                offsets.apply(newKeyframe);
                redo.add(new EditorSceneHistoryAction.SetKeyframe(this.type, trackIndex, newTick, newKeyframe));

                count += 1;
            }
        }

        editorScene.push(new EditorSceneHistoryEntry(undo, redo, "Pasted " + count + " keyframe(s)"));

        return count;
    }
}
