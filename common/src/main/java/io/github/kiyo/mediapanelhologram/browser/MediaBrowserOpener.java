package io.github.kiyo.mediapanelhologram.browser;

import net.minecraft.core.BlockPos;

/**
 * Opens the client-only direct-control browser screen for the Media Block
 * at the given position. The only implementation lives client-side
 * (MediaBrowserScreen, in the client.screen package) and is registered via
 * MediaBrowserOpenerProvider from each platform's client entrypoint --
 * exact same indirection pattern as IBrowserEngine/BrowserEngineProvider,
 * for the exact same reason: MediaBlock (this interface's only caller) is
 * common-module code that also loads on a dedicated server, so it must
 * never import the client-only Screen class directly.
 */
public interface MediaBrowserOpener {
    void open(BlockPos pos);
}
