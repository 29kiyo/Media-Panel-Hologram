package io.github.kiyo.mediapanelhologram.network;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.architectury.networking.NetworkManager;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import io.github.kiyo.mediapanelhologram.block.entity.MediaSource;

/**
 * Server-side shared playback ("broadcast") per screen, kept in memory only. Page URLs never reach the
 * server: clients send a hash (PlaybackUrl). The first client whose page reports playback sets the
 * baseline; later joiners are told the current position (baseline + elapsed wall-clock time x rate).
 * Play/pause, seek and rate commands are applied here as absolute values and sent to every player of the
 * level; clients apply them only if their page URL hash equals the baseline's and their page reports the
 * same video duration (a page may report an ad or a preview element instead).
 * A join for the same page that reports a clearly LONGER video replaces the baseline (the baseline was
 * taken from an ad / preview); no ad is detected, only durations are compared.
 * A baseline nobody has watched for EXPIRE_MS is discarded. All calls run on the server thread.
 */
public final class PlaybackService {
    private static final long EXPIRE_MS = 10L * 60L * 1000L;
    private static final boolean DEBUG = "1".equals(System.getenv("MPH_SYNC_DEBUG"));
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("MPH-SYNC");

    private record Key(ResourceKey<Level> dimension, BlockPos pos) {
    }

    private static final class Broadcast {
        long urlHash;
        double position;
        boolean playing;
        double rate;
        double duration; // seconds of the shared video, -1 = unknown
        boolean pristine = true; // no play/pause/seek/rate command has touched this baseline yet
        long at;
        long emptySince;
        final Map<UUID, Long> viewers = new HashMap<>();

        Broadcast(long urlHash, double position, boolean playing, double rate, double duration, long now) {
            this.urlHash = urlHash;
            this.duration = duration;
            set(position, playing, rate, now);
            this.emptySince = now;
        }

        double positionAt(long now) {
            double p = playing ? position + (now - at) / 1000.0 * rate : position;
            return duration > 0 ? Math.min(p, duration) : p;
        }

        /** True once the video has played to its end (the position stops at the duration). */
        boolean atEnd(long now) {
            return duration > 0 && positionAt(now) >= duration - 0.05;
        }

        /** True while playing and not at the end of the video. */
        boolean playingAt(long now) {
            return playing && !atEnd(now);
        }

        void set(double position, boolean playing, double rate, long now) {
            this.position = position;
            this.playing = playing;
            this.rate = rate;
            this.at = now;
        }
    }

    private static final Map<Key, Broadcast> BROADCASTS = new HashMap<>();
    private static MinecraftServer owner;

    private PlaybackService() {
    }

    private static void log(String msg, Object... args) {
        if (DEBUG) {
            LOG.info("server: " + msg, args);
        }
    }

    private static String h(long v) {
        return Long.toHexString(v);
    }

    /** A new server (new world in the same JVM) starts with no state. */
    private static void claim(ServerLevel level) {
        if (owner != level.getServer()) {
            BROADCASTS.clear();
            owner = level.getServer();
        }
    }

    private static double sanePosition(double v) {
        return Double.isFinite(v) ? Math.max(0.0, Math.min(1.0e7, v)) : 0.0;
    }

    private static double saneRate(double v) {
        return Double.isFinite(v) ? Math.max(0.25, Math.min(4.0, v)) : 1.0;
    }

    private static double saneDuration(double v) {
        return Double.isFinite(v) && v > 0 ? Math.min(1.0e7, v) : -1.0;
    }

    /** Durations of one video seen by different pages agree within about 1 s (or 1 %). */
    private static boolean sameDuration(double a, double b) {
        return Math.abs(a - b) <= Math.max(1.0, Math.max(a, b) * 0.01);
    }

    /** The live broadcast for this key, or null (none, block gone, or expired). Drops offline viewers. */
    @Nullable
    private static Broadcast live(Key k, ServerLevel level, long now) {
        Broadcast b = BROADCASTS.get(k);
        if (b == null) {
            return null;
        }
        if (!(level.getBlockEntity(k.pos()) instanceof MediaSource)) {
            BROADCASTS.remove(k);
            return null;
        }
        if (!b.viewers.isEmpty()) {
            Set<UUID> online = new HashSet<>();
            for (ServerPlayer p : level.players()) {
                online.add(p.getUUID());
            }
            if (b.viewers.keySet().removeIf(id -> !online.contains(id)) && b.viewers.isEmpty()) {
                b.emptySince = now;
            }
        }
        if (b.viewers.isEmpty() && now - b.emptySince > EXPIRE_MS) {
            BROADCASTS.remove(k);
            log("baseline expired key={}", k.pos());
            return null;
        }
        return b;
    }

    private static PlaybackStatePayload stateOf(Key k, Broadcast b, long now) {
        return new PlaybackStatePayload(k.pos(), b.urlHash, b.positionAt(now), b.playingAt(now), b.rate, b.duration);
    }

    private static void sendState(ServerPlayer player, Key k, Broadcast b, long now) {
        NetworkManager.sendToPlayer(player, stateOf(k, b, now));
    }

    private static void broadcast(ServerLevel level, Key k, Broadcast b, long now) {
        PlaybackStatePayload state = stateOf(k, b, now);
        for (ServerPlayer p : level.players()) {
            NetworkManager.sendToPlayer(p, state);
        }
    }

    /** A client's page started reporting (or its page URL changed, or it offers a longer video). */
    public static void join(ServerLevel level, ServerPlayer player, BlockPos key, long urlHash, double position,
            boolean playing, double rate, double duration) {
        if (urlHash == 0L) {
            return;
        }
        claim(level);
        long now = System.currentTimeMillis();
        Key k = new Key(level.dimension(), key.immutable());
        UUID id = player.getUUID();
        double dur = saneDuration(duration);
        Broadcast b = live(k, level, now);
        if (b == null) {
            b = new Broadcast(urlHash, sanePosition(position), playing, saneRate(rate), dur, now);
            b.viewers.put(id, urlHash);
            BROADCASTS.put(k, b);
            log("create baseline key={} urlHash={} pos={} playing={} duration={}", key, h(urlHash), b.position, playing, dur);
            return;
        }
        b.viewers.put(id, urlHash);
        if (b.urlHash == urlHash) {
            if (b.pristine && !b.playing && b.position < 1.0 && playing
                    && (b.duration <= 0 || dur <= 0 || sameDuration(b.duration, dur))) {
                // The baseline is still the untouched paused start, taken from a page that had not begun
                // playing yet; this page is playing now. Follow it instead of keeping everybody paused at 0.
                if (dur > 0 && b.duration <= 0) {
                    b.duration = dur;
                }
                b.set(sanePosition(position), true, saneRate(rate), now);
                broadcast(level, k, b, now);
                log("baseline started by first play key={} pos={}", key, b.position);
                return;
            }
            if (dur > 0 && (b.duration <= 0 || dur > b.duration * 1.01 + 1.0)) {
                // Same page, clearly longer video: the baseline was taken from an ad / preview (or from
                // a page that had no video yet). Adopt this client's state for everybody.
                double was = b.duration;
                b.duration = dur;
                b.set(sanePosition(position), playing, saneRate(rate), now);
                broadcast(level, k, b, now);
                log("baseline adopted longer video key={} duration {} -> {} pos={}", key, was, dur, b.position);
                return;
            }
            sendState(player, k, b, now);
            log("join key={} synced to pos={} playing={}", key, b.positionAt(now), b.playingAt(now));
            return;
        }
        for (Map.Entry<UUID, Long> e : b.viewers.entrySet()) {
            if (!e.getKey().equals(id) && e.getValue() == b.urlHash) {
                log("join key={} ignored (other URL, baseline still followed)", key);
                return; // someone still follows the old baseline
            }
        }
        b.urlHash = urlHash;
        b.duration = dur;
        b.set(sanePosition(position), playing, saneRate(rate), now);
        broadcast(level, k, b, now);
        log("baseline replaced key={} urlHash={} pos={} duration={}", key, h(urlHash), b.position, dur);
    }

    public static void leave(ServerLevel level, ServerPlayer player, BlockPos key) {
        claim(level);
        Key k = new Key(level.dimension(), key.immutable());
        Broadcast b = BROADCASTS.get(k);
        if (b != null && b.viewers.remove(player.getUUID()) != null && b.viewers.isEmpty()) {
            b.emptySince = System.currentTimeMillis();
        }
    }

    /** Remote-control entry point (no commander position). */
    public static boolean command(ServerLevel level, BlockPos key, RemoteCommand cmd, int arg) {
        return command(level, key, cmd, arg, null, null);
    }

    /**
     * Applies PLAY_TOGGLE / SEEK_BY / SET_RATE to the shared state and broadcasts it.
     *
     * @param ref {position, playing(1/0), rate, duration} of the commander, or null. Used as the new
     *            baseline only if the commander follows the baseline URL and its video duration matches.
     * @return false if this is not a shared command or there is no live baseline (caller falls back to the old behaviour)
     */
    public static boolean command(ServerLevel level, BlockPos key, RemoteCommand cmd, int arg,
            @Nullable UUID commander, @Nullable double[] ref) {
        if (cmd != RemoteCommand.PLAY_TOGGLE && cmd != RemoteCommand.SEEK_BY && cmd != RemoteCommand.SET_RATE) {
            return false;
        }
        claim(level);
        long now = System.currentTimeMillis();
        Key k = new Key(level.dimension(), key.immutable());
        Broadcast b = live(k, level, now);
        if (b == null) {
            return false;
        }
        Long viewerHash = commander == null ? null : b.viewers.get(commander);
        if (ref != null && viewerHash != null && viewerHash.longValue() == b.urlHash) {
            double refDur = ref.length > 3 ? saneDuration(ref[3]) : -1.0;
            boolean ok;
            if (b.duration > 0) {
                ok = refDur > 0 && sameDuration(b.duration, refDur);
            } else {
                ok = true;
                if (refDur > 0) {
                    b.duration = refDur;
                }
            }
            if (ok) {
                b.set(sanePosition(ref[0]), ref[1] > 0.5, saneRate(ref[2]), now);
            } else {
                log("commander position rejected key={} (page duration {} vs shared {})", key, refDur, b.duration);
            }
        }
        double cur = b.positionAt(now);
        b.pristine = false;
        switch (cmd) {
            case PLAY_TOGGLE -> {
                if (b.atEnd(now)) {
                    b.set(0.0, true, b.rate, now); // play at the end starts again from the beginning
                } else {
                    b.set(cur, !b.playing, b.rate, now);
                }
            }
            case SEEK_BY -> b.set(Math.max(0.0, cur + Math.max(-600, Math.min(600, arg))), b.playing, b.rate, now);
            case SET_RATE -> b.set(cur, b.playing, Math.max(25, Math.min(400, arg)) / 100.0, now);
            default -> {
            }
        }
        broadcast(level, k, b, now);
        log("command {} arg={} key={} -> pos={} playing={} rate={}", cmd, arg, key, b.positionAt(now), b.playingAt(now), b.rate);
        return true;
    }
}
