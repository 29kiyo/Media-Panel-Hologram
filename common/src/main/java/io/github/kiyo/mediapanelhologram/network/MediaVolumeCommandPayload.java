package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S: a volume/mute command from a block's browser screen. Only a fixed
 * operation enum plus an int is sent (never a JS string); the server
 * validates and computes the resulting absolute state.
 */
public record MediaVolumeCommandPayload(BlockPos pos, MediaVolumeService.VolumeOp op, int arg) implements CustomPacketPayload {
    public static final Type<MediaVolumeCommandPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "media_volume_command"));

    public static final StreamCodec<ByteBuf, MediaVolumeCommandPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, MediaVolumeCommandPayload::pos,
            ByteBufCodecs.idMapper(MediaVolumeService.VolumeOp::fromOrdinal, MediaVolumeService.VolumeOp::ordinal),
            MediaVolumeCommandPayload::op,
            ByteBufCodecs.VAR_INT, MediaVolumeCommandPayload::arg,
            MediaVolumeCommandPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
