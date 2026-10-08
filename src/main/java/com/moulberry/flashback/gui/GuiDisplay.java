package com.moulberry.flashback.gui;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.packet.FlashbackRemoteContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Shows a recorded container on the client that is watching the replay.
 *
 * <p>This is the only side that may build a screen. The replay's own packet handlers run on its
 * server thread and know nothing about the viewer's windows, so they forward the container here and
 * this turns it into something to look at.
 *
 * <p>The screen is built from the recorded menu type, which means the game draws the real chest,
 * crafting table or furnace; the contents are then placed into it. A recording that does not name a
 * type, or names one this game does not have, is not shown rather than shown wrongly.
 */
public final class GuiDisplay {

    /** The container currently on screen, so an update can find it again. */
    private static int shownContainerId = -1;

    private GuiDisplay() {
    }

    /** Called on the client thread when the replay says a container changed. */
    public static void handle(FlashbackRemoteContainer container) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        if (!GuiPlayback.shouldShow()) {
            closeIfShown(minecraft);
            return;
        }

        if (container.closed()) {
            closeIfShown(minecraft);
            return;
        }

        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        AbstractContainerMenu menu = shownContainerId == container.containerId()
            ? player.containerMenu
            : openScreen(minecraft, player, container);
        if (menu == null || menu.containerId != container.containerId()) {
            return;
        }

        // The contents the recording saw, placed into the screen that was just built.
        try {
            List<ItemStack> items = container.items();
            if (!items.isEmpty() && items.size() == menu.slots.size()) {
                menu.initializeContents(container.containerId(), items, container.carried());
            } else if (items.size() == menu.slots.size()) {
                menu.setCarried(container.carried());
            }
        } catch (Throwable t) {
            Flashback.LOGGER.warn("Could not fill the recorded container on screen", t);
        }
    }

    /** Builds and shows the screen for a recorded container. */
    @Nullable
    private static AbstractContainerMenu openScreen(Minecraft minecraft, LocalPlayer player,
                                                    FlashbackRemoteContainer container) {
        closeIfShown(minecraft);

        Identifier id = Identifier.tryParse(container.menuType() == null ? "" : container.menuType());
        if (id == null) {
            return null;
        }
        MenuType<?> menuType = BuiltInRegistries.MENU.getValue(id);
        if (menuType == null) {
            // A container this game does not have: showing nothing beats showing the wrong thing.
            return null;
        }

        try {
            MenuScreens.create(castMenuType(menuType), minecraft, container.containerId(), container.title());
            shownContainerId = container.containerId();
            return player.containerMenu;
        } catch (Throwable t) {
            Flashback.LOGGER.warn("Could not show the recorded container {}", id, t);
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static MenuType castMenuType(MenuType<?> menuType) {
        return (MenuType) menuType;
    }

    /**
     * Takes down a screen this put up, and only one this put up.
     *
     * <p>The open screen is not read back from the client - this version does not expose it - so what
     * is remembered is whether a container was shown here at all.
     */
    private static void closeIfShown(Minecraft minecraft) {
        if (shownContainerId == -1) {
            return;
        }
        shownContainerId = -1;
        minecraft.setScreenAndShow(null);
    }

}
