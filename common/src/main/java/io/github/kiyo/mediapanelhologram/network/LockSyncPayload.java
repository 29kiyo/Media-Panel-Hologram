package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import io.github.kiyo.mediapanelhologram.block.LockData;

/** S2C: "this screen's lock state is now X" (explicit packet, like MediaBlockSyncPayload). */
public record LockSyncPayload(BlockPos pos, LockData lock) implements CustomPacketPayload {
    public static final Type<LockSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "lock_sync"));

    public static final StreamCodec<ByteBuf, LockSyncPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, LockSyncPayload::pos,
            LockData.STREAM_CODEC, LockSyncPayload::lock,
            LockSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
