package com.moulberry.flashback.state;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Turns a camera or player id into something showable on the timeline and in the keyframe editor.
 *
 * <p>The timeline is where the user reads the structure of their project, so a row or a switch has
 * to say what it names rather than showing a bare UUID.
 */
public final class CameraSourceDisplay {

    private CameraSourceDisplay() {
    }

    /** The name of a camera, or a clear marker when it no longer exists. */
    public static String describeCamera(@Nullable EditorScene scene, @Nullable UUID cameraId) {
        if (scene == null) {
            return I18n.get("flashback.camera");
        }
        EditorCamera camera = scene.cameraById(cameraId);
        if (camera == null) {
            return I18n.get("flashback.missing_camera");
        }
        return scene.displayNameOf(camera);
    }

    /** The name of the player a spectate source follows. */
    public static String describePlayer(@Nullable UUID playerId) {
        if (playerId == null) {
            return I18n.get("flashback.replay_viewpoint");
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            Entity entity = level.getEntities().get(playerId);
            if (entity != null) {
                return entity.getName().getString();
            }
        }

        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            PlayerInfo info = connection.getPlayerInfo(playerId);
            if (info != null) {
                return info.getProfile().name();
            }
        }

        return I18n.get("flashback.unknown_player");
    }

}
