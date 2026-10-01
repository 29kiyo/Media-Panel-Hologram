package io.github.kiyo.mediapanelhologram.network;

import dev.architectury.networking.NetworkManager;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import io.github.kiyo.mediapanelhologram.block.entity.MediaBlockEntity;

/**
 * Registers the mod's custom network payloads.
 *
 * Called unconditionally from MediaPanelHologram.init() on both physical
 * sides: payload Type/StreamCodec registration must happen wherever the
 * payload might be encoded/decoded (i.e. both sides), while each payload's
 * receiver callback only ever actually runs on the side it's meant for
 * (MediaBlockUpdatePayload: server only; MediaBlockSyncPayload: client
 * only -- see MediaBlockSyncPayload's Javadoc for why the S2C payload
 * exists).
 *
 * TABS REMOVED: MediaBlockTabsPayload/MediaBlockTabsSyncPayload handling
 * was here and has been reverted -- see MediaBlockEntity's Javadoc.
 */
public final class ModNetworking {
    public static void init() {
        NetworkManager.registerC2S(
                MediaBlockUpdatePayload.TYPE,
                MediaBlockUpdatePayload.STREAM_CODEC,
                ModNetworking::handleMediaBlockUpdate);
        NetworkManager.registerS2C(
                MediaBlockSyncPayload.TYPE,
                MediaBlockSyncPayload.STREAM_CODEC,
                ModNetworking::handleMediaBlockSync);
    }

    private static void handleMediaBlockUpdate(MediaBlockUpdatePayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            Player player = context.getPlayer();
            Level level = player.level();
            if (level.getBlockEntity(payload.pos()) instanceof MediaBlockEntity mediaBe) {
                mediaBe.setUrlAndMode(payload.url(), payload.mode());
                mediaBe.setQuality(payload.quality());
                level.sendBlockUpdated(payload.pos(), mediaBe.getBlockState(), mediaBe.getBlockState(),
                        Block.UPDATE_CLIENTS);

                MediaBlockSyncPayload syncPayload =
                        new MediaBlockSyncPayload(payload.pos(), payload.url(), payload.mode(), payload.quality());
                if (level instanceof ServerLevel serverLevel) {
                    for (ServerPlayer serverPlayer : serverLevel.getServer().getPlayerList().getPlayers()) {
                        NetworkManager.sendToPlayer(serverPlayer, syncPayload);
                    }
                }
            }
        });
    }

    private static void handleMediaBlockSync(MediaBlockSyncPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            Level level = context.getPlayer().level();
            if (level.getBlockEntity(payload.pos()) instanceof MediaBlockEntity mediaBe) {
                mediaBe.setUrlAndMode(payload.url(), payload.mode());
                mediaBe.setQuality(payload.quality());
            }
        });
    }

    private ModNetworking() {
    }
}
