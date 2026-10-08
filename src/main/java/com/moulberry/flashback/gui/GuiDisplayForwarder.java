package com.moulberry.flashback.gui;

import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.playback.ReplayPlayer;
import com.moulberry.flashback.packet.FlashbackRemoteContainer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Sends a recorded container to the client watching the replay.
 *
 * <p>This runs on the replay server's thread, where the recorded player's container lives, and does
 * nothing but describe it: no screen is built here, because a screen belongs to the client and the
 * client's menus belong to the viewer. The client draws it from what it is sent.
 */
public final class GuiDisplayForwarder {

    private GuiDisplayForwarder() {
    }

    /** A container was opened. */
    public static void open(ReplayServer server, int containerId, MenuType<?> menuType, Component title) {
        send(server, containerId, menuType, title, null, null, false);
    }

    /** The whole contents of a container were sent. */
    public static void update(ReplayServer server, int containerId, List<ItemStack> items, ItemStack carried) {
        send(server, containerId, null, null, copyOf(items), carried.copy(), false);
    }

    /** One slot of a container changed. */
    public static void slot(ReplayServer server, int containerId, int slot, ItemStack item) {
        if (!GuiPlayback.shouldShow()) {
            return;
        }
        ServerPlayer viewer = recordedPlayer(server);
        if (viewer == null || viewer.containerMenu.containerId != containerId) {
            return;
        }
        List<ItemStack> items = copyOf(viewer.containerMenu.getItems());
        if (slot < 0 || slot >= items.size()) {
            return;
        }
        items.set(slot, item.copy());

        // The container's own kind is not repeated for an update; the client already has it open.
        for (ReplayPlayer replayViewer : server.getReplayViewers()) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(replayViewer,
                new FlashbackRemoteContainer(containerId, "", Component.empty(), items,
                    viewer.containerMenu.getCarried().copy(), false));
        }
    }

    /** A container was closed. */
    public static void close(ReplayServer server, int containerId) {
        if (!GuiPlayback.shouldShow()) {
            return;
        }
        for (ReplayPlayer replayViewer : server.getReplayViewers()) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(replayViewer,
                new FlashbackRemoteContainer(containerId, "", Component.empty(), List.of(),
                    ItemStack.EMPTY, true));
        }
    }

    private static void send(ReplayServer server, int containerId, @Nullable MenuType<?> menuType,
                             @Nullable Component title, @Nullable List<ItemStack> items,
                             @Nullable ItemStack carried, boolean closed) {
        if (!GuiPlayback.shouldShow()) {
            return;
        }
        ServerPlayer viewer = recordedPlayer(server);
        if (viewer == null) {
            return;
        }

        // Whatever was not given is taken from the recorded player's own container, which is the
        // authority for what was on screen.
        AbstractContainerMenu menu = viewer.containerMenu;
        String typeId = menuType == null ? "" : nameOf(menuType);
        Component menuTitle = title == null ? Component.empty() : title;
        List<ItemStack> contents = items != null ? items : copyOf(menu.getItems());
        ItemStack cursor = carried != null ? carried : menu.getCarried().copy();

        FlashbackRemoteContainer payload = new FlashbackRemoteContainer(containerId, typeId, menuTitle,
            contents, cursor, closed);
        for (ReplayPlayer replayViewer : server.getReplayViewers()) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(replayViewer, payload);
        }
    }

    /** The player being recorded, as the replay server sees them. */
    @Nullable
    private static ServerPlayer recordedPlayer(ReplayServer server) {
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                return player;
            }
        } catch (Throwable ignored) {
            // No player yet; nothing to describe.
        }
        return null;
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
