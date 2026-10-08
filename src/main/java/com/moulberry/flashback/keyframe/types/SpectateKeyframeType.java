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
 * A player viewpoint as a timeline object.
 *
 * <p>Add this track when you want a player's view to be one of the things the camera switch can cut
 * to. The track's keyframes say who is being watched; the switch decides when this source is live.
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
        return I18n.get("flashback.spectate");
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
     * <p>Returning a keyframe here would bypass the popup entirely (the timeline only opens it when
     * this is null) and silently target the local player - which is the replay viewer, and
     * spectating yourself does nothing. That looked like "cannot choose a player" and "the switch
     * does not work" at the same time.
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
            if (ImGui.selectable(I18n.get("flashback.stop_spectating") + "##spectate_kf_stop")) {
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
