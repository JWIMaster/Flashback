package com.moulberry.flashback.packet;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.action.PositionAndAngle;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public record FlashbackAccurateEntityPosition(int entityId, List<PositionAndAngle> positionAndAngles,
                                             int replayTick, long replayEpoch) implements CustomPacketPayload {
    public FlashbackAccurateEntityPosition(int entityId, List<PositionAndAngle> positionAndAngles) {
        this(entityId, positionAndAngles, -1, -1);
    }
    public static final Type<FlashbackAccurateEntityPosition> TYPE = new Type<>(Flashback.createIdentifier("accurate_entity_position"));

    // Keep the recording codec byte-for-byte compatible with existing replay files.
    public static final StreamCodec<FriendlyByteBuf, FlashbackAccurateEntityPosition> STREAM_CODEC = new AccurateEntityPositionStreamCodec();
    // Only the live replay connection carries source-domain timing; recordings have action ticks.
    public static final StreamCodec<FriendlyByteBuf, FlashbackAccurateEntityPosition> PLAYBACK_STREAM_CODEC = new StreamCodec<>() {
        @Override
        public FlashbackAccurateEntityPosition decode(FriendlyByteBuf buffer) {
            FlashbackAccurateEntityPosition data = STREAM_CODEC.decode(buffer);
            return new FlashbackAccurateEntityPosition(data.entityId(), data.positionAndAngles(), buffer.readVarInt(), buffer.readVarLong());
        }

        @Override
        public void encode(FriendlyByteBuf buffer, FlashbackAccurateEntityPosition data) {
            STREAM_CODEC.encode(buffer, data);
            buffer.writeVarInt(data.replayTick());
            buffer.writeVarLong(data.replayEpoch());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class AccurateEntityPositionStreamCodec implements StreamCodec<FriendlyByteBuf, FlashbackAccurateEntityPosition> {
        @Override
        public FlashbackAccurateEntityPosition decode(FriendlyByteBuf friendlyByteBuf) {
            int entityId = friendlyByteBuf.readVarInt();
            int interpolatedCount = friendlyByteBuf.readVarInt();

            List<PositionAndAngle> interpolatedPositions = new ArrayList<>(interpolatedCount);
            for (int i = 0; i < interpolatedCount; i++) {
                double x = friendlyByteBuf.readDouble();
                double y = friendlyByteBuf.readDouble();
                double z = friendlyByteBuf.readDouble();
                float yaw = friendlyByteBuf.readFloat();
                float pitch = friendlyByteBuf.readFloat();

                interpolatedPositions.add(new PositionAndAngle(x, y, z, yaw, pitch));
            }

            return new FlashbackAccurateEntityPosition(entityId, interpolatedPositions);
        }

        @Override
        public void encode(FriendlyByteBuf friendlyByteBuf, FlashbackAccurateEntityPosition accurateEntityPosition) {
            friendlyByteBuf.writeVarInt(accurateEntityPosition.entityId);
            friendlyByteBuf.writeVarInt(accurateEntityPosition.positionAndAngles.size());
            for (PositionAndAngle interpolatedPosition : accurateEntityPosition.positionAndAngles) {
                friendlyByteBuf.writeDouble(interpolatedPosition.x());
                friendlyByteBuf.writeDouble(interpolatedPosition.y());
                friendlyByteBuf.writeDouble(interpolatedPosition.z());
                friendlyByteBuf.writeFloat(interpolatedPosition.yaw());
                friendlyByteBuf.writeFloat(interpolatedPosition.pitch());
            }
        }
    }

}
