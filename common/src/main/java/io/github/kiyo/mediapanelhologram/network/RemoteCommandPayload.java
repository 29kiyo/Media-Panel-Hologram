package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S: run a command on the remote in the given hand (0 main, 1 off).
 * The command goes to every CHECKED connected screen. If includePos is true,
 * the connected screen at pos (the row highlighted on the client) is
 * included too, checked or not; the server still requires it to be in the
 * held remote's list.
 */
public record RemoteCommandPayload(int hand, RemoteCommand cmd, int arg, boolean includePos, BlockPos pos) implements CustomPacketPayload {
    public static final Type<RemoteCommandPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "remote_command"));

    public static final StreamCodec<ByteBuf, RemoteCommandPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RemoteCommandPayload::hand,
            ByteBufCodecs.idMapper(RemoteCommand::fromOrdinal, RemoteCommand::ordinal), RemoteCommandPayload::cmd,
            ByteBufCodecs.VAR_INT, RemoteCommandPayload::arg,
            ByteBufCodecs.BOOL, RemoteCommandPayload::includePos,
            BlockPos.STREAM_CODEC, RemoteCommandPayload::pos,
            RemoteCommandPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
