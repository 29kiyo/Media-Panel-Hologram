package io.github.kiyo.mediapanelhologram.client.browser;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import io.github.kiyo.mediapanelhologram.block.entity.MediaBlockEntity;
import io.github.kiyo.mediapanelhologram.browser.BrowserEngineProvider;
import io.github.kiyo.mediapanelhologram.browser.IBrowserEngine;
import io.github.kiyo.mediapanelhologram.browser.IBrowserSession;

/**
 * Owns every MediaBlock's IBrowserSession, keyed by BlockPos, shared between
 * MediaBlockEntityRenderer (draws the texture every frame a block is on
 * screen) and MediaBrowserScreen (forwards mouse/keyboard when the player
 * has that block's direct-control screen open).
 *
 * TABS REVERTED: an earlier revision of this class keyed sessions by
 * (BlockPos, tab id) to support multiple browser tabs per block. That
 * feature produced enough actual-run bugs (tab ids skipping/duplicating,
 * a tab-close crash from a since-closed GPU sampler still being
 * referenced, stale click targets from an incompletely-cleared Screen
 * widget list) that the user asked to drop tabs entirely rather than keep
 * debugging them. This class is back to one session per block (BlockPos
 * alone as the key) -- see MediaBlockEntity's Javadoc (also reverted) for
 * the corresponding removal of browserTabs/activeTabIndex there.
 *
 * CURSOR DESYNC ON CLOSE (fixed after an actual-run bug: mouse cursor stayed
 * visible/free during normal gameplay -- not while any screen was open --
 * after destroying a block whose browser was actively playing). Root cause,
 * traced into mcef-fork's own source: MCEFBrowser#close() unconditionally
 * calls its cursorChangeListener with cursor id 0 (CefCursorType.POINTER),
 * whose listener (installed by MCEFBrowser's own constructor) calls
 * GLFW.glfwSetInputMode(..., GLFW_CURSOR_NORMAL) DIRECTLY, bypassing
 * Minecraft's own MouseHandler entirely. Every close() call in this class
 * re-asserts the correct grab state via MouseHandler.grabMouse() afterwards,
 * but only when no Screen is currently open.
 */
@Environment(EnvType.CLIENT)
public final class ClientBrowserSessionManager {
    private static final int TEXTURE_WIDTH = 960;
    private static final int TEXTURE_HEIGHT = 540;

    private static final Map<BlockPos, Entry> SESSIONS = new HashMap<>();

    /**
     * URLs awaiting session creation on the NEXT tick (see getOrCreate's
     * Javadoc for why creation can't happen synchronously with the call
     * that first requests a URL).
     */
    private static final Map<BlockPos, String> PENDING_CREATE = new HashMap<>();

    /** BlockPos queued to have its session closed on the next tick. See queueClose. */
    private static final java.util.Set<BlockPos> PENDING_CLOSE = new java.util.HashSet<>();

    private ClientBrowserSessionManager() {
    }

    /**
     * Returns this block's session, creating it if needed and reloading it
     * if the stored URL changed since last call. Returns null if there is
     * nothing to show (empty URL) or the browser engine hasn't finished
     * starting yet.
     */
    @Nullable
    public static IBrowserSession getOrCreate(BlockPos pos, String url) {
        if (url == null || url.isEmpty()) {
            queueClose(pos);
            return null;
        }

        Entry entry = SESSIONS.get(pos);
        if (entry == null) {
            // Deliberately NOT created synchronously here. An actual-run
            // NeoForge crash ("Sampler ... has been closed!") happened when
            // a block's very first session was created in the exact same
            // frame a Screen was closing (e.g. pressing Done right after
            // switching to browser mode with no URL set, which -- via the
            // resolveContentUrl Google fallback -- makes this the first call
            // ever made for that block). MCEF's CefRenderUpdateMixin pumps
            // the CEF message loop from GameRenderer#render, so a
            // browser-creation call landing mid-frame there can race with
            // that same frame's draw of an already-queued (but not-yet-ready)
            // texture/sampler. Queuing the URL and creating the session on
            // the next tick (see processPendingCreations, called from
            // ClientTickEvent.CLIENT_POST alongside pruneStale) keeps session
            // creation out of the render path entirely. The one visible
            // effect is that the very first frame after switching modes
            // shows nothing, for a single tick.
            PENDING_CREATE.put(pos, url);
            return null;
        } else if (!entry.loadedUrl.equals(url)) {
            entry.session.loadUrl(url);
            entry.loadedUrl = url;
        }
        return entry.session;
    }

    /**
     * Actually creates sessions queued by getOrCreate. Called once per tick
     * (ClientTickEvent.CLIENT_POST), never from the render path -- see
     * getOrCreate's Javadoc for why.
     */
    /**
     * Actually closes sessions queued by queueClose/pruneStale. Called once
     * per tick (ClientTickEvent.CLIENT_POST), never from the render path.
     */
    public static void processPendingCloses() {
        if (PENDING_CLOSE.isEmpty()) {
            return;
        }
        org.slf4j.LoggerFactory.getLogger("MPH-DEBUG").info(
                "processPendingCloses: closing {} session(s): {}", PENDING_CLOSE.size(), PENDING_CLOSE);
        for (BlockPos pos : PENDING_CLOSE) {
            Entry entry = SESSIONS.remove(pos);
            if (entry != null) {
                closeSession(entry);
            }
        }
        PENDING_CLOSE.clear();
    }

    public static void processPendingCreations() {
        if (PENDING_CREATE.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Iterator<Map.Entry<BlockPos, String>> it = PENDING_CREATE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, String> pending = it.next();
            it.remove();
            BlockPos pos = pending.getKey();
            String url = pending.getValue();

            // The block may have been removed, or a session may have been
            // created for it some other way, while this was queued.
            if (mc.level == null
                    || !(mc.level.getBlockEntity(pos) instanceof MediaBlockEntity)
                    || SESSIONS.containsKey(pos)) {
                continue;
            }
            IBrowserEngine engine = BrowserEngineProvider.get();
            if (engine == null || !engine.isAvailable()) {
                continue;
            }
            IBrowserSession session = engine.createSession(url, TEXTURE_WIDTH, TEXTURE_HEIGHT);
            SESSIONS.put(pos, new Entry(session, url));
        }
    }

    /**
     * Queues this block's session (if any) to be closed on the next tick.
     * Never closes synchronously -- see getOrCreate's Javadoc for why
     * (mirrors the same mid-frame-close race that fix addressed, but for
     * removal instead of creation: this used to close immediately, and an
     * actual-run NeoForge crash -- "Sampler ... has been closed!" --
     * reproduced by simply breaking a MediaBlock while its browser was
     * showing, once creation alone was deferred).
     */
    public static void closeAndRemove(BlockPos pos) {
        queueClose(pos);
    }

    private static void queueClose(BlockPos pos) {
        if (SESSIONS.containsKey(pos)) {
            PENDING_CLOSE.add(pos);
        }
        // A session that hasn't been created yet (still only in
        // PENDING_CREATE) can simply be dropped from that queue instead;
        // nothing to close.
        PENDING_CREATE.remove(pos);
    }

    /**
     * Closes and forgets any session whose owning block is gone. Must be
     * called regularly from client code that already runs every frame --
     * currently MediaBlockEntityRenderer#extractRenderState.
     */
    public static void pruneStale() {
        Minecraft mc = Minecraft.getInstance();
        PENDING_CREATE.keySet().removeIf(pos ->
                mc.level == null || !(mc.level.getBlockEntity(pos) instanceof MediaBlockEntity mediaBe)
                        || mediaBe.isRemoved());
        Iterator<Map.Entry<BlockPos, Entry>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Entry> mapEntry = it.next();
            BlockPos pos = mapEntry.getKey();
            boolean stale = mc.level == null
                    || !(mc.level.getBlockEntity(pos) instanceof MediaBlockEntity mediaBe)
                    || mediaBe.isRemoved();
            if (stale) {
                PENDING_CLOSE.add(pos);
            }
        }
    }

    private static void closeSession(Entry entry) {
        entry.session.close();
        Minecraft mc = Minecraft.getInstance();
        // ACTUAL-RUN BUG FIX: previously gated on
        // !mc.mouseHandler.isMouseGrabbed(), but MCEFBrowser#setCursor
        // writes GLFW's cursor state directly (bypassing MouseHandler
        // entirely -- see MCEFBrowser.java), so that flag can be stale
        // (still reporting "grabbed" when GLFW's real cursor is actually
        // free). That stale flag caused this to skip re-grabbing, leaving
        // the cursor visibly free during normal gameplay -- observed both
        // after closing a browser screen AND right after breaking a
        // MediaBlock with an active session. Unconditionally re-grabbing
        // when no Screen is open is safe even if already grabbed.
        if (mc.gui.screen() == null) {
            // grabMouse() alone was observed to be a no-op here: it's very
            // likely gated on MouseHandler's own isMouseGrabbed flag, which
            // MCEF's direct-GLFW cursor write (see setCursor) never touches.
            // So the flag still reads "grabbed" even though GLFW's actual
            // cursor mode was just forced to NORMAL, and grabMouse() then
            // thinks there's nothing to do. Releasing first resets that flag
            // unconditionally, so the following grab actually re-applies.
            mc.mouseHandler.releaseMouse();
            mc.mouseHandler.grabMouse();
        }
    }

    private static final class Entry {
        final IBrowserSession session;
        String loadedUrl;

        Entry(IBrowserSession session, String loadedUrl) {
            this.session = session;
            this.loadedUrl = loadedUrl;
        }
    }
}
