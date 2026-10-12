package io.github.kiyo.mediapanelhologram.registry;

import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;

import io.github.kiyo.mediapanelhologram.MediaPanelHologram;
import io.github.kiyo.mediapanelhologram.item.RemoteData;

/** Data component types (Registries.DATA_COMPONENT_TYPE, confirmed via javap). */
public final class ModDataComponents {
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(MediaPanelHologram.MOD_ID, Registries.DATA_COMPONENT_TYPE);

    public static final RegistrySupplier<DataComponentType<RemoteData>> REMOTE_DATA =
            COMPONENTS.register("remote_data",
                    () -> new DataComponentType.Builder<RemoteData>()
                            .persistent(RemoteData.CODEC)
                            .networkSynchronized(RemoteData.STREAM_CODEC)
                            .build());

    public static void init() {
        COMPONENTS.register();
    }

    private ModDataComponents() {
    }
}
