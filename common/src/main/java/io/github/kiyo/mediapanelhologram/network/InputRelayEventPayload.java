package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** S2C: a mouse input made by another player (see InputRelayPayload for the fields). name = the operator. */
public record InputRelayEventPayload(BlockPos key, long urlHash, int kind, int nx, int ny, int button,
        double amount, String name) implements CustomPacketPayload {
    public static final Type<InputRelayEventPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "input_relay_event"));

    public static final StreamCodec<ByteBuf, InputRelayEventPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, InputRelayEventPayload::key,
            ByteBufCodecs.LONG, InputRelayEventPayload::urlHash,
            ByteBufCodecs.VAR_INT, InputRelayEventPayload::kind,
            ByteBufCodecs.VAR_INT, InputRelayEventPayload::nx,
            ByteBufCodecs.VAR_INT, InputRelayEventPayload::ny,
            ByteBufCodecs.VAR_INT, InputRelayEventPayload::button,
            ByteBufCodecs.DOUBLE, InputRelayEventPayload::amount,
            ByteBufCodecs.STRING_UTF8, InputRelayEventPayload::name,
            InputRelayEventPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
