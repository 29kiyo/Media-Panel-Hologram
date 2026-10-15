package io.github.kiyo.mediapanelhologram.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.architectury.networking.NetworkManager;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Relays mouse input from one player to the others, with one "operator" per screen at a time: a press or
 * wheel takes the screen if nobody else holds it; it is released HOLD_MS after the holder's last input.
 * Other players' inputs are dropped meanwhile. Moves and releases never take the screen. Memory only.
 */
public final class InputRelayService {
    private static final long HOLD_MS = 1500L;
    private static final boolean DEBUG = "1".equals(System.getenv("MPH_SYNC_DEBUG"));
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("MPH-INPUT");

    private record Key(ResourceKey<Level> dimension, BlockPos pos) {
    }

    private static final class Owner {
        final UUID id;
        final String name;
        long at;
        long deniedAt;

        Owner(UUID id, String name, long at) {
            this.id = id;
            this.name = name;
            this.at = at;
        }
    }

    private static final Map<Key, Owner> OWNERS = new HashMap<>();
    private static MinecraftServer owner;

    private InputRelayService() {
    }

    public static void relay(ServerLevel level, ServerPlayer sp, InputRelayPayload p) {
        if (owner != level.getServer()) {
            OWNERS.clear();
            owner = level.getServer();
        }
        long now = System.currentTimeMillis();
        if (OWNERS.size() > 32) {
            OWNERS.values().removeIf(o -> now - o.at > 60_000L);
        }
        int kind = p.kind();
        boolean claims = kind == 1 || kind == 3 || kind == 4 || kind == 6;
        Key k = new Key(level.dimension(), p.key().immutable());
        Owner o = OWNERS.get(k);
        if (o != null && !o.id.equals(sp.getUUID()) && now - o.at < HOLD_MS) {
            if (claims && now - o.deniedAt > 1000L) {
                o.deniedAt = now;
                sp.sendOverlayMessage(Component.translatable("message.mediapanelhologram.input.busy", o.name));
            }
            return;
        }
        if (o == null || !o.id.equals(sp.getUUID())) {
            if (!claims) {
                return; // a move / release from someone who does not hold the screen
            }
            o = new Owner(sp.getUUID(), sp.getGameProfile().name(), now);
            OWNERS.put(k, o);
            if (DEBUG) {
                LOG.info("server: input owner key={} -> {}", k.pos(), o.name);
            }
        }
        o.at = now;
        InputRelayEventPayload ev = new InputRelayEventPayload(p.key(), p.urlHash(), kind, p.nx(), p.ny(),
                p.button(), p.amount(), sp.getGameProfile().name());
        for (ServerPlayer other : level.players()) {
            if (other != sp) {
                NetworkManager.sendToPlayer(other, ev);
            }
        }
    }
}
