package io.github.kiyo.mediapanelhologram.block;

/**
 * Browser texture quality (16:9). Ordinal is persisted (NBT) and sent over
 * the network via idMapper: DO NOT reorder without a migration plan.
 */
public enum QualityPreset {
    P480("480p", 854, 480),
    P720("720p", 1280, 720),
    P1080("1080p", 1920, 1080),
    P1440("1440p", 2560, 1440),
    P2160("4K", 3840, 2160);

    public static final QualityPreset DEFAULT = P720;

    private final String label;
    private final int width;
    private final int height;

    QualityPreset(String label, int width, int height) {
        this.label = label;
        this.width = width;
        this.height = height;
    }

    public String label() { return label; }
    public int width() { return width; }
    public int height() { return height; }

    public QualityPreset next() {
        QualityPreset[] v = values();
        return v[(ordinal() + 1) % v.length];
    }

    public static QualityPreset fromOrdinal(int i) {
        QualityPreset[] v = values();
        return (i >= 0 && i < v.length) ? v[i] : DEFAULT;
    }
}
