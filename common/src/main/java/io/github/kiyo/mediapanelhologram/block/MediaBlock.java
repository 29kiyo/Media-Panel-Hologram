package io.github.kiyo.mediapanelhologram.block;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import dev.architectury.registry.menu.MenuRegistry;

import io.github.kiyo.mediapanelhologram.block.entity.MediaBlockEntity;
import io.github.kiyo.mediapanelhologram.browser.MediaBrowserOpenerProvider;

/**
 * Camouflage + media playback block.
 *
 * Interaction summary (Phase 4, tasks 2 + 3 + "ブロック単体でのブラウザ
 * 直接操作モード"):
 * - Shift+right-click (empty hand OR holding any item) -> opens the URL/mode
 *   GUI (MediaBlockScreen). Checked first, before the camouflage-set /
 *   camouflage-clear / browser-direct-control branches below, so it always
 *   wins regardless of what's in the player's hand.
 * - Right-click with a block item in hand, not shifting, camouflage not yet
 *   set -> stores that block as the camouflage, consumes one item (survival
 *   only).
 * - Right-click with an empty hand, not shifting, camouflage currently set
 *   -> clears the camouflage, gives the stored block item back to the
 *   player.
 * - Right-click with an empty hand, not shifting, camouflage NOT set, mode
 *   == BROWSER -> (THIS REVISION) opens the client-only direct-control
 *   browser screen (MediaBrowserScreen) so the player can move a cursor
 *   over the block's own browser content and click/type/scroll in it, no
 *   remote required. Every other mode falls through to PASS unchanged (no
 *   direct-control screen for IMAGE/VIDEO/MUSIC yet -- not requested).
 *
 * WHY MediaBrowserOpenerProvider INSTEAD OF `new MediaBrowserScreen(pos)`
 * DIRECTLY: this class (MediaBlock) is common-module code that also loads
 * on a dedicated server, while MediaBrowserScreen is client-only. Even
 * though the call would only ever execute inside a level.isClientSide()
 * branch, this MOD's established pattern (see IBrowserEngine/
 * BrowserEngineProvider) is to never let a common class's source reference
 * a client-only class's name at all, and to go through a common-defined
 * interface + static provider instead -- kept consistent here rather than
 * relying on JVM lazy class resolution behaving the same way on every
 * loader/version.
 *
 * IMPORTANT #1 (empty-hand right-click did nothing at all in an actual
 * run): useWithoutItem is only reached when useItemOn's return value is
 * TRY_WITH_EMPTY_HAND (confirmed via javap on ServerPlayerGameMode). This is
 * also, by the same confirmed mechanism, the reason the browser-direct-
 * control branch below is guaranteed to only fire on an ACTUALLY empty
 * hand, not "empty hand as far as this method can tell".
 *
 * IMPORTANT #2 (double-draw / z-fighting): getRenderShape(BlockState) must
 * return RenderShape.INVISIBLE while camouflaged, tracked via
 * CAMOUFLAGED_PROPERTY.
 *
 * IMPORTANT #3 (solid-black camouflage rendering + vanished neighbor faces +
 * no response to nearby light sources): getOcclusionShape() must be
 * Shapes.empty() -- see MediaBlockEntityRenderer's Javadoc for the full
 * trace through BlockState#isSolidRender/getLightDampening.
 *
 * IMPORTANT #4 (this block should act as an actual light source when the
 * camouflaged content is itself a light-emitting block): the camouflaged
 * block's light emission is mirrored into LIGHT_LEVEL_PROPERTY, kept in
 * sync alongside CAMOUFLAGED_PROPERTY every time the camouflage changes.
 *
 * CONFIRMED VANILLA LIMITATION (via javap on
 * ServerPlayerGameMode#useItemOn's bytecode, and an actual run): Shift+
 * right-click only opens the URL/mode GUI when the player's hand is
 * completely empty (see the class-level note on useItemOn's ordering).
 *
 * NOT YET IMPLEMENTED (tracked in phase4_design.md, follow-up commits):
 * - Picking a BlockState variant of the camouflaged block (slab top/bottom,
 *   stair facing, etc.) -- only defaultBlockState() is used right now
 * - Floor/ceiling/wall placement auto-orientation (CLAUDE.md 2.3)
 * - Crafting recipe
 * - Subtitle on/off + audio-translation on/off toggle for the direct-
 *   control screen (user-requested, deferred: "他の機能と同時に作成する
 *   から今は決めない" -- see development_status.md Phase4 notes for the
 *   two candidate approaches discussed)
 */
public class MediaBlock extends Block implements EntityBlock {
    public static final BooleanProperty CAMOUFLAGED_PROPERTY = BooleanProperty.create("camouflaged");
    public static final IntegerProperty LIGHT_LEVEL_PROPERTY = IntegerProperty.create("light_level", 0, 15);

    public MediaBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(CAMOUFLAGED_PROPERTY, false)
                .setValue(LIGHT_LEVEL_PROPERTY, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CAMOUFLAGED_PROPERTY, LIGHT_LEVEL_PROPERTY);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return state.getValue(CAMOUFLAGED_PROPERTY) ? RenderShape.INVISIBLE : RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state) {
        return Shapes.empty();
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MediaBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!(level.getBlockEntity(pos) instanceof MediaBlockEntity mediaBe)) {
            return InteractionResult.PASS;
        }

        if (player.isShiftKeyDown()) {
            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                MenuRegistry.openExtendedMenu(serverPlayer, mediaBe);
            }
            return InteractionResult.SUCCESS;
        }

        if (stack.getItem() instanceof BlockItem blockItem && !mediaBe.hasCamouflage()) {
            if (!level.isClientSide()) {
                BlockState camouflageDefaultState = blockItem.getBlock().defaultBlockState();
                mediaBe.setCamouflageItem(new ItemStack(blockItem));
                level.setBlock(pos, state
                        .setValue(CAMOUFLAGED_PROPERTY, true)
                        .setValue(LIGHT_LEVEL_PROPERTY, camouflageDefaultState.getLightEmission()),
                        Block.UPDATE_CLIENTS);
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                }
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hitResult) {
        if (!(level.getBlockEntity(pos) instanceof MediaBlockEntity mediaBe)) {
            return InteractionResult.PASS;
        }

        if (mediaBe.hasCamouflage()) {
            if (!level.isClientSide()) {
                ItemStack removed = mediaBe.getCamouflageItem();
                mediaBe.setCamouflageItem(ItemStack.EMPTY);
                level.setBlock(pos, state
                        .setValue(CAMOUFLAGED_PROPERTY, false)
                        .setValue(LIGHT_LEVEL_PROPERTY, 0),
                        Block.UPDATE_CLIENTS);
                if (!player.getInventory().add(removed)) {
                    player.drop(removed, false);
                }
            }
            return InteractionResult.SUCCESS;
        }

        if (mediaBe.getMode() == MediaMode.BROWSER) {
            if (level.isClientSide()) {
                MediaBrowserOpenerProvider.open(pos);
            }
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }
}
