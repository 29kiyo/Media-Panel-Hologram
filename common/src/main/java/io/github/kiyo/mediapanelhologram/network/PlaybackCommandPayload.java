package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S: play/pause, seek or rate command from a block's browser screen. Only PLAY_TOGGLE, SEEK_BY and
 * SET_RATE are accepted. When hasRef is true, ref* is the commander's own current playback state; the
 * server uses it as the new baseline first only if refDuration matches the shared video's duration.
 */
public record PlaybackCommandPayload(BlockPos pos, RemoteCommand cmd, int arg, boolean hasRef, double refPos,
        boolean refPlaying, double refRate, double refDuration) implements CustomPacketPayload {
    public static final Type<PlaybackCommandPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "playback_command"));

    public static final StreamCodec<ByteBuf, PlaybackCommandPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, PlaybackCommandPayload::pos,
            ByteBufCodecs.idMapper(RemoteCommand::fromOrdinal, RemoteCommand::ordinal), PlaybackCommandPayload::cmd,
            ByteBufCodecs.VAR_INT, PlaybackCommandPayload::arg,
            ByteBufCodecs.BOOL, PlaybackCommandPayload::hasRef,
            ByteBufCodecs.DOUBLE, PlaybackCommandPayload::refPos,
            ByteBufCodecs.BOOL, PlaybackCommandPayload::refPlaying,
            ByteBufCodecs.DOUBLE, PlaybackCommandPayload::refRate,
            ByteBufCodecs.DOUBLE, PlaybackCommandPayload::refDuration,
            PlaybackCommandPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
