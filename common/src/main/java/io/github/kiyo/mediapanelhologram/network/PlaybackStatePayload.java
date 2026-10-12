package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C: shared playback state of a screen. A session applies it only if the hash of its page URL equals
 * `urlHash` and its page reports the same duration (duration of the shared video, -1 = unknown).
 */
public record PlaybackStatePayload(BlockPos key, long urlHash, double position, boolean playing, double rate,
        double duration) implements CustomPacketPayload {
    public static final Type<PlaybackStatePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "playback_state"));

    public static final StreamCodec<ByteBuf, PlaybackStatePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, PlaybackStatePayload::key,
            ByteBufCodecs.LONG, PlaybackStatePayload::urlHash,
            ByteBufCodecs.DOUBLE, PlaybackStatePayload::position,
            ByteBufCodecs.BOOL, PlaybackStatePayload::playing,
            ByteBufCodecs.DOUBLE, PlaybackStatePayload::rate,
            ByteBufCodecs.DOUBLE, PlaybackStatePayload::duration,
            PlaybackStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
