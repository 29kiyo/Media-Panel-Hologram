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
import io.github.kiyo.mediapanelhologram.block.entity.MediaSource;
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
    // IME-PAGE: the vanilla TextInputManager keeps the IME off unless a text widget has focus,
    // so the page area announces itself as a text input while it is focused (see focusPage).
    private boolean pageFocused;
    private net.minecraft.client.input.PreeditEvent preedit;
    private int imeX;
    private int imeY;

    @Override
    public void removed() {
        super.removed();
        leavePage();
    }

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

        MediaSource mediaBe = findBlockEntity();
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

        addOwnWidget(Button.builder(Component.translatable("gui.mediapanelhologram.media_block.share"), b -> sharePage())
                .bounds(232, ADDRESS_BAR_HEIGHT + 4, 50, 16)
                .build());

        // Media control row: calls window.mph.* (defined by the mph.local player pages).
        addMediaButton("-10", 8, 30, () -> playback(io.github.kiyo.mediapanelhologram.network.RemoteCommand.SEEK_BY, -10));
        addMediaButton("Play", 40, 40, () -> playback(io.github.kiyo.mediapanelhologram.network.RemoteCommand.PLAY_TOGGLE, 0));
        addMediaButton("+10", 82, 30, () -> playback(io.github.kiyo.mediapanelhologram.network.RemoteCommand.SEEK_BY, 10));
        addMediaButton("Vol-", 118, 36, () -> adjustVolume(-0.1));
        addMediaButton("Vol+", 156, 36, () -> adjustVolume(0.1));
        addMediaButton("Mute", 198, 40, () -> sendVolume(
                io.github.kiyo.mediapanelhologram.network.MediaVolumeService.VolumeOp.TOGGLE_MUTE, 0));
        addMediaButton("Speed", 240, 46, () -> playback(io.github.kiyo.mediapanelhologram.network.RemoteCommand.SET_RATE, (int) Math.round(nextDevRate() * 100)));
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
        MediaSource be = findBlockEntity();
        return be != null ? be.getQuality().baseZoomLevel() : 0.0;
    }


    private static String mphScript(String call) {
        return io.github.kiyo.mediapanelhologram.client.browser.MphScripts.script(call);
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

    /** Play/pause, seek and rate go through the server so every viewer follows (and the lock applies). */
    private void playback(io.github.kiyo.mediapanelhologram.network.RemoteCommand cmd, int arg) {
        MediaSource src = findBlockEntity();
        double[] ref = src == null ? null
                : io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager.playbackRefForCommand(src.getSessionKey());
        dev.architectury.networking.NetworkManager.sendToServer(
                new io.github.kiyo.mediapanelhologram.network.PlaybackCommandPayload(pos, cmd, arg, ref != null,
                        ref != null ? ref[0] : 0.0, ref != null && ref[1] > 0.5, ref != null ? ref[2] : 1.0,
                        ref != null ? ref[3] : -1.0));
    }

    // SHARE-PAGE: publish the page this client is looking at as the block's URL, so everybody opens it.
    private Component shareMessage;
    private long shareMessageUntil;

    private static final java.util.regex.Pattern SHARE_BLOCKED = java.util.regex.Pattern.compile(
            "(?i)(login|log-in|signin|sign-in|signup|oauth|token|password|passwd|accounts\\.google\\.com|[?&]auth=|/auth(?:[/?#]|$))");
    private static final java.util.regex.Pattern SHARE_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private void say(String key) {
        shareMessage = Component.translatable(key);
        shareMessageUntil = System.currentTimeMillis() + 3000L;
    }

    private void drawShareMessage(GuiGraphicsExtractor g) {
        if (shareMessage == null || System.currentTimeMillis() > shareMessageUntil) {
            return;
        }
        String t = shareMessage.getString();
        int w = this.font.width(t);
        int x = Math.max(4, (this.width - w) / 2);
        int y = this.height - 18;
        g.fill(x - 4, y - 3, x + w + 4, y + 11, 0xE0000000);
        g.text(this.font, t, x, y, 0xFFFFFFFF);
    }

    private static String queryParam(String rawQuery, String name) {
        for (String part : rawQuery.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name)) {
                try {
                    return java.net.URLDecoder.decode(part.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Original URL for a page URL: this MOD's own player pages map back to the video / file / image URL; other internal pages cannot be shared (null). */
    private static String publicUrlOf(String page) {
        if (page == null) {
            return null;
        }
        String u = page.trim();
        if (!u.startsWith("https://mph.local/")) {
            return (u.startsWith("http://") || u.startsWith("https://")) ? u : null;
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(u);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        if (path.equals("/player")) {
            String v = queryParam(query, "v");
            String list = queryParam(query, "list");
            if (v != null && SHARE_ID.matcher(v).matches()) {
                return "https://www.youtube.com/watch?v=" + v
                        + (list != null && SHARE_ID.matcher(list).matches() ? "&list=" + list : "");
            }
            if (list != null && SHARE_ID.matcher(list).matches()) {
                return "https://www.youtube.com/playlist?list=" + list;
            }
            return null;
        }
        if (path.equals("/file") || path.equals("/image")) {
            String target = queryParam(query, "u");
            return target != null && (target.startsWith("http://") || target.startsWith("https://")) ? target : null;
        }
        return null;
    }

    private void sharePage() {
        MediaSource src = findBlockEntity();
        IBrowserSession session = currentSession();
        if (src == null || session == null) {
            return;
        }
        if (!io.github.kiyo.mediapanelhologram.network.LockAccess.canOperate(
                net.minecraft.client.Minecraft.getInstance().player, src.getLock())) {
            say("message.mediapanelhologram.lock.denied");
            return;
        }
        String current;
        try {
            current = session.getCurrentUrl();
        } catch (RuntimeException e) {
            current = null;
        }
        String url = publicUrlOf(current);
        if (url == null || url.length() > 2048) {
            say("gui.mediapanelhologram.share.unavailable");
            return;
        }
        if (SHARE_BLOCKED.matcher(url).find()) {
            say("gui.mediapanelhologram.share.blocked");
            return;
        }
        if (url.equals(src.getUrl())) {
            say("gui.mediapanelhologram.share.same");
            return;
        }
        navigateTo(url);
        say("gui.mediapanelhologram.share.done");
    }

    private void adjustVolume(double delta) {
        sendVolume(io.github.kiyo.mediapanelhologram.network.MediaVolumeService.VolumeOp.VOLUME_BY,
                (int) Math.round(delta * 100));
    }

    /** Volume/mute go through the server so the saved state and every viewer stay in sync. */
    private void sendVolume(io.github.kiyo.mediapanelhologram.network.MediaVolumeService.VolumeOp op, int arg) {
        dev.architectury.networking.NetworkManager.sendToServer(
                new io.github.kiyo.mediapanelhologram.network.MediaVolumeCommandPayload(pos, op, arg));
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
        MediaSource currentBe = findBlockEntity();
        io.github.kiyo.mediapanelhologram.block.QualityPreset currentQuality = currentBe != null
                ? currentBe.getQuality() : io.github.kiyo.mediapanelhologram.block.QualityPreset.DEFAULT;
        NetworkManager.sendToServer(new MediaBlockUpdatePayload(this.pos, url, currentBe != null ? currentBe.getMode() : MediaMode.BROWSER, currentQuality));
    }

    private void navigate() {
        navigateTo(resolveNavigationTarget(this.urlBox.getValue()));
    }

    private MediaSource findBlockEntity() {
        var level = Minecraft.getInstance().level;
        if (level != null && level.getBlockEntity(pos) instanceof MediaSource mediaBe) {
            return mediaBe;
        }
        return null;
    }

    private IBrowserSession currentSession() {
        MediaSource mediaBe = findBlockEntity();
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
    public void extractRenderState(GuiGraphicsExtractor g0, int m0, int m1, float p0) {
        extractRenderStateBase(g0, m0, m1, p0);
        drawPreedit(g0);
        drawShareMessage(g0);
    }

    private void extractRenderStateBase(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
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
        MediaSource modeBe = findBlockEntity();
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

    // INPUT-RELAY: mouse input is also sent to the server, which replays it on the other players' browsers.
    private long relayActiveUntil;
    private long relayLastMoveAt;
    private int relayLastX = -1;
    private int relayLastY = -1;
    private int relayButtonsDown;

    /** kind: 0 move, 1 press, 2 release, 3 wheel. px/py are texture pixels of this session. */
    private void relayMouse(IBrowserSession session, int kind, int px, int py, int button, double amount) {
        long now = System.currentTimeMillis();
        if (kind == 0) {
            if (relayButtonsDown <= 0 && now > relayActiveUntil) {
                return;
            }
            if (now - relayLastMoveAt < 50L || (px == relayLastX && py == relayLastY)) {
                return;
            }
            relayLastMoveAt = now;
            relayLastX = px;
            relayLastY = py;
        } else {
            relayActiveUntil = now + 1500L;
            if (kind == 1) {
                relayButtonsDown++;
            } else if (kind == 2) {
                relayButtonsDown = Math.max(0, relayButtonsDown - 1);
            }
        }
        MediaSource src = findBlockEntity();
        if (src == null || Minecraft.getInstance().getConnection() == null) {
            return;
        }
        BlockPos key = src.getSessionKey();
        long hash = io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager.currentUrlHash(key);
        if (hash == 0L) {
            return;
        }
        int nx = (int) Math.round(px * 65535.0 / Math.max(1, session.getTextureWidth() - 1));
        int ny = (int) Math.round(py * 65535.0 / Math.max(1, session.getTextureHeight() - 1));
        try {
            dev.architectury.networking.NetworkManager.sendToServer(
                    new io.github.kiyo.mediapanelhologram.network.InputRelayPayload(pos, key, hash, kind, nx, ny,
                            button, amount));
        } catch (RuntimeException ignored) {
            // not connected
        }
    }

    /**
     * INPUT-RELAY (keys): kind 4 press, 5 release, 6 typed. a = key code or char, scan = scancode.
     * Never sent while a password-like field has the focus, and key presses with Ctrl/Alt/Super are not
     * relayed (paste would use the receiver's own clipboard; zoom/reload/back would act on the receiver).
     */
    private void relayKey(int kind, int a, int mods, long scan) {
        if (kind != 6 && (mods & 14) != 0) {
            return;
        }
        MediaSource src = findBlockEntity();
        if (src == null || Minecraft.getInstance().getConnection() == null) {
            return;
        }
        BlockPos key = src.getSessionKey();
        if (io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager.passwordFocused(key)) {
            return;
        }
        long hash = io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager.currentUrlHash(key);
        if (hash == 0L) {
            return;
        }
        try {
            dev.architectury.networking.NetworkManager.sendToServer(
                    new io.github.kiyo.mediapanelhologram.network.InputRelayPayload(pos, key, hash, kind,
                            Math.max(0, Math.min(65535, a)), Math.max(0, Math.min(65535, mods)), 0, (double) scan));
        } catch (RuntimeException ignored) {
            // not connected
        }
    }

    /** True while another player's input is being replayed on this screen (and we are not holding a button). */
    private boolean remoteBusy() {
        if (relayButtonsDown > 0) {
            return false;
        }
        MediaSource src = findBlockEntity();
        return src != null && io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager
                .remoteOperator(src.getSessionKey()) != null;
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
        if (session != null && session.isTextureReady() && !remoteBusy()) {
            relayMouse(session, 0, toBrowserX(mouseX, session), toBrowserY(mouseY, session), 0, 0);
            session.sendMouseMove(toBrowserX(mouseX, session), toBrowserY(mouseY, session));
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.y() >= TOP_BAR_HEIGHT) {
            focusPage(event.x(), event.y());
        } else {
            leavePage();
        }
        if (event.y() < TOP_BAR_HEIGHT) {
            return super.mouseClicked(event, doubleClick);
        }
        IBrowserSession session = currentSession();
        if (session != null && session.isTextureReady() && !remoteBusy()) {
            relayMouse(session, 1, toBrowserX(event.x(), session), toBrowserY(event.y(), session), event.buttonInfo().button(), 0);
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
        if (session != null && session.isTextureReady() && !remoteBusy()) {
            relayMouse(session, 2, toBrowserX(event.x(), session), toBrowserY(event.y(), session), event.buttonInfo().button(), 0);
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
        if (session != null && session.isTextureReady() && !remoteBusy()) {
            relayMouse(session, 0, toBrowserX(event.x(), session), toBrowserY(event.y(), session), 0, 0);
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
        if (session != null && session.isTextureReady() && !remoteBusy()) {
            relayMouse(session, 3, toBrowserX(mouseX, session), toBrowserY(mouseY, session), 0, scrollY);
            session.sendMouseWheel(toBrowserX(mouseX, session), toBrowserY(mouseY, session), scrollY, 0);
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (composing() && isCompositionKey(event.key())) {
            return true;
        }
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
            if (!remoteBusy()) {
                relayKey(4, event.key(), event.modifiers(), event.scancode());
                session.sendKeyPress(event.key(), event.scancode(), event.modifiers());
            }
        }
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (composing() && isCompositionKey(event.key())) {
            return true;
        }
        if (this.getFocused() == urlBox) {
            return super.keyReleased(event);
        }
        IBrowserSession session = currentSession();
        if (session != null) {
            relayKey(5, event.key(), event.modifiers(), event.scancode());
            session.sendKeyRelease(event.key(), event.scancode(), event.modifiers());
        }
        return true;
    }

    @Override
    public boolean preeditUpdated(net.minecraft.client.input.PreeditEvent event) {
        if (pageFocused) {
            preedit = (event == null || event.fullText().isEmpty()) ? null : event;
            return true;
        }
        return super.preeditUpdated(event);
    }

    private void focusPage(double x, double y) {
        this.setFocused(null);
        imeX = (int) x;
        imeY = (int) y;
        pageFocused = true;
        net.minecraft.client.Minecraft.getInstance().onTextInputFocusChange(this, true);
    }

    private void leavePage() {
        if (pageFocused) {
            pageFocused = false;
            preedit = null;
            net.minecraft.client.Minecraft.getInstance().onTextInputFocusChange(this, false);
        }
    }

    private boolean composing() {
        return pageFocused && preedit != null && !preedit.fullText().isEmpty();
    }

    private static boolean isCompositionKey(int key) {
        return switch (key) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_BACKSPACE,
                    GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_LEFT,
                    GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_HOME,
                    GLFW.GLFW_KEY_END -> true;
            default -> false;
        };
    }

    private void drawPreedit(GuiGraphicsExtractor g) {
        MediaSource opSrc = findBlockEntity();
        String op = opSrc == null ? null : io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager
                .remoteOperator(opSrc.getSessionKey());
        if (op != null && relayButtonsDown <= 0) {
            String label = Component.translatable("gui.mediapanelhologram.input.busy", op).getString();
            int lw = this.font.width(label);
            g.fill(6, TOP_BAR_HEIGHT + 2, lw + 14, TOP_BAR_HEIGHT + 15, 0xE0000000);
            g.text(this.font, label, 10, TOP_BAR_HEIGHT + 4, 0xFFFFD27F);
        }

        if (!pageFocused) {
            return;
        }
        net.minecraft.client.Minecraft.getInstance().textInputManager().setTextInputArea(imeX, imeY, 1, 12);
        if (!composing()) {
            return;
        }
        String t = preedit.fullText();
        int w = this.font.width(t);
        int x = Math.max(4, Math.min(imeX, this.width - w - 4));
        int y = Math.max(4, imeY - 20); // above the click point; the IME candidate window opens below it
        g.fill(x - 2, y - 2, x + w + 2, y + 11, 0xE0000000);
        g.text(this.font, t, x, y, 0xFFFFFFFF);
        g.fill(x, y + 10, x + w, y + 11, 0xFFFFFFFF);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        preedit = null;
        if (this.getFocused() != urlBox) {
            int cp = event.codepoint();
            if (!Character.isBmpCodePoint(cp)) {
                IBrowserSession session = currentSession();
                if (session != null) {
                    for (char c : Character.toChars(cp)) {
                        if (remoteBusy()) {
                            continue;
                        }
                        relayKey(6, c, 0, 0);
                        session.sendKeyTyped(c, 0);
                    }
                }
                return true;
            }
        }
        return charTypedBase(event);
    }

    private boolean charTypedBase(CharacterEvent event) {
        if (this.getFocused() == urlBox) {
            return super.charTyped(event);
        }
        IBrowserSession session = currentSession();
        if (session != null) {
            int codepoint = event.codepoint();
            char c = Character.isBmpCodePoint(codepoint) ? (char) codepoint : '?';
            if (!remoteBusy()) {
                relayKey(6, c, 0, 0);
                session.sendKeyTyped(c, 0);
            }
        }
        return true;
    }
}
