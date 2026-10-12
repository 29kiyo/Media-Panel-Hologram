package io.github.kiyo.mediapanelhologram.item;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import io.github.kiyo.mediapanelhologram.block.entity.MediaSource;
import io.github.kiyo.mediapanelhologram.registry.ModDataComponents;

/**
 * The remote control.
 * - Sneak + use on a MediaSource block (Media Block, Hologram Block, Panel):
 *   connect / disconnect it. (A sneaking player holding an item skips the
 *   block's own useItemOn and goes straight to Item#useOn.)
 * - Use in the air (sneaking or not): opens the remote screen (client only,
 *   through RemoteOpenerProvider so this class stays server-safe).
 * - Server side, once a second while carried (and when used): connected
 *   screens whose block is gone are dropped, see RemoteData#validated.
 */
public class RemoteItem extends Item {
    public RemoteItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (!player.isShiftKeyDown()) {
            return use(level, player, context.getHand());
        }
        if (!(level.getBlockEntity(context.getClickedPos()) instanceof MediaSource source)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        BlockPos key = source.getSessionKey();
        ItemStack stack = context.getItemInHand();
        RemoteData data = stack.getOrDefault(ModDataComponents.REMOTE_DATA.get(), RemoteData.EMPTY);
        ResourceKey<Level> dimension = level.dimension();
        int index = data.indexOf(dimension, key);
        if (index >= 0) {
            RemoteTarget removed = data.targets().get(index);
            stack.set(ModDataComponents.REMOTE_DATA.get(), data.without(index));
            player.sendOverlayMessage(Component.translatable("message.mediapanelhologram.remote.removed", removed.name()));
        } else if (data.targets().size() >= RemoteData.MAX_TARGETS) {
            player.sendOverlayMessage(Component.translatable("message.mediapanelhologram.remote.full", RemoteData.MAX_TARGETS));
        } else if (!io.github.kiyo.mediapanelhologram.network.LockAccess.canOperate(player, level, context.getClickedPos())) {
            player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.mediapanelhologram.lock.denied"));
        } else {
            String name = data.nextName();
            stack.set(ModDataComponents.REMOTE_DATA.get(), data.with(new RemoteTarget(dimension, key, name, true)));
            player.sendOverlayMessage(Component.translatable("message.mediapanelhologram.remote.added", name));
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            RemoteOpenerProvider.open(hand);
            return InteractionResult.SUCCESS;
        }
        prune(player.getItemInHand(hand), level);
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
        super.inventoryTick(stack, level, entity, slot);
        if (level.getGameTime() % 20 == 0) {
            prune(stack, level);
        }
    }

    /** Drops/remaps targets that no longer match a live MediaSource; writes the stack only on change. */
    private static void prune(ItemStack stack, Level level) {
        RemoteData data = stack.get(ModDataComponents.REMOTE_DATA.get());
        if (data == null || data.targets().isEmpty()) {
            return;
        }
        RemoteData valid = data.validated(level);
        if (valid != data) {
            stack.set(ModDataComponents.REMOTE_DATA.get(), valid);
        }
    }
}
