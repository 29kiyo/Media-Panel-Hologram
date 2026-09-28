package io.github.kiyo.mediapanelhologram.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import io.github.kiyo.mediapanelhologram.registry.ModBlockEntities;

/**
 * Group merge bookkeeping added (1.2): each panel knows the bounding
 * rectangle (origin position + width/height, in the group's in-plane axes)
 * of the merged group it currently belongs to. A lone panel is its own
 * 1x1 group (origin == its own pos, width == height == 1).
 *
 * NOT YET IMPLEMENTED (tracked in phase3_design.md, follow-up commits):
 * - Broken-part hides content, aspect ratio kept (1.3)
 * - Redstone on/off toggle (1.4)
 * - Thickness slider -> shape (1.5)
 * - Self-support (no auto-break when backing block is removed)
 * - Crafting recipe
 * - Actual config-file-backed max group size (currently PanelConfig constant)
 */
public class PanelBlockEntity extends BlockEntity {
    // Thickness in sixteenths of a block (1-16). Matches the fixed 2 used by
    // the current model/shape until the slider is wired up.
    private int thickness = 2;

    // Whether the panel is currently "on" (no redstone signal = always true;
    // signal present = toggled by redstone power state).
    private boolean enabled = true;

    // Group bounding rectangle this panel currently belongs to.
    private BlockPos groupOrigin;
    private int groupWidth = 1;
    private int groupHeight = 1;

    public PanelBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PANEL.get(), pos, state);
        this.groupOrigin = pos;
    }

    public int getThickness() {
        return thickness;
    }

    public void setThickness(int thickness) {
        this.thickness = thickness;
        this.setChanged();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.setChanged();
    }

    public BlockPos getGroupOrigin() {
        return groupOrigin;
    }

    public int getGroupWidth() {
        return groupWidth;
    }

    public int getGroupHeight() {
        return groupHeight;
    }

    public boolean isSoloGroup() {
        return groupWidth == 1 && groupHeight == 1;
    }

    public void setGroup(BlockPos origin, int width, int height) {
        this.groupOrigin = origin;
        this.groupWidth = width;
        this.groupHeight = height;
        this.setChanged();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("Thickness", thickness);
        output.putBoolean("Enabled", enabled);
        output.putInt("GroupOriginX", groupOrigin.getX());
        output.putInt("GroupOriginY", groupOrigin.getY());
        output.putInt("GroupOriginZ", groupOrigin.getZ());
        output.putInt("GroupWidth", groupWidth);
        output.putInt("GroupHeight", groupHeight);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        thickness = input.getIntOr("Thickness", 2);
        enabled = input.getBooleanOr("Enabled", true);
        int ox = input.getIntOr("GroupOriginX", this.getBlockPos().getX());
        int oy = input.getIntOr("GroupOriginY", this.getBlockPos().getY());
        int oz = input.getIntOr("GroupOriginZ", this.getBlockPos().getZ());
        groupOrigin = new BlockPos(ox, oy, oz);
        groupWidth = input.getIntOr("GroupWidth", 1);
        groupHeight = input.getIntOr("GroupHeight", 1);
    }
}
