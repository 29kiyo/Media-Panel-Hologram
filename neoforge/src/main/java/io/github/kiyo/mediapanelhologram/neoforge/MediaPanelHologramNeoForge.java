package io.github.kiyo.mediapanelhologram.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

import io.github.kiyo.mediapanelhologram.MediaPanelHologram;
import io.github.kiyo.mediapanelhologram.neoforge.client.MediaPanelHologramNeoForgeClientEvents;

@Mod(MediaPanelHologram.MOD_ID)
public final class MediaPanelHologramNeoForge {
    public MediaPanelHologramNeoForge() {
        // Run our common setup.
        MediaPanelHologram.init();

        // Spike-only: dev-time MCEF test screen (see MediaPanelHologramNeoForgeClientEvents).
        // Guarded by Dist.CLIENT so the client-only class is never referenced/loaded on a
        // dedicated server, and by the env var so it never registers outside the spike check.
        if (FMLEnvironment.getDist() == Dist.CLIENT && "1".equals(System.getenv("MPH_MCEF_TEST"))) {
            NeoForge.EVENT_BUS.register(MediaPanelHologramNeoForgeClientEvents.class);
        }
    }
}
