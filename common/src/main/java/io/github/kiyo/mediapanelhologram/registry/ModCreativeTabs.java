package io.github.kiyo.mediapanelhologram.registry;

import dev.architectury.registry.CreativeTabRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import io.github.kiyo.mediapanelhologram.MediaPanelHologram;

/**
 * Registers this mod's own creative tab. Icon is media_block's BlockItem
 * per the Phase2 hand-off note in development_status.md (user-specified).
 *
 * NOTE: CreativeModeTab.Builder#displayItems takes a
 * DisplayItemsGenerator whose accept() parameter is CreativeModeTab.Output.
 * That nested type's class file bytecode reports ACC_PUBLIC (visible via a
 * plain javap run), but its InnerClasses attribute marks it protected at the
 * source level, so javac rejects referencing it (even implicitly through a
 * lambda parameter) from code outside CreativeModeTab's class hierarchy --
 * confirmed by hitting "Output has protected access in CreativeModeTab" on
 * an actual compile. Architectury's intended workaround is
 * CreativeTabRegistry.append/appendStack(DeferredSupplier<CreativeModeTab>,
 * ...), which resolves the display-items wiring on the platform side instead
 * of going through the builder's displayItems callback directly.
 */
public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(MediaPanelHologram.MOD_ID, Registries.CREATIVE_MODE_TAB);

    public static final RegistrySupplier<CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeTabRegistry.create(builder -> {
                builder.title(Component.translatable("itemGroup.mediapanelhologram"));
                builder.icon(() -> new ItemStack(ModItems.MEDIA_BLOCK.get()));
            }));

    static {
        CreativeTabRegistry.append(MAIN, ModItems.PANEL, ModItems.MEDIA_BLOCK, ModItems.HOLOGRAM_BLOCK, ModItems.REMOTE);
    }

    public static void init() {
        TABS.register();
    }

    private ModCreativeTabs() {
    }
}
