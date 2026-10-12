package io.github.kiyo.mediapanelhologram.network;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import io.github.kiyo.mediapanelhologram.block.entity.MediaSource;
import io.github.kiyo.mediapanelhologram.item.RemoteData;
import io.github.kiyo.mediapanelhologram.item.RemoteItem;
import io.github.kiyo.mediapanelhologram.registry.ModDataComponents;

/**
 * Server-side reach rule shared by the packet handlers that act on a
 * screen from a GUI: the player is within 8 blocks (the old rule), OR holds
 * (either hand) a remote that has this screen connected. Dimension must
 * match and the chunk must be loaded.
 */
public final class RemoteAccess {
    public static boolean canReach(Player player, BlockPos pos) {
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0) {
            return true;
        }
        Level level = player.level();
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockPos key = level.getBlockEntity(pos) instanceof MediaSource src ? src.getSessionKey() : pos;
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof RemoteItem) {
                RemoteData data = stack.getOrDefault(ModDataComponents.REMOTE_DATA.get(), RemoteData.EMPTY);
                if (data.indexOf(level.dimension(), key) >= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private RemoteAccess() {
    }
}
