package io.github.kiyo.mediapanelhologram.network;

import java.util.UUID;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C2S: lock / unlock / add or remove an allowed player. The server validates everything. */
public record LockUpdatePayload(BlockPos pos, Action action, UUID target) implements CustomPacketPayload {
    public enum Action { LOCK, UNLOCK, ADD, REMOVE }

    public static final Type<LockUpdatePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "lock_update"));

    public static final StreamCodec<ByteBuf, LockUpdatePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, LockUpdatePayload::pos,
            ByteBufCodecs.idMapper(i -> Action.values()[Math.floorMod(i, Action.values().length)], Action::ordinal),
            LockUpdatePayload::action,
            UUIDUtil.STREAM_CODEC, LockUpdatePayload::target,
            LockUpdatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
