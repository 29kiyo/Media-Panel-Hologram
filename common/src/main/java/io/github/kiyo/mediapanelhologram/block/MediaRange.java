package io.github.kiyo.mediapanelhologram.block;

/** Display range (in blocks) limits shared by the data layer, the network handler and the Range screen. */
public final class MediaRange {
    public static final int MIN = 4;
    public static final int MAX = 256;
    public static final int DEFAULT = 32;

    private MediaRange() {
    }

    public static int clamp(int value) {
        return Math.max(MIN, Math.min(MAX, value));
    }
}
