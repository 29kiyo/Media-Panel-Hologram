package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** S2C: "run this forwarded command on your browser session for the screen with this session key (if you have one)". */
public record RemoteSyncPayload(BlockPos pos, RemoteCommand cmd, int arg) implements CustomPacketPayload {
    public static final Type<RemoteSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "remote_sync"));

    public static final StreamCodec<ByteBuf, RemoteSyncPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RemoteSyncPayload::pos,
            ByteBufCodecs.idMapper(RemoteCommand::fromOrdinal, RemoteCommand::ordinal), RemoteSyncPayload::cmd,
            ByteBufCodecs.VAR_INT, RemoteSyncPayload::arg,
            RemoteSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
