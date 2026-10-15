package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S: one mouse input made on a block's browser screen, to be replayed on the other players' browsers.
 * pos = the block whose screen was opened (permission / distance checks), key = its session key.
 * kind: 0 move, 1 press, 2 release, 3 wheel. nx/ny: position as 0..65535 of the texture size (quality independent).
 * urlHash: hash of the sender's page URL (receivers apply only if their page has the same hash).
 */
public record InputRelayPayload(BlockPos pos, BlockPos key, long urlHash, int kind, int nx, int ny, int button,
        double amount) implements CustomPacketPayload {
    public static final Type<InputRelayPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "input_relay"));

    public static final StreamCodec<ByteBuf, InputRelayPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, InputRelayPayload::pos,
            BlockPos.STREAM_CODEC, InputRelayPayload::key,
            ByteBufCodecs.LONG, InputRelayPayload::urlHash,
            ByteBufCodecs.VAR_INT, InputRelayPayload::kind,
            ByteBufCodecs.VAR_INT, InputRelayPayload::nx,
            ByteBufCodecs.VAR_INT, InputRelayPayload::ny,
            ByteBufCodecs.VAR_INT, InputRelayPayload::button,
            ByteBufCodecs.DOUBLE, InputRelayPayload::amount,
            InputRelayPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
