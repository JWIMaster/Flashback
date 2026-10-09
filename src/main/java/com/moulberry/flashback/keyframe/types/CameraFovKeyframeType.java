package com.moulberry.flashback.keyframe.types;

import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraFov;
import com.moulberry.flashback.keyframe.impl.CameraFovKeyframe;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import imgui.moulberry90.ImGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;

/**
 * A camera's own field of view lane.
 *
 * <p>The scene-wide FOV lane still exists and is unchanged; this one belongs to a single camera, so
 * cutting to that camera brings its FOV with it. Both are applied through the same handler method
 * and both are independent tracks, so the one later in the timeline wins for the frame.
 */
public class CameraFovKeyframeType implements KeyframeType<CameraFovKeyframe> {

    public static final CameraFovKeyframeType INSTANCE = new CameraFovKeyframeType();

    private CameraFovKeyframeType() {
    }

    @Override
    public Class<? extends KeyframeChange> keyframeChangeType() {
        return KeyframeChangeCameraFov.class;
    }

    @Override
    public @Nullable String icon() {
        return "\ue3af";
    }

    @Override
    public String name() {
        return I18n.get("flashback.keyframe.camera_fov");
    }

    @Override
    public String id() {
        return "CAMERA_FOV";
    }

    @Override
    public @Nullable CameraFovKeyframe createDirect() {
        return null;
    }

    @Override
    public KeyframeCreatePopup<CameraFovKeyframe> createPopup() {
        float[] fovInput = new float[]{Minecraft.getInstance().options.fov().get()};
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null && editorState.replayVisuals.cameraVisuals().overrideFov && editorState.replayVisuals.cameraVisuals().overrideFovAmount >= 0) {
            fovInput[0] = editorState.replayVisuals.cameraVisuals().overrideFovAmount;
        }

        return () -> {
            ImGui.sliderFloat(I18n.get("flashback.fov"), fovInput, 1f, 110f);
            if (ImGui.button(I18n.get("flashback.add")) || ReplayUI.consumeConfirm()) {
                return new CameraFovKeyframe(fovInput[0]);
            }
            ImGui.sameLine();
            if (ImGui.button(I18n.get("gui.cancel")) || ReplayUI.consumeCancel()) {
                ImGui.closeCurrentPopup();
            }
            return null;
        };
    }

}
