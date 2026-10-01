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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import dev.architectury.registry.menu.ExtendedMenuProvider;

import io.github.kiyo.mediapanelhologram.block.MediaMode;
import io.github.kiyo.mediapanelhologram.block.menu.MediaBlockMenu;
import io.github.kiyo.mediapanelhologram.registry.ModBlockEntities;

/**
 * Camouflage + media block entity (Phase 4, task 2: camouflage mechanism;
 * task 3: URL registration + mode switching state).
 *
 * TABS REMOVED: a browser-tabs feature (browserTabs/activeTabIndex/
 * allocateTabId, plus BrowserTab/MediaBlockTabsPayload/
 * MediaBlockTabsSyncPayload) was implemented and then reverted at the
 * user's request after several actual-run bugs proved hard to fully root-
 * cause in the time available (tab ids skipping/duplicating, a tab-close
 * crash from a since-closed GPU sampler, stale click targets from an
 * incompletely-cleared Screen widget list). This class is back to a single
 * url/mode pair per block, same as before that feature existed.
 *
 * See the class's original Javadoc (still below) for the camouflage-sync
 * design notes. url/mode are added to that SAME saveAdditional/loadAdditional
 * + getUpdateTag sync path, so opening the GUI never needs a dedicated
 * "send me the current state" packet -- see MediaBlockUpdatePayload's
 * Javadoc for the full reasoning.
 *
 * ExtendedMenuProvider (dev.architectury.registry.menu) extends vanilla's
 * MenuProvider and adds saveExtraData(FriendlyByteBuf), which
 * MenuRegistry.openExtendedMenu uses to hand the client's MenuType factory
 * (see ModMenuTypes) the one piece of data it needs to construct a client-
 * side MediaBlockMenu: this block's own position.
 */
public class MediaBlockEntity extends BlockEntity implements ExtendedMenuProvider {
    private ItemStack camouflageItem = ItemStack.EMPTY;
    private BlockState camouflageState = null;
    private String url = "";
    private MediaMode mode = MediaMode.IMAGE;
    private io.github.kiyo.mediapanelhologram.block.QualityPreset quality = io.github.kiyo.mediapanelhologram.block.QualityPreset.DEFAULT;

    public MediaBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MEDIA_BLOCK.get(), pos, state);
    }

    public ItemStack getCamouflageItem() {
        return camouflageItem;
    }

    public boolean hasCamouflage() {
        return !camouflageItem.isEmpty();
    }

    /**
     * Sets the camouflage item. Callers are responsible for player-inventory
     * bookkeeping (this only updates the block's own state); pass
     * ItemStack.EMPTY to clear the camouflage.
     */
    public void setCamouflageItem(ItemStack stack) {
        this.camouflageItem = stack.copyWithCount(1);
        if (stack.isEmpty()) {
            this.camouflageState = null;
        }
        this.setChanged();
    }

    /** Camouflage BlockState (slab half, stair facing, log axis...). Falls back to the item's default state (old saves). */
    public BlockState getCamouflageState() {
        if (!hasCamouflage()) {
            return null;
        }
        if (camouflageState != null) {
            return camouflageState;
        }
        return camouflageItem.getItem() instanceof net.minecraft.world.item.BlockItem bi
                ? bi.getBlock().defaultBlockState() : null;
    }

    /** Call after setCamouflageItem (clearing the item resets this). */
    public void setCamouflageState(BlockState state) {
        this.camouflageState = state;
        this.setChanged();
    }

    public String getUrl() {
        return url;
    }

    public io.github.kiyo.mediapanelhologram.block.QualityPreset getQuality() {
        return quality;
    }

    public void setQuality(io.github.kiyo.mediapanelhologram.block.QualityPreset quality) {
        this.quality = quality;
        this.setChanged();
    }

    public MediaMode getMode() {
        return mode;
    }

    /**
     * Sets both URL and mode together (they are always submitted together
     * from MediaBlockScreen). Caller (ModNetworking's receiver) is
     * responsible for the explicit sendBlockUpdated call afterwards -- same
     * division of responsibility as setCamouflageItem/MediaBlock, kept here
     * so this method stays usable from a non-block-update context later
     * (e.g. a future remote-control packet in Phase6) without forcing a
     * block update as a side effect.
     */
    public void setUrlAndMode(String url, MediaMode mode) {
        this.url = url;
        this.mode = mode;
        this.setChanged();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("Camouflage", ItemStack.OPTIONAL_CODEC, camouflageItem);
        if (camouflageState != null) {
            output.store("CamouflageState", BlockState.CODEC, camouflageState);
        }
        output.putString("Url", url);
        output.putInt("Mode", mode.ordinal());
        output.putInt("Quality", quality.ordinal());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        camouflageItem = input.read("Camouflage", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
        camouflageState = input.read("CamouflageState", BlockState.CODEC).orElse(null);
        url = input.getStringOr("Url", "");
        int modeOrdinal = input.getIntOr("Mode", MediaMode.IMAGE.ordinal());
        MediaMode[] values = MediaMode.values();
        mode = (modeOrdinal >= 0 && modeOrdinal < values.length) ? values[modeOrdinal] : MediaMode.IMAGE;
        quality = io.github.kiyo.mediapanelhologram.block.QualityPreset.fromOrdinal(input.getIntOr("Quality", io.github.kiyo.mediapanelhologram.block.QualityPreset.DEFAULT.ordinal()));
    }

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
        return Component.translatable("gui.mediapanelhologram.media_block.title");
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
