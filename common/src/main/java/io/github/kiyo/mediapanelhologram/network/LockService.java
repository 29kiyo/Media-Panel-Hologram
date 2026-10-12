package io.github.kiyo.mediapanelhologram.network;

import dev.architectury.networking.NetworkManager;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import io.github.kiyo.mediapanelhologram.block.LockData;
import io.github.kiyo.mediapanelhologram.block.entity.MediaSource;
import io.github.kiyo.mediapanelhologram.block.entity.PanelBlockEntity;
import io.github.kiyo.mediapanelhologram.block.panel.PanelGroupManager;

/** Server side of LockUpdatePayload. */
public final class LockService {
    public static void handle(Player player, LockUpdatePayload p) {
        Level level = player.level();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!RemoteAccess.canReach(player, p.pos())) {
            return;
        }
        MediaSource src = LockAccess.representative(level, p.pos());
        if (src == null) {
            return;
        }
        LockData cur = src.getLock();
        LockData next = cur;
        switch (p.action()) {
            case LOCK -> {
                if (!cur.locked()) {
                    next = LockData.lockedBy(player.getUUID(), player.getGameProfile().name());
                }
            }
            case UNLOCK -> {
                if (LockAccess.canManage(player, cur)) {
                    next = LockData.NONE;
                }
            }
            case ADD -> {
                if (LockAccess.canManage(player, cur) && !cur.isOwner(p.target())) {
                    // Name comes from the server's own player list, never from the client.
                    ServerPlayer target = serverLevel.getServer().getPlayerList().getPlayer(p.target());
                    if (target != null) {
                        next = cur.withAllowed(target.getUUID(), target.getGameProfile().name());
                    }
                }
            }
            case REMOVE -> {
                if (LockAccess.canManage(player, cur)) {
                    next = cur.withoutAllowed(p.target());
                }
            }
        }
        if (next.equals(cur)) {
            if (cur.locked() && !LockAccess.canManage(player, cur)) {
                player.sendOverlayMessage(Component.translatable("message.mediapanelhologram.lock.denied"));
            }
            return;
        }
        src.setLock(next);
        if (src instanceof PanelBlockEntity panel) {
            PanelGroupManager.propagateSettings(level, panel);
        }
        BlockState s = level.getBlockState(src.getBlockPos());
        level.sendBlockUpdated(src.getBlockPos(), s, s, Block.UPDATE_CLIENTS);
        LockSyncPayload sync = new LockSyncPayload(src.getBlockPos(), next);
        for (ServerPlayer sp : serverLevel.getServer().getPlayerList().getPlayers()) {
            NetworkManager.sendToPlayer(sp, sync);
        }
    }

    private LockService() {
    }
}
