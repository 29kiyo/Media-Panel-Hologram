package io.github.kiyo.mediapanelhologram.block;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A BlockPlaceContext that pretends the clicked block (the Media Block itself)
 * is replaceable, so getClickedPos() returns the Media Block's own position
 * instead of the neighbouring cell. This lets us ask any block "what state
 * would you have if the player placed you here?" without the neighbour-slab
 * "double slab" problem.
 */
public class CamouflagePlacementContext extends BlockPlaceContext {
    public CamouflagePlacementContext(Player player, InteractionHand hand, ItemStack stack, BlockHitResult hit) {
        super(player, hand, stack, hit);
        this.replaceClicked = true;
    }

    /** State the block would get if placed at the clicked Media Block; falls back to the default state. */
    public static BlockState resolveState(BlockItem item, Player player, InteractionHand hand, ItemStack stack,
            BlockHitResult hit) {
        BlockState placed = null;
        try {
            placed = item.getBlock().getStateForPlacement(new CamouflagePlacementContext(player, hand, stack, hit));
        } catch (RuntimeException ignored) {
            // Some blocks assume a real placement; fall back below.
        }
        return placed != null ? placed : item.getBlock().defaultBlockState();
    }
}
