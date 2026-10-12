package io.github.kiyo.mediapanelhologram.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import io.github.kiyo.mediapanelhologram.block.LockData;
import io.github.kiyo.mediapanelhologram.block.entity.MediaSource;
import io.github.kiyo.mediapanelhologram.block.entity.PanelBlockEntity;

/**
 * Single place for lock decisions (server and client). Panels always resolve to
 * the group representative, so a stale copy on a member never changes the answer.
 * Only OPERATION is managed; breaking is left to vanilla / protection mods.
 */
public final class LockAccess {
    /** The MediaSource at pos, or for a panel the group's representative. Null if none. */
    public static MediaSource representative(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof MediaSource src)) {
            return null;
        }
        if (src instanceof PanelBlockEntity own) {
            BlockPos origin = own.getGroupOrigin();
            if (!origin.equals(pos) && level.getBlockEntity(origin) instanceof PanelBlockEntity rep) {
                return rep;
            }
        }
        return src;
    }

    public static LockData lockOf(Level level, BlockPos pos) {
        MediaSource src = representative(level, pos);
        return src == null ? LockData.NONE : src.getLock();
    }

    /** Dev only: env MPH_LOCK_TEST=1 treats everybody as non-OP so denial paths can be tested in single player. */
    private static final boolean DEV_NO_OP = "1".equals(System.getenv("MPH_LOCK_TEST"));

    public static boolean isOp(Player player) {
        if (DEV_NO_OP) {
            return false;
        }
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /** May change settings / open screens / use the remote on this screen. */
    public static boolean canOperate(Player player, Level level, BlockPos pos) {
        return canOperate(player, lockOf(level, pos));
    }

    public static boolean canOperate(Player player, LockData lock) {
        if (!lock.locked()) {
            return true;
        }
        return isOp(player) || lock.isOwner(player.getUUID()) || lock.isAllowed(player.getUUID());
    }

    /** May unlock and edit the allowed list. */
    public static boolean canManage(Player player, LockData lock) {
        return lock.locked() && (isOp(player) || lock.isOwner(player.getUUID()));
    }

    private LockAccess() {
    }
}
