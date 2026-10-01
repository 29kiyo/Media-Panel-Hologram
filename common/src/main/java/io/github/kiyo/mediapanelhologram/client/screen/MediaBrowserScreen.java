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
    private static final int ADDRESS_BAR_HEIGHT = 24;
    private static final int NAV_ROW_HEIGHT = 24;
    private static final int TOP_BAR_HEIGHT = ADDRESS_BAR_HEIGHT + NAV_ROW_HEIGHT;

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
        String initialUrl = mediaBe != null ? MediaBlockEntityRenderer.resolveContentUrl(mediaBe) : "";

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
                        b -> setZoom(0.0))
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
        NetworkManager.sendToServer(new MediaBlockUpdatePayload(this.pos, url, MediaMode.BROWSER, currentQuality));
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
        graphics.fill(0, 0, this.width, TOP_BAR_HEIGHT, 0xFF202225);

        IBrowserSession session = currentSession();
        if (session != null && session.isTextureReady()) {
            int[] rect = drawRect(session);
            int texW = session.getTextureWidth();
            int texH = session.getTextureHeight();
            graphics.blit(RenderPipelines.GUI_TEXTURED, session.getTextureLocation(),
                    rect[0], rect[1], 0f, 0f, rect[2], rect[3], texW, texH, texW, texH);
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
