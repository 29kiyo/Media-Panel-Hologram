package io.github.kiyo.mediapanelhologram.browser;

import java.util.function.Consumer;

/**
 * Facade over "is the embedded-browser engine available, and can I create a
 * session". Obtained via BrowserEngineProvider#get(); common-module code
 * must never import anything from com.cinemamod.mcef.* directly (CLAUDE.md
 * "ブラウザ/動画埋め込み方針" -- this interface + IBrowserSession is that
 * abstraction layer). The only implementation is mcef-fork's
 * MCEFBrowserEngine.
 */
public interface IBrowserEngine {
    /**
     * Mirrors MCEF#isInitialized: whether CEF has finished starting up and
     * createSession can be called. CEF init is asynchronous and, per the
     * Phase1 spike notes, is only pumped forward by screen transitions --
     * so this can legitimately be false for a while after the game starts.
     */
    boolean isAvailable();

    /**
     * Creates and immediately starts loading a new browser session at the
     * given pixel size. Caller must call the returned session's close()
     * exactly once when done. Throws (mirrors MCEF#createBrowser's own
     * assertInitialized) if isAvailable() is false -- callers should check
     * isAvailable() or use whenReady() first.
     */
    IBrowserSession createSession(String url, int width, int height);

    /**
     * Runs callback (with true/false success) once CEF finishes
     * initializing, or immediately if it already has. Mirrors
     * MCEF#scheduleForInit; use this instead of polling isAvailable() in a
     * tick loop where possible.
     */
    void whenReady(Consumer<Boolean> callback);
}
