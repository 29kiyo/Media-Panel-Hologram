package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Display range of one block (range_update, C2S). See ModNetworking for the server-side checks. */
public record RangeUpdatePayload(BlockPos pos, int range) implements CustomPacketPayload {
    public static final Type<RangeUpdatePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "range_update"));

    public static final StreamCodec<ByteBuf, RangeUpdatePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RangeUpdatePayload::pos,
            ByteBufCodecs.VAR_INT, RangeUpdatePayload::range,
            RangeUpdatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
