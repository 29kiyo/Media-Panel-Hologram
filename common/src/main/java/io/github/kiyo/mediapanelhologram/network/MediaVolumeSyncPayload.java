package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C: the server changed a block's persisted volume/mute state. pos is the
 * SESSION KEY block (a panel group's representative). volume is 0..100, or
 * -1 = "never set, leave the page's own volume alone" (mute may still apply).
 */
public record MediaVolumeSyncPayload(BlockPos pos, int volume, boolean muted) implements CustomPacketPayload {
    public static final Type<MediaVolumeSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "media_volume_sync"));

    public static final StreamCodec<ByteBuf, MediaVolumeSyncPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, MediaVolumeSyncPayload::pos,
            ByteBufCodecs.VAR_INT, MediaVolumeSyncPayload::volume,
            ByteBufCodecs.BOOL, MediaVolumeSyncPayload::muted,
            MediaVolumeSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
