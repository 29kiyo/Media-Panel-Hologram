package io.github.kiyo.mediapanelhologram.block;

import java.util.Locale;

/**
 * Display mode for a MediaBlock (Phase4, "モード切替UI" task).
 * Ordinal is persisted directly (NBT) and sent over the network as a raw
 * int index via ByteBufCodecs#idMapper, so DO NOT reorder these constants
 * in a released version without a migration plan.
 */
public enum MediaMode {
    IMAGE,
    VIDEO,
    MUSIC,
    BROWSER;

    public String translationKey() {
        return "mediapanelhologram.mode." + name().toLowerCase(Locale.ROOT);
    }
}
