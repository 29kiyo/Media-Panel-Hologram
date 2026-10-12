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
 * Server-side single entry point for changing a block's persisted volume /
 * mute state. Used by the block's browser screen now and by the remote
 * control later. Callers do their own permission/range checks; this class
 * only validates the block and clamps values.
 *
 * Relative operations are turned into ABSOLUTE values here so every client
 * (and the saved NBT) ends up with the same state.
 */
public final class MediaVolumeService {
    public enum VolumeOp {
        VOLUME_BY, TOGGLE_MUTE, SET_VOLUME;

        public static VolumeOp fromOrdinal(int i) {
            VolumeOp[] v = values();
            return (i >= 0 && i < v.length) ? v[i] : VOLUME_BY;
        }
    }

    /** @param pos any block of the screen (a panel group member is fine) */
    public static void apply(Level level, BlockPos pos, VolumeOp op, int arg) {
        if (!(level.getBlockEntity(pos) instanceof MediaSource src)) {
            return;
        }
        BlockPos key = src.getSessionKey();
        MediaSource target = level.getBlockEntity(key) instanceof MediaSource t ? t : src;

        int stored = target.getVolume();
        int base = stored < 0 ? 100 : stored; // first relative change starts from 100%
        int vol = stored;
        boolean muted = target.isMuted();
        switch (op) {
            case VOLUME_BY -> vol = Math.max(0, Math.min(100, base + Math.max(-100, Math.min(100, arg))));
            case SET_VOLUME -> vol = Math.max(0, Math.min(100, arg));
            case TOGGLE_MUTE -> muted = !muted;
        }
        target.setVolumeState(vol, muted);
        if (target instanceof PanelBlockEntity panel) {
            PanelGroupManager.propagateSettings(level, panel);
        }
        if (level instanceof ServerLevel serverLevel) {
            MediaVolumeSyncPayload sync = new MediaVolumeSyncPayload(target.getBlockPos(), vol, muted);
            for (ServerPlayer p : serverLevel.players()) {
                NetworkManager.sendToPlayer(p, sync);
            }
        }
    }

    private MediaVolumeService() {
    }
}
