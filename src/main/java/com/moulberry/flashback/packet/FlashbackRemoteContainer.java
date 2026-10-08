package com.moulberry.flashback.packet;

import com.moulberry.flashback.Flashback;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * A container the recorded player had open, sent to whoever is watching the replay.
 *
 * <p>A replay's packets are applied on its own server thread, so the container cannot simply be
 * opened there: a screen belongs to the client, and the client's menus belong to the viewer rather
 * than to the player being recorded. This carries what is needed across to the client, which is the
 * only place a screen may be built - the same shape of solution the recorded player's held item
 * already uses.
 *
 * @param menuType the container kind, empty when the recording does not say (an older replay)
 * @param closed   true when the container was closed rather than opened or updated
 */
public record FlashbackRemoteContainer(int containerId, String menuType, Component title,
                                       List<ItemStack> items, ItemStack carried, boolean closed)
        implements CustomPacketPayload {

    public static final Type<FlashbackRemoteContainer> TYPE =
        new Type<>(Flashback.createIdentifier("remote_container"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FlashbackRemoteContainer> STREAM_CODEC =
        new ContainerStreamCodec();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class ContainerStreamCodec implements StreamCodec<RegistryFriendlyByteBuf, FlashbackRemoteContainer> {

        @Override
        public FlashbackRemoteContainer decode(RegistryFriendlyByteBuf buffer) {
            int containerId = buffer.readVarInt();
            boolean closed = buffer.readBoolean();
            String menuType = buffer.readUtf();
            Component title = ComponentSerialization.STREAM_CODEC.decode(buffer);

            int size = buffer.readVarInt();
            List<ItemStack> items = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                items.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
            }
            ItemStack carried = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
            return new FlashbackRemoteContainer(containerId, menuType, title, items, carried, closed);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, FlashbackRemoteContainer container) {
            buffer.writeVarInt(container.containerId());
            buffer.writeBoolean(container.closed());
            buffer.writeUtf(container.menuType() == null ? "" : container.menuType());
            ComponentSerialization.STREAM_CODEC.encode(buffer, container.title());

            buffer.writeVarInt(container.items().size());
            for (ItemStack item : container.items()) {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, item);
            }
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, container.carried());
        }
    }

}
