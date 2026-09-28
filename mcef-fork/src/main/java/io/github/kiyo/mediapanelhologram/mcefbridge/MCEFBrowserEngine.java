package io.github.kiyo.mediapanelhologram.mcefbridge;

import com.cinemamod.mcef.MCEF;
import io.github.kiyo.mediapanelhologram.browser.IBrowserEngine;
import io.github.kiyo.mediapanelhologram.browser.IBrowserSession;

import java.util.function.Consumer;

/**
 * The only IBrowserEngine implementation. Registered once per platform via
 * BrowserEngineProvider#setInstance -- see MediaPanelHologramFabricClient /
 * MediaPanelHologramNeoForge's client-dist branch for the two call sites.
 */
public final class MCEFBrowserEngine implements IBrowserEngine {
    @Override
    public boolean isAvailable() {
        return MCEF.isInitialized();
    }

    @Override
    public IBrowserSession createSession(String url, int width, int height) {
        return new MCEFBrowserSession(MCEF.createBrowser(url, false, width, height));
    }

    @Override
    public void whenReady(Consumer<Boolean> callback) {
        if (MCEF.isInitialized()) {
            callback.accept(true);
            return;
        }
        MCEF.scheduleForInit(success -> callback.accept(success));
    }
}
