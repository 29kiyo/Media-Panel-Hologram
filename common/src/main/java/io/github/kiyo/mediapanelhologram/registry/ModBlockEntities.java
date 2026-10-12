package io.github.kiyo.mediapanelhologram.registry;

import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Set;

import io.github.kiyo.mediapanelhologram.MediaPanelHologram;
import io.github.kiyo.mediapanelhologram.block.entity.MediaBlockEntity;
import io.github.kiyo.mediapanelhologram.block.entity.PanelBlockEntity;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(MediaPanelHologram.MOD_ID, Registries.BLOCK_ENTITY_TYPE);

    // BlockEntityType.Builder was removed in MC 1.21.2+; the constructor is now
    // public and takes just the (BlockPos, BlockState) factory + valid blocks.
    public static final RegistrySupplier<BlockEntityType<PanelBlockEntity>> PANEL =
            BLOCK_ENTITIES.register("panel",
                    () -> new BlockEntityType<>(PanelBlockEntity::new, Set.of(ModBlocks.PANEL.get())));

    public static final RegistrySupplier<BlockEntityType<MediaBlockEntity>> MEDIA_BLOCK =
            BLOCK_ENTITIES.register("media_block",
                    () -> new BlockEntityType<>(MediaBlockEntity::new, Set.of(ModBlocks.MEDIA_BLOCK.get(), ModBlocks.HOLOGRAM_BLOCK.get())));

    public static void init() {
        BLOCK_ENTITIES.register();
    }

    private ModBlockEntities() {
    }
}
