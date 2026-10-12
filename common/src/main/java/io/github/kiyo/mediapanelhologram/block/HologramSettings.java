package io.github.kiyo.mediapanelhologram.block;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Per-block hologram + screen switches. Immutable.
 * Decimals are stored as integers x1000 (blocks, degrees) so NBT and packets
 * only need int/boolean. opacity is a percent (10-100).
 * screenOn = the block-face picture; holoOn = the floating hologram. Independent.
 */
public record HologramSettings(boolean screenOn, boolean holoOn, int x, int y, int z,
        int width, int height, int pitch, int opacity, boolean autoRatio, boolean marker) {

    public static final int POS_MAX = 256000;
    public static final int SIZE_MIN = 100;
    public static final int SIZE_MAX = 512000;
    public static final int PITCH_MAX = 90000;
    public static final int OPACITY_MIN = 10;
    public static final int OPACITY_MAX = 100;

    public static final HologramSettings DEFAULT =
            new HologramSettings(true, false, 0, 1500, 0, 3200, 1800, 0, 80, true, true);

    public static final StreamCodec<ByteBuf, HologramSettings> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, HologramSettings::screenOn,
            ByteBufCodecs.BOOL, HologramSettings::holoOn,
            ByteBufCodecs.INT, HologramSettings::x,
            ByteBufCodecs.INT, HologramSettings::y,
            ByteBufCodecs.INT, HologramSettings::z,
            ByteBufCodecs.INT, HologramSettings::width,
            ByteBufCodecs.INT, HologramSettings::height,
            ByteBufCodecs.INT, HologramSettings::pitch,
            ByteBufCodecs.INT, HologramSettings::opacity,
            ByteBufCodecs.BOOL, HologramSettings::autoRatio,
            ByteBufCodecs.BOOL, HologramSettings::marker,
            HologramSettings::new);

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Server-side guard: never trust values from a client. */
    public HologramSettings sanitized() {
        return new HologramSettings(screenOn, holoOn,
                clamp(x, -POS_MAX, POS_MAX), clamp(y, -POS_MAX, POS_MAX), clamp(z, -POS_MAX, POS_MAX),
                clamp(width, SIZE_MIN, SIZE_MAX), clamp(height, SIZE_MIN, SIZE_MAX),
                clamp(pitch, -PITCH_MAX, PITCH_MAX), clamp(opacity, OPACITY_MIN, OPACITY_MAX), autoRatio, marker);
    }

    /** Vertical size actually used for drawing (auto ratio = 16:9 from the width). */
    public int effectiveHeight() {
        return autoRatio ? Math.max(SIZE_MIN, (int) ((long) width * 9 / 16)) : height;
    }

    public HologramSettings withScreenOn(boolean v) {
        return new HologramSettings(v, holoOn, x, y, z, width, height, pitch, opacity, autoRatio, marker);
    }

    public HologramSettings withHoloOn(boolean v) {
        return new HologramSettings(screenOn, v, x, y, z, width, height, pitch, opacity, autoRatio, marker);
    }

    public HologramSettings withX(int v) {
        return new HologramSettings(screenOn, holoOn, v, y, z, width, height, pitch, opacity, autoRatio, marker);
    }

    public HologramSettings withY(int v) {
        return new HologramSettings(screenOn, holoOn, x, v, z, width, height, pitch, opacity, autoRatio, marker);
    }

    public HologramSettings withZ(int v) {
        return new HologramSettings(screenOn, holoOn, x, y, v, width, height, pitch, opacity, autoRatio, marker);
    }

    public HologramSettings withWidth(int v) {
        return new HologramSettings(screenOn, holoOn, x, y, z, v, height, pitch, opacity, autoRatio, marker);
    }

    public HologramSettings withHeight(int v) {
        return new HologramSettings(screenOn, holoOn, x, y, z, width, v, pitch, opacity, autoRatio, marker);
    }

    public HologramSettings withPitch(int v) {
        return new HologramSettings(screenOn, holoOn, x, y, z, width, height, v, opacity, autoRatio, marker);
    }

    public HologramSettings withOpacity(int v) {
        return new HologramSettings(screenOn, holoOn, x, y, z, width, height, pitch, v, autoRatio, marker);
    }

    public HologramSettings withAutoRatio(boolean v) {
        return new HologramSettings(screenOn, holoOn, x, y, z, width, height, pitch, opacity, v, marker);
    }

    public HologramSettings withMarker(boolean v) {
        return new HologramSettings(screenOn, holoOn, x, y, z, width, height, pitch, opacity, autoRatio, v);
    }

    public void save(ValueOutput out) {
        out.putBoolean("HoloScreenOn", screenOn);
        out.putBoolean("HoloOn", holoOn);
        out.putInt("HoloX", x);
        out.putInt("HoloY", y);
        out.putInt("HoloZ", z);
        out.putInt("HoloWidth", width);
        out.putInt("HoloHeight", height);
        out.putInt("HoloPitch", pitch);
        out.putInt("HoloOpacity", opacity);
        out.putBoolean("HoloAutoRatio", autoRatio);
        out.putBoolean("HoloMarker", marker);
    }

    public static HologramSettings load(ValueInput in) {
        HologramSettings d = DEFAULT;
        return new HologramSettings(
                in.getBooleanOr("HoloScreenOn", d.screenOn),
                in.getBooleanOr("HoloOn", d.holoOn),
                in.getIntOr("HoloX", d.x), in.getIntOr("HoloY", d.y), in.getIntOr("HoloZ", d.z),
                in.getIntOr("HoloWidth", d.width), in.getIntOr("HoloHeight", d.height),
                in.getIntOr("HoloPitch", d.pitch), in.getIntOr("HoloOpacity", d.opacity),
                in.getBooleanOr("HoloAutoRatio", d.autoRatio),
                in.getBooleanOr("HoloMarker", d.marker)).sanitized();
    }
}
