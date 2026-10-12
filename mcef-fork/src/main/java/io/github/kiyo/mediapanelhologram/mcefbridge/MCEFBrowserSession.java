package io.github.kiyo.mediapanelhologram.mcefbridge;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import io.github.kiyo.mediapanelhologram.browser.BrowserLoadListener;
import io.github.kiyo.mediapanelhologram.browser.IBrowserSession;
import net.minecraft.resources.Identifier;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandler;
import org.cef.network.CefRequest;
import org.jetbrains.annotations.Nullable;

/**
 * Wraps one MCEFBrowser as an IBrowserSession. Every method here is a
 * direct 1:1 pass-through to an MCEFBrowser/MCEFRenderer method whose exact
 * signature was confirmed by reading mcef-fork's own source (MCEFBrowser
 * .java, MCEFRenderer.java) -- see IBrowserSession's Javadoc for the one
 * exception (loadUrl).
 *
 * Load-listener wiring: MCEFClient (obtained via MCEF#getClient()) is a
 * SINGLE shared CefClient-wrapper across every browser MCEF creates -- its
 * addLoadHandler broadcasts every load event for every browser to every
 * registered handler (see MCEFClient.java: onLoadingStateChange/onLoadError
 * simply loop over all registered CefLoadHandlers with no per-browser
 * filtering). So this class registers one CefLoadHandler per session and
 * filters events down to just this session's own `browser` instance before
 * forwarding to the (single, nullable) BrowserLoadListener.
 *
 * KNOWN LIMITATION: MCEFClient (as confirmed by reading its source) only
 * exposes addLoadHandler, not a matching removeLoadHandler -- so the
 * per-session CefLoadHandler registered below is never actually removed
 * from MCEFClient's internal list, even after close(). The `closed` flag
 * makes it a permanent no-op after close() so it can't leak callbacks into
 * dead sessions, but the handler object itself stays referenced for the
 * process lifetime. Acceptable for now (sessions are one-per-open-GUI /
 * one-per-block, not created in a hot loop); if this becomes a real problem
 * later, the fix belongs in mcef-fork's MCEFClient (add
 * removeLoadHandler), since we own that fork.
 */
public final class MCEFBrowserSession implements IBrowserSession {
    @Override
    public String getCurrentUrl() {
        return browser.getURL();
    }

    private final MCEFBrowser browser;
    private volatile boolean closed = false;
    @Nullable
    private volatile java.util.function.Consumer<String> consoleListener;

    @Override
    public void setConsoleListener(@Nullable java.util.function.Consumer<String> listener) {
        this.consoleListener = listener;
    }
    @Nullable
    private volatile BrowserLoadListener loadListener;

    MCEFBrowserSession(MCEFBrowser browser) {
        this.browser = browser;
        // Same pattern as the load handler: MCEFClient broadcasts to every handler, so filter by browser.
        MCEF.getClient().addDisplayHandler(new org.cef.handler.CefDisplayHandlerAdapter() {
            @Override
            public boolean onConsoleMessage(CefBrowser cefBrowser, org.cef.CefSettings.LogSeverity level,
                    String message, String source, int line) {
                java.util.function.Consumer<String> l = consoleListener;
                if (!closed && cefBrowser == browser && l != null && message != null && message.startsWith("MPH-")) {
                    l.accept(message);
                }
                return false;
            }
        });
        MCEF.getClient().addLoadHandler(new CefLoadHandler() {
            @Override
            public void onLoadingStateChange(CefBrowser cefBrowser, boolean isLoading, boolean canGoBack,
                    boolean canGoForward) {
                if (!closed && cefBrowser == browser && loadListener != null) {
                    loadListener.onLoadingStateChange(isLoading, canGoBack, canGoForward);
                }
            }

            @Override
            public void onLoadStart(CefBrowser cefBrowser, CefFrame frame, CefRequest.TransitionType transitionType) {
                // Not exposed on BrowserLoadListener (Phase4 error-handling task only
                // needs loading-state + error text/URL); add here later if needed.
            }

            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                if (!closed && cefBrowser == browser && loadListener != null
                        && httpStatusCode >= 400 && (frame == null || frame.isMain())) {
                    loadListener.onHttpError(httpStatusCode, frame != null ? frame.getURL() : "");
                }
            }

            @Override
            public void onLoadError(CefBrowser cefBrowser, CefFrame frame, ErrorCode errorCode, String errorText,
                    String failedUrl) {
                if (!closed && cefBrowser == browser && loadListener != null) {
                    // ERR_ABORTED = normal navigation interrupted; sub-frame errors are not page errors.
                    if (errorCode != ErrorCode.ERR_ABORTED && (frame == null || frame.isMain())) {
                        loadListener.onLoadError(errorCode.name()
                                + (errorText == null || errorText.isEmpty() ? "" : " - " + errorText), failedUrl);
                    }
                }
            }
        });
    }

    @Override
    public Identifier getTextureLocation() {
        return browser.getTextureLocation();
    }

    @Override
    public boolean isTextureReady() {
        return browser.isTextureReady();
    }

    @Override
    public int getTextureWidth() {
        return browser.getRenderer().getTextureWidth();
    }

    @Override
    public int getTextureHeight() {
        return browser.getRenderer().getTextureHeight();
    }

    @Override
    public void loadUrl(String url) {
        // ASSUMPTION, not yet javap-confirmed -- see IBrowserSession's Javadoc.
        browser.loadURL(url);
    }

    @Override
    public void executeJavaScript(String code) {
        if (!closed) {
            browser.executeJavaScript(code, browser.getURL(), 0);
        }
    }

    @Override
    public void resize(int width, int height) {
        browser.resize(width, height);
    }

    @Override
    public double getZoomLevel() {
        return browser.getZoomLevel();
    }

    @Override
    public void setZoomLevel(double level) {
        browser.setZoomLevel(level);
    }

    @Override
    public boolean canGoBack() {
        return browser.canGoBack();
    }

    @Override
    public void goBack() {
        browser.goBack();
    }

    @Override
    public boolean canGoForward() {
        return browser.canGoForward();
    }

    @Override
    public void goForward() {
        browser.goForward();
    }

    @Override
    public void reload() {
        browser.reload();
    }

    @Override
    public void sendMouseMove(int mouseX, int mouseY) {
        browser.sendMouseMove(mouseX, mouseY);
    }

    @Override
    public void sendMousePress(int mouseX, int mouseY, int button) {
        browser.sendMousePress(mouseX, mouseY, button);
    }

    @Override
    public void sendMouseRelease(int mouseX, int mouseY, int button) {
        browser.sendMouseRelease(mouseX, mouseY, button);
    }

    @Override
    public void sendMouseWheel(int mouseX, int mouseY, double amount, int modifiers) {
        browser.sendMouseWheel(mouseX, mouseY, amount, modifiers);
    }

    @Override
    public void sendKeyPress(int keyCode, long scanCode, int modifiers) {
        browser.sendKeyPress(keyCode, scanCode, modifiers);
    }

    @Override
    public void sendKeyRelease(int keyCode, long scanCode, int modifiers) {
        browser.sendKeyRelease(keyCode, scanCode, modifiers);
    }

    @Override
    public void sendKeyTyped(char character, int modifiers) {
        browser.sendKeyTyped(character, modifiers);
    }

    @Override
    public void setLoadListener(@Nullable BrowserLoadListener listener) {
        this.loadListener = listener;
    }

    @Override
    public void close() {
        closed = true;
        browser.close();
    }
}
