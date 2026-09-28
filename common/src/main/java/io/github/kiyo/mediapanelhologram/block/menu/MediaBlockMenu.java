package io.github.kiyo.mediapanelhologram.block.menu;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;

import io.github.kiyo.mediapanelhologram.block.entity.MediaBlockEntity;
import io.github.kiyo.mediapanelhologram.registry.ModBlocks;
import io.github.kiyo.mediapanelhologram.registry.ModMenuTypes;

/**
 * Menu for the Media Block's URL/mode GUI. Deliberately has zero Slots: the
 * only thing being edited is a URL string + a MediaMode enum, both plain
 * BlockEntity fields, not inventory contents. quickMoveStack is therefore a
 * no-op stub required only because AbstractContainerMenu declares it
 * abstract.
 *
 * One constructor serves both sides: the server calls it directly from
 * MediaBlockEntity#createMenu (which already has a BlockPos via
 * getBlockPos()); the client calls it from the ExtendedMenuTypeFactory
 * lambda in ModMenuTypes, reading the BlockPos back out of the
 * FriendlyByteBuf that MediaBlockEntity#saveExtraData wrote.
 *
 * NOTE (unverified assumption, confirm on first real run): Inventory#player
 * is assumed to be a public field, as it has been across many past MC
 * versions -- not explicitly re-confirmed via javap for MC 26.2 in this
 * round of investigation (time-boxed). If this fails to compile, check
 * Inventory's actual field/accessor name via javap.
 */
public class MediaBlockMenu extends AbstractContainerMenu {
    private final BlockPos pos;
    private final ContainerLevelAccess access;

    public MediaBlockMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(ModMenuTypes.MEDIA_BLOCK.get(), containerId);
        this.pos = pos;
        this.access = ContainerLevelAccess.create(playerInventory.player.level(), pos);
    }

    public BlockPos getBlockPos() {
        return pos;
    }

    @Nullable
    public MediaBlockEntity getBlockEntity() {
        return access.evaluate((level, blockPos) ->
                level.getBlockEntity(blockPos) instanceof MediaBlockEntity mediaBe ? mediaBe : null, null);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ModBlocks.MEDIA_BLOCK.get());
    }
}
