package io.github.kiyo.mediapanelhologram.block;

/**
 * Browser texture quality (16:9). Ordinal is persisted (NBT) and sent over
 * the network via idMapper: DO NOT reorder without a migration plan.
 */
public enum QualityPreset {
    P480("480p", 854, 480, -1),
    P720("720p", 1280, 720, 2),
    P1080("1080p", 1920, 1080, 4),
    P1440("1440p", 2560, 1440, 5),
    P2160("4K", 3840, 2160, 8);

    public static final QualityPreset DEFAULT = P720;

    private final String label;
    private final int width;
    private final int height;
    private final int baseZoomLevel;

    QualityPreset(String label, int width, int height, int baseZoomLevel) {
        this.baseZoomLevel = baseZoomLevel;
        this.label = label;
        this.width = width;
        this.height = height;
    }

    public String label() { return label; }
    public int width() { return width; }
    public int height() { return height; }

    /** CEF zoom step (1.2^n) that keeps the effective layout width near 960px, so text size stays similar across qualities. */
    public int baseZoomLevel() { return baseZoomLevel; }

    public QualityPreset next() {
        QualityPreset[] v = values();
        return v[(ordinal() + 1) % v.length];
    }

    public static QualityPreset fromOrdinal(int i) {
        QualityPreset[] v = values();
        return (i >= 0 && i < v.length) ? v[i] : DEFAULT;
    }
}
