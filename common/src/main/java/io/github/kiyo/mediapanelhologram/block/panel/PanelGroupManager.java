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
    public static void onRemoved(Level level, BlockPos removedPos, Direction facing, int screenUp) {
        Set<BlockPos> visited = new HashSet<>();
        for (BlockPos offset : inPlaneOffsets(facing)) {
            BlockPos neighbor = removedPos.offset(offset);
            if (!visited.contains(neighbor) && isMatchingPanel(level, neighbor, facing, screenUp)) {
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
        int screenUp = state.getValue(PanelBlock.SCREEN_UP);

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
                if (!seen.contains(neighbor) && isMatchingPanel(level, neighbor, facing, screenUp)) {
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
            unifySettings(level, component);
            refreshGroupOff(level, component);
            for (BlockPos p : component) {
                setGroupData(level, p, origin, width, height);
                markShape(level, p, false);
            }
        } else {
            // Not a valid solid rectangle (or too big): fall back to 1x1 each.
            for (BlockPos p : component) {
                setGroupData(level, p, p, 1, 1);
                markShape(level, p, component.size() > 1);
                refreshGroupOff(level, java.util.List.of(p));
            }
        }
    }

    private static void setGroupData(Level level, BlockPos pos, BlockPos origin, int width, int height) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof PanelBlockEntity panelBe) {
            boolean changed = !panelBe.getGroupOrigin().equals(origin)
                    || panelBe.getGroupWidth() != width || panelBe.getGroupHeight() != height;
            panelBe.setGroup(origin, width, height);
            if (changed) {
                BlockState s = level.getBlockState(pos);
                level.sendBlockUpdated(pos, s, s, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            }
        }
    }

    /**
     * Makes every panel of a freshly computed rectangular group carry the
     * same url/mode/quality. Source: the panel with a non-empty URL (smallest
     * x,y,z wins), else the smallest-coordinate panel. Because all members
     * hold the settings, merge/split/break of the representative never loses them.
     */
    private static void unifySettings(Level level, List<BlockPos> component) {
        List<PanelBlockEntity> bes = new ArrayList<>();
        for (BlockPos p : component) {
            if (level.getBlockEntity(p) instanceof PanelBlockEntity pb) {
                bes.add(pb);
            }
        }
        if (bes.isEmpty()) {
            return;
        }
        bes.sort(java.util.Comparator.comparingInt((PanelBlockEntity b) -> b.getBlockPos().getX())
                .thenComparingInt(b -> b.getBlockPos().getY())
                .thenComparingInt(b -> b.getBlockPos().getZ()));
        PanelBlockEntity src = bes.get(0);
        // Locked panels win over everything: a lock must never be overwritten by
        // an adjacent unlocked panel (merge / extend of a locked group).
        boolean picked = false;
        for (PanelBlockEntity b : bes) {
            if (b.getLock().locked()) {
                src = b;
                picked = true;
                break;
            }
        }
        if (!picked) {
            for (PanelBlockEntity b : bes) {
                if (b.isConfigured()) {
                    src = b;
                    break;
                }
            }
        }
        for (PanelBlockEntity b : bes) {
            copySettings(level, src, b);
        }
    }

    private static void copySettings(Level level, PanelBlockEntity from, PanelBlockEntity to) {
        if (from == to) {
            return;
        }
        if (from.isConfigured() == to.isConfigured() && from.getScreenUpRef() == to.getScreenUpRef() && from.getUrl().equals(to.getUrl()) && from.getMode() == to.getMode()
                && from.getQuality() == to.getQuality() && from.getVolume() == to.getVolume()
                && from.isMuted() == to.isMuted() && from.getLock().equals(to.getLock())
                && from.getRange() == to.getRange()) {
            return;
        }
        to.setUrlAndMode(from.getUrl(), from.getMode());
        to.setQuality(from.getQuality());
        to.setVolumeState(from.getVolume(), from.isMuted());
        to.setConfigured(from.isConfigured());
        to.setLock(from.getLock());
        to.setRange(from.getRange());
        to.setScreenUpRef(from.getScreenUpRef());
        BlockState s = level.getBlockState(to.getBlockPos());
        level.sendBlockUpdated(to.getBlockPos(), s, s, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
    }

    /** After the representative's settings changed, copy them to every other panel of its group. */
    public static void propagateSettings(Level level, PanelBlockEntity rep) {
        Direction facing = rep.getBlockState().getValue(PanelBlock.FACING);
        Axis2D axes = axesFor(facing);
        BlockPos o = rep.getGroupOrigin();
        for (int a = 0; a < rep.getGroupWidth(); a++) {
            for (int b = 0; b < rep.getGroupHeight(); b++) {
                BlockPos p = axes.fromAB(axes.a(o) + a, axes.b(o) + b, axes.normal(o));
                if (!p.equals(rep.getBlockPos()) && level.getBlockEntity(p) instanceof PanelBlockEntity pb
                        && pb.getGroupOrigin().equals(o)) {
                    copySettings(level, rep, pb);
                }
            }
        }
    }

    /** Sets groupOff on every given panel = (any of them has enabled=false); syncs only on change. */
    private static void refreshGroupOff(Level level, List<BlockPos> members) {
        boolean off = false;
        for (BlockPos p : members) {
            if (level.getBlockEntity(p) instanceof PanelBlockEntity pb && !pb.isEnabled()) {
                off = true;
                break;
            }
        }
        for (BlockPos p : members) {
            if (level.getBlockEntity(p) instanceof PanelBlockEntity pb && pb.isGroupOff() != off) {
                pb.setGroupOff(off);
                BlockState s = level.getBlockState(p);
                level.sendBlockUpdated(p, s, s, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            }
        }
    }

    /** Call after a panel's redstone state changed: recompute groupOff for its whole group. */
    public static void onPowerChanged(Level level, PanelBlockEntity changed) {
        Direction facing = changed.getBlockState().getValue(PanelBlock.FACING);
        Axis2D axes = axesFor(facing);
        BlockPos o = changed.getGroupOrigin();
        List<BlockPos> members = new ArrayList<>();
        for (int a = 0; a < changed.getGroupWidth(); a++) {
            for (int b = 0; b < changed.getGroupHeight(); b++) {
                BlockPos p = axes.fromAB(axes.a(o) + a, axes.b(o) + b, axes.normal(o));
                if (level.getBlockEntity(p) instanceof PanelBlockEntity pb && pb.getGroupOrigin().equals(o)) {
                    members.add(p);
                }
            }
        }
        if (members.isEmpty()) {
            members.add(changed.getBlockPos());
        }
        refreshGroupOff(level, members);
    }

    private static void markShape(Level level, BlockPos pos, boolean invalid) {
        if (level.getBlockEntity(pos) instanceof PanelBlockEntity pb && pb.isInvalidShape() != invalid) {
            pb.setInvalidShape(invalid);
            BlockState s = level.getBlockState(pos);
            level.sendBlockUpdated(pos, s, s, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
    }

    private static boolean isMatchingPanel(Level level, BlockPos pos, Direction facing, int screenUp) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof PanelBlock && state.getValue(PanelBlock.FACING) == facing
                && true; // screen_up no longer separates groups
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
