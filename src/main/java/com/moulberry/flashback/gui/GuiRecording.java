package com.moulberry.flashback.gui;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.packet.FlashbackRemoteContainer;
import com.moulberry.flashback.record.Recorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes the parts of a container's life that the server never sends a packet for.
 *
 * <p>A replay is the packets the server sent, and two things a player does with a container are not
 * among them.
 *
 * <p>The first is the player's own inventory. Pressing E is a decision the client makes on its own,
 * so there is no open packet for it and the recording never mentioned it; a replay of somebody
 * sorting their inventory showed them standing still.
 *
 * <p>The second is closing anything at all. When a player presses Escape, the server runs
 * {@code ServerPlayer.doCloseContainer}, which puts the player's own menu back and sends nothing -
 * only the server closing a container for its own reasons goes through {@code closeContainer} and
 * sends a packet. So a recording would hold the opening of every chest and the closing of none,
 * which is why a replayed chest sat on screen until the recording ran out.
 *
 * <p>Both are written as payloads of the kind the playback server sends the client, because that is
 * what they are: descriptions of a container change, in the recording's own order, that only the
 * client can act on.
 */
public final class GuiRecording {

    /** The screen last reported as shown, so that a window resize is not taken for a new screen. */
    @Nullable
    private static Screen lastShown;

    private GuiRecording() {
    }

    /** Forget which screen was last seen, so a new recording starts with nothing open. */
    public static void reset() {
        lastShown = null;
    }

    /**
     * A screen was shown.
     *
     * <p>Hooked on {@code Screen.added} rather than on {@code Screen.init} on purpose: a container
     * screen overrides {@code init}, and an injection into a method a subclass overrides never runs
     * for that subclass, which is a silent way for this to do nothing at all.
     */
    public static void screenShown(Screen screen) {
        if (lastShown == screen) {
            return;
        }
        lastShown = screen;

        try {
            AbstractContainerMenu menu = ownInventory(screen);
            if (menu == null) {
                return;
            }

            // Only the inventory needs describing here. Every other container was opened by the
            // server, which sent a packet saying so, and the recording of that packet is the better
            // account of it: it names the container's kind, which the client cannot work out.
            write(FlashbackRemoteContainer.open(menu.containerId, FlashbackRemoteContainer.PLAYER_INVENTORY_TYPE,
                screen.getTitle()));
            write(FlashbackRemoteContainer.content(menu.containerId, copyOf(menu.getItems()), menu.getCarried()));
        } catch (Throwable t) {
            Flashback.LOGGER.warn("Could not record the screen {}", screen.getClass().getName(), t);
        }
    }

    /**
     * A container screen was dismissed.
     *
     * <p>Every container screen, not just the inventory: closing a chest is the case the recording
     * would otherwise miss entirely, and the same close serves both. Hooked on the container screen's
     * own {@code removed}, which is the one that runs - the one on {@code Screen} is overridden and
     * never called for a container.
     */
    public static void screenClosed(Screen screen) {
        if (lastShown == screen) {
            lastShown = null;
        }

        try {
            if (!recording() || !(screen instanceof AbstractContainerScreen<?> containerScreen)) {
                return;
            }
            write(FlashbackRemoteContainer.close(containerScreen.getMenu().containerId));
        } catch (Throwable t) {
            Flashback.LOGGER.warn("Could not record the screen closing", t);
        }
    }

    /**
     * The menu behind a screen the recording has to describe itself, or null.
     *
     * <p>Only the player's own inventory qualifies. Everything else that shows items was opened by
     * the server, which sent a packet for it, and describing it here as well would say the same
     * thing twice.
     */
    @Nullable
    private static AbstractContainerMenu ownInventory(Screen screen) {
        if (!recording() || !(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return null;
        }
        AbstractContainerMenu menu = containerScreen.getMenu();
        return menu instanceof InventoryMenu ? menu : null;
    }

    /** Whether there is a recording that wants the screens a player opens. */
    private static boolean recording() {
        if (Flashback.isInReplay()) {
            // Every screen opened while watching a replay belongs to the viewer, not the recording.
            return false;
        }
        if (!GuiPlayback.enabled()) {
            return false;
        }
        Recorder recorder = Flashback.RECORDER;
        if (recorder == null || recorder.isPaused() || !recorder.readyToWrite()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.player != null;
    }

    private static void write(FlashbackRemoteContainer payload) {
        Recorder recorder = Flashback.RECORDER;
        if (recorder == null) {
            return;
        }
        recorder.writePacketAsync(new ClientboundCustomPayloadPacket(payload), ConnectionProtocol.PLAY);
    }

    private static List<ItemStack> copyOf(List<ItemStack> items) {
        List<ItemStack> copy = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            copy.add(item.copy());
        }
        return copy;
    }

}
