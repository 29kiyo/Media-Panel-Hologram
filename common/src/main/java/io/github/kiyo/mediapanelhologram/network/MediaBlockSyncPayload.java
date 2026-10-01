package io.github.kiyo.mediapanelhologram.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import io.github.kiyo.mediapanelhologram.block.MediaMode;

/**
 * S2C packet: server -> client, "this MediaBlockEntity's url/mode changed,
 * update your local copy directly". Added after an actual-run bug: relying
 * on vanilla's own BlockEntity resync (triggered via
 * Level#sendBlockUpdated, the same mechanism the camouflage feature uses
 * successfully) turned out to NOT reliably deliver MediaBlockEntity's
 * custom update packet for a SUBSEQUENT url/mode change on an
 * already-loaded client -- confirmed via temporary diagnostic logging that
 * MediaBlockEntity#loadAdditional never ran client-side after the server
 * received and applied a MediaBlockUpdatePayload from the direct-control
 * screen's address bar. (The exact vanilla internal reason wasn't fully
 * isolated -- camouflage's case also changes the actual BlockState value,
 * this case doesn't, and that difference is the leading suspect -- but
 * rather than keep reverse-engineering vanilla's dirty-tracking further,
 * this explicit payload sidesteps it entirely and is guaranteed correct
 * regardless of the root cause.)
 *
 * Sent to every connected player (see ModNetworking's Javadoc) rather than
 * only players tracking this chunk -- simplest correct option for a MOD
 * with a small expected player count; revisit with proper chunk-tracking
 * if this ever becomes a real concern.
 */
public record MediaBlockSyncPayload(BlockPos pos, String url, MediaMode mode, io.github.kiyo.mediapanelhologram.block.QualityPreset quality) implements CustomPacketPayload {
    public static final Type<MediaBlockSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("mediapanelhologram", "media_block_sync"));

    public static final StreamCodec<ByteBuf, MediaBlockSyncPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, MediaBlockSyncPayload::pos,
            ByteBufCodecs.STRING_UTF8, MediaBlockSyncPayload::url,
            ByteBufCodecs.idMapper(i -> MediaMode.values()[i], MediaMode::ordinal), MediaBlockSyncPayload::mode,
            ByteBufCodecs.idMapper(io.github.kiyo.mediapanelhologram.block.QualityPreset::fromOrdinal, io.github.kiyo.mediapanelhologram.block.QualityPreset::ordinal), MediaBlockSyncPayload::quality,
            MediaBlockSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
