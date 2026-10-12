package io.github.kiyo.mediapanelhologram.block.entity;

import net.minecraft.core.BlockPos;

import io.github.kiyo.mediapanelhologram.block.MediaMode;
import io.github.kiyo.mediapanelhologram.block.QualityPreset;

/**
 * Anything that owns a URL / mode / quality and can show a browser session:
 * MediaBlockEntity now, PanelBlockEntity (group representative) later.
 * Screens, networking and the session manager depend on this instead of
 * MediaBlockEntity.
 */
public interface MediaSource {
    String getUrl();

    MediaMode getMode();

    QualityPreset getQuality();

    void setUrlAndMode(String url, MediaMode mode);

    void setQuality(QualityPreset quality);

    /** Persisted volume 0..100 (set through the server); -1 = never set, the page keeps its own. */
    int getVolume();

    boolean isMuted();

    void setVolumeState(int volume, boolean muted);

    /** Lock state (owner + allowed players). LockData.NONE = unlocked. */
    io.github.kiyo.mediapanelhologram.block.LockData getLock();

    void setLock(io.github.kiyo.mediapanelhologram.block.LockData lock);

    BlockPos getBlockPos();

    /** How far (in blocks) a player may be for this screen/hologram to be shown. 4..256, default 32. */
    int getRange();

    void setRange(int range);

    /** Provided by BlockEntity; declared here so callers can check liveness via the interface. */
    boolean isRemoved();

    /** False when this entry's browser session key is no longer valid (e.g. merged-away panel). */
    default boolean ownsSession() {
        return true;
    }

    /** Key for the browser session. Panels will override this with the group origin. */
    default BlockPos getSessionKey() {
        return getBlockPos();
    }
}
