package io.github.kiyo.mediapanelhologram.browser;

/**
 * Load-state/error callbacks for one IBrowserSession. Intended for the
 * future "URL無効・地域制限・削除済みのエラーハンドリング表示" task
 * (phase4_design.md) -- onLoadError is where that error text/URL surfaces.
 *
 * Single listener per session (not a list): each session in this MOD is
 * owned by exactly one GUI/renderer, so there is never more than one
 * interested party, unlike MCEFClient's own internal handler list which is
 * shared across every browser on the whole MCEFClient.
 */
public interface BrowserLoadListener {
    default void onLoadingStateChange(boolean isLoading, boolean canGoBack, boolean canGoForward) {
    }

    default void onLoadError(String errorText, String failedUrl) {
    }
}
