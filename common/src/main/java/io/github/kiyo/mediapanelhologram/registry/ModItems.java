package io.github.kiyo.mediapanelhologram.registry;

import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

import io.github.kiyo.mediapanelhologram.MediaPanelHologram;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(MediaPanelHologram.MOD_ID, Registries.ITEM);

    public static final RegistrySupplier<Item> PANEL = ITEMS.register("panel",
            () -> new BlockItem(ModBlocks.PANEL.get(),
                    new Item.Properties().setId(itemKey("panel"))));

    public static final RegistrySupplier<Item> MEDIA_BLOCK = ITEMS.register("media_block",
            () -> new BlockItem(ModBlocks.MEDIA_BLOCK.get(),
                    new Item.Properties().setId(itemKey("media_block"))));

    public static final RegistrySupplier<Item> HOLOGRAM_BLOCK = ITEMS.register("hologram_block",
            () -> new BlockItem(ModBlocks.HOLOGRAM_BLOCK.get(),
                    new Item.Properties().setId(itemKey("hologram_block"))));

    public static final RegistrySupplier<Item> REMOTE = ITEMS.register("remote",
            () -> new io.github.kiyo.mediapanelhologram.item.RemoteItem(
                    new Item.Properties().setId(itemKey("remote")).stacksTo(1)));

    public static void init() {
        ITEMS.register();
    }

    // Same requirement as BlockBehaviour.Properties: Item.Properties needs its
    // own ResourceKey via setId before the Item is constructed.
    private static ResourceKey<Item> itemKey(String name) {
        return ResourceKey.create(Registries.ITEM,
                Identifier.fromNamespaceAndPath(MediaPanelHologram.MOD_ID, name));
    }

    private ModItems() {
    }
}
