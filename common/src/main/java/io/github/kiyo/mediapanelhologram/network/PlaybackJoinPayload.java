package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S: this client's page for the screen with session key `key` reports playback (join the shared state, or
 * offer its longer video). urlHash is PlaybackUrl.hash of the page URL, never the URL itself.
 */
public record PlaybackJoinPayload(BlockPos key, long urlHash, double position, boolean playing, double rate,
        double duration) implements CustomPacketPayload {
    public static final Type<PlaybackJoinPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "playback_join"));

    public static final StreamCodec<ByteBuf, PlaybackJoinPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, PlaybackJoinPayload::key,
            ByteBufCodecs.LONG, PlaybackJoinPayload::urlHash,
            ByteBufCodecs.DOUBLE, PlaybackJoinPayload::position,
            ByteBufCodecs.BOOL, PlaybackJoinPayload::playing,
            ByteBufCodecs.DOUBLE, PlaybackJoinPayload::rate,
            ByteBufCodecs.DOUBLE, PlaybackJoinPayload::duration,
            PlaybackJoinPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
