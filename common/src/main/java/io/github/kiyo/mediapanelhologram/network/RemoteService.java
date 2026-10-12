package io.github.kiyo.mediapanelhologram.network;

import dev.architectury.networking.NetworkManager;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import io.github.kiyo.mediapanelhologram.block.QualityPreset;
import io.github.kiyo.mediapanelhologram.block.entity.MediaSource;
import io.github.kiyo.mediapanelhologram.block.entity.PanelBlockEntity;
import io.github.kiyo.mediapanelhologram.block.panel.PanelGroupManager;
import io.github.kiyo.mediapanelhologram.item.RemoteData;
import io.github.kiyo.mediapanelhologram.item.RemoteItem;
import io.github.kiyo.mediapanelhologram.item.RemoteTarget;
import io.github.kiyo.mediapanelhologram.registry.ModDataComponents;

/**
 * Server side of the remote control: validates that the player really holds
 * a remote, then applies the command to each SELECTED connected screen that
 * is in the player's dimension and in a loaded chunk. No distance limit (by
 * design). The list always comes from the held stack, never from the packet.
 */
public final class RemoteService {
    public static void handle(Player player, RemoteCommandPayload p) {
        InteractionHand hand = p.hand() == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof RemoteItem)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        RemoteData data = stack.getOrDefault(ModDataComponents.REMOTE_DATA.get(), RemoteData.EMPTY);
        int reached = 0;
        int denied = 0;
        for (RemoteTarget t : data.targets()) {
            boolean wanted = t.selected() || (p.includePos() && t.pos().equals(p.pos()));
            if (!wanted || !t.dimension().equals(level.dimension()) || !level.isLoaded(t.pos())) {
                continue;
            }
            if (!(level.getBlockEntity(t.pos()) instanceof MediaSource src) || !src.getSessionKey().equals(t.pos())) {
                continue;
            }
            if (!LockAccess.canOperate(player, level, t.pos())) {
                denied++;
                continue;
            }
            reached++;
            switch (p.cmd()) {
                case VOLUME_BY -> MediaVolumeService.apply(level, t.pos(),
                        MediaVolumeService.VolumeOp.VOLUME_BY, Math.max(-100, Math.min(100, p.arg())));
                case TOGGLE_MUTE -> MediaVolumeService.apply(level, t.pos(), MediaVolumeService.VolumeOp.TOGGLE_MUTE, 0);
                case NEXT_QUALITY -> nextQuality(level, t.pos(), src);
                default -> {
                    if (!PlaybackService.command(level, t.pos(), p.cmd(), p.arg()) && p.cmd().js(p.arg()) != null) {
                        RemoteSyncPayload sync = new RemoteSyncPayload(t.pos(), p.cmd(), p.arg());
                        for (ServerPlayer sp : level.players()) {
                            NetworkManager.sendToPlayer(sp, sync);
                        }
                    }
                }
            }
        }
        if (denied > 0) {
            player.sendOverlayMessage(Component.translatable("message.mediapanelhologram.lock.denied"));
        } else if (reached == 0) {
            player.sendOverlayMessage(Component.translatable("message.mediapanelhologram.remote.no_target"));
        }
    }

    private static void nextQuality(ServerLevel level, BlockPos pos, MediaSource src) {
        QualityPreset q = src.getQuality().next();
        src.setQuality(q);
        if (src instanceof PanelBlockEntity panel) {
            PanelGroupManager.propagateSettings(level, panel);
        }
        BlockState s = level.getBlockState(pos);
        level.sendBlockUpdated(pos, s, s, Block.UPDATE_CLIENTS);
        MediaBlockSyncPayload sync = new MediaBlockSyncPayload(pos, src.getUrl(), src.getMode(), q);
        for (ServerPlayer sp : level.players()) {
            NetworkManager.sendToPlayer(sp, sync);
        }
    }

    private RemoteService() {
    }
}
