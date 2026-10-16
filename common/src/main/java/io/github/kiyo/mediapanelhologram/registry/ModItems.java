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
                    lore(new Item.Properties().setId(itemKey("panel")), "settings", "control")));

    public static final RegistrySupplier<Item> MEDIA_BLOCK = ITEMS.register("media_block",
            () -> new BlockItem(ModBlocks.MEDIA_BLOCK.get(),
                    lore(new Item.Properties().setId(itemKey("media_block")), "settings", "control")));

    public static final RegistrySupplier<Item> HOLOGRAM_BLOCK = ITEMS.register("hologram_block",
            () -> new BlockItem(ModBlocks.HOLOGRAM_BLOCK.get(),
                    lore(new Item.Properties().setId(itemKey("hologram_block")), "settings", "control")));

    public static final RegistrySupplier<Item> REMOTE = ITEMS.register("remote",
            () -> new io.github.kiyo.mediapanelhologram.item.RemoteItem(
                    lore(new Item.Properties().setId(itemKey("remote")).stacksTo(1), "remote_connect", "remote_open")));

    public static void init() {
        ITEMS.register();
    }

    // Same requirement as BlockBehaviour.Properties: Item.Properties needs its
    // own ResourceKey via setId before the Item is constructed.
    private static ResourceKey<Item> itemKey(String name) {
        return ResourceKey.create(Registries.ITEM,
                Identifier.fromNamespaceAndPath(MediaPanelHologram.MOD_ID, name));
    }

    /** Tooltip line: yellow key/button part (%s) inside a gray, non-italic sentence. */
    private static net.minecraft.network.chat.Component tip(String name, String key) {
        net.minecraft.network.chat.Component k = net.minecraft.network.chat.Component
                .translatable("tooltip." + MediaPanelHologram.MOD_ID + ".key." + key)
                .withStyle(st -> st.withColor(net.minecraft.ChatFormatting.YELLOW).withItalic(false));
        return net.minecraft.network.chat.Component
                .translatable("tooltip." + MediaPanelHologram.MOD_ID + "." + name, k)
                .withStyle(st -> st.withColor(net.minecraft.ChatFormatting.GRAY).withItalic(false));
    }

    private static Item.Properties lore(Item.Properties props, String... names) {
        java.util.List<net.minecraft.network.chat.Component> lines = new java.util.ArrayList<>();
        for (String n : names) {
            lines.add(tip(n, n.equals("settings") || n.equals("remote_connect") ? "shift_rmb" : "rmb"));
        }
        return props.component(net.minecraft.core.component.DataComponents.LORE,
                new net.minecraft.world.item.component.ItemLore(lines));
    }

    private ModItems() {
    }
}
