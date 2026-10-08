package com.moulberry.flashback.keyframe.types;

import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.keyframe.CameraSource;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraSwitch;
import com.moulberry.flashback.keyframe.impl.CameraSwitchKeyframe;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.flashback.state.KeyframeTrack;
import com.moulberry.flashback.state.NamedCamera;
import imgui.moulberry90.ImGui;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The camera lane: keyframes here decide which timeline source is output.
 *
 * <p>It is purely a selector. It does not move the camera or follow anyone itself - it names a
 * source, and the editor applies whatever that source is (a camera's tracks, or a spectate object's
 * player). Every entry is a hard cut, so interpolation is not offered.
 */
public class CameraSwitchKeyframeType implements KeyframeType<CameraSwitchKeyframe> {

    public static final CameraSwitchKeyframeType INSTANCE = new CameraSwitchKeyframeType();

    private CameraSwitchKeyframeType() {
    }

    @Override
    public Class<? extends KeyframeChange> keyframeChangeType() {
        return KeyframeChangeCameraSwitch.class;
    }

    @Override
    public @Nullable String icon() {
        return "\ue04b";
    }

    @Override
    public String name() {
        return I18n.get("flashback.keyframe.camera_switch");
    }

    @Override
    public String id() {
        return "CAMERA_SWITCH";
    }

    /** A cut is discrete, so there is nothing to interpolate. */
    @Override
    public boolean allowChangingInterpolationType() {
        return false;
    }

    @Override
    public @Nullable CameraSwitchKeyframe createDirect() {
        return null;
    }

    @Override
    public KeyframeCreatePopup<CameraSwitchKeyframe> createPopup() {
        return () -> {
            CameraSource source = pickSource();
            if (source != null) {
                ImGui.closeCurrentPopup();
                return new CameraSwitchKeyframe(source);
            }
            if (ImGui.button(I18n.get("gui.cancel")) || ReplayUI.consumeCancel()) {
                ImGui.closeCurrentPopup();
            }
            return null;
        };
    }

    /**
     * Lists every source that can be output: the camera tracks (grouped by camera) and the spectate
     * objects. Also offers creating a new camera, since a source has to exist before it can be cut
     * to.
     *
     * @return the chosen source, or null while the user is still choosing
     */
    private static @Nullable CameraSource pickSource() {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState == null) {
            return null;
        }

        List<KeyframeTrack> tracks = editorState.currentSceneTracks();

        // Sources are derived FROM THE TRACKS, not from the camera list. The tracks are the truth:
        // if a camera id appears on a track, that camera exists and can be cut to, whether or not it
        // was ever registered in editorState.cameras. Reading the list instead was why newly added
        // cameras did not appear here.
        java.util.LinkedHashMap<java.util.UUID, String> cameraSources = new java.util.LinkedHashMap<>();
        for (KeyframeTrack track : tracks) {
            if (track.cameraId == null) {
                continue;
            }
            // Spectate and timelapse are sources in their own right and are listed separately.
            // Omitting only spectate here made a timelapse track show up as an extra camera.
            if (track.keyframeType instanceof SpectateKeyframeType
                || track.keyframeType instanceof TimelapseKeyframeType) {
                continue;
            }
            cameraSources.putIfAbsent(track.cameraId, null);
        }

        // Give each a name, preferring the registered camera's name.
        List<NamedCamera> registered = editorState.cameras != null ? editorState.cameras : List.of();
        int unnamed = 0;
        for (java.util.Map.Entry<java.util.UUID, String> entry : cameraSources.entrySet()) {
            String name = null;
            for (NamedCamera camera : registered) {
                if (camera.id.equals(entry.getKey())) {
                    name = camera.name;
                    break;
                }
            }
            if (name == null) {
                name = I18n.get("flashback.camera") + " " + (++unnamed);
            }

            // A name typed on the timeline wins, so the switch matches the track you are looking at.
            for (KeyframeTrack track : tracks) {
                if (entry.getKey().equals(track.cameraId)
                    && track.customName != null && !track.customName.isBlank()) {
                    name = track.customName;
                    break;
                }
            }
            entry.setValue(name);
        }

        for (java.util.Map.Entry<java.util.UUID, String> entry : cameraSources.entrySet()) {
            if (ImGui.selectable(entry.getValue() + "##source_camera_" + entry.getKey(), false)) {
                return CameraSource.of(entry.getKey());
            }
        }

        // Other sources: a spectate or timelapse track is a source in its own right, so it can be
        // cut to exactly like a camera.
        List<KeyframeTrack> otherSources = new ArrayList<>();
        for (KeyframeTrack track : tracks) {
            if (track.keyframeType instanceof SpectateKeyframeType
                || track.keyframeType instanceof TimelapseKeyframeType) {
                otherSources.add(track);
            }
        }
        if (!otherSources.isEmpty()) {
            ImGui.separator();
            for (KeyframeTrack track : otherSources) {
                String label = com.moulberry.flashback.state.CameraSourceDisplay.describeSource(track.cameraId);
                if (ImGui.selectable(label + "##source_other_" + track.cameraId, false)) {
                    return CameraSource.of(track.cameraId);
                }
            }
        }

        ImGui.separator();
        if (ImGui.selectable(I18n.get("flashback.new_camera") + "##new_camera_source")) {
            // Register the name, and let the caller create the camera's track so the source exists
            // on the timeline immediately - a source with no track would be invisible here.
            NamedCamera camera = new NamedCamera(I18n.get("flashback.camera") + " " + (registered.size() + 1));
            if (editorState.cameras == null) {
                editorState.cameras = new ArrayList<>();
            }
            editorState.cameras.add(camera);
            editorState.activeCameraIndex = editorState.cameras.size() - 1;

            // Sources are derived from tracks, so the camera needs one immediately or it would be
            // invisible in this very menu next time it opens.
            KeyframeTrack cameraTrack = new KeyframeTrack(CameraKeyframeType.INSTANCE);
            cameraTrack.cameraId = camera.id;
            editorState.currentSceneTracks().add(cameraTrack);

            editorState.markDirty();
            return CameraSource.of(camera.id);
        }

        return null;
    }

}
