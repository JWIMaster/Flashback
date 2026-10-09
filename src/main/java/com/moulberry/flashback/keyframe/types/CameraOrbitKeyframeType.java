package com.moulberry.flashback.keyframe.types;

import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraPosition;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraPositionOrbit;
import com.moulberry.flashback.keyframe.handler.KeyframeHandler;
import com.moulberry.flashback.keyframe.impl.CameraKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraOrbitKeyframe;
import com.moulberry.flashback.state.CameraSourceDisplay;
import net.minecraft.world.entity.player.Player;
import java.util.UUID;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.type.ImBoolean;
import imgui.moulberry90.type.ImFloat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3f;

public class CameraOrbitKeyframeType implements KeyframeType<CameraOrbitKeyframe> {

    public static CameraOrbitKeyframeType INSTANCE = new CameraOrbitKeyframeType();

    private CameraOrbitKeyframeType() {
    }

    @Override
    public Class<? extends KeyframeChange> keyframeChangeType() {
        return KeyframeChangeCameraPositionOrbit.class;
    }

    @Override
    public @Nullable String icon() {
        return "\ue577";
    }

    @Override
    public String name() {
        return I18n.get("flashback.keyframe.camera_orbit");
    }

    @Override
    public String id() {
        return "CAMERA_ORBIT";
    }

    @Override
    public @Nullable CameraOrbitKeyframe createDirect() {
        return null;
    }

    @Override
    public KeyframeCreatePopup<CameraOrbitKeyframe> createPopup() {
       float[] cameraOrbitCenter = new float[]{0.0f, 0.0f, 0.0f};
       float[] cameraOrbitDistance = new float[]{8.0f};
       float[] cameraOrbitYaw = new float[]{0.0f};
       float[] cameraOrbitPitch = new float[]{0.0f};

       // Centre on the player by default: an orbit is nearly always "turn around them", and a point
       // captured while the replay happens to be parked is rarely what was wanted. Every fallback
       // here exists so a new orbit is never centred on the world origin, which is what makes an
       // orbit look broken when the subject cannot be found.
       Vec3 subject = null;
       LocalPlayer player = Minecraft.getInstance().player;
       if (player != null) {
           subject = player.getEyePosition();
       } else {
           Entity cameraEntity = Minecraft.getInstance().getCameraEntity();
           if (cameraEntity != null) {
               subject = cameraEntity.getEyePosition();
           }
       }
       if (subject == null) {
           // Nothing identifiable to turn around, so start from wherever the view is looking from.
           subject = Minecraft.getInstance().gameRenderer.mainCamera().position();
       }
       cameraOrbitCenter[0] = (float) subject.x;
       cameraOrbitCenter[1] = (float) subject.y;
       cameraOrbitCenter[2] = (float) subject.z;
       ImBoolean centreOnTarget = new ImBoolean(true);
        UUID[] selectedTarget = {null};
        // A new orbit trails its subject by default: that is the softer look the option exists for.
        ImBoolean lagBehind = new ImBoolean(true);
        float[] cameraOrbitLag = new float[]{0.35f};

        return () -> {
            ImGui.checkbox(I18n.get("flashback.orbit_centre_on_player"), centreOnTarget);
            ImGuiHelper.tooltip(I18n.get("flashback.orbit_centre_on_player_hint"));
            if (centreOnTarget.get()) {
                if (ImGui.beginCombo(I18n.get("flashback.keyframe.spectate"),
                    selectedTarget[0] == null ? I18n.get("flashback.no_players_available") : CameraSourceDisplay.describePlayer(selectedTarget[0]))) {
                    for (Player playerOption : SpectateKeyframeType.availablePlayers()) {
                        if (playerOption != Minecraft.getInstance().player &&
                            ImGui.selectable(playerOption.getName().getString() + "##orbit_new_" + playerOption.getUUID(),
                                playerOption.getUUID().equals(selectedTarget[0]))) {
                            selectedTarget[0] = playerOption.getUUID();
                            Vec3 eye = playerOption.getEyePosition();
                            cameraOrbitCenter[0] = (float) eye.x;
                            cameraOrbitCenter[1] = (float) eye.y;
                            cameraOrbitCenter[2] = (float) eye.z;
                        }
                    }
                    ImGui.endCombo();
                }
            } else {
                ImGuiHelper.inputFloat(I18n.get("flashback.position"), cameraOrbitCenter);
            }
            ImGuiHelper.inputFloat(I18n.get("flashback.distance"), cameraOrbitDistance);
            ImGuiHelper.inputFloat(I18n.get("flashback.yaw"), cameraOrbitYaw);
            ImGuiHelper.inputFloat(I18n.get("flashback.pitch"), cameraOrbitPitch);
            if (centreOnTarget.get()) {
                ImGui.checkbox(I18n.get("flashback.orbit_lag_behind"), lagBehind);
                ImGuiHelper.tooltip(I18n.get("flashback.orbit_lag_behind_hint"));
                if (lagBehind.get()) {
                    ImGuiHelper.inputFloat(I18n.get("flashback.orbit_lag_seconds"), cameraOrbitLag);
                }
            }

            boolean needsTarget = centreOnTarget.get() && selectedTarget[0] == null;
            if (needsTarget) ImGui.beginDisabled();
            boolean add = ImGui.button(I18n.get("flashback.add"));
            if (needsTarget) ImGui.endDisabled();
            if (!needsTarget && (add || ReplayUI.consumeConfirm())) {
                Vector3d center = new Vector3d(cameraOrbitCenter[0], cameraOrbitCenter[1], cameraOrbitCenter[2]);
                return new CameraOrbitKeyframe(center, cameraOrbitDistance[0], cameraOrbitYaw[0], cameraOrbitPitch[0],
                    InterpolationType.getDefault(), centreOnTarget.get(), centreOnTarget.get() ? selectedTarget[0] : null,
                    centreOnTarget.get() && lagBehind.get(), Math.max(0.0f, Math.min(2.0f, cameraOrbitLag[0])));
            }
            ImGui.sameLine();
            if (ImGui.button(I18n.get("gui.cancel")) || ReplayUI.consumeCancel()) {
                ImGui.closeCurrentPopup();
            }
            return null;
        };
    }
}
