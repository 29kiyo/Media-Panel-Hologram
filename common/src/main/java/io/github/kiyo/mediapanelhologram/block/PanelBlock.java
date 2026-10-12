package io.github.kiyo.mediapanelhologram.block;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import io.github.kiyo.mediapanelhologram.block.entity.PanelBlockEntity;
import io.github.kiyo.mediapanelhologram.block.panel.PanelGroupManager;

/**
 * Thin panel block. Can be mounted on the floor, ceiling, or any wall face.
 * FACING stores the direction the panel's display surface points toward
 * (matches the "up" orientation baked into models/block/panel.json).
 *
 * NOT YET IMPLEMENTED (tracked in phase3_design.md, follow-up commits):
 * - Adjacent-panel group detection / merge (1.2)
 * - Broken-part hides content, aspect ratio kept (1.3)
 * - Redstone on/off toggle (1.4)
 * - Thickness slider -> shape (1.5 / this class's getShape is fixed for now)
 * - Self-support (no auto-break when backing block is removed)
 * - Crafting recipe
 */
public class PanelBlock extends Block implements EntityBlock {
    // DirectionProperty was removed in MC 1.21.5+; BlockStateProperties.FACING
    // is now a plain EnumProperty<Direction>.
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    // Which horizontal direction the screen's top edge points to (floor/ceiling panels only;
    // always 0 on walls). Same encoding as MediaBlock: SOUTH=0, WEST=1, NORTH=2, EAST=3.
    public static final net.minecraft.world.level.block.state.properties.IntegerProperty SCREEN_UP = net.minecraft.world.level.block.state.properties.IntegerProperty.create("screen_up", 0, 3);

    private static int encodeScreenUp(Direction horizontal) {
        return switch (horizontal) {
            case WEST -> 1;
            case NORTH -> 2;
            case EAST -> 3;
            default -> 0;
        };
    }

    // Fixed 16x2x16 shape (matches the current thickness=2 model). Will become
    // thickness-dependent once the thickness slider (BlockState/BE) is wired up.
    private static final VoxelShape SHAPE_UP = Block.box(0, 0, 0, 16, 2, 16);
    private static final VoxelShape SHAPE_DOWN = Block.box(0, 14, 0, 16, 16, 16);
    private static final VoxelShape SHAPE_NORTH = Block.box(0, 0, 14, 16, 16, 16);
    private static final VoxelShape SHAPE_SOUTH = Block.box(0, 0, 0, 16, 16, 2);
    private static final VoxelShape SHAPE_WEST = Block.box(14, 0, 0, 16, 16, 16);
    private static final VoxelShape SHAPE_EAST = Block.box(0, 0, 0, 2, 16, 16);

    public PanelBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.UP).setValue(SCREEN_UP, 0));
    }

    // Shift + empty-hand right-click opens the settings menu of the group's
    // representative panel (the one at groupOrigin). A non-empty hand with
    // Shift never reaches here (vanilla goes straight to the item), and
    // plain right-click returns PASS so normal block placement still works.
    @Override
    protected net.minecraft.world.InteractionResult useItemOn(net.minecraft.world.item.ItemStack stack,
            BlockState state, Level level, BlockPos pos, net.minecraft.world.entity.player.Player player,
            net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hitResult) {
        if (!player.isShiftKeyDown()) {
            // PASS would skip useWithoutItem even for an empty hand.
            return stack.isEmpty()
                    ? net.minecraft.world.InteractionResult.TRY_WITH_EMPTY_HAND
                    : net.minecraft.world.InteractionResult.PASS;
        }
        if (!level.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            if (!io.github.kiyo.mediapanelhologram.network.LockAccess.canOperate(serverPlayer, level, pos)) {
                serverPlayer.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.mediapanelhologram.lock.denied"));
                return net.minecraft.world.InteractionResult.SUCCESS;
            }
            PanelBlockEntity target = null;
            if (level.getBlockEntity(pos) instanceof PanelBlockEntity own) {
                target = own;
                BlockPos origin = own.getGroupOrigin();
                if (!origin.equals(pos)
                        && level.getBlockState(origin).getBlock() instanceof PanelBlock
                        && level.getBlockEntity(origin) instanceof PanelBlockEntity rep) {
                    target = rep;
                }
            }
            if (target != null) {
                dev.architectury.registry.menu.MenuRegistry.openExtendedMenu(serverPlayer, target);
            }
        }
        return net.minecraft.world.InteractionResult.SUCCESS;
    }

    // Plain empty-hand right-click: open the browser screen of the group's
    // representative panel (client side only), Browser/Video modes only.
    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hitResult) {
        if (!(level.getBlockEntity(pos) instanceof PanelBlockEntity own)) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        BlockPos origin = own.getGroupOrigin();
        PanelBlockEntity target = own;
        if (!origin.equals(pos) && level.getBlockEntity(origin) instanceof PanelBlockEntity rep) {
            target = rep;
        }
        MediaMode m = target.getMode();
        if (m == MediaMode.BROWSER || m == MediaMode.VIDEO) {
            if (!io.github.kiyo.mediapanelhologram.network.LockAccess.canOperate(player, level, pos)) {
                if (!level.isClientSide()) {
                    player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.mediapanelhologram.lock.denied"));
                }
                return net.minecraft.world.InteractionResult.SUCCESS;
            }
            if (level.isClientSide()) {
                io.github.kiyo.mediapanelhologram.browser.MediaBrowserOpenerProvider.open(target.getBlockPos());
            }
            return net.minecraft.world.InteractionResult.SUCCESS;
        }
        return net.minecraft.world.InteractionResult.PASS;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SCREEN_UP);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // IMPORTANT: BlockPlaceContext#getClickedPos() returns the position
        // where the NEW block will be placed, NOT the position of the
        // existing block the player actually clicked on (it's pre-offset by
        // the clicked face). To find the block that was really clicked, we
        // have to step back one block in the opposite direction.
        Direction clickedFace = context.getClickedFace();
        BlockPos actuallyClickedPos = context.getClickedPos().relative(clickedFace.getOpposite());
        BlockState actuallyClickedState = context.getLevel().getBlockState(actuallyClickedPos);

        // The panel is very thin (2/16 block), so its own up/down/side edge
        // faces are exposed and easy to click by accident when extending an
        // existing wall of panels (e.g. clicking near the top edge of a
        // wall-mounted panel can register as its "up" face instead of the
        // intended wall face). To make extending a panel wall reliable,
        // clicking on any part of an existing PanelBlock inherits that
        // panel's FACING directly, instead of using the raw clicked face.
        if (actuallyClickedState.getBlock() instanceof PanelBlock) {
            return this.defaultBlockState()
                    .setValue(FACING, actuallyClickedState.getValue(FACING))
                    .setValue(SCREEN_UP, actuallyClickedState.getValue(SCREEN_UP));
        }
        // Otherwise (placing against a normal block): panel's display face
        // points opposite the face the player clicked (i.e. toward the
        // player), same convention as item frames.
        int screenUp = clickedFace.getAxis() == Direction.Axis.Y
                ? encodeScreenUp(context.getHorizontalDirection()) : 0;
        return this.defaultBlockState().setValue(FACING, clickedFace).setValue(SCREEN_UP, screenUp);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case DOWN -> SHAPE_DOWN;
            case NORTH -> SHAPE_NORTH;
            case SOUTH -> SHAPE_SOUTH;
            case WEST -> SHAPE_WEST;
            case EAST -> SHAPE_EAST;
            default -> SHAPE_UP;
        };
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PanelBlockEntity(pos, state);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (!level.isClientSide()) {
            PanelGroupManager.onPlaced(level, pos);
            updatePoweredState(level, pos);
        }
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
            Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, orientation, movedByPiston);
        if (!level.isClientSide()) {
            updatePoweredState(level, pos);
        }
    }

    // CLAUDE.md 1.4: no redstone signal -> always on; while wired and
    // receiving a signal -> toggled off (and back on once the signal drops).
    // ASSUMPTION (not explicit in CLAUDE.md which direction "toggle" means):
    // signal present = OFF, signal absent = ON. Flip the boolean below if the
    // intended behavior is the opposite.
    private static void updatePoweredState(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof PanelBlockEntity panelBe) {
            boolean hasSignal = level.hasNeighborSignal(pos);
            boolean wasEnabled = panelBe.isEnabled();
            panelBe.setEnabled(!hasSignal);
            if (wasEnabled != panelBe.isEnabled()) {
                PanelGroupManager.onPowerChanged(level, panelBe);
            }
        }
    }

    @Override
    public void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        // Unlike the old onRemove, this is only called when the block is
        // actually gone (not on a plain BlockState property change), so no
        // extra "did the block type change" check is needed here.
        PanelGroupManager.onRemoved(level, pos, state.getValue(FACING), state.getValue(SCREEN_UP));
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
    }
}
