package com.moulberry.flashback.packet;

import com.moulberry.flashback.Flashback;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Tells the client that nothing is being broken any more.
 *
 * <p>A replay forwards the crack overlay as the recording sent it, but a seek moves the client to a
 * different tick without replaying the packets that would have stopped a crack. What is left is a
 * block part-way through breaking because of something that happened later, or earlier, in a
 * recording - and it is most visible in an export, which jumps ticks for every frame.
 */
public class FlashbackClearBlockDestruction implements CustomPacketPayload {
    public static final Type<FlashbackClearBlockDestruction> TYPE =
        new Type<>(Flashback.createIdentifier("clear_block_destruction"));
    public static final FlashbackClearBlockDestruction INSTANCE = new FlashbackClearBlockDestruction();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
