package io.github.kiyo.mediapanelhologram.client.screen;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import dev.architectury.networking.NetworkManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import io.github.kiyo.mediapanelhologram.block.MediaMode;
import io.github.kiyo.mediapanelhologram.block.entity.MediaBlockEntity;
import io.github.kiyo.mediapanelhologram.browser.IBrowserSession;
import io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager;
import io.github.kiyo.mediapanelhologram.client.renderer.MediaBlockEntityRenderer;
import io.github.kiyo.mediapanelhologram.network.MediaBlockUpdatePayload;

/**
 * Direct mouse/keyboard control of a Media Block's embedded browser, no
 * remote required (Phase4, "ブロック単体でのブラウザ直接操作モード").
 * Opened via empty-hand right-click on a BROWSER-mode block with no
 * camouflage set -- see MediaBlock#useWithoutItem and
 * MediaBrowserOpenerProvider's Javadoc for why that call goes through an
 * indirection instead of MediaBlock constructing this class directly.
 *
 * TABS REMOVED: a browser-tabs feature was implemented and reverted at the
 * user's request after several actual-run bugs (see MediaBlockEntity's
 * Javadoc for the details). This class is back to controlling a single
 * IBrowserSession per block, keyed by BlockPos alone.
 *
 * PAGE ZOOM: IBrowserSession#getZoomLevel/setZoomLevel -- CEF/Chromium's
 * own native page zoom (same effect as Ctrl+/- in a real browser). Known
 * limitation, user-confirmed and accepted: doesn't reliably affect YouTube
 * (its own JS-driven player layout fights the zoom).
 *
 * ADDRESS BAR: a top strip (ADDRESS_BAR_HEIGHT px) holds a URL EditBox +
 * "Go" button. Input is resolved via resolveNavigationTarget (URL vs.
 * search-phrase heuristic). Submitting it sends the SAME C2S
 * MediaBlockUpdatePayload MediaBlockScreen's "Set URL" button sends --
 * deliberately NOT a direct session.loadUrl() call; see
 * MediaBlockSyncPayload's Javadoc for why a server round trip is required.
 *
 * NAV/ZOOM ROW: a second strip (NAV_ROW_HEIGHT px) holds -/+/reset zoom
 * buttons and back/forward/reload/home navigation buttons. Home's target is
 * a fixed constant (HOME_URL), not this block's own persisted url --
 * user-confirmed ("ホームボタンの戻り先"). goHome() bypasses the usual
 * url-diff-based reload check and calls session.loadUrl() directly (see its
 * Javadoc) since client-side page navigation, e.g. Google's own search
 * results routing, doesn't change this MOD's persisted url on its own.
 *
 * CONTENT MARGIN: drawRect scales the browser texture DOWN (never up) to
 * fit within CONTENT_MARGIN of the window edges on every side, preserving
 * aspect ratio -- added after user feedback that the browser filled the
 * window edge-to-edge with no breathing room. toBrowserX/Y convert screen
 * clicks back to native texture-pixel coordinates by dividing out the same
 * scale factor (a click-position bug from forgetting this was found and
 * fixed via an actual run).
 *
 * NOT a menu/AbstractContainerScreen: nothing here is server-authoritative
 * beyond the C2S payload sent on navigation -- a plain Screen is sufficient
 * to OPEN it.
 *
 * OWN-WIDGET DRAWING: same requirement/fix as MediaBlockScreen -- widgets
 * are tracked in ownWidgets and drawn explicitly.
 *
 * REENTRANT-OPEN GUARD: openFrom() no-ops if a MediaBrowserScreen is
 * already the active screen.
 *
 * KNOWN GAPS (not addressed by this revision, candidates for later):
 * - No reach/distance check.
 * - mouseScrolled's modifiers are always passed as 0.
 * - charTyped truncates codepoints outside the Basic Multilingual Plane.
 */
@Environment(EnvType.CLIENT)
public final class MediaBrowserScreen extends Screen {
    /** Dev only: F6-F10 call window.mph.* on mph.local pages (set env MPH_JS_TEST=1). */
    private static final boolean DEV_JS_TEST = "1".equals(System.getenv("MPH_JS_TEST"));
    private static final double[] DEV_RATES = {1.0, 1.5, 2.0, 0.5};
    private int devRateIndex = 0;

    private double nextDevRate() {
        devRateIndex = (devRateIndex + 1) % DEV_RATES.length;
        return DEV_RATES[devRateIndex];
    }
    private static final int ADDRESS_BAR_HEIGHT = 24;
    private static final int NAV_ROW_HEIGHT = 24;
    private static final int MEDIA_ROW_HEIGHT = 24;
    private static final int TOP_BAR_HEIGHT = ADDRESS_BAR_HEIGHT + NAV_ROW_HEIGHT + MEDIA_ROW_HEIGHT;

    private static final double ZOOM_STEP = 1.0;
    private static final double ZOOM_MIN = -9.0;
    private static final double ZOOM_MAX = 9.0;

    private static final String HOME_URL = "https://www.google.com";

    private static final Pattern BARE_DOMAIN = Pattern.compile(
            "^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+(:[0-9]+)?([/?#].*)?$");

    /** Margin kept between the browser content and the window edges on every side. */
    private static final int CONTENT_MARGIN = 16;

    private final BlockPos pos;
    private final List<Renderable> ownWidgets = new ArrayList<>();
    private EditBox urlBox;

    public MediaBrowserScreen(BlockPos pos) {
        super(Component.translatable("gui.mediapanelhologram.media_block.browser_control"));
        this.pos = pos;
    }

    public static void openFrom(BlockPos pos) {
        if (Minecraft.getInstance().gui.screen() instanceof MediaBrowserScreen) {
            return;
        }
        Minecraft.getInstance().setScreenAndShow(new MediaBrowserScreen(pos));
    }

    private <T extends Renderable & net.minecraft.client.gui.components.events.GuiEventListener
            & net.minecraft.client.gui.narration.NarratableEntry> T addOwnWidget(T widget) {
        T added = this.addRenderableWidget(widget);
        this.ownWidgets.add(added);
        return added;
    }

    @Override
    protected void init() {
        super.init();
        this.ownWidgets.clear();

        MediaBlockEntity mediaBe = findBlockEntity();
        String initialUrl = mediaBe != null ? (mediaBe.getMode() == MediaMode.VIDEO ? mediaBe.getUrl()
                : MediaBlockEntityRenderer.resolveContentUrl(mediaBe)) : "";

        this.urlBox = addOwnWidget(new EditBox(this.font, 8, 4, this.width - 90, 16,
                Component.translatable("gui.mediapanelhologram.media_block.url")));
        this.urlBox.setMaxLength(256);
        this.urlBox.setValue(initialUrl);

        addOwnWidget(Button.builder(Component.translatable("gui.mediapanelhologram.media_block.go"), b -> navigate())
                .bounds(this.width - 78, 4, 70, 16)
                .build());

        addOwnWidget(Button.builder(Component.literal("-"), b -> adjustZoom(-ZOOM_STEP))
                .bounds(8, ADDRESS_BAR_HEIGHT + 4, 20, 16)
                .build());
        addOwnWidget(Button.builder(Component.literal("+"), b -> adjustZoom(ZOOM_STEP))
                .bounds(30, ADDRESS_BAR_HEIGHT + 4, 20, 16)
                .build());
        addOwnWidget(Button.builder(Component.translatable("gui.mediapanelhologram.media_block.zoom_reset"),
                        b -> setZoom(baseZoom()))
                .bounds(54, ADDRESS_BAR_HEIGHT + 4, 50, 16)
                .build());

        addOwnWidget(Button.builder(Component.literal("<"), b -> goBack())
                .bounds(112, ADDRESS_BAR_HEIGHT + 4, 20, 16)
                .build());
        addOwnWidget(Button.builder(Component.literal(">"), b -> goForward())
                .bounds(134, ADDRESS_BAR_HEIGHT + 4, 20, 16)
                .build());
        addOwnWidget(Button.builder(Component.literal("\u21bb"), b -> reload())
                .bounds(156, ADDRESS_BAR_HEIGHT + 4, 20, 16)
                .build());
        addOwnWidget(Button.builder(Component.translatable("gui.mediapanelhologram.media_block.home"),
                        b -> goHome())
                .bounds(178, ADDRESS_BAR_HEIGHT + 4, 50, 16)
                .build());

        // Media control row: calls window.mph.* (defined by the mph.local player pages).
        addMediaButton("-10", 8, 30, () -> mph("seekBy(-10)"));
        addMediaButton("Play", 40, 40, () -> mph("toggle()"));
        addMediaButton("+10", 82, 30, () -> mph("seekBy(10)"));
        addMediaButton("Vol-", 118, 36, () -> adjustVolume(-0.1));
        addMediaButton("Vol+", 156, 36, () -> adjustVolume(0.1));
        addMediaButton("Mute", 198, 40, () -> mph("toggleMute()"));
        addMediaButton("Speed", 240, 46, () -> mph("setRate(" + nextDevRate() + ")"));
        addMediaButton("CC", 288, 30, () -> mph("toggleCaptions()"));
        // Playlist buttons are grouped at the right edge (total width 130).
        int plX = Math.max(322, this.width - 8 - 130);
        addMediaButton("|<", plX, 24, () -> mph("prev()"));
        addMediaButton(">|", plX + 26, 24, () -> mph("next()"));
        addMediaButton("Shuf", plX + 52, 34, () -> mph("toggleShuffle()"));
        addMediaButton("Queue", plX + 88, 42, () -> mph("toggleQueue()"));
    }

    /** Reset target: the current block's quality base zoom (0 if the block is gone). */
    private double baseZoom() {
        MediaBlockEntity be = findBlockEntity();
        return be != null ? be.getQuality().baseZoomLevel() : 0.0;
    }

    /**
     * Fallback window.mph for ordinary pages (best effort): operates the largest <video> in the main
     * frame. Does nothing if there is no video, it sits in a cross-origin iframe, or the site uses
     * DRM. Pages under https://mph.local/ already define their own window.mph, so this is skipped.
     */
    private static final String GENERIC_MPH = """
            if(!window.mph){(function(){
            function vid(){var best=null,area=-1;
              Array.prototype.slice.call(document.querySelectorAll('video')).forEach(function(v){
                var r=v.getBoundingClientRect();var a=r.width*r.height;if(a>area){area=a;best=v;}});
              if(best){return best;}
              var au=Array.prototype.slice.call(document.querySelectorAll('audio'));
              for(var i=0;i<au.length;i++){if(!au[i].paused){return au[i];}}
              return au.length?au[0]:null;}
            function w(f){return function(x){var v=vid();if(v){f(v,x);}};}
            window.mph={
              play:w(function(v){v.play();}),
              pause:w(function(v){v.pause();}),
              toggle:w(function(v){if(v.paused){v.play();}else{v.pause();}}),
              setVolume:w(function(v,x){v.volume=Math.max(0,Math.min(1,x));}),
              setMuted:w(function(v,x){v.muted=!!x;}),
              setRate:w(function(v,x){
                window.__mphRate=x;v.playbackRate=x;
                if(!v.__mphHook){v.__mphHook=true;
                  v.addEventListener('ratechange',function(){
                    var r=window.__mphRate;
                    if(r&&r!==1&&v.playbackRate!==r){v.playbackRate=r;}
                  });}
              }),
              seek:w(function(v,x){v.currentTime=x;}),
              seekBy:w(function(v,x){v.currentTime=Math.max(0,v.currentTime+x);}),
              volumeBy:w(function(v,x){v.volume=Math.max(0,Math.min(1,v.volume+x));}),
              toggleMute:w(function(v){if(!v.muted){window.__mphVol=v.volume;v.muted=true;}else{v.muted=false;if(v.volume===0){v.volume=(window.__mphVol>0)?window.__mphVol:0.5;}}}),
              next:function(){var b=document.querySelector('.ytp-next-button');if(b){b.click();}}
            };})();}
            """;

    private static String mphScript(String call) {
        return GENERIC_MPH + "try{window.mph." + call + ";}catch(e){}";
    }

    private void mph(String call) {
        IBrowserSession session = currentSession();
        if (session != null) {
            session.executeJavaScript(mphScript(call));
        }
    }

    private void addMediaButton(String label, int x, int w, Runnable action) {
        addOwnWidget(Button.builder(Component.literal(label), b -> action.run())
                .bounds(x, ADDRESS_BAR_HEIGHT + NAV_ROW_HEIGHT + 4, w, 16)
                .build());
    }

    private void adjustVolume(double delta) {
        mph("volumeBy(" + delta + ")");
    }

    private void adjustZoom(double delta) {
        IBrowserSession session = currentSession();
        if (session != null) {
            setZoom(session.getZoomLevel() + delta);
        }
    }

    private void setZoom(double level) {
        IBrowserSession session = currentSession();
        if (session != null) {
            session.setZoomLevel(Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, level)));
        }
    }

    private void goBack() {
        IBrowserSession session = currentSession();
        if (session != null && session.canGoBack()) {
            session.goBack();
        }
    }

    private void goForward() {
        IBrowserSession session = currentSession();
        if (session != null && session.canGoForward()) {
            session.goForward();
        }
    }

    private void reload() {
        IBrowserSession session = currentSession();
        if (session != null) {
            session.reload();
        }
    }

    private void goHome() {
        navigateTo(HOME_URL);
        IBrowserSession session = currentSession();
        if (session != null) {
            session.loadUrl(HOME_URL);
        }
    }

    private static String resolveNavigationTarget(String input) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        if (trimmed.indexOf(' ') < 0) {
            if (trimmed.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*$")) {
                return trimmed;
            }
            if (BARE_DOMAIN.matcher(trimmed).matches()) {
                return "https://" + trimmed;
            }
        }
        String encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8);
        return "https://www.google.com/search?q=" + encoded;
    }

    private void navigateTo(String url) {
        MediaBlockEntity currentBe = findBlockEntity();
        io.github.kiyo.mediapanelhologram.block.QualityPreset currentQuality = currentBe != null
                ? currentBe.getQuality() : io.github.kiyo.mediapanelhologram.block.QualityPreset.DEFAULT;
        NetworkManager.sendToServer(new MediaBlockUpdatePayload(this.pos, url, currentBe != null ? currentBe.getMode() : MediaMode.BROWSER, currentQuality));
    }

    private void navigate() {
        navigateTo(resolveNavigationTarget(this.urlBox.getValue()));
    }

    private MediaBlockEntity findBlockEntity() {
        var level = Minecraft.getInstance().level;
        if (level != null && level.getBlockEntity(pos) instanceof MediaBlockEntity mediaBe) {
            return mediaBe;
        }
        return null;
    }

    private IBrowserSession currentSession() {
        MediaBlockEntity mediaBe = findBlockEntity();
        if (mediaBe == null) {
            return null;
        }
        String url = MediaBlockEntityRenderer.resolveContentUrl(mediaBe);
        return ClientBrowserSessionManager.getOrCreate(pos, url);
    }

    private int[] drawRect(IBrowserSession session) {
        int texW = session.getTextureWidth();
        int texH = session.getTextureHeight();
        int maxW = Math.max(1, this.width - CONTENT_MARGIN * 2);
        int maxH = Math.max(1, (this.height - TOP_BAR_HEIGHT) - CONTENT_MARGIN * 2);
        double scale = Math.min(1.0, Math.min((double) maxW / texW, (double) maxH / texH));
        int drawW = (int) Math.round(texW * scale);
        int drawH = (int) Math.round(texH * scale);
        int x = (this.width - drawW) / 2;
        int areaHeight = this.height - TOP_BAR_HEIGHT;
        int y = TOP_BAR_HEIGHT + Math.max(0, (areaHeight - drawH) / 2);
        return new int[] {x, y, drawW, drawH};
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (findBlockEntity() == null) {
            this.onClose();
            return;
        }

        graphics.fill(0, 0, this.width, this.height, 0xC0101010);
        if (mouseY < TOP_BAR_HEIGHT) {
            // The page may have hidden the cursor (e.g. a video player idling) and cannot
            // learn that the mouse moved onto our own bar, so un-hide it here every frame.
            GLFW.glfwSetInputMode(Minecraft.getInstance().getWindow().handle(),
                    GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        }
        graphics.fill(0, 0, this.width, TOP_BAR_HEIGHT, 0xFF202225);
        MediaBlockEntity modeBe = findBlockEntity();
        if (modeBe != null) {
            Component modeLabel = Component.translatable(modeBe.getMode().translationKey());
            graphics.text(this.font, modeLabel, this.width - 8 - this.font.width(modeLabel),
                    ADDRESS_BAR_HEIGHT + 8, 0xFF4FD1C5);
        }

        IBrowserSession session = currentSession();
        if (session != null && session.isTextureReady()) {
            int[] rect = drawRect(session);
            int texW = session.getTextureWidth();
            int texH = session.getTextureHeight();
            graphics.blit(RenderPipelines.GUI_TEXTURED, session.getTextureLocation(),
                    rect[0], rect[1], 0f, 0f, rect[2], rect[3], texW, texH, texW, texH);
            String[] httpError = ClientBrowserSessionManager.getHttpError(pos);
            if (httpError != null) {
                graphics.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + 14, 0xE0802020);
                graphics.text(this.font, Component.translatable(
                        "gui.mediapanelhologram.media_block.http_error", httpError[0]),
                        rect[0] + 6, rect[1] + 3, 0xFFFFFFFF);
            }
            String[] loadError = ClientBrowserSessionManager.getError(pos);
            if (loadError != null) {
                graphics.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3], 0xE0101010);
                int textMaxW = Math.max(10, rect[2] - 32);
                graphics.text(this.font,
                        Component.translatable("gui.mediapanelhologram.media_block.load_error"),
                        rect[0] + 16, rect[1] + 16, 0xFFFF6B6B);
                graphics.text(this.font, Component.literal(this.font.plainSubstrByWidth(loadError[0], textMaxW)),
                        rect[0] + 16, rect[1] + 32, 0xFFFFFFFF);
                graphics.text(this.font, Component.literal(this.font.plainSubstrByWidth(loadError[1], textMaxW)),
                        rect[0] + 16, rect[1] + 48, 0xFFAAAAAA);
            }
        } else {
            graphics.text(this.font, Component.translatable("gui.mediapanelhologram.media_block.browser_loading"),
                    10, TOP_BAR_HEIGHT + 10, 0xFFFFFFFF);
        }

        for (Renderable widget : ownWidgets) {
            widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
    }

    private int toBrowserX(double screenX, IBrowserSession session) {
        int[] rect = drawRect(session);
        double localOnScreen = screenX - rect[0];
        double scale = rect[2] / (double) session.getTextureWidth();
        double localNative = localOnScreen / scale;
        return (int) Math.max(0, Math.min(session.getTextureWidth() - 1, localNative));
    }

    private int toBrowserY(double screenY, IBrowserSession session) {
        int[] rect = drawRect(session);
        double localOnScreen = screenY - rect[1];
        double scale = rect[3] / (double) session.getTextureHeight();
        double localNative = localOnScreen / scale;
        return (int) Math.max(0, Math.min(session.getTextureHeight() - 1, localNative));
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX, mouseY);
        if (mouseY < TOP_BAR_HEIGHT) {
            return;
        }
        IBrowserSession session = currentSession();
        if (session != null && session.isTextureReady()) {
            session.sendMouseMove(toBrowserX(mouseX, session), toBrowserY(mouseY, session));
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.y() < TOP_BAR_HEIGHT) {
            return super.mouseClicked(event, doubleClick);
        }
        IBrowserSession session = currentSession();
        if (session != null && session.isTextureReady()) {
            session.sendMousePress(toBrowserX(event.x(), session), toBrowserY(event.y(), session),
                    event.buttonInfo().button());
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.y() < TOP_BAR_HEIGHT) {
            return super.mouseReleased(event);
        }
        IBrowserSession session = currentSession();
        if (session != null && session.isTextureReady()) {
            session.sendMouseRelease(toBrowserX(event.x(), session), toBrowserY(event.y(), session),
                    event.buttonInfo().button());
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (event.y() < TOP_BAR_HEIGHT) {
            return super.mouseDragged(event, dragX, dragY);
        }
        IBrowserSession session = currentSession();
        if (session != null && session.isTextureReady()) {
            session.sendMouseMove(toBrowserX(event.x(), session), toBrowserY(event.y(), session));
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseY < TOP_BAR_HEIGHT) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        IBrowserSession session = currentSession();
        if (session != null && session.isTextureReady()) {
            session.sendMouseWheel(toBrowserX(mouseX, session), toBrowserY(mouseY, session), scrollY, 0);
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        if (this.getFocused() == urlBox) {
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                navigate();
                return true;
            }
            return super.keyPressed(event);
        }
        IBrowserSession session = currentSession();
        if (session != null) {
            if (DEV_JS_TEST) {
                String js = switch (event.key()) {
                    case GLFW.GLFW_KEY_F6 -> "toggle()";
                    case GLFW.GLFW_KEY_F7 -> "seekBy(10)";
                    case GLFW.GLFW_KEY_F8 -> "setVolume(0.2)";
                    case GLFW.GLFW_KEY_F9 -> "setVolume(1)";
                    case GLFW.GLFW_KEY_F10 -> "setRate(" + nextDevRate() + ")";
                    case GLFW.GLFW_KEY_F5 -> "seekBy(-10)";
                    default -> null;
                };
                if (js != null) {
                    session.executeJavaScript(mphScript(js));
                    return true;
                }
            }
            session.sendKeyPress(event.key(), event.scancode(), event.modifiers());
        }
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (this.getFocused() == urlBox) {
            return super.keyReleased(event);
        }
        IBrowserSession session = currentSession();
        if (session != null) {
            session.sendKeyRelease(event.key(), event.scancode(), event.modifiers());
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (this.getFocused() == urlBox) {
            return super.charTyped(event);
        }
        IBrowserSession session = currentSession();
        if (session != null) {
            int codepoint = event.codepoint();
            char c = Character.isBmpCodePoint(codepoint) ? (char) codepoint : '?';
            session.sendKeyTyped(c, 0);
        }
        return true;
    }
}
