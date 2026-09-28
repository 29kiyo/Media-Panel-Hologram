package io.github.kiyo.mediapanelhologram.browser;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Static access point for the current MediaBrowserOpener implementation.
 * Mirrors BrowserEngineProvider's Javadoc/rationale exactly -- see that
 * class. Each platform's client entrypoint calls setInstance(...) once
 * during client startup, right alongside its BrowserEngineProvider
 * .setInstance(...) call.
 *
 * Client-side only: MediaBlock only ever calls open() from inside a
 * level.isClientSide() branch, so get()/open() being a no-op on a
 * dedicated server (instance stays null there) is expected, not an error.
 */
public final class MediaBrowserOpenerProvider {
    @Nullable
    private static MediaBrowserOpener instance;

    private MediaBrowserOpenerProvider() {
    }

    public static void setInstance(MediaBrowserOpener opener) {
        instance = opener;
    }

    public static void open(BlockPos pos) {
        if (instance != null) {
            instance.open(pos);
        }
    }
}
