package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C2S: this client closed its session for the screen with session key `key`. */
public record PlaybackLeavePayload(BlockPos key) implements CustomPacketPayload {
    public static final Type<PlaybackLeavePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "playback_leave"));

    public static final StreamCodec<ByteBuf, PlaybackLeavePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, PlaybackLeavePayload::key,
            PlaybackLeavePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
