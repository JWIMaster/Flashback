package com.moulberry.flashback.record;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.configuration.ClientboundCodeOfConductPacket;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;

import java.util.Set;

public class IgnoredPacketSet {

    /**
     * The packets that only matter when recorded containers are being shown.
     *
     * <p>They are worth a lot of bytes - a slot update is sent every time a player moves an item -
     * so they are only written when {@code showGuisInReplays} asks for them, and a replay made
     * without that behaves and weighs what it always did.
     */
    private static final Set<Class<?>> CONTAINER_PACKETS = Set.of(
        ClientboundOpenScreenPacket.class,
        ClientboundContainerClosePacket.class,
        ClientboundContainerSetContentPacket.class,
        ClientboundContainerSetDataPacket.class,
        ClientboundContainerSetSlotPacket.class,
        ClientboundSetCursorItemPacket.class,
        ClientboundMerchantOffersPacket.class,
        ClientboundMountScreenOpenPacket.class
    );

    public static boolean isIgnored(Packet<?> packet) {
        if (CONTAINER_PACKETS.contains(packet.getClass())
                && !com.moulberry.flashback.gui.GuiPlayback.enabled()) {
            return true;
        }
        return IGNORED.contains(packet.getClass());
    }

    public static boolean isIgnoredInReplay(Packet<?> packet) {
        return IGNORED_IN_REPLAY.contains(packet.getClass());
    }

    private static final Set<Class<?>> IGNORED_IN_REPLAY = Set.of(
        ClientboundAwardStatsPacket.class,
        ClientboundRecipeBookAddPacket.class,
        ClientboundRecipeBookRemovePacket.class,
        ClientboundRecipeBookSettingsPacket.class,
        ClientboundUpdateRecipesPacket.class,
        ClientboundTransferPacket.class,
        ClientboundUpdateAdvancementsPacket.class,
        ClientboundClearDialogPacket.class,
        ClientboundShowDialogPacket.class,
        ClientboundTrackedWaypointPacket.class
    );

    /**
     * Packets that are not written to a replay at all.
     *
     * <p>Containers are deliberately absent: a replay is meant to show what the player saw, and what
     * they saw when they opened a chest was a chest. Those packets are recorded and then only applied
     * at playback if {@code showGuisInReplays} asks for it, so a replay made by someone who does not
     * want them still behaves as before.
     */
    private static final Set<Class<?>> IGNORED = Set.of(
        // Ignored because these are added directly by mixin/record/MixinClientLevel
        ClientboundLevelEventPacket.class,
        ClientboundSoundPacket.class,
        ClientboundSoundEntityPacket.class,

        // Common
        ClientboundStoreCookiePacket.class,
        ClientboundCustomReportDetailsPacket.class,
        ClientboundServerLinksPacket.class,
        ClientboundCookieRequestPacket.class,
        ClientboundDisconnectPacket.class,
        ClientboundPingPacket.class,
        ClientboundKeepAlivePacket.class,
        ClientboundTransferPacket.class,
        ClientboundClearDialogPacket.class,
        ClientboundShowDialogPacket.class,

        // Configuration
        ClientboundFinishConfigurationPacket.class,
        ClientboundCodeOfConductPacket.class,

        // Game
        ClientboundAwardStatsPacket.class,
        ClientboundRecipeBookAddPacket.class,
        ClientboundRecipeBookRemovePacket.class,
        ClientboundRecipeBookSettingsPacket.class,
        ClientboundOpenSignEditorPacket.class,
        ClientboundRotateHeadPacket.class,
        ClientboundMoveEntityPacket.Pos.class,
        ClientboundMoveEntityPacket.Rot.class,
        ClientboundMoveEntityPacket.PosRot.class,
        ClientboundPlayerPositionPacket.class,
        ClientboundPlayerChatPacket.class,
        ClientboundDeleteChatPacket.class,
        ClientboundMoveMinecartPacket.class,
        ClientboundForgetLevelChunkPacket.class,
        ClientboundPlayerAbilitiesPacket.class,
        ClientboundSetExperiencePacket.class,
        ClientboundSetHealthPacket.class,
        ClientboundSetPlayerInventoryPacket.class,
        ClientboundTickingStatePacket.class,
        ClientboundTickingStepPacket.class,
        ClientboundPlayerCombatEndPacket.class,
        ClientboundPlayerCombatEnterPacket.class,
        ClientboundPlayerCombatKillPacket.class,
        ClientboundSetCameraPacket.class,
        ClientboundCooldownPacket.class,
        ClientboundUpdateAdvancementsPacket.class,
        ClientboundSelectAdvancementsTabPacket.class,
        ClientboundPlaceGhostRecipePacket.class,
        ClientboundCommandsPacket.class,
        ClientboundCommandSuggestionsPacket.class,
        ClientboundUpdateRecipesPacket.class,
        ClientboundTagQueryPacket.class,
        ClientboundOpenBookPacket.class,
        ClientboundSetChunkCacheRadiusPacket.class,
        ClientboundSetSimulationDistancePacket.class,
        ClientboundSetChunkCacheCenterPacket.class,
        ClientboundBlockChangedAckPacket.class,
        ClientboundCustomChatCompletionsPacket.class,
        ClientboundStartConfigurationPacket.class,
        ClientboundChunkBatchStartPacket.class,
        ClientboundChunkBatchFinishedPacket.class,
        ClientboundDebugSamplePacket.class,
        ClientboundPongResponsePacket.class,
        ClientboundTestInstanceBlockStatus.class,
        ClientboundTrackedWaypointPacket.class,
        ClientboundDebugChunkValuePacket.class,
        ClientboundDebugBlockValuePacket.class,
        ClientboundDebugEntityValuePacket.class,
        ClientboundDebugEventPacket.class,
        ClientboundGameRuleValuesPacket.class,
        ClientboundLowDiskSpaceWarningPacket.class
    );

}
