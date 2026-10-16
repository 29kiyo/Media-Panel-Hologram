package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** S2C: the block's repeat state changed. pos is the SESSION KEY block (a panel group's representative). */
public record RepeatSyncPayload(BlockPos pos, boolean repeat) implements CustomPacketPayload {
    public static final Type<RepeatSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "media_repeat_sync"));

    public static final StreamCodec<ByteBuf, RepeatSyncPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RepeatSyncPayload::pos,
            ByteBufCodecs.BOOL, RepeatSyncPayload::repeat,
            RepeatSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
