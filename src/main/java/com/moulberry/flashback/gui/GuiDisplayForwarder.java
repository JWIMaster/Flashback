package com.moulberry.flashback.gui;

import com.moulberry.flashback.packet.FlashbackRemoteContainer;
import com.moulberry.flashback.playback.ReplayPlayer;
import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes the recorded player's container to whoever is watching the replay.
 *
 * <p>This runs on the replay server's thread, where the recorded packets are applied, and it reads
 * nothing else: the recording is the authority for what the player had open, so the packets are the
 * only input. In particular it does not look at any live player's menu - the viewer is not the
 * recorded player, and the recorded player's own menu is not driven by the replay's packets.
 */
public final class GuiDisplayForwarder {

    /**
     * The container the recording says is open, or -1.
     *
     * <p>Kept so that a container nobody is looking at is not described: a recorded player's own
     * inventory produces content packets continuously, and forwarding them all for a screen that was
     * never opened would be most of the traffic for nothing.
     */
    private static int openContainerId = -1;

    private GuiDisplayForwarder() {
    }

    /** A container was opened. */
    public static void open(ReplayServer server, int containerId, MenuType<?> menuType, Component title) {
        openWithType(server, containerId, menuType == null ? "" : nameOf(menuType), title);
    }

    /** A mount's inventory was opened, which has an entity rather than a menu type. */
    public static void openMount(ReplayServer server, int containerId, int entityId, int columns, Component title) {
        openWithType(server, containerId, FlashbackRemoteContainer.mountType(entityId, columns), title);
    }

    private static void openWithType(ReplayServer server, int containerId, String menuType, Component title) {
        if (!GuiPlayback.shouldShow()) {
            return;
        }
        openContainerId = containerId;
        send(server, FlashbackRemoteContainer.open(containerId, menuType, title));
    }

    /** The whole contents of the open container were sent. */
    public static void content(ReplayServer server, int containerId, List<ItemStack> items, ItemStack carried) {
        if (!isOpen(containerId)) {
            return;
        }
        send(server, FlashbackRemoteContainer.content(containerId, copyOf(items), carried.copy()));
    }

    /** One slot of the open container changed. */
    public static void slot(ReplayServer server, int containerId, int slot, ItemStack item) {
        if (!isOpen(containerId)) {
            return;
        }
        send(server, FlashbackRemoteContainer.slot(containerId, slot, item.copy()));
    }

    /** The stack on the recorded player's cursor changed. */
    public static void carried(ReplayServer server, ItemStack carried) {
        if (openContainerId == -1) {
            return;
        }
        send(server, FlashbackRemoteContainer.carried(openContainerId, carried.copy()));
    }

    /** A container was closed. */
    public static void close(ReplayServer server, int containerId) {
        if (openContainerId == containerId) {
            openContainerId = -1;
        }
        if (!GuiPlayback.shouldShow()) {
            return;
        }
        send(server, FlashbackRemoteContainer.close(containerId));
    }

    /**
     * Nobody is looking at any container any more.
     *
     * <p>Called when the recording jumps, because a jump forwards skips the packets that would have
     * closed what was open, and when a replay stops. The client has no other way to learn that the
     * container it is drawing belongs to a part of the recording that is no longer playing.
     */
    public static void reset(ReplayServer server) {
        openContainerId = -1;
        send(server, FlashbackRemoteContainer.reset());
    }

    /** True when the recording says this container is the one on screen. */
    public static boolean isOpen(int containerId) {
        return openContainerId == containerId && GuiPlayback.shouldShow();
    }

    /**
     * Notes a container change the recording wrote itself rather than one the server sent.
     *
     * <p>The recording writes the openings the server never hears about - the player's own inventory
     * - and every closing, because the server sends nothing when a player presses Escape. Those reach
     * the client along a different path, so this is where the replay keeps its own idea of what is
     * open in step with them.
     */
    public static void observe(FlashbackRemoteContainer container) {
        switch (container.kind()) {
            case OPEN -> openContainerId = container.containerId();
            case CLOSE -> {
                if (openContainerId == container.containerId()) {
                    openContainerId = -1;
                }
            }
            case RESET -> openContainerId = -1;
            default -> {
            }
        }
    }

    private static void send(ReplayServer server, FlashbackRemoteContainer payload) {
        if (server == null) {
            return;
        }
        for (ReplayPlayer replayViewer : server.getReplayViewers()) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(replayViewer, payload);
        }
    }

    private static String nameOf(MenuType<?> menuType) {
        Identifier id = BuiltInRegistries.MENU.getKey(menuType);
        return id == null ? "" : id.toString();
    }

    private static List<ItemStack> copyOf(List<ItemStack> items) {
        List<ItemStack> copy = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            copy.add(item.copy());
        }
        return copy;
    }

}
