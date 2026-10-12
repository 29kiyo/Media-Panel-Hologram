package io.github.kiyo.mediapanelhologram.item;

import net.minecraft.world.InteractionHand;

/**
 * Opens the client-only remote control screen for the remote held in the
 * given hand. Same indirection as MediaBrowserOpener: RemoteItem lives in
 * common code that also loads on a dedicated server, so it must not import
 * the client Screen class.
 */
public interface RemoteOpener {
    void open(InteractionHand hand);
}
