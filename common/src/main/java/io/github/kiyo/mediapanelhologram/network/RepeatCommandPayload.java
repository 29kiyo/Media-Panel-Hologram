package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S: "toggle repeat" for a block's screen. Carries only the position; the
 * server flips its stored state and broadcasts the absolute result.
 */
public record RepeatCommandPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<RepeatCommandPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "media_repeat_command"));

    public static final StreamCodec<ByteBuf, RepeatCommandPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RepeatCommandPayload::pos,
            RepeatCommandPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
