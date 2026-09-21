package io.github.kiyo.mediapanelhologram.fabric;

import net.fabricmc.api.ModInitializer;

import io.github.kiyo.mediapanelhologram.MediaPanelHologram;

public final class MediaPanelHologramFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.

        // Run our common setup.
        MediaPanelHologram.init();
    }
}
