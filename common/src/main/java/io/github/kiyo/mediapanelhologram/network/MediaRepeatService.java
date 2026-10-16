package io.github.kiyo.mediapanelhologram.network;

import dev.architectury.networking.NetworkManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import io.github.kiyo.mediapanelhologram.block.entity.MediaSource;
import io.github.kiyo.mediapanelhologram.block.entity.PanelBlockEntity;
import io.github.kiyo.mediapanelhologram.block.panel.PanelGroupManager;

/**
 * Server-side entry point for the persisted repeat flag. Callers do their own
 * permission/range checks (RemoteAccess#canReach, LockAccess#canOperate).
 * The toggle is resolved to an ABSOLUTE value here so every client ends up
 * with the same state.
 */
public final class MediaRepeatService {
    /** @param pos any block of the screen (a panel group member is fine) */
    public static void toggle(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof MediaSource src)) {
            return;
        }
        BlockPos key = src.getSessionKey();
        MediaSource target = level.getBlockEntity(key) instanceof MediaSource t ? t : src;

        boolean on = !target.isRepeat();
        target.setRepeat(on);
        if (target instanceof PanelBlockEntity panel) {
            PanelGroupManager.propagateSettings(level, panel);
        }
        if (level instanceof ServerLevel serverLevel) {
            RepeatSyncPayload sync = new RepeatSyncPayload(target.getBlockPos(), on);
            for (ServerPlayer p : serverLevel.players()) {
                NetworkManager.sendToPlayer(p, sync);
            }
        }
    }

    /** Sets the flag to an absolute value (no-op if it already matches). */
    public static void set(Level level, BlockPos pos, boolean on) {
        if (!(level.getBlockEntity(pos) instanceof MediaSource src)) {
            return;
        }
        MediaSource target = level.getBlockEntity(src.getSessionKey()) instanceof MediaSource t ? t : src;
        if (target.isRepeat() != on) {
            toggle(level, pos);
        }
    }

    private MediaRepeatService() {
    }
}
