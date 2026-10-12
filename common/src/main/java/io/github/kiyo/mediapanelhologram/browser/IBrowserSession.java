package io.github.kiyo.mediapanelhologram.browser;

import net.minecraft.resources.Identifier;

/**
 * A single embedded-browser instance, abstracted away from MCEF/CEF types.
 * Common-module code (blocks, block entities, renderers, screens, network
 * payloads) is only ever allowed to talk to a browser through this
 * interface -- never through com.cinemamod.mcef.* directly (see CLAUDE.md
 * "ブラウザ/動画埋め込み方針"). The only implementation right now is
 * mcef-fork's MCEFBrowserSession, obtained via IBrowserEngine#createSession.
 *
 * Method names/signatures deliberately mirror MCEFBrowser's own methods
 * 1:1 (sendMouseMove/sendMousePress/... etc.) since those already match
 * the shapes callers need (GUI mouse/keyboard forwarding). This keeps the
 * wrapper in MCEFBrowserSession a pure pass-through with no translation
 * logic to get wrong.
 */
public interface IBrowserSession {
    /**
     * The Identifier this session's texture is registered under with the
     * TextureManager. Usable directly with GuiGraphicsExtractor#blit.
     */
    Identifier getTextureLocation();

    boolean isTextureReady();

    int getTextureWidth();

    int getTextureHeight();

    /**
     * Assumption (NOT YET javap-confirmed): backed by CefBrowser#loadURL.
     * Confirm against the CefBrowser/CefBrowserOsr javap output before
     * relying on this in real GUI code; update MCEFBrowserSession if the
     * actual method name differs.
     */
    void loadUrl(String url);

    /** The page's current URL (follows in-page navigation), or null if unknown. */
    String getCurrentUrl();

    /** Runs JavaScript in the page's main frame (backed by CefBrowser#executeJavaScript). */
    void executeJavaScript(String code);

    void resize(int width, int height);

    /**
     * The page's own zoom level (CEF/Chromium's native concept -- NOT this
     * MOD's in-world texture size). 0.0 is 100%; each whole step is roughly
     * a further +/-20% (Chromium's own logarithmic zoom scale, not a linear
     * percentage) -- confirmed via mcef-fork's own MCEFBrowser#sendKeyPress,
     * which clamps this to the range -9..9 for its Ctrl+/- browser-control
     * shortcut.
     */
    double getZoomLevel();

    /** See getZoomLevel's Javadoc for the unit. Callers should clamp to roughly -9..9, matching MCEFBrowser's own shortcut. */
    void setZoomLevel(double level);

    boolean canGoBack();

    void goBack();

    boolean canGoForward();

    void goForward();

    void reload();

    void sendMouseMove(int mouseX, int mouseY);

    void sendMousePress(int mouseX, int mouseY, int button);

    void sendMouseRelease(int mouseX, int mouseY, int button);

    void sendMouseWheel(int mouseX, int mouseY, double amount, int modifiers);

    void sendKeyPress(int keyCode, long scanCode, int modifiers);

    void sendKeyRelease(int keyCode, long scanCode, int modifiers);

    void sendKeyTyped(char character, int modifiers);

    /**
     * Registers the (single) listener for this session's load-state/error
     * events. Pass null to clear. See BrowserLoadListener's Javadoc for why
     * this is a single slot rather than a list.
     */
    void setLoadListener(BrowserLoadListener listener);

    /** Receives page console messages that start with "MPH-" (any thread). Pass null to clear. */
    void setConsoleListener(java.util.function.Consumer<String> listener);

    /**
     * Releases the underlying browser and its GPU texture. Must be called
     * exactly once when this session is no longer needed (e.g. block
     * removed, GUI closed for a browser owned by that GUI) -- mirrors
     * MCEFBrowser#close's own one-shot cleanup contract.
     */
    void close();
}
