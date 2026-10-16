package io.github.kiyo.mediapanelhologram.block.entity;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import dev.architectury.registry.menu.ExtendedMenuProvider;

import io.github.kiyo.mediapanelhologram.block.MediaMode;
import io.github.kiyo.mediapanelhologram.block.QualityPreset;
import io.github.kiyo.mediapanelhologram.block.menu.MediaBlockMenu;
import io.github.kiyo.mediapanelhologram.registry.ModBlockEntities;

/**
 * Panel block entity. Besides the group bookkeeping (origin + width/height),
 * it now also owns url/mode/quality (MediaSource). The settings that count
 * for a merged group live on the group's representative panel (the one at
 * groupOrigin); PanelBlock#useItemOn opens the menu on that panel.
 *
 * Group settings are NOT yet carried over when the representative changes
 * (merge/split/break) -- planned for a later step.
 */
public class PanelBlockEntity extends BlockEntity implements ExtendedMenuProvider, MediaSource {
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

    private String url = "";
    private MediaMode mode = MediaMode.IMAGE;
    private QualityPreset quality = QualityPreset.DEFAULT;
    private int volume = -1;
    private boolean muted = false;
    private boolean repeat = false;
    // True once settings were saved by a player (or copied from such a panel).
    // An empty URL in Browser mode is valid, so URL emptiness can't tell.
    private boolean configured = false;
    // True when ANY panel of this panel's group has no redstone-on state (enabled=false).
    // Kept equal on every group member by PanelGroupManager.refreshGroupOff.
    private boolean groupOff = false;
    // True when this panel is part of a connected set that is NOT a solid rectangle
    // (e.g. mid-construction L-shape): it draws nothing and owns no session.
    private boolean invalidShape = false;
    // Screen-up direction chosen when settings were saved (floor/ceiling groups); -1 = use own block state.
    private int screenUpRef = -1;


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

    /** All panels of a merged group share one browser session keyed by the group origin. */
    @Override
    public BlockPos getSessionKey() {
        return groupOrigin;
    }

    public boolean isInvalidShape() {
        return invalidShape;
    }

    public void setInvalidShape(boolean invalidShape) {
        this.invalidShape = invalidShape;
        this.setChanged();
    }

    public int getScreenUpRef() {
        return screenUpRef;
    }

    public void setScreenUpRef(int screenUpRef) {
        this.screenUpRef = screenUpRef;
        this.setChanged();
    }

    @Override
    public boolean ownsSession() {
        return !invalidShape && groupOrigin.equals(getBlockPos());
    }

    public boolean isGroupOff() {
        return groupOff;
    }

    public void setGroupOff(boolean groupOff) {
        this.groupOff = groupOff;
        this.setChanged();
    }

    public boolean isConfigured() {
        return configured;
    }

    public void setConfigured(boolean configured) {
        this.configured = configured;
        this.setChanged();
    }

    @Override
    public String getUrl() {
        return url;
    }

    @Override
    public MediaMode getMode() {
        return mode;
    }

    @Override
    public QualityPreset getQuality() {
        return quality;
    }

    @Override
    public void setUrlAndMode(String url, MediaMode mode) {
        this.url = url;
        this.mode = mode;
        this.configured = true;
        this.setChanged();
    }

    @Override
    public int getVolume() {
        return volume;
    }

    @Override
    public boolean isMuted() {
        return muted;
    }

    @Override
    public void setVolumeState(int volume, boolean muted) {
        this.volume = volume;
        this.muted = muted;
        this.setChanged();
    }

    @Override
    public boolean isRepeat() {
        return repeat;
    }

    @Override
    public void setRepeat(boolean repeat) {
        this.repeat = repeat;
        this.setChanged();
    }

    @Override
    public void setQuality(QualityPreset quality) {
        this.quality = quality;
        this.setChanged();
    }

    private int range = io.github.kiyo.mediapanelhologram.block.MediaRange.DEFAULT;

    @Override
    public int getRange() {
        return range;
    }

    @Override
    public void setRange(int range) {
        this.range = io.github.kiyo.mediapanelhologram.block.MediaRange.clamp(range);
        this.setChanged();
    }

    private io.github.kiyo.mediapanelhologram.block.LockData lock = io.github.kiyo.mediapanelhologram.block.LockData.NONE;

    @Override
    public io.github.kiyo.mediapanelhologram.block.LockData getLock() {
        return lock;
    }

    @Override
    public void setLock(io.github.kiyo.mediapanelhologram.block.LockData lock) {
        this.lock = lock;
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
        output.putString("Url", url);
        output.putInt("Mode", mode.ordinal());
        output.putInt("Quality", quality.ordinal());
        output.putInt("Volume", volume);
        output.putInt("Range", range);
        output.putBoolean("Muted", muted);
        output.putBoolean("Repeat", repeat);
        output.putBoolean("Configured", configured);
        output.putBoolean("GroupOff", groupOff);
        output.putBoolean("InvalidShape", invalidShape);
        output.putInt("ScreenUpRef", screenUpRef);
        lock.save(output);
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
        url = input.getStringOr("Url", "");
        int modeOrdinal = input.getIntOr("Mode", MediaMode.IMAGE.ordinal());
        MediaMode[] values = MediaMode.values();
        mode = (modeOrdinal >= 0 && modeOrdinal < values.length) ? values[modeOrdinal] : MediaMode.IMAGE;
        quality = QualityPreset.fromOrdinal(input.getIntOr("Quality", QualityPreset.DEFAULT.ordinal()));
        volume = input.getIntOr("Volume", -1);
        range = io.github.kiyo.mediapanelhologram.block.MediaRange.clamp(input.getIntOr("Range", io.github.kiyo.mediapanelhologram.block.MediaRange.DEFAULT));
        muted = input.getBooleanOr("Muted", false);
        repeat = input.getBooleanOr("Repeat", false);
        configured = input.getBooleanOr("Configured", !url.isEmpty());
        groupOff = input.getBooleanOr("GroupOff", false);
        invalidShape = input.getBooleanOr("InvalidShape", false);
        screenUpRef = input.getIntOr("ScreenUpRef", -1);
        lock = io.github.kiyo.mediapanelhologram.block.LockData.load(input);
    }

    // BlockEntity's default getUpdateTag/getUpdatePacket sync nothing
    // (same finding as MediaBlockEntity), so override both.
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        TagValueOutput output = TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, registries);
        saveAdditional(output);
        return output.buildResult();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("gui.mediapanelhologram.panel.title");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new MediaBlockMenu(containerId, inventory, this.getBlockPos());
    }

    @Override
    public void saveExtraData(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.getBlockPos());
    }
}
