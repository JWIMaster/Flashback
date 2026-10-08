package com.moulberry.flashback.state;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Turns a camera or player UUID into something showable in the timeline and keyframe editor.
 */
public final class CameraSourceDisplay {

    private CameraSourceDisplay() {
    }

    /**
     * Names whatever source this id refers to: a spectate object's tracked player, a camera's name,
     * or a clear "missing" marker.
     */
    public static String describeSource(@Nullable UUID sourceId) {
        if (sourceId == null) {
            return "No source";
        }
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null) {
            // A camera and its sub-parts (a timelapse) share an id, so take the name from a viewpoint
            // track wherever there is one: a sub-part's own name describes the sub-part, not the
            // camera it hangs off, and would otherwise depend on the order the tracks happen to be in.
            KeyframeTrack best = null;
            for (KeyframeTrack track : editorState.currentSceneTracks()) {
                if (!sourceId.equals(track.cameraId)) {
                    continue;
                }
                if (best == null || (NamedCamera.isViewpointTrack(track) && !NamedCamera.isViewpointTrack(best))) {
                    best = track;
                }
            }

            if (best != null) {
                // An explicit timeline name always wins, for any source type.
                if (best.customName != null && !best.customName.isBlank()) {
                    return best.customName;
                }

                if (best.keyframeType instanceof com.moulberry.flashback.keyframe.types.SpectateKeyframeType) {
                    // A spectate object's own keyframes say who is watched, so show that.
                    Object first = best.keyframesByTick.isEmpty() ? null
                        : best.keyframesByTick.firstEntry().getValue();
                    if (first instanceof com.moulberry.flashback.keyframe.impl.SpectateKeyframe spectate) {
                        return describeSpectate(spectate.target);
                    }
                    return "Spectate";
                }
            }
        }

        // A name typed on the timeline wins, so renaming a camera's track renames the camera
        // everywhere - otherwise the switch would keep showing a generated "Camera 4" that matches
        // nothing.
        return describeCamera(sourceId);
    }

    public static String describeCamera(@Nullable UUID cameraId) {
        if (cameraId == null) {
            return "Camera";
        }
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null && editorState.cameras != null) {
            for (NamedCamera camera : editorState.cameras) {
                if (camera.id.equals(cameraId)) {
                    return camera.name;
                }
            }
        }
        return "Missing camera";
    }

    public static String describeSpectate(@Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return "Spectate";
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            Entity entity = level.getEntities().get(playerUuid);
            if (entity != null) {
                return "Spectate: " + entity.getName().getString();
            }
            PlayerInfo info = Minecraft.getInstance().getConnection() != null
                ? Minecraft.getInstance().getConnection().getPlayerInfo(playerUuid)
                : null;
            if (info != null) {
                return "Spectate: " + info.getProfile().name();
            }
        }
        return "Spectate: (unknown)";
    }

}
