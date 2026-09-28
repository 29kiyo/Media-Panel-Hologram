package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import io.github.kiyo.mediapanelhologram.block.MediaMode;

/**
 * C2S packet: client -> server, "commit the URL/mode currently shown in the
 * Media Block GUI". Sent whenever the player presses "Set URL" or clicks a
 * mode button (see MediaBlockScreen).
 *
 * There is deliberately no S2C counterpart for the *initial* GUI values:
 * MediaBlockEntity's existing getUpdateTag()/saveAdditional() sync (already
 * built for the camouflage field, see MediaBlockEntity's Javadoc) already
 * keeps url/mode current on the client's copy of the block entity by the
 * time the GUI opens, so MediaBlockScreen just reads them straight from
 * menu.getBlockEntity() instead of waiting on a dedicated packet.
 *
 * StreamCodec note: BlockPos.STREAM_CODEC / ByteBufCodecs.STRING_UTF8 /
 * ByteBufCodecs.idMapper(...) are all StreamCodec<ByteBuf, X> (confirmed via
 * javap), which satisfies NetworkManager.registerC2S's
 * "StreamCodec<? super RegistryFriendlyByteBuf, T>" parameter type because
 * RegistryFriendlyByteBuf extends FriendlyByteBuf extends ByteBuf --
 * ByteBuf is a valid supertype for that wildcard. No RegistryFriendlyByteBuf
 * -specific codec is needed since nothing here references registries.
 */
public record MediaBlockUpdatePayload(BlockPos pos, String url, MediaMode mode) implements CustomPacketPayload {
    // CustomPacketPayload.createType(String) treats its whole argument as a
    // *path* under the "minecraft" namespace (confirmed by an actual crash:
    // "mediapanelhologram:media_block_update" produced the Identifier
    // "minecraft:mediapanelhologram:media_block_update", rejected for the
    // stray colon in the path). Type's own public constructor takes an
    // Identifier directly, so build one explicitly via
    // Identifier.fromNamespaceAndPath instead of using createType at all.
    public static final Type<MediaBlockUpdatePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "media_block_update"));

    public static final StreamCodec<ByteBuf, MediaBlockUpdatePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, MediaBlockUpdatePayload::pos,
            ByteBufCodecs.STRING_UTF8, MediaBlockUpdatePayload::url,
            ByteBufCodecs.idMapper(i -> MediaMode.values()[i], MediaMode::ordinal), MediaBlockUpdatePayload::mode,
            MediaBlockUpdatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
