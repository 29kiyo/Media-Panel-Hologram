package io.github.kiyo.mediapanelhologram.client.browser;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import io.github.kiyo.mediapanelhologram.block.entity.MediaSource;
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
    /** Quality stored on the block entity (default if the block is gone). */
    private static io.github.kiyo.mediapanelhologram.block.QualityPreset qualityOf(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getBlockEntity(pos) instanceof MediaSource be
                ? be.getQuality() : io.github.kiyo.mediapanelhologram.block.QualityPreset.DEFAULT;
    }

    private static final Map<BlockPos, Entry> SESSIONS = new HashMap<>();

    /**
     * URLs awaiting session creation on the NEXT tick (see getOrCreate's
     * Javadoc for why creation can't happen synchronously with the call
     * that first requests a URL).
     */
    private static final Map<BlockPos, String> PENDING_CREATE = new HashMap<>();

    /** BlockPos queued to have its session closed on the next tick. See queueClose. */
    private static final java.util.Set<BlockPos> PENDING_CLOSE = new java.util.HashSet<>();

    /** Extra blocks beyond a block's range before an already-open session is closed (prevents open/close flapping at the edge). */
    public static final double HYSTERESIS = 4.0;

    /** Dev-only: MPH_PERF=1 logs a one-line summary once per second. */
    private static final boolean PERF = "1".equals(System.getenv("MPH_PERF"));
    private static long perfCalls;
    private static long perfNanos;
    private static long perfExtractCalls;
    private static long perfExtractNanos;
    private static long perfSubmitCalls;
    private static long perfSubmitNanos;

    public static boolean perfEnabled() {
        return PERF;
    }

    /** Dev-only (MPH_PERF=1): render-thread time spent in the block renderers' extract/submit. */
    public static void perfRecord(boolean extract, long nanos) {
        if (extract) {
            perfExtractCalls++;
            perfExtractNanos += nanos;
        } else {
            perfSubmitCalls++;
            perfSubmitNanos += nanos;
        }
    }

    private static long perfCreated;
    private static long perfClosed;
    private static int perfTicks;

    /** Blocks whose session is stopped because the camera is out of range (statistics only). */
    private static final java.util.Set<BlockPos> STOPPED = new java.util.HashSet<>();

    /**
     * True when the camera is close enough for this source to be shown. A session that is already open
     * gets HYSTERESIS extra blocks. Measured from the camera entity (the orbit camera of the hologram
     * screen counts), against every visible output: block centre if the screen is on, hologram centre
     * (plus its radius) if the hologram is on.
     */
    private static boolean inRange(MediaSource src, boolean alreadyOpen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof io.github.kiyo.mediapanelhologram.client.screen.MediaBrowserScreen) {
            return true; // direct-control screen open (may be a far block opened from the remote)
        }
        net.minecraft.world.entity.Entity cam = mc.getCameraEntity();
        if (cam == null) {
            cam = mc.player;
        }
        if (cam == null) {
            return true;
        }
        net.minecraft.world.phys.Vec3 c = cam.position();
        double limit = src.getRange() + (alreadyOpen ? HYSTERESIS : 0.0);
        BlockPos bp = src.getBlockPos();
        if (src instanceof io.github.kiyo.mediapanelhologram.block.entity.PanelBlockEntity pb) {
            limit += Math.max(pb.getGroupWidth(), pb.getGroupHeight());
        }
        boolean blockCounts = true;
        if (src instanceof io.github.kiyo.mediapanelhologram.block.entity.MediaBlockEntity mbe) {
            io.github.kiyo.mediapanelhologram.block.HologramSettings s = mbe.getHologram();
            if (s.holoOn()) {
                net.minecraft.world.phys.Vec3 h = new net.minecraft.world.phys.Vec3(
                        bp.getX() + 0.5 + s.x() / 1000.0,
                        bp.getY() + 0.5 + s.y() / 1000.0,
                        bp.getZ() + 0.5 + s.z() / 1000.0);
                double r = Math.hypot(s.width() / 2000.0, s.effectiveHeight() / 2000.0);
                if (h.closerThan(c, limit + r)) {
                    return true;
                }
                blockCounts = s.screenOn();
            }
        }
        return blockCounts && net.minecraft.world.phys.Vec3.atCenterOf(bp).closerThan(c, limit);
    }

    private static boolean outOfRange(BlockPos pos, MediaSource src) {
        if (SESSIONS.containsKey(pos) && !inRange(src, true)) {
            RESUME_ON_CLOSE.add(pos);
        }
        if (inRange(src, true)) {
            return false;
        }
        STOPPED.add(pos);
        return true;
    }

    /** Once per second: forget stopped blocks that are gone, and (MPH_PERF=1) log a summary line. */
    private static void perfTick() {
        if (++perfTicks < 20) {
            return;
        }
        perfTicks = 0;
        Minecraft mc = Minecraft.getInstance();
        STOPPED.removeIf(p -> mc.level == null || !(mc.level.getBlockEntity(p) instanceof MediaSource));
        if (!PERF) {
            return;
        }
        Runtime rt = Runtime.getRuntime();
        org.slf4j.LoggerFactory.getLogger("MPH-PERF").info(
                "sessions={} stoppedByRange={} pendingCreate={} pendingClose={} created={} closed={} "
                        + "getOrCreate={} calls/{} us heapMB={}",
                SESSIONS.size(), STOPPED.size(), PENDING_CREATE.size(), PENDING_CLOSE.size(),
                perfCreated, perfClosed, perfCalls, perfNanos / 1000, (rt.totalMemory() - rt.freeMemory()) >> 20);
        perfCalls = 0;
        perfNanos = 0;
        org.slf4j.LoggerFactory.getLogger("MPH-PERF").info(
                "render extract={} calls/{} us, submit={} calls/{} us",
                perfExtractCalls, perfExtractNanos / 1000, perfSubmitCalls, perfSubmitNanos / 1000);
        perfExtractCalls = 0;
        perfExtractNanos = 0;
        perfSubmitCalls = 0;
        perfSubmitNanos = 0;
        perfThreads();
    }

    private static final java.util.Map<Long, Long> PERF_ALLOC = new java.util.HashMap<>();
    private static long perfGcCount = 0;
    private static long perfGcMs = 0;

    /** Dev-only (MPH_PERF=1): per-thread heap allocation per second + GC activity. */
    private static void perfThreads() {
        if (!(java.lang.management.ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean tb)) {
            return;
        }
        long[] ids = tb.getAllThreadIds();
        long[] bytes = tb.getThreadAllocatedBytes(ids);
        java.lang.management.ThreadInfo[] infos = tb.getThreadInfo(ids);
        record Row(String name, long delta) {
        }
        java.util.List<Row> rows = new java.util.ArrayList<>();
        long total = 0;
        for (int i = 0; i < ids.length; i++) {
            if (bytes[i] < 0 || infos[i] == null) {
                continue;
            }
            long prev = PERF_ALLOC.getOrDefault(ids[i], bytes[i]);
            PERF_ALLOC.put(ids[i], bytes[i]);
            long d = bytes[i] - prev;
            total += d;
            rows.add(new Row(infos[i].getThreadName(), d));
        }
        rows.sort((x, y) -> Long.compare(y.delta(), x.delta()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, rows.size()); i++) {
            sb.append(rows.get(i).name()).append('=').append(rows.get(i).delta() >> 20).append("MB ");
        }
        long gcCount = 0;
        long gcMs = 0;
        for (java.lang.management.GarbageCollectorMXBean gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, gc.getCollectionCount());
            gcMs += Math.max(0, gc.getCollectionTime());
        }
        org.slf4j.LoggerFactory.getLogger("MPH-PERF").info(
                "alloc totalMB/s={} gcCount+={} gcMs+={} top: {}",
                total >> 20, gcCount - perfGcCount, gcMs - perfGcMs, sb);
        perfGcCount = gcCount;
        perfGcMs = gcMs;
    }

    /** Positions whose next close (out of range / quality change) should remember the page's current URL. */
    private static final java.util.Set<BlockPos> RESUME_ON_CLOSE = new java.util.HashSet<>();

    /** settingUrl = the URL the session was opened for; url = where the page actually was when it was closed. */
    private record Resume(String settingUrl, String url, long at) {
    }

    private static final Map<BlockPos, Resume> RESUME = new HashMap<>();
    private static final long RESUME_MAX_AGE_MS = 30L * 60L * 1000L;

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
        long t0 = PERF ? System.nanoTime() : 0L;
        try {
            return getOrCreateImpl(pos, url);
        } finally {
            if (PERF) {
                perfCalls++;
                perfNanos += System.nanoTime() - t0;
            }
        }
    }

    @Nullable
    private static IBrowserSession getOrCreateImpl(BlockPos pos, String url) {
        Minecraft rangeMc = Minecraft.getInstance();
        if (rangeMc.level != null && rangeMc.level.getBlockEntity(pos) instanceof MediaSource rangeSrc
                && !inRange(rangeSrc, SESSIONS.containsKey(pos))) {
            if (SESSIONS.containsKey(pos)) {
                RESUME_ON_CLOSE.add(pos);
            }
            STOPPED.add(pos);
            queueClose(pos);
            return null;
        }
        STOPPED.remove(pos);
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
        } else if (entry.quality != qualityOf(pos)) {
            RESUME_ON_CLOSE.add(pos);
            // Quality changed: close now (next tick) and recreate through the
            // same deferred path as a first creation. Never resize in place.
            queueClose(pos);
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
            boolean resume = RESUME_ON_CLOSE.remove(pos);
            if (entry != null && resume) {
                rememberUrl(pos, entry);
            }
            if (entry != null) {
                closeSession(entry);
                perfClosed++;
                sendLeave(pos, entry);
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
                    || !(mc.level.getBlockEntity(pos) instanceof MediaSource)
                    || SESSIONS.containsKey(pos)) {
                continue;
            }
            IBrowserEngine engine = BrowserEngineProvider.get();
            if (engine == null || !engine.isAvailable()) {
                continue;
            }
            io.github.kiyo.mediapanelhologram.block.QualityPreset quality = qualityOf(pos);
            String startUrl = url;
            Resume resume = RESUME.remove(pos);
            if (resume != null && resume.settingUrl().equals(url)
                    && System.currentTimeMillis() - resume.at() < RESUME_MAX_AGE_MS) {
                startUrl = resume.url();
            }
            IBrowserSession session = engine.createSession(startUrl, quality.width(), quality.height());
            final Entry entry = new Entry(session, url, quality);
            session.setConsoleListener(msg -> onConsole(pos, entry, msg)); // MPH-SYNC-SPIKE
            // Apply the quality's base zoom ONCE, when the first load finishes
            // (zoom set before the page exists may be ignored). Later manual
            // zoom changes are never overwritten.
            session.setLoadListener(new io.github.kiyo.mediapanelhologram.browser.BrowserLoadListener() {
                private boolean applied = false;

                @Override
                public void onHttpError(int statusCode, String url) {
                    entry.httpStatus = statusCode;
                    entry.httpUrl = url;
                }

                @Override
                public void onLoadError(String errorText, String failedUrl) {
                    entry.errorText = errorText;
                    entry.failedUrl = failedUrl;
                    if (failedUrl == null || !failedUrl.startsWith("https://mph.local/")) {
                        final String errPage = "https://mph.local/error?c="
                                + java.net.URLEncoder.encode(errorText == null ? "" : errorText, java.nio.charset.StandardCharsets.UTF_8)
                                + "&u="
                                + java.net.URLEncoder.encode(failedUrl == null ? "" : failedUrl, java.nio.charset.StandardCharsets.UTF_8);
                        Minecraft.getInstance().execute(() -> {
                            if (SESSIONS.get(pos) == entry) {
                                entry.session.loadUrl(errPage);
                            }
                        });
                    }
                }

                @Override
                public void onLoadingStateChange(boolean isLoading, boolean canGoBack, boolean canGoForward) {
                    if (isLoading) {
                        entry.pwdFocused = true; // fail closed until the page reports (MPH-PWD)
                        entry.errorText = null;
                        entry.failedUrl = null;
                        entry.httpStatus = 0;
                        entry.httpUrl = null;
                    }
                    if (!isLoading) {
                        Minecraft.getInstance().execute(() -> {
                            reapplyVolume(pos, entry);
                            if (SESSIONS.get(pos) == entry) {
                                entry.session.executeJavaScript(MphScripts.REPORTER + MphScripts.PWD_WATCH);
                            }
                        });
                    }
                    if (isLoading || applied) {
                        return;
                    }
                    applied = true;
                    Minecraft.getInstance().execute(() -> {
                        Entry current = SESSIONS.get(pos);
                        if (current != null && current.session == session) {
                            session.setZoomLevel(quality.baseZoomLevel());
                        }
                    });
                }
            });
            SESSIONS.put(pos, entry);
            perfCreated++;
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
        perfTick();
        Minecraft mc = Minecraft.getInstance();
        PENDING_CREATE.keySet().removeIf(pos ->
                mc.level == null || !(mc.level.getBlockEntity(pos) instanceof MediaSource mediaBe)
                        || mediaBe.isRemoved() || !mediaBe.ownsSession());
        Iterator<Map.Entry<BlockPos, Entry>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Entry> mapEntry = it.next();
            BlockPos pos = mapEntry.getKey();
            boolean stale = mc.level == null
                    || !(mc.level.getBlockEntity(pos) instanceof MediaSource mediaBe)
                    || mediaBe.isRemoved() || !mediaBe.ownsSession()
                    || outOfRange(pos, mediaBe);
            if (stale) {
                PENDING_CLOSE.add(pos);
            }
        }
    }

    /** {errorText, failedUrl} of the last main-frame load error for this block's session, or null. */
    @Nullable
    public static String[] getError(BlockPos pos) {
        Entry entry = SESSIONS.get(pos);
        if (entry == null || entry.errorText == null) {
            return null;
        }
        return new String[] {entry.errorText, entry.failedUrl == null ? "" : entry.failedUrl};
    }

    /** {status, url} of the last main-frame HTTP error (>= 400) for this block's session, or null. */
    @Nullable
    public static String[] getHttpError(BlockPos pos) {
        Entry entry = SESSIONS.get(pos);
        if (entry == null || entry.httpStatus < 400) {
            return null;
        }
        return new String[] {Integer.toString(entry.httpStatus), entry.httpUrl == null ? "" : entry.httpUrl};
    }

    /** Remembers where the page currently is (http/https only, never this MOD's own mph.local pages). */
    private static void rememberUrl(BlockPos pos, Entry entry) {
        String cur = null;
        try {
            cur = entry.session.getCurrentUrl();
        } catch (RuntimeException ignored) {
            // session already unusable: nothing to remember
        }
        if (cur == null || !(cur.startsWith("http://") || cur.startsWith("https://"))
                || cur.startsWith("https://mph.local/")) {
            RESUME.remove(pos);
            return;
        }
        RESUME.put(pos, new Resume(entry.loadedUrl, cur, System.currentTimeMillis()));
    }

    private static final boolean SYNC_DEBUG = "1".equals(System.getenv("MPH_SYNC_DEBUG"));

    /** Parses "MPH-POS position playing rate duration" (see MphScripts.REPORTER). Called on the CEF thread. */
    private static void onConsole(BlockPos pos, Entry entry, String msg) {
        if (msg.startsWith("MPH-PWD ")) {
            entry.pwdFocused = msg.startsWith("MPH-PWD 1");
            if (SYNC_DEBUG) {
                org.slf4j.LoggerFactory.getLogger("MPH-SYNC").info("key={} password-focus={}", pos, entry.pwdFocused);
            }
            return;
        }
        if (!msg.startsWith("MPH-POS ")) {
            return;
        }
        String[] p = msg.substring(8).trim().split(" ");
        if (p.length < 4) {
            return;
        }
        try {
            entry.pbPos = Double.parseDouble(p[0]);
            entry.pbPlaying = "1".equals(p[1]);
            entry.pbRate = Double.parseDouble(p[2]);
            entry.pbDuration = Double.parseDouble(p[3]);
            if (Math.abs(entry.pbDuration - entry.durLast) < 0.01) {
                entry.durStable++;
            } else {
                entry.durLast = entry.pbDuration;
                entry.durStable = 1;
            }
            entry.pbAt = System.currentTimeMillis();
            Minecraft.getInstance().execute(() -> checkJoin(pos, entry));
        } catch (NumberFormatException e) {
            return;
        }
        if (SYNC_DEBUG && entry.pbAt - entry.pbLogAt >= 2000) {
            entry.pbLogAt = entry.pbAt;
            org.slf4j.LoggerFactory.getLogger("MPH-SYNC").info("key={} pos={} playing={} rate={} duration={}",
                    pos, entry.pbPos, entry.pbPlaying, entry.pbRate, entry.pbDuration);
        }
    }

    /**
     * Playback reference for a command sent by this client: its own page's {position, playing, rate}, but only
     * if the page has reported since the last shared state was applied AND since the last command. An older
     * report does not contain their effect and would undo them (quick repeated button presses).
     */
    @Nullable
    public static double[] playbackRefForCommand(BlockPos key) {
        Entry e = SESSIONS.get(key);
        if (e == null) {
            return null;
        }
        double[] ref = e.pbAt > e.lastCmdAt + 1200L ? playbackRef(key) : null;
        e.lastCmdAt = System.currentTimeMillis();
        return ref;
    }

    /** Durations of one video seen by different pages agree within about 1 s (or 1 %). */
    private static boolean durationMatches(double a, double b) {
        return Math.abs(a - b) <= Math.max(1.0, Math.max(a, b) * 0.01);
    }

    /** {position, playing (1/0), rate} of this page estimated now from its last report. */
    private static double[] estimateNow(Entry e) {
        long age = Math.max(0L, Math.min(5000L, System.currentTimeMillis() - e.pbAt));
        double pos = e.pbPlaying ? e.pbPos + age / 1000.0 * e.pbRate : e.pbPos;
        return new double[] {pos, e.pbPlaying ? 1.0 : 0.0, e.pbRate};
    }

    private static String normUrl(@Nullable String u) {
        if (u == null) {
            return "";
        }
        int h = u.indexOf('#');
        return h >= 0 ? u.substring(0, h) : u;
    }

    /** MPH-PLAYBACK: once per page URL, tell the server this session is playing (main thread). */
    private static void checkJoin(BlockPos pos, Entry entry) {
        if (SESSIONS.get(pos) != entry || Minecraft.getInstance().getConnection() == null) {
            return;
        }
        String url;
        try {
            url = normUrl(entry.session.getCurrentUrl());
        } catch (RuntimeException e) {
            return;
        }
        if (entry.resync && entry.wantDuration > 0 && entry.pbDuration > 0
                && durationMatches(entry.pbDuration, entry.wantDuration)) {
            entry.resync = false;
            entry.joinedUrl = null; // join again: the server answers with the current shared state
        }
        if (!url.isEmpty() && url.equals(entry.joinedUrl) && entry.wantDuration > 0 && entry.pbDuration > 0
                && entry.pbDuration > entry.wantDuration * 1.01 + 1.0 && entry.durStable >= 3
                && System.currentTimeMillis() - entry.lastOfferAt > 4000L) {
            // The shared state describes a shorter video (an ad / preview taken as the baseline) while this
            // page has shown a longer one for a few seconds: offer it, the server decides.
            entry.lastOfferAt = System.currentTimeMillis();
            double[] est = estimateNow(entry);
            try {
                dev.architectury.networking.NetworkManager.sendToServer(
                        new io.github.kiyo.mediapanelhologram.network.PlaybackJoinPayload(
                                pos, io.github.kiyo.mediapanelhologram.network.PlaybackUrl.hash(url), est[0], entry.pbPlaying, entry.pbRate, entry.pbDuration));
            } catch (RuntimeException ignored) {
                // not connected any more
            }
            if (SYNC_DEBUG) {
                org.slf4j.LoggerFactory.getLogger("MPH-SYNC").info(
                        "offer key={} page duration {} vs shared {}", pos, entry.pbDuration, entry.wantDuration);
            }
            return;
        }
        if (entry.startPaused && url.equals(entry.joinedUrl)) {
            if (System.currentTimeMillis() - entry.joinedAt > 60000L) {
                entry.startPaused = false;
            } else if (entry.pbPlaying) {
                // Joined while paused at the start and the page is playing now: tell the server again; it adopts
                // this state if the shared state is still the untouched paused start.
                entry.startPaused = false;
                double[] est = estimateNow(entry);
                try {
                    dev.architectury.networking.NetworkManager.sendToServer(
                            new io.github.kiyo.mediapanelhologram.network.PlaybackJoinPayload(
                                    pos, io.github.kiyo.mediapanelhologram.network.PlaybackUrl.hash(url), est[0], true,
                                    entry.pbRate, entry.pbDuration));
                } catch (RuntimeException ignored) {
                    // not connected any more
                }
                if (SYNC_DEBUG) {
                    org.slf4j.LoggerFactory.getLogger("MPH-SYNC").info("start key={} page started playing at {}", pos, est[0]);
                }
                return;
            }
        }
        if (url.isEmpty() || url.equals(entry.joinedUrl)) {
            return;
        }
        entry.joinedUrl = url;
        entry.wantDuration = entry.pbDuration; // until the server answers, the shared video is this page's own
        entry.startPaused = !entry.pbPlaying && entry.pbPos < 1.0;
        entry.joinedAt = System.currentTimeMillis();
        try {
            dev.architectury.networking.NetworkManager.sendToServer(
                    new io.github.kiyo.mediapanelhologram.network.PlaybackJoinPayload(
                            pos, io.github.kiyo.mediapanelhologram.network.PlaybackUrl.hash(url), entry.pbPos, entry.pbPlaying, entry.pbRate, entry.pbDuration));
        } catch (RuntimeException e) {
            entry.joinedUrl = null;
        }
    }

    private static void sendLeave(BlockPos pos, Entry entry) {
        if (entry.joinedUrl == null) {
            return;
        }
        entry.joinedUrl = null;
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        try {
            dev.architectury.networking.NetworkManager.sendToServer(
                    new io.github.kiyo.mediapanelhologram.network.PlaybackLeavePayload(pos));
        } catch (RuntimeException ignored) {
            // not connected any more: the server drops offline viewers itself
        }
    }

    /** {position, playing (1/0), rate} estimated now from the last page report, or null if there is none / it is stale. */
    @Nullable
    public static double[] playbackRef(BlockPos key) {
        Entry e = SESSIONS.get(key);
        if (e == null || e.pbAt == 0L || e.pbAt <= e.lastApplyAt) {
            return null;
        }
        long age = System.currentTimeMillis() - e.pbAt;
        if (age > 5000L) {
            return null;
        }
        double pos = e.pbPlaying ? e.pbPos + age / 1000.0 * e.pbRate : e.pbPos;
        return new double[] {pos, e.pbPlaying ? 1.0 : 0.0, e.pbRate, e.pbDuration};
    }

    /** Server told us the shared state: apply it if this session's page is the same URL (no seek when already within 1 s). */
    public static void applyPlayback(BlockPos key, long urlHash, double position, boolean playing, double rate,
            double duration) {
        Entry entry = SESSIONS.get(key);
        if (entry == null || !Double.isFinite(position) || !Double.isFinite(rate)) {
            return;
        }
        String cur;
        try {
            cur = normUrl(entry.session.getCurrentUrl());
        } catch (RuntimeException e) {
            return;
        }
        if (io.github.kiyo.mediapanelhologram.network.PlaybackUrl.hash(cur) != urlHash) {
            return;
        }
        entry.wantDuration = duration;
        entry.startPaused = false; // the server told us its state: nothing to correct
        boolean fresh = entry.pbAt > 0L && System.currentTimeMillis() - entry.pbAt < 5000L;
        if (duration > 0 && !(fresh && entry.pbDuration > 0 && durationMatches(entry.pbDuration, duration))) {
            // this page currently reports another video (ad / preview) or nothing: do not touch it
            entry.resync = true;
            if (SYNC_DEBUG) {
                org.slf4j.LoggerFactory.getLogger("MPH-SYNC").info(
                        "skip apply key={} (page duration {}, last report {} ms ago, vs shared {})", key, entry.pbDuration,
                        entry.pbAt == 0L ? -1L : System.currentTimeMillis() - entry.pbAt, duration);
            }
            return;
        }
        double[] est = playbackRef(key);
        boolean seek = est == null || Math.abs(est[0] - position) >= 1.0;
        entry.lastApplyAt = System.currentTimeMillis();
        if (SYNC_DEBUG) {
            org.slf4j.LoggerFactory.getLogger("MPH-SYNC").info("apply key={} pos={} playing={} rate={} seek={}",
                    key, position, playing, rate, seek);
        }
        entry.session.executeJavaScript(MphScripts.applyPlayback(position, playing, rate, seek));
    }

    /** True while a password-like field has the focus in this page (or the state is not known yet): no key relay. */
    public static boolean passwordFocused(BlockPos key) {
        Entry e = SESSIONS.get(key);
        return e == null || e.pwdFocused;
    }

    /** Name of the player whose mouse input was replayed on this screen within the last 1.5 s, else null. */
    @Nullable
    public static String remoteOperator(BlockPos key) {
        Entry e = SESSIONS.get(key);
        if (e == null || System.currentTimeMillis() - e.remoteAt > 1500L) {
            return null;
        }
        return e.remoteName;
    }

    /** Hash of this session's current page URL (as used by the playback sync), 0 if there is no session / URL. */
    public static long currentUrlHash(BlockPos key) {
        Entry e = SESSIONS.get(key);
        if (e == null) {
            return 0L;
        }
        try {
            return io.github.kiyo.mediapanelhologram.network.PlaybackUrl.hash(normUrl(e.session.getCurrentUrl()));
        } catch (RuntimeException ex) {
            return 0L;
        }
    }

    /** Another player's mouse input (INPUT-RELAY): replayed only if this page has the same URL hash as theirs. */
    public static void applyInput(BlockPos key, long urlHash, int kind, int nx, int ny, int button, double amount,
            String name) {
        Entry e = SESSIONS.get(key);
        if (e == null || urlHash == 0L || !e.session.isTextureReady() || currentUrlHash(key) != urlHash) {
            return;
        }
        if (kind >= 4 && e.pwdFocused) {
            return; // never type into a password-like field of this page
        }
        e.remoteAt = System.currentTimeMillis();
        e.remoteName = name;
        IBrowserSession s = e.session;
        int x = (int) Math.round(Math.max(0, Math.min(65535, nx)) * Math.max(0, s.getTextureWidth() - 1) / 65535.0);
        int y = (int) Math.round(Math.max(0, Math.min(65535, ny)) * Math.max(0, s.getTextureHeight() - 1) / 65535.0);
        switch (kind) {
            case 0 -> s.sendMouseMove(x, y);
            case 1 -> s.sendMousePress(x, y, button);
            case 2 -> s.sendMouseRelease(x, y, button);
            case 3 -> s.sendMouseWheel(x, y, amount, 0);
            case 4 -> s.sendKeyPress(nx, (long) amount, ny);
            case 5 -> s.sendKeyRelease(nx, (long) amount, ny);
            case 6 -> s.sendKeyTyped((char) nx, 0);
            default -> {
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

    /** Re-applies the block's saved volume/mute to a freshly loaded page (no-op if never set). */
    private static void reapplyVolume(BlockPos pos, Entry entry) {
        if (SESSIONS.get(pos) != entry) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof MediaSource be
                && (be.getVolume() >= 0 || be.isMuted())) {
            entry.session.executeJavaScript(MphScripts.applyVolume(
                    be.getVolume() < 0 ? -1.0 : be.getVolume() / 100.0, be.isMuted()));
        }
    }

    /** Server told us the state changed: update the local block entity and, if a session exists, the page. */
    public static void applyVolumeState(BlockPos key, int volume, boolean muted) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(key) instanceof MediaSource be) {
            be.setVolumeState(volume, muted);
        }
        Entry entry = SESSIONS.get(key);
        if (entry != null) {
            entry.session.executeJavaScript(MphScripts.applyVolume(volume < 0 ? -1.0 : volume / 100.0, muted));
        }
    }

    /** Runs a window.mph call on this key's session if one exists (no session = nothing to do). */
    public static void runMph(BlockPos key, String call) {
        Entry entry = SESSIONS.get(key);
        if (entry != null) {
            entry.session.executeJavaScript(MphScripts.script(call));
        }
    }

    private static final class Entry {
        final IBrowserSession session;
        String loadedUrl;
        volatile String errorText;
        volatile String failedUrl;
        volatile int httpStatus;
        volatile String httpUrl;
        // last playback state printed by the page (MPH-POS): position (s), playing, rate, duration (s, -1 = unknown)
        volatile double pbPos = -1;
        volatile boolean pbPlaying;
        volatile double pbRate = 1;
        volatile double pbDuration = -1;
        volatile long pbAt;
        long pbLogAt;
        String joinedUrl; // page URL this session last told the server about (MPH-PLAYBACK)
        volatile double durLast;   // last reported duration, and how many reports in a row repeated it
        volatile int durStable;
        volatile long lastOfferAt; // last time this page offered its (longer) video to the server
        volatile boolean startPaused; // joined while the page was still paused at the very start (autoplay not begun)
        volatile long joinedAt;
        volatile boolean resync;      // an apply was skipped (page shows another video): join again when the duration matches
        volatile double wantDuration; // duration of the shared video, from the last state received
        volatile long lastApplyAt; // when a shared state was last applied to this page
        volatile long remoteAt;    // when another player's mouse input was last replayed here
        volatile String remoteName;
        volatile boolean pwdFocused = true; // password-like field focused (or unknown): keys are not relayed/applied
        volatile long lastCmdAt;   // when this client last sent a playback command
        final io.github.kiyo.mediapanelhologram.block.QualityPreset quality;

        Entry(IBrowserSession session, String loadedUrl, io.github.kiyo.mediapanelhologram.block.QualityPreset quality) {
            this.session = session;
            this.loadedUrl = loadedUrl;
            this.quality = quality;
        }
    }
}
