package io.github.kiyo.mediapanelhologram.registry;

import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import io.github.kiyo.mediapanelhologram.MediaPanelHologram;
import io.github.kiyo.mediapanelhologram.block.HologramBlock;
import io.github.kiyo.mediapanelhologram.block.MediaBlock;
import io.github.kiyo.mediapanelhologram.block.PanelBlock;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(MediaPanelHologram.MOD_ID, Registries.BLOCK);

    public static final RegistrySupplier<Block> PANEL = BLOCKS.register("panel",
            () -> new PanelBlock(BlockBehaviour.Properties.of()
                    .setId(blockKey("panel"))
                    .mapColor(MapColor.METAL)
                    .strength(1.5f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    // noOcclusion() + getOcclusionShape()==empty (in MediaBlock) are both
    // required for correct lighting/face-culling; see MediaBlock's Javadoc.
    // lightLevel(...) reads back the LIGHT_LEVEL_PROPERTY BlockState value,
    // which MediaBlock keeps in sync with the camouflaged block's own light
    // emission, so this block acts as an actual light source while
    // camouflaging a light-emitting block (user-requested, e.g. a froglight).
    public static final RegistrySupplier<Block> MEDIA_BLOCK = BLOCKS.register("media_block",
            () -> new MediaBlock(BlockBehaviour.Properties.of()
                    .setId(blockKey("media_block"))
                    .mapColor(MapColor.METAL)
                    .strength(2.0f)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .lightLevel(state -> state.getValue(MediaBlock.LIGHT_LEVEL_PROPERTY))));

    // Hologram-only block: invisible, no collision (click box is in HologramBlock#getShape).
    public static final RegistrySupplier<Block> HOLOGRAM_BLOCK = BLOCKS.register("hologram_block",
            () -> new HologramBlock(BlockBehaviour.Properties.of()
                    .setId(blockKey("hologram_block"))
                    .mapColor(MapColor.NONE)
                    .strength(1.0f)
                    .sound(SoundType.GLASS)
                    .noOcclusion()
                    .noCollision()));

    public static void init() {
        BLOCKS.register();
    }

    private static ResourceKey<Block> blockKey(String name) {
        return ResourceKey.create(Registries.BLOCK,
                Identifier.fromNamespaceAndPath(MediaPanelHologram.MOD_ID, name));
    }

    private ModBlocks() {
    }
}
