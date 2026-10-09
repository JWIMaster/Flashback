package com.moulberry.flashback.keyframe.types;

import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraPositionOnly;
import com.moulberry.flashback.keyframe.impl.CameraPositionKeyframe;
import imgui.moulberry90.ImGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * The lane that moves a camera without turning it.
 *
 * <p>Its keyframes carry only a position, so it and {@link CameraRotationKeyframeType} can animate
 * the same camera at the same time and neither overwrites the other.
 */
public class CameraPositionKeyframeType implements KeyframeType<CameraPositionKeyframe> {

    public static final CameraPositionKeyframeType INSTANCE = new CameraPositionKeyframeType();

    private CameraPositionKeyframeType() {
    }

    @Override
    public Class<? extends KeyframeChange> keyframeChangeType() {
        return KeyframeChangeCameraPositionOnly.class;
    }

    @Override
    public @Nullable String icon() {
        return "\ue04b";
    }

    @Override
    public String name() {
        return I18n.get("flashback.keyframe.camera_position");
    }

    @Override
    public String id() {
        return "CAMERA_POSITION";
    }

    @Override
    public @Nullable CameraPositionKeyframe createDirect() {
        Entity entity = Minecraft.getInstance().getCameraEntity();
        if (entity == null) {
            entity = Minecraft.getInstance().player;
        }
        if (entity == null) {
            return null;
        }
        return new CameraPositionKeyframe(new Vector3d(entity.getX(), entity.getY(), entity.getZ()));
    }

    @Override
    public KeyframeCreatePopup<CameraPositionKeyframe> createPopup() {
        float[] position = new float[3];
        Entity entity = Minecraft.getInstance().getCameraEntity();
        if (entity == null) {
            entity = Minecraft.getInstance().player;
        }
        if (entity != null) {
            position[0] = (float) entity.getX();
            position[1] = (float) entity.getY();
            position[2] = (float) entity.getZ();
        }

        return () -> {
            ImGuiHelper.inputFloat(I18n.get("flashback.position"), position);
            if (ImGui.button(I18n.get("flashback.add")) || ReplayUI.consumeConfirm()) {
                return new CameraPositionKeyframe(new Vector3d(position[0], position[1], position[2]));
            }
            ImGui.sameLine();
            if (ImGui.button(I18n.get("gui.cancel")) || ReplayUI.consumeCancel()) {
                ImGui.closeCurrentPopup();
            }
            return null;
        };
    }

}
