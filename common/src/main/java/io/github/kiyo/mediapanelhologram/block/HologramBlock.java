package io.github.kiyo.mediapanelhologram.block;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import dev.architectury.networking.NetworkManager;
import dev.architectury.registry.menu.MenuRegistry;

import io.github.kiyo.mediapanelhologram.block.entity.MediaBlockEntity;
import io.github.kiyo.mediapanelhologram.network.HologramSyncPayload;

/**
 * Hologram-only block: invisible body, no collision, small click/outline box.
 *
 * Extends MediaBlock on purpose: MediaBlockEntityRenderer, MediaBlockMenu and the
 * networking all read MediaBlock's BlockState properties (POWERED/FACING/...)
 * and MediaBlockEntity. Sharing them keeps every already-verified path intact.
 * Differences: never camouflages, never draws the screen face (screenOn is forced
 * OFF on placement and the Screen button is hidden in MediaBlockScreen),
 * holoOn starts ON.
 */
public class HologramBlock extends MediaBlock {
    private static final VoxelShape CLICK_SHAPE = Block.box(5, 5, 5, 11, 11, 11);

    public HologramBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    /** Client-only: a small particle ring every 2 seconds so the invisible block can be found. */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends net.minecraft.world.level.block.entity.BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (!level.isClientSide()
                || type != io.github.kiyo.mediapanelhologram.registry.ModBlockEntities.MEDIA_BLOCK.get()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<MediaBlockEntity>) HologramBlock::tickMarker;
    }

    private static void tickMarker(Level level, BlockPos pos, BlockState state, MediaBlockEntity be) {
        if (level.getGameTime() % 40 != 0 || !be.getHologram().marker()) {
            return;
        }
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * 2 * i / 6;
            level.addParticle(ParticleTypes.END_ROD, cx + Math.cos(a) * 0.25, cy, cz + Math.sin(a) * 0.25,
                    0.0, 0.02, 0.0);
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return CLICK_SHAPE;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof MediaBlockEntity be) {
            HologramSettings settings = be.getHologram().withHoloOn(true).withScreenOn(false);
            be.setHologram(settings);
            if (level instanceof ServerLevel serverLevel) {
                HologramSyncPayload sync = new HologramSyncPayload(pos, settings);
                for (ServerPlayer sp : serverLevel.getServer().getPlayerList().getPlayers()) {
                    NetworkManager.sendToPlayer(sp, sync);
                }
            }
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /** Same as MediaBlock#useItemOn minus camouflage: Shift opens the settings menu. */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!(level.getBlockEntity(pos) instanceof MediaBlockEntity mediaBe)) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                if (!io.github.kiyo.mediapanelhologram.network.LockAccess.canOperate(serverPlayer, level, pos)) {
                    serverPlayer.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.mediapanelhologram.lock.denied"));
                } else {
                    MenuRegistry.openExtendedMenu(serverPlayer, mediaBe);
                }
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }
}
