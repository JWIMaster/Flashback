package com.moulberry.flashback.packet;

import com.moulberry.flashback.Flashback;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.List;

/**
 * One change to a container the recorded player had open.
 *
 * <p>A replay's packets are applied on its own server thread, so the container cannot be opened
 * there: a screen belongs to the client, and the client's menus belong to the viewer rather than to
 * the player being recorded. The recording server describes what changed and the watching client
 * decides what to draw, which is the same shape of solution the recorded player's held item uses.
 *
 * <p>This is a description of a change rather than of a container, because a slot update is a single
 * stack and sending the whole container for one would put a chest's worth of items on the wire every
 * time the recorded player moved something.
 *
 * @param containerId the recorded container's id, which is what ties the changes together
 * @param kind        which change this is, and therefore which of the fields below mean anything
 * @param menuType    the container's kind, on {@link Kind#OPEN} only
 * @param title       the container's title, on {@link Kind#OPEN} only
 * @param slot        the slot index, on {@link Kind#SLOT} only
 * @param item        the stack in that slot, on {@link Kind#SLOT} only
 * @param items       the whole contents, on {@link Kind#CONTENT} only
 * @param carried     the stack on the cursor, on {@link Kind#OPEN}, {@link Kind#CONTENT} and
 *                    {@link Kind#CARRIED}
 * @param offers      a merchant's trades, on {@link Kind#OFFERS} only
 * @param villagerLevel the merchant's level, on {@link Kind#OFFERS} only
 * @param villagerXp  the merchant's experience, on {@link Kind#OFFERS} only
 * @param showProgress whether the merchant's progress bar is drawn, on {@link Kind#OFFERS} only
 * @param canRestock  whether the merchant can restock, on {@link Kind#OFFERS} only
 */
public record FlashbackRemoteContainer(int containerId, Kind kind, String menuType, Component title,
                                       int slot, ItemStack item, List<ItemStack> items, ItemStack carried,
                                       MerchantOffers offers, int villagerLevel, int villagerXp,
                                       boolean showProgress, boolean canRestock)
        implements CustomPacketPayload {

    /**
     * The menu type id used for the player's own inventory.
     *
     * <p>Opening it is not a server event - the client does it on its own - so a recording has no
     * packet to carry it and the menu has no {@code MenuType} to name. The recording writes this
     * instead, and the client knows what it means.
     */
    public static final String PLAYER_INVENTORY_TYPE = "flashback:player_inventory";

    /**
     * The menu type id used for a mount's inventory, which carries the mount and its width.
     *
     * <p>A mount has no menu type either - the client builds its menu from the entity the packet
     * names - so the recording writes the entity's id and the column count instead, and the client
     * makes the same menu the game would have.
     */
    public static final String MOUNT_TYPE_PREFIX = "flashback:mount/";

    public static String mountType(int entityId, int columns) {
        return MOUNT_TYPE_PREFIX + entityId + "/" + columns;
    }

    /** The entity id a mount menu type names, or -1 when this is not one. */
    public static int mountEntityId(String menuType) {
        return mountPart(menuType, 0);
    }

    /** The column count a mount menu type names, or 0 when this is not one. */
    public static int mountColumns(String menuType) {
        return mountPart(menuType, 1);
    }

    private static int mountPart(String menuType, int index) {
        if (menuType == null || !menuType.startsWith(MOUNT_TYPE_PREFIX)) {
            return index == 0 ? -1 : 0;
        }
        String[] parts = menuType.substring(MOUNT_TYPE_PREFIX.length()).split("/");
        if (parts.length != 2) {
            return index == 0 ? -1 : 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return index == 0 ? -1 : 0;
        }
    }

    /** What a payload is describing. */
    public enum Kind {
        /** A container was opened. Its contents follow in a {@link #CONTENT}. */
        OPEN,
        /** The whole contents of the open container. */
        CONTENT,
        /** A single slot of the open container. */
        SLOT,
        /** The stack on the cursor. */
        CARRIED,
        /** The container was closed. */
        CLOSE,
        /**
         * Whatever is open is not open any more because the recording jumped.
         *
         * <p>Seeking forwards skips the packets that would have closed a container, so the recording
         * has to say "forget it" rather than rely on a close that will never be played.
         */
        RESET,
        /**
         * A merchant's trades.
         *
         * <p>The trades are not the container's contents: a villager's menu has one slot per trade
         * side, and the rows the screen draws come from the menu's offers rather than from any slot.
         * So the contents packets never carry them and this has to.
         *
         * <p>Appended after {@link #RESET} on purpose: the ordinal is what goes on the wire, so
         * inserting anywhere earlier would silently reinterpret every recording already written.
         */
        OFFERS
    }

    public static final Type<FlashbackRemoteContainer> TYPE =
        new Type<>(Flashback.createIdentifier("remote_container"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FlashbackRemoteContainer> STREAM_CODEC =
        new ContainerStreamCodec();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static FlashbackRemoteContainer open(int containerId, String menuType, Component title) {
        return new FlashbackRemoteContainer(containerId, Kind.OPEN, menuType, title, 0, ItemStack.EMPTY,
            List.of(), ItemStack.EMPTY, null, 0, 0, false, false);
    }

    public static FlashbackRemoteContainer content(int containerId, List<ItemStack> items, ItemStack carried) {
        return new FlashbackRemoteContainer(containerId, Kind.CONTENT, "", Component.empty(), 0, ItemStack.EMPTY,
            items, carried, null, 0, 0, false, false);
    }

    public static FlashbackRemoteContainer slot(int containerId, int slot, ItemStack item) {
        return new FlashbackRemoteContainer(containerId, Kind.SLOT, "", Component.empty(), slot, item,
            List.of(), ItemStack.EMPTY, null, 0, 0, false, false);
    }

    public static FlashbackRemoteContainer carried(int containerId, ItemStack carried) {
        return new FlashbackRemoteContainer(containerId, Kind.CARRIED, "", Component.empty(), 0, ItemStack.EMPTY,
            List.of(), carried, null, 0, 0, false, false);
    }

    public static FlashbackRemoteContainer close(int containerId) {
        return new FlashbackRemoteContainer(containerId, Kind.CLOSE, "", Component.empty(), 0, ItemStack.EMPTY,
            List.of(), ItemStack.EMPTY, null, 0, 0, false, false);
    }

    public static FlashbackRemoteContainer reset() {
        return new FlashbackRemoteContainer(-1, Kind.RESET, "", Component.empty(), 0, ItemStack.EMPTY,
            List.of(), ItemStack.EMPTY, null, 0, 0, false, false);
    }

    public static FlashbackRemoteContainer offers(int containerId, MerchantOffers offers, int villagerLevel,
                                                  int villagerXp, boolean showProgress, boolean canRestock) {
        return new FlashbackRemoteContainer(containerId, Kind.OFFERS, "", Component.empty(), 0, ItemStack.EMPTY,
            List.of(), ItemStack.EMPTY, offers, villagerLevel, villagerXp, showProgress, canRestock);
    }

    private static final Kind[] KINDS = Kind.values();

    public static class ContainerStreamCodec implements StreamCodec<RegistryFriendlyByteBuf, FlashbackRemoteContainer> {

        @Override
        public FlashbackRemoteContainer decode(RegistryFriendlyByteBuf buffer) {
            int containerId = buffer.readVarInt();
            int kindOrdinal = buffer.readVarInt();
            Kind kind = kindOrdinal >= 0 && kindOrdinal < KINDS.length ? KINDS[kindOrdinal] : Kind.RESET;

            String menuType = "";
            Component title = Component.empty();
            int slot = 0;
            ItemStack item = ItemStack.EMPTY;
            List<ItemStack> items = List.of();
            ItemStack carried = ItemStack.EMPTY;
            MerchantOffers offers = null;
            int villagerLevel = 0;
            int villagerXp = 0;
            boolean showProgress = false;
            boolean canRestock = false;

            switch (kind) {
                case OPEN -> {
                    menuType = buffer.readUtf();
                    title = ComponentSerialization.STREAM_CODEC.decode(buffer);
                }
                case CONTENT -> {
                    items = readItems(buffer);
                    carried = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
                }
                case SLOT -> {
                    slot = buffer.readVarInt();
                    item = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
                }
                case CARRIED -> carried = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
                case OFFERS -> {
                    offers = MerchantOffers.STREAM_CODEC.decode(buffer);
                    villagerLevel = buffer.readVarInt();
                    villagerXp = buffer.readVarInt();
                    showProgress = buffer.readBoolean();
                    canRestock = buffer.readBoolean();
                }
                default -> {
                }
            }

            return new FlashbackRemoteContainer(containerId, kind, menuType, title, slot, item, items, carried,
                offers, villagerLevel, villagerXp, showProgress, canRestock);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, FlashbackRemoteContainer container) {
            buffer.writeVarInt(container.containerId());
            buffer.writeVarInt(container.kind().ordinal());

            switch (container.kind()) {
                case OPEN -> {
                    buffer.writeUtf(container.menuType() == null ? "" : container.menuType());
                    ComponentSerialization.STREAM_CODEC.encode(buffer, container.title());
                }
                case CONTENT -> {
                    writeItems(buffer, container.items());
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, container.carried());
                }
                case SLOT -> {
                    buffer.writeVarInt(container.slot());
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, container.item());
                }
                case CARRIED -> ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, container.carried());
                case OFFERS -> {
                    MerchantOffers.STREAM_CODEC.encode(buffer,
                        container.offers() == null ? new MerchantOffers() : container.offers());
                    buffer.writeVarInt(container.villagerLevel());
                    buffer.writeVarInt(container.villagerXp());
                    buffer.writeBoolean(container.showProgress());
                    buffer.writeBoolean(container.canRestock());
                }
                default -> {
                }
            }
        }

        private static List<ItemStack> readItems(RegistryFriendlyByteBuf buffer) {
            int size = buffer.readVarInt();
            List<ItemStack> items = new ArrayList<>(Math.max(0, Math.min(size, 256)));
            for (int i = 0; i < size; i++) {
                items.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
            }
            return items;
        }

        private static void writeItems(RegistryFriendlyByteBuf buffer, List<ItemStack> items) {
            buffer.writeVarInt(items.size());
            for (ItemStack item : items) {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, item);
            }
        }
    }

}
