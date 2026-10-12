package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C2S: open the settings menu of a screen connected to the remote in the given hand (0 main, 1 off). */
public record RemoteOpenPayload(int hand, BlockPos pos) implements CustomPacketPayload {
    public static final Type<RemoteOpenPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "remote_open"));

    public static final StreamCodec<ByteBuf, RemoteOpenPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RemoteOpenPayload::hand,
            BlockPos.STREAM_CODEC, RemoteOpenPayload::pos,
            RemoteOpenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
