package com.moulberry.flashback.keyframe.types;

import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeSpectate;
import com.moulberry.flashback.keyframe.impl.SpectateKeyframe;
import imgui.moulberry90.ImGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The animation lane of a spectate camera: keyframes say which player is watched.
 *
 * <p>Only spectate cameras own this lane. A normal camera is positioned by its own tracks, whereas a
 * spectate camera follows an entity, so the two never appear on the same camera.
 */
public class SpectateKeyframeType implements KeyframeType<SpectateKeyframe> {

    public static final SpectateKeyframeType INSTANCE = new SpectateKeyframeType();

    private SpectateKeyframeType() {
    }

    @Override
    public Class<? extends KeyframeChange> keyframeChangeType() {
        return KeyframeChangeSpectate.class;
    }

    @Override
    public @Nullable String icon() {
        return "\ue8f4";
    }

    @Override
    public String name() {
        return I18n.get("flashback.keyframe.spectate");
    }

    @Override
    public String id() {
        return "SPECTATE";
    }

    /** Which player is watched is discrete, so there is nothing to interpolate. */
    @Override
    public boolean allowChangingInterpolationType() {
        return false;
    }

    /**
     * Always null, so the player picker is shown.
     *
     * <p>Returning a keyframe directly would bypass the picker and silently follow the local player,
     * which is the replay viewer and therefore does nothing.
     */
    @Override
    public @Nullable SpectateKeyframe createDirect() {
        return null;
    }

    @Override
    public KeyframeCreatePopup<SpectateKeyframe> createPopup() {
        return () -> {
            List<Player> players = availablePlayers();
            if (players.isEmpty()) {
                ImGui.textUnformatted(I18n.get("flashback.no_players_available"));
            }
            for (Player player : players) {
                if (ImGui.selectable(player.getName().getString() + "##spectate_kf_" + player.getUUID(), false)) {
                    ImGui.closeCurrentPopup();
                    return new SpectateKeyframe(player.getUUID());
                }
            }

            ImGui.separator();
            if (ImGui.selectable(I18n.get("flashback.replay_viewpoint") + "##spectate_kf_self", false)) {
                ImGui.closeCurrentPopup();
                return new SpectateKeyframe(null);
            }

            if (ImGui.button(I18n.get("gui.cancel")) || ReplayUI.consumeCancel()) {
                ImGui.closeCurrentPopup();
            }
            return null;
        };
    }

    /** Players in the replay, name-ordered so the list is stable. */
    private static List<Player> availablePlayers() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return List.of();
        }
        List<Player> players = new ArrayList<>();
        for (Player player : level.players()) {
            if (player != null) {
                players.add(player);
            }
        }
        players.sort(Comparator.comparing(p -> p.getName().getString()));
        return players;
    }

}
