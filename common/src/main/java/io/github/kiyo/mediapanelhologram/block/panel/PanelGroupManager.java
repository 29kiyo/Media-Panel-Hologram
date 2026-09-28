package io.github.kiyo.mediapanelhologram.block.panel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import io.github.kiyo.mediapanelhologram.block.PanelBlock;
import io.github.kiyo.mediapanelhologram.block.entity.PanelBlockEntity;
import io.github.kiyo.mediapanelhologram.config.PanelConfig;

/**
 * Detects rectangular groups of adjacent same-facing PanelBlocks and keeps
 * each member's PanelBlockEntity updated with the group's bounding origin
 * and size.
 *
 * Merge rule (CLAUDE.md 1.2): a group only forms when the connected set of
 * same-facing panels exactly fills a solid rectangle (no gaps), up to the
 * configured max size (PanelConfig, default 16x16). Any other connected
 * shape (L-shape, plus-shape, oversized block, etc.) is left as individual
 * 1x1 groups.
 *
 * Assumption (not explicitly stated in CLAUDE.md, flagging for review):
 * only panels sharing the exact same FACING merge together, since panels
 * with different facings live on different planes and can't form one flat
 * surface.
 *
 * NOT YET IMPLEMENTED: re-rendering/content re-layout when a group's shape
 * changes -- this class only maintains bookkeeping (origin + size). Media
 * rendering is Phase4+ scope (see CLAUDE.md 1.3 "自動検知され表示内容が追従").
 */
public final class PanelGroupManager {

    public static void onPlaced(Level level, BlockPos pos) {
        recomputeFrom(level, pos, new HashSet<>());
    }

    /**
     * Call right after a panel at {@code removedPos} (which had {@code facing})
     * has been removed. Recomputes every neighboring group, since removing
     * one panel can split a big rectangle into several smaller pieces.
     */
    public static void onRemoved(Level level, BlockPos removedPos, Direction facing) {
        Set<BlockPos> visited = new HashSet<>();
        for (BlockPos offset : inPlaneOffsets(facing)) {
            BlockPos neighbor = removedPos.offset(offset);
            if (!visited.contains(neighbor) && isMatchingPanel(level, neighbor, facing)) {
                recomputeFrom(level, neighbor, visited);
            }
        }
    }

    private static void recomputeFrom(Level level, BlockPos pos, Set<BlockPos> globalVisited) {
        if (globalVisited.contains(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof PanelBlock)) {
            return;
        }
        Direction facing = state.getValue(PanelBlock.FACING);

        // Flood fill the connected component of same-block, same-facing panels.
        List<BlockPos> component = new ArrayList<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(pos);
        seen.add(pos);

        int maxSize = PanelConfig.maxGroupSize();
        // Generous cap so a huge accidental grid can't cause a runaway flood
        // fill; anything this large is already far past the merge limit.
        int hardCap = maxSize * maxSize * 4;

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            component.add(current);
            if (component.size() > hardCap) {
                break;
            }
            for (BlockPos offset : inPlaneOffsets(facing)) {
                BlockPos neighbor = current.offset(offset);
                if (!seen.contains(neighbor) && isMatchingPanel(level, neighbor, facing)) {
                    seen.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }

        globalVisited.addAll(component);
        applyGroup(level, facing, component, maxSize);
    }

    private static void applyGroup(Level level, Direction facing, List<BlockPos> component, int maxSize) {
        Axis2D axes = axesFor(facing);

        int minA = Integer.MAX_VALUE, maxA = Integer.MIN_VALUE;
        int minB = Integer.MAX_VALUE, maxB = Integer.MIN_VALUE;
        for (BlockPos p : component) {
            minA = Math.min(minA, axes.a(p));
            maxA = Math.max(maxA, axes.a(p));
            minB = Math.min(minB, axes.b(p));
            maxB = Math.max(maxB, axes.b(p));
        }

        int width = maxA - minA + 1;
        int height = maxB - minB + 1;
        boolean fitsSize = width <= maxSize && height <= maxSize;
        boolean isSolidRectangle = fitsSize && component.size() == width * height;

        if (isSolidRectangle) {
            int normal = axes.normal(component.get(0));
            BlockPos origin = axes.fromAB(minA, minB, normal);
            for (BlockPos p : component) {
                setGroupData(level, p, origin, width, height);
            }
        } else {
            // Not a valid solid rectangle (or too big): fall back to 1x1 each.
            for (BlockPos p : component) {
                setGroupData(level, p, p, 1, 1);
            }
        }
    }

    private static void setGroupData(Level level, BlockPos pos, BlockPos origin, int width, int height) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof PanelBlockEntity panelBe) {
            panelBe.setGroup(origin, width, height);
        }
    }

    private static boolean isMatchingPanel(Level level, BlockPos pos, Direction facing) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof PanelBlock && state.getValue(PanelBlock.FACING) == facing;
    }

    private static BlockPos[] inPlaneOffsets(Direction facing) {
        return switch (facing.getAxis()) {
            case Y -> new BlockPos[]{
                    new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
                    new BlockPos(0, 0, 1), new BlockPos(0, 0, -1)
            };
            case Z -> new BlockPos[]{
                    new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
                    new BlockPos(0, 1, 0), new BlockPos(0, -1, 0)
            };
            case X -> new BlockPos[]{
                    new BlockPos(0, 1, 0), new BlockPos(0, -1, 0),
                    new BlockPos(0, 0, 1), new BlockPos(0, 0, -1)
            };
        };
    }

    // Maps a facing's two in-plane axes ("a"/"b") plus its normal axis to
    // plain ints, so the flood fill / rectangle math stays the same
    // regardless of whether the panel is on the floor, ceiling, or a wall.
    private static Axis2D axesFor(Direction facing) {
        return switch (facing.getAxis()) {
            case Y -> new Axis2D() {
                public int a(BlockPos p) { return p.getX(); }
                public int b(BlockPos p) { return p.getZ(); }
                public int normal(BlockPos p) { return p.getY(); }
                public BlockPos fromAB(int a, int b, int normal) { return new BlockPos(a, normal, b); }
            };
            case Z -> new Axis2D() {
                public int a(BlockPos p) { return p.getX(); }
                public int b(BlockPos p) { return p.getY(); }
                public int normal(BlockPos p) { return p.getZ(); }
                public BlockPos fromAB(int a, int b, int normal) { return new BlockPos(a, b, normal); }
            };
            case X -> new Axis2D() {
                public int a(BlockPos p) { return p.getY(); }
                public int b(BlockPos p) { return p.getZ(); }
                public int normal(BlockPos p) { return p.getX(); }
                public BlockPos fromAB(int a, int b, int normal) { return new BlockPos(normal, a, b); }
            };
        };
    }

    private interface Axis2D {
        int a(BlockPos p);
        int b(BlockPos p);
        int normal(BlockPos p);
        BlockPos fromAB(int a, int b, int normal);
    }

    private PanelGroupManager() {
    }
}
