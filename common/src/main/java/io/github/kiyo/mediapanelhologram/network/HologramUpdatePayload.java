package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import io.github.kiyo.mediapanelhologram.block.HologramSettings;

/** Hologram/screen settings of one block (hologram_update). See ModNetworking for who sends/receives it. */
public record HologramUpdatePayload(BlockPos pos, HologramSettings settings) implements CustomPacketPayload {
    public static final Type<HologramUpdatePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "hologram_update"));

    public static final StreamCodec<ByteBuf, HologramUpdatePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, HologramUpdatePayload::pos,
            HologramSettings.STREAM_CODEC, HologramUpdatePayload::settings,
            HologramUpdatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
