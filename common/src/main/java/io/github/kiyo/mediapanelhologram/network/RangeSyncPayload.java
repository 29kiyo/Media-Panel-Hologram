package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Display range of one block (range_sync, S2C). Sent explicitly because sendBlockUpdated is unreliable for Media Blocks. */
public record RangeSyncPayload(BlockPos pos, int range) implements CustomPacketPayload {
    public static final Type<RangeSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "range_sync"));

    public static final StreamCodec<ByteBuf, RangeSyncPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RangeSyncPayload::pos,
            ByteBufCodecs.VAR_INT, RangeSyncPayload::range,
            RangeSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
