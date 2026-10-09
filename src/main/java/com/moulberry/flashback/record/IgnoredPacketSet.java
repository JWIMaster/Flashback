package com.moulberry.flashback.record;

import com.moulberry.flashback.gui.GuiPlayback;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.configuration.ClientboundCodeOfConductPacket;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;

import java.util.Set;

public class IgnoredPacketSet {

    public static boolean isIgnored(Packet<?> packet) {
        return isIgnoredType(packet.getClass());
    }

    /**
     * Whether a packet of this kind is dropped instead of written to the replay.
     *
     * <p>Which packets are worth their bytes is a decision about the kind of packet and nothing else,
     * so it can be asked of the class without having one to hand.
     */
    public static boolean isIgnoredType(Class<?> packetClass) {
        if (CONTAINER_PACKETS.contains(packetClass) && !GuiPlayback.enabled()) {
            // Only worth their bytes when the containers they describe are going to be shown.
            return true;
        }
        return IGNORED.contains(packetClass);
    }

    public static boolean isIgnoredInReplay(Packet<?> packet) {
        return IGNORED_IN_REPLAY.contains(packet.getClass());
    }

    /** The kinds of packet that carry a container, and are therefore written only when asked for. */
    public static Set<Class<?>> containerPackets() {
        return CONTAINER_PACKETS;
    }

    /** The kinds of packet that are never written to a replay at all. */
    public static Set<Class<?>> alwaysIgnored() {
        return IGNORED;
    }

    /**
     * Packets describing a container being opened, used and closed.
     *
     * <p>They are worth a lot of bytes - a slot update is sent every time the recorded player moves
     * an item - so they are written only when {@code showGuisInReplays} asks for them, and a replay
     * made without that option behaves and weighs what it always did.
     *
     * <p>These must not also sit in {@link #IGNORED}: being in both means the second check wins and
     * nothing about containers is ever written, which is a silent way for the whole feature to do
     * nothing at all.
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

    /** Packets that are written to a replay but deliberately not applied when it plays. */
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
