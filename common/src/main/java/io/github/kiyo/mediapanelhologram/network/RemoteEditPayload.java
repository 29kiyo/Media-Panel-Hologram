package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S: edit the connected-screen list of the remote the player holds.
 * hand: 0 = main hand, 1 = off hand. index AND pos identify the target: the
 * list can shrink on its own (broken blocks are pruned), so the server only
 * applies the edit if the entry at index still has this pos.
 */
public record RemoteEditPayload(int hand, Action action, int index, BlockPos pos, String text, boolean flag) implements CustomPacketPayload {
    public enum Action {
        RENAME, REMOVE, SET_SELECTED;

        public static Action fromOrdinal(int i) {
            Action[] v = values();
            return (i >= 0 && i < v.length) ? v[i] : RENAME;
        }
    }

    public static final Type<RemoteEditPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "remote_edit"));

    public static final StreamCodec<ByteBuf, RemoteEditPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RemoteEditPayload::hand,
            ByteBufCodecs.idMapper(Action::fromOrdinal, Action::ordinal), RemoteEditPayload::action,
            ByteBufCodecs.VAR_INT, RemoteEditPayload::index,
            BlockPos.STREAM_CODEC, RemoteEditPayload::pos,
            ByteBufCodecs.STRING_UTF8, RemoteEditPayload::text,
            ByteBufCodecs.BOOL, RemoteEditPayload::flag,
            RemoteEditPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
