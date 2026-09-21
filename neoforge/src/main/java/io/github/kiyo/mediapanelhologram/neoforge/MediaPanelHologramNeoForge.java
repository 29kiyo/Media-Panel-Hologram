package io.github.kiyo.mediapanelhologram.neoforge;

import net.neoforged.fml.common.Mod;

import io.github.kiyo.mediapanelhologram.MediaPanelHologram;

@Mod(MediaPanelHologram.MOD_ID)
public final class MediaPanelHologramNeoForge {
    public MediaPanelHologramNeoForge() {
        // Run our common setup.
        MediaPanelHologram.init();
    }
}
