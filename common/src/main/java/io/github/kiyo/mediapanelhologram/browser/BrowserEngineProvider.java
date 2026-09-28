package io.github.kiyo.mediapanelhologram.browser;

import org.jetbrains.annotations.Nullable;

/**
 * Static access point for the current IBrowserEngine implementation.
 *
 * Why a plain static holder instead of @ExpectPlatform: mcef-fork (which
 * owns the only implementation, MCEFBrowserEngine) is itself a
 * platform-independent module already shaded into both the Fabric and
 * NeoForge jars identically (see settings.gradle / fabric+neoforge
 * build.gradle "common" configuration) -- there is no per-platform
 * difference to dispatch on, so @ExpectPlatform would just add a layer of
 * indirection around two identical implementations. Each platform's client
 * entrypoint (MediaPanelHologramFabricClient#onInitializeClient,
 * MediaPanelHologramNeoForge's Dist.CLIENT branch) calls setInstance(...)
 * exactly once during client startup.
 *
 * Client-side only: never call get()/setInstance() from server-only code
 * paths (dedicated server never has mcef-fork's CEF native libs loaded).
 */
public final class BrowserEngineProvider {
    @Nullable
    private static IBrowserEngine instance;

    private BrowserEngineProvider() {
    }

    public static void setInstance(IBrowserEngine engine) {
        instance = engine;
    }

    @Nullable
    public static IBrowserEngine get() {
        return instance;
    }

    /**
     * Convenience: true only if an engine has been registered AND that
     * engine reports isAvailable() (CEF finished initializing).
     */
    public static boolean isAvailable() {
        return instance != null && instance.isAvailable();
    }
}
