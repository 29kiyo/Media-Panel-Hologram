package io.github.kiyo.mediapanelhologram.config;

/**
 * Placeholder for the mod's config system. The actual file-based config
 * loading approach (Fabric vs NeoForge, or a common Architectury config
 * lib) hasn't been decided yet -- tracked as a follow-up. For now this
 * just hardcodes the default max panel group size per CLAUDE.md 1.2
 * (default 16x16, must become config-changeable later).
 */
public final class PanelConfig {
    private static final int DEFAULT_MAX_GROUP_SIZE = 16;

    public static int maxGroupSize() {
        return DEFAULT_MAX_GROUP_SIZE;
    }

    private PanelConfig() {
    }
}
