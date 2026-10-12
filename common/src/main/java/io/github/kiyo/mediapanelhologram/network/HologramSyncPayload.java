package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import io.github.kiyo.mediapanelhologram.block.HologramSettings;

/** Hologram/screen settings of one block (hologram_sync). See ModNetworking for who sends/receives it. */
public record HologramSyncPayload(BlockPos pos, HologramSettings settings) implements CustomPacketPayload {
    public static final Type<HologramSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "hologram_sync"));

    public static final StreamCodec<ByteBuf, HologramSyncPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, HologramSyncPayload::pos,
            HologramSettings.STREAM_CODEC, HologramSyncPayload::settings,
            HologramSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
