package com.moulberry.flashback.gui;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.packet.FlashbackRemoteContainer;
import com.moulberry.flashback.record.Recorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundMountScreenOpenPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes what a recording cannot otherwise contain about the containers a player opens.
 *
 * <p>A replay is the packets the server sent, and three things about a container are not in them.
 *
 * <p>The first is the player's own inventory. Pressing E is a decision the client makes on its own,
 * so there is no open packet for it and a replay of somebody sorting their inventory showed them
 * standing still.
 *
 * <p>The second is closing anything at all. When a player presses Escape, the server runs
 * {@code ServerPlayer.doCloseContainer}, which restores the player's own menu and sends nothing -
 * only the server closing a container for its own reasons goes through {@code closeContainer} and
 * sends a packet. So a recording would hold the opening of every chest and the closing of none.
 *
 * <p>The third, and the least obvious, is everything the client predicted and the server therefore
 * never repeated. A click sends the server a hash of every slot the client now believes it has
 * ({@code ServerboundContainerClickPacket.changedSlots}), and the server records those as already
 * known, so {@code synchronizeSlotToRemote} sees them as matching and stays quiet. A crafting grid
 * is the clearest case: it is filled entirely by prediction, so a replay had the result - which the
 * server computes itself and therefore does have to send - and an empty grid beside it. This watches
 * each open container for the difference between what the server last said and what the client has,
 * and writes that difference, which is exactly what would otherwise be lost.
 *
 * <p>The same mirror lets a recording say what was open at each snapshot, so seeking into the middle
 * of a container's life still lands with it on screen. Without that, a replay could only show a
 * container from the moment the recording next opened one.
 */
public final class GuiRecording {

    /** The screen last reported as shown, so that a window resize is not taken for a new screen. */
    @Nullable
    private static Screen lastShown;

    /**
     * The open container's kind, remembered from the packet that opened it.
     *
     * <p>The client never needs to know a container's kind - it is told what screen to build - but a
     * recording has to be able to say it again after a seek, and this is where that comes from.
     */
    private static final Map<Integer, String> openTypes = new ConcurrentHashMap<>();

    /** The container being mirrored, and what the server last said was in it. */
    private static int mirroredId = -1;
    private static final List<ItemStack> mirrored = new ArrayList<>();
    private static ItemStack mirroredCarried = ItemStack.EMPTY;
    /** Set when a snapshot is written, so the next tick restates what is open. */
    private static volatile boolean restateOnTick;

    private GuiRecording() {
    }

    /** Forget everything, so a new recording starts with nothing open. */
    public static void reset() {
        lastShown = null;
        openTypes.clear();
        forgetMirror();
        restateOnTick = false;
    }

    /**
     * A packet was written to the recording, before it reaches the client.
     *
     * <p>This runs ahead of the client applying it, so what is remembered here is what the client is
     * about to have - which is what makes the difference in {@link #tick()} exactly the set of
     * changes the server did not describe.
     */
    public static void observePacket(Packet<?> packet) {
        if (mirroredId == -1 && openTypes.isEmpty() && !(packet instanceof ClientboundOpenScreenPacket)) {
            return;
        }
        try {
            if (packet instanceof ClientboundOpenScreenPacket open) {
                openTypes.put(open.getContainerId(), nameOf(open.getType()));
            } else if (packet instanceof ClientboundMountScreenOpenPacket mount) {
                openTypes.put(mount.getContainerId(),
                    FlashbackRemoteContainer.mountType(mount.getEntityId(), mount.getInventoryColumns()));
            } else if (packet instanceof ClientboundContainerClosePacket close) {
                openTypes.remove(close.getContainerId());
                if (close.getContainerId() == mirroredId) {
                    forgetMirror();
                }
            } else if (packet instanceof ClientboundContainerSetSlotPacket slot) {
                if (slot.getContainerId() == mirroredId && slot.getSlot() >= 0 && slot.getSlot() < mirrored.size()) {
                    mirrored.set(slot.getSlot(), slot.getItem().copy());
                }
            } else if (packet instanceof ClientboundContainerSetContentPacket content) {
                if (content.containerId() == mirroredId) {
                    fillMirror(content.items(), content.carriedItem());
                }
            }
        } catch (Throwable ignored) {
            // A mirror that falls behind costs a duplicate update, not a broken recording.
        }
    }

    /** A snapshot was just written, so the next tick should restate what is open. */
    public static void onSnapshot() {
        restateOnTick = true;
    }

    /** Once a tick, while a container screen is open. */
    public static void tick() {
        try {
            if (!recording()) {
                forgetMirror();
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            Screen screen = minecraft == null || minecraft.gui == null ? null : minecraft.gui.screen();
            if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
                forgetMirror();
                return;
            }

            AbstractContainerMenu menu = containerScreen.getMenu();
            if (menu.containerId != mirroredId) {
                // A container that was just opened is described by whoever opened it: the server's
                // own open packet, or the screen hook for the player's inventory.
                mirroredId = menu.containerId;
                fillMirror(menu.getItems(), menu.getCarried());
            }

            if (restateOnTick) {
                restateOnTick = false;
                restate(menu, containerScreen);
            }
            writeDifferences(menu);
        } catch (Throwable t) {
            Flashback.LOGGER.warn("Could not mirror the open container", t);
        }
    }

    /**
     * Restates the open container in full, so that a seek which starts after its opening packet
     * still finds it.
     */
    private static void restate(AbstractContainerMenu menu, AbstractContainerScreen<?> screen) {
        String menuType = typeOf(menu);
        if (menuType == null) {
            return;
        }
        write(FlashbackRemoteContainer.open(menu.containerId, menuType, screen.getTitle()));
        write(FlashbackRemoteContainer.content(menu.containerId, menu.getItems(), menu.getCarried()));
        fillMirror(menu.getItems(), menu.getCarried());
    }

    /**
     * Writes the crafting grid the client has and the server has not said.
     *
     * <p>Only the grid. It is the one part of a container that is filled entirely by prediction: a
     * click sends the server a hash of what the client now believes, the server files it as already
     * known, and the slots are never sent. Everything else in a container is the server's, and it
     * describes its own changes - writing this side's view of those as well would put the client's
     * guess where the server's answer belongs, which is exactly what makes a chest look wrong.
     *
     * <p>Recognised by the container behind the slot rather than by slot number, because a furnace
     * and a crafting table put the grid in different places and a hard-coded range would eventually
     * describe the wrong slot in one of them.
     */
    private static void writeDifferences(AbstractContainerMenu menu) {
        int slots = Math.min(menu.slots.size(), mirrored.size());
        for (int i = 0; i < slots; i++) {
            Slot slot = menu.getSlot(i);
            if (!(slot.container instanceof CraftingContainer)) {
                continue;
            }
            ItemStack now = slot.getItem();
            if (!ItemStack.matches(now, mirrored.get(i))) {
                mirrored.set(i, now.copy());
                // A copy, because the payload is not encoded until the end of the tick and the
                // stack it describes belongs to a live menu.
                write(FlashbackRemoteContainer.slot(menu.containerId, i, now.copy()));
                if (!now.isEmpty()) {
                }
            }
        }
    }

    /** The recorded kind of a container, or null when nothing has said what it is. */
    @Nullable
    private static String typeOf(AbstractContainerMenu menu) {
        if (menu instanceof InventoryMenu) {
            return FlashbackRemoteContainer.PLAYER_INVENTORY_TYPE;
        }
        return openTypes.get(menu.containerId);
    }

    private static void fillMirror(List<ItemStack> items, ItemStack carried) {
        mirrored.clear();
        for (ItemStack item : items) {
            mirrored.add(item.copy());
        }
        mirroredCarried = carried == null ? ItemStack.EMPTY : carried.copy();
    }

    private static void forgetMirror() {
        mirroredId = -1;
        mirrored.clear();
        mirroredCarried = ItemStack.EMPTY;
        restateOnTick = false;
    }

    private static String nameOf(MenuType<?> menuType) {
        Identifier id = BuiltInRegistries.MENU.getKey(menuType);
        return id == null ? "" : id.toString();
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
            write(FlashbackRemoteContainer.content(menu.containerId, menu.getItems(), menu.getCarried()));
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
            forgetMirror();
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

}
