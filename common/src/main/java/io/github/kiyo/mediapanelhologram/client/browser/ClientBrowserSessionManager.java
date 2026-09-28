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
            closeAndRemove(pos);
            return null;
        }

        Entry entry = SESSIONS.get(pos);
        if (entry == null) {
            IBrowserEngine engine = BrowserEngineProvider.get();
            if (engine == null || !engine.isAvailable()) {
                return null;
            }
            IBrowserSession session = engine.createSession(url, TEXTURE_WIDTH, TEXTURE_HEIGHT);
            entry = new Entry(session, url);
            SESSIONS.put(pos, entry);
        } else if (!entry.loadedUrl.equals(url)) {
            entry.session.loadUrl(url);
            entry.loadedUrl = url;
        }
        return entry.session;
    }

    /** Closes and forgets this block's session, if any (e.g. URL was cleared). */
    public static void closeAndRemove(BlockPos pos) {
        Entry entry = SESSIONS.remove(pos);
        if (entry != null) {
            closeSession(entry);
        }
    }

    /**
     * Closes and forgets any session whose owning block is gone. Must be
     * called regularly from client code that already runs every frame --
     * currently MediaBlockEntityRenderer#extractRenderState.
     */
    public static void pruneStale() {
        Minecraft mc = Minecraft.getInstance();
        Iterator<Map.Entry<BlockPos, Entry>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Entry> mapEntry = it.next();
            BlockPos pos = mapEntry.getKey();
            boolean stale = mc.level == null
                    || !(mc.level.getBlockEntity(pos) instanceof MediaBlockEntity mediaBe)
                    || mediaBe.isRemoved();
            if (stale) {
                closeSession(mapEntry.getValue());
                it.remove();
            }
        }
    }

    private static void closeSession(Entry entry) {
        entry.session.close();
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() == null && !mc.mouseHandler.isMouseGrabbed()) {
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
