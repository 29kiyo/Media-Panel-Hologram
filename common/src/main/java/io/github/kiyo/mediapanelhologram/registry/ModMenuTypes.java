package io.github.kiyo.mediapanelhologram.registry;

import dev.architectury.registry.menu.MenuRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;

import io.github.kiyo.mediapanelhologram.MediaPanelHologram;
import io.github.kiyo.mediapanelhologram.block.menu.MediaBlockMenu;

/**
 * MenuType registration, following the same DeferredRegister pattern as
 * ModBlockEntities. Registries.MENU confirmed via javap
 * (net.minecraft.core.registries.Registries#MENU).
 *
 * MenuRegistry.ofExtended(...) (dev.architectury.registry.menu) builds a
 * MenuType from an ExtendedMenuTypeFactory<T> -- T create(int, Inventory,
 * FriendlyByteBuf) -- which is exactly what's needed for a menu whose only
 * client-side construction input is the BlockPos written by
 * MediaBlockEntity#saveExtraData.
 */
public final class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(MediaPanelHologram.MOD_ID, Registries.MENU);

    public static final RegistrySupplier<MenuType<MediaBlockMenu>> MEDIA_BLOCK = MENUS.register("media_block",
            () -> MenuRegistry.ofExtended((containerId, inventory, buf) ->
                    new MediaBlockMenu(containerId, inventory, buf.readBlockPos())));

    public static void init() {
        MENUS.register();
    }

    private ModMenuTypes() {
    }
}
