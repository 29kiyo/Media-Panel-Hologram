package io.github.kiyo.mediapanelhologram;

import io.github.kiyo.mediapanelhologram.network.ModNetworking;
import io.github.kiyo.mediapanelhologram.registry.ModBlockEntities;
import io.github.kiyo.mediapanelhologram.registry.ModBlocks;
import io.github.kiyo.mediapanelhologram.registry.ModCreativeTabs;
import io.github.kiyo.mediapanelhologram.registry.ModItems;
import io.github.kiyo.mediapanelhologram.registry.ModMenuTypes;

public final class MediaPanelHologram {
    public static final String MOD_ID = "mediapanelhologram";

    public static void init() {
        ModBlocks.init();
        ModItems.init();
        ModBlockEntities.init();
        ModMenuTypes.init();
        ModCreativeTabs.init();
        ModNetworking.init();
    }
}
