package com.moulberry.flashback.keyframe.types;

import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraRotationOnly;
import com.moulberry.flashback.keyframe.impl.CameraRotationKeyframe;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import imgui.moulberry90.ImGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * The lane that turns a camera without moving it.
 *
 * <p>Its keyframes carry only angles, so it and {@link CameraPositionKeyframeType} can animate the
 * same camera at the same time and neither overwrites the other.
 */
public class CameraRotationKeyframeType implements KeyframeType<CameraRotationKeyframe> {

    public static final CameraRotationKeyframeType INSTANCE = new CameraRotationKeyframeType();

    private CameraRotationKeyframeType() {
    }

    @Override
    public Class<? extends KeyframeChange> keyframeChangeType() {
        return KeyframeChangeCameraRotationOnly.class;
    }

    @Override
    public @Nullable String icon() {
        return "\ue04b";
    }

    @Override
    public String name() {
        return I18n.get("flashback.keyframe.camera_rotation");
    }

    @Override
    public String id() {
        return "CAMERA_ROTATION";
    }

    /** The roll the editor is currently showing, so a new keyframe starts from what is on screen. */
    private static float defaultRoll() {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null && editorState.replayVisuals.cameraVisuals().overrideRoll) {
            return editorState.replayVisuals.cameraVisuals().overrideRollAmount;
        }
        return 0.0f;
    }

    @Override
    public @Nullable CameraRotationKeyframe createDirect() {
        Entity entity = Minecraft.getInstance().getCameraEntity();
        if (entity == null) {
            entity = Minecraft.getInstance().player;
        }
        if (entity == null) {
            return null;
        }
        return new CameraRotationKeyframe(entity.getYRot(), entity.getXRot(), defaultRoll());
    }

    @Override
    public KeyframeCreatePopup<CameraRotationKeyframe> createPopup() {
        float[] rotation = new float[3];
        Entity entity = Minecraft.getInstance().getCameraEntity();
        if (entity == null) {
            entity = Minecraft.getInstance().player;
        }
        if (entity != null) {
            rotation[0] = entity.getYRot();
            rotation[1] = entity.getXRot();
        }
        rotation[2] = defaultRoll();

        return () -> {
            float[] yaw = new float[]{rotation[0]};
            if (ImGuiHelper.inputFloat(I18n.get("flashback.yaw"), yaw)) {
                rotation[0] = yaw[0];
            }
            float[] pitch = new float[]{rotation[1]};
            if (ImGuiHelper.inputFloat(I18n.get("flashback.pitch"), pitch)) {
                rotation[1] = pitch[0];
            }
            float[] roll = new float[]{rotation[2]};
            if (ImGuiHelper.inputFloat(I18n.get("flashback.roll"), roll)) {
                rotation[2] = roll[0];
            }

            if (ImGui.button(I18n.get("flashback.add")) || ReplayUI.consumeConfirm()) {
                return new CameraRotationKeyframe(rotation[0], rotation[1], rotation[2]);
            }
            ImGui.sameLine();
            if (ImGui.button(I18n.get("gui.cancel")) || ReplayUI.consumeCancel()) {
                ImGui.closeCurrentPopup();
            }
            return null;
        };
    }

}
