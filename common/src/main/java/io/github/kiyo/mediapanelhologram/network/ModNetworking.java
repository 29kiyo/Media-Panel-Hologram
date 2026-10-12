package io.github.kiyo.mediapanelhologram.network;

import dev.architectury.networking.NetworkManager;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import io.github.kiyo.mediapanelhologram.block.entity.MediaSource;

/**
 * Registers the mod's custom network payloads.
 *
 * Called unconditionally from MediaPanelHologram.init() on both physical
 * sides: payload Type/StreamCodec registration must happen wherever the
 * payload might be encoded/decoded (i.e. both sides), while each payload's
 * receiver callback only ever actually runs on the side it's meant for
 * (MediaBlockUpdatePayload: server only; MediaBlockSyncPayload: client
 * only -- see MediaBlockSyncPayload's Javadoc for why the S2C payload
 * exists).
 *
 * TABS REMOVED: MediaBlockTabsPayload/MediaBlockTabsSyncPayload handling
 * was here and has been reverted -- see MediaBlockEntity's Javadoc.
 */
public final class ModNetworking {
    public static void init() {
        NetworkManager.registerC2S(
                MediaBlockUpdatePayload.TYPE,
                MediaBlockUpdatePayload.STREAM_CODEC,
                ModNetworking::handleMediaBlockUpdate);
        NetworkManager.registerS2C(
                MediaBlockSyncPayload.TYPE,
                MediaBlockSyncPayload.STREAM_CODEC,
                ModNetworking::handleMediaBlockSync);
        NetworkManager.registerC2S(
                HologramUpdatePayload.TYPE,
                HologramUpdatePayload.STREAM_CODEC,
                ModNetworking::handleHologramUpdate);
        NetworkManager.registerS2C(
                HologramSyncPayload.TYPE,
                HologramSyncPayload.STREAM_CODEC,
                ModNetworking::handleHologramSync);
        NetworkManager.registerC2S(
                MediaVolumeCommandPayload.TYPE,
                MediaVolumeCommandPayload.STREAM_CODEC,
                ModNetworking::handleVolumeCommand);
        NetworkManager.registerS2C(
                MediaVolumeSyncPayload.TYPE,
                MediaVolumeSyncPayload.STREAM_CODEC,
                ModNetworking::handleVolumeSync);
        NetworkManager.registerC2S(
                RemoteEditPayload.TYPE,
                RemoteEditPayload.STREAM_CODEC,
                ModNetworking::handleRemoteEdit);
        NetworkManager.registerC2S(
                RemoteCommandPayload.TYPE,
                RemoteCommandPayload.STREAM_CODEC,
                ModNetworking::handleRemoteCommand);
        NetworkManager.registerS2C(
                RemoteSyncPayload.TYPE,
                RemoteSyncPayload.STREAM_CODEC,
                ModNetworking::handleRemoteSync);
        NetworkManager.registerC2S(
                RemoteOpenPayload.TYPE,
                RemoteOpenPayload.STREAM_CODEC,
                ModNetworking::handleRemoteOpen);
        NetworkManager.registerS2C(
                LockSyncPayload.TYPE,
                LockSyncPayload.STREAM_CODEC,
                ModNetworking::handleLockSync);
        NetworkManager.registerC2S(
                LockUpdatePayload.TYPE,
                LockUpdatePayload.STREAM_CODEC,
                (payload, context) -> context.queue(() -> LockService.handle(context.getPlayer(), payload)));
        NetworkManager.registerC2S(
                RangeUpdatePayload.TYPE,
                RangeUpdatePayload.STREAM_CODEC,
                ModNetworking::handleRangeUpdate);
        NetworkManager.registerS2C(
                RangeSyncPayload.TYPE,
                RangeSyncPayload.STREAM_CODEC,
                ModNetworking::handleRangeSync);
        NetworkManager.registerC2S(
                PlaybackJoinPayload.TYPE,
                PlaybackJoinPayload.STREAM_CODEC,
                ModNetworking::handlePlaybackJoin);
        NetworkManager.registerC2S(
                PlaybackLeavePayload.TYPE,
                PlaybackLeavePayload.STREAM_CODEC,
                ModNetworking::handlePlaybackLeave);
        NetworkManager.registerC2S(
                PlaybackCommandPayload.TYPE,
                PlaybackCommandPayload.STREAM_CODEC,
                ModNetworking::handlePlaybackCommand);
        NetworkManager.registerS2C(
                PlaybackStatePayload.TYPE,
                PlaybackStatePayload.STREAM_CODEC,
                ModNetworking::handlePlaybackState);
    }

    private static void handleRangeUpdate(RangeUpdatePayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer sp)) {
                return;
            }
            Level level = sp.level();
            net.minecraft.core.BlockPos pos = payload.pos();
            if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof MediaSource src)) {
                return;
            }
            // 8 blocks, or a held remote that has this screen connected.
            if (!RemoteAccess.canReach(sp, pos)) {
                return;
            }
            if (!LockAccess.canOperate(sp, level, pos)) {
                sp.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.mediapanelhologram.lock.denied"));
                return;
            }
            int range = io.github.kiyo.mediapanelhologram.block.MediaRange.clamp(payload.range());
            net.minecraft.core.BlockPos syncPos = pos;
            if (src instanceof io.github.kiyo.mediapanelhologram.block.entity.PanelBlockEntity pb
                    && level.getBlockEntity(pb.getGroupOrigin())
                            instanceof io.github.kiyo.mediapanelhologram.block.entity.PanelBlockEntity rep) {
                // Panels: the group representative owns the setting, then it is copied to every member.
                rep.setRange(range);
                io.github.kiyo.mediapanelhologram.block.panel.PanelGroupManager.propagateSettings(level, rep);
                net.minecraft.world.level.block.state.BlockState s = level.getBlockState(rep.getBlockPos());
                level.sendBlockUpdated(rep.getBlockPos(), s, s, Block.UPDATE_CLIENTS);
                syncPos = rep.getBlockPos();
            } else {
                src.setRange(range);
            }
            NetworkManager.sendToPlayers(sp.level().players(), new RangeSyncPayload(syncPos, range));
        });
    }

    private static void handleRangeSync(RangeSyncPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            if (context.getPlayer().level().getBlockEntity(payload.pos()) instanceof MediaSource src) {
                src.setRange(payload.range());
            }
        });
    }

    private static void handlePlaybackJoin(PlaybackJoinPayload p, NetworkManager.PacketContext context) {
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer sp) || !(sp.level() instanceof ServerLevel level)) {
                return;
            }
            net.minecraft.core.BlockPos key = p.key();
            if (!level.isLoaded(key) || !(level.getBlockEntity(key) instanceof MediaSource src)
                    || !src.getSessionKey().equals(key)) {
                return;
            }
            PlaybackService.join(level, sp, key, p.urlHash(), p.position(), p.playing(), p.rate(), p.duration());
        });
    }

    private static void handlePlaybackLeave(PlaybackLeavePayload p, NetworkManager.PacketContext context) {
        context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer sp && sp.level() instanceof ServerLevel level) {
                PlaybackService.leave(level, sp, p.key());
            }
        });
    }

    private static void handlePlaybackCommand(PlaybackCommandPayload p, NetworkManager.PacketContext context) {
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer sp) || !(sp.level() instanceof ServerLevel level)) {
                return;
            }
            net.minecraft.core.BlockPos pos = p.pos();
            if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof MediaSource src)) {
                return;
            }
            RemoteCommand cmd = p.cmd();
            if (cmd != RemoteCommand.PLAY_TOGGLE && cmd != RemoteCommand.SEEK_BY && cmd != RemoteCommand.SET_RATE) {
                return;
            }
            // 8 blocks, or a held remote that has this screen connected.
            if (!RemoteAccess.canReach(sp, pos)) {
                return;
            }
            if (!LockAccess.canOperate(sp, level, pos)) {
                sp.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.mediapanelhologram.lock.denied"));
                return;
            }
            net.minecraft.core.BlockPos key = src.getSessionKey();
            double[] ref = p.hasRef()
                    ? new double[] {p.refPos(), p.refPlaying() ? 1.0 : 0.0, p.refRate(), p.refDuration()} : null;
            if (!PlaybackService.command(level, key, cmd, p.arg(), sp.getUUID(), ref)) {
                // no shared state yet: behave as before for this player's own page
                NetworkManager.sendToPlayer(sp, new RemoteSyncPayload(key, cmd, p.arg()));
            }
        });
    }

    private static void handlePlaybackState(PlaybackStatePayload p, NetworkManager.PacketContext context) {
        context.queue(() -> io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager
                .applyPlayback(p.key(), p.urlHash(), p.position(), p.playing(), p.rate(), p.duration()));
    }

    private static void handleLockSync(LockSyncPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            if (context.getPlayer().level().getBlockEntity(payload.pos()) instanceof MediaSource src) {
                src.setLock(payload.lock());
            }
        });
    }

    private static void handleVolumeCommand(MediaVolumeCommandPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            Player player = context.getPlayer();
            Level level = player.level();
            net.minecraft.core.BlockPos pos = payload.pos();
            if (!(level.getBlockEntity(pos) instanceof MediaSource)) {
                return;
            }
            // 8 blocks, or a held remote that has this screen connected.
            if (!RemoteAccess.canReach(player, pos)) {
                return;
            }
            if (!LockAccess.canOperate(player, level, pos)) {
                player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.mediapanelhologram.lock.denied"));
                return;
            }
            MediaVolumeService.apply(level, pos, payload.op(), payload.arg());
        });
    }

    private static void handleVolumeSync(MediaVolumeSyncPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager
                .applyVolumeState(payload.pos(), payload.volume(), payload.muted()));
    }

    private static void handleRemoteOpen(RemoteOpenPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer sp)) {
                return;
            }
            net.minecraft.world.InteractionHand hand = payload.hand() == 1
                    ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND;
            net.minecraft.world.item.ItemStack stack = sp.getItemInHand(hand);
            if (!(stack.getItem() instanceof io.github.kiyo.mediapanelhologram.item.RemoteItem)) {
                return;
            }
            io.github.kiyo.mediapanelhologram.item.RemoteData data = stack.getOrDefault(
                    io.github.kiyo.mediapanelhologram.registry.ModDataComponents.REMOTE_DATA.get(),
                    io.github.kiyo.mediapanelhologram.item.RemoteData.EMPTY);
            Level level = sp.level();
            net.minecraft.core.BlockPos pos = payload.pos();
            if (data.indexOf(level.dimension(), pos) < 0 || !level.isLoaded(pos)) {
                return;
            }
            net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
            if (!LockAccess.canOperate(sp, level, pos)) {
                sp.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.mediapanelhologram.lock.denied"));
                return;
            }
            if (be instanceof dev.architectury.registry.menu.ExtendedMenuProvider provider && be instanceof MediaSource) {
                dev.architectury.registry.menu.MenuRegistry.openExtendedMenu(sp, provider);
            }
        });
    }

    private static void handleRemoteCommand(RemoteCommandPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> RemoteService.handle(context.getPlayer(), payload));
    }

    private static void handleRemoteSync(RemoteSyncPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            String js = payload.cmd().js(payload.arg());
            if (js != null) {
                io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager.runMph(payload.pos(), js);
            }
        });
    }

    private static void handleRemoteEdit(RemoteEditPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            Player player = context.getPlayer();
            net.minecraft.world.InteractionHand hand = payload.hand() == 1
                    ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND;
            net.minecraft.world.item.ItemStack stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof io.github.kiyo.mediapanelhologram.item.RemoteItem)) {
                return;
            }
            io.github.kiyo.mediapanelhologram.item.RemoteData data = stack.getOrDefault(
                    io.github.kiyo.mediapanelhologram.registry.ModDataComponents.REMOTE_DATA.get(),
                    io.github.kiyo.mediapanelhologram.item.RemoteData.EMPTY);
            int i = payload.index();
            if (i < 0 || i >= data.targets().size()) {
                return;
            }
            io.github.kiyo.mediapanelhologram.item.RemoteTarget t = data.targets().get(i);
            if (!t.pos().equals(payload.pos())) {
                return; // the list changed since the client looked at it
            }
            io.github.kiyo.mediapanelhologram.item.RemoteData next;
            switch (payload.action()) {
                case REMOVE -> next = data.without(i);
                case SET_SELECTED -> next = data.replace(i, new io.github.kiyo.mediapanelhologram.item.RemoteTarget(
                        t.dimension(), t.pos(), t.name(), payload.flag()));
                case RENAME -> {
                    String name = sanitizeName(payload.text());
                    if (name.isEmpty()) {
                        return;
                    }
                    next = data.replace(i, new io.github.kiyo.mediapanelhologram.item.RemoteTarget(
                            t.dimension(), t.pos(), name, t.selected()));
                }
                default -> {
                    return;
                }
            }
            stack.set(io.github.kiyo.mediapanelhologram.registry.ModDataComponents.REMOTE_DATA.get(), next);
        });
    }

    /** Trim, drop control characters, cap at 24 characters. May return "" (rejected by the caller). */
    private static String sanitizeName(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < raw.length(); k++) {
            char c = raw.charAt(k);
            if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        String s = sb.toString().trim();
        return s.length() > 24 ? s.substring(0, 24).trim() : s;
    }

    private static void handleMediaBlockUpdate(MediaBlockUpdatePayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            Player player = context.getPlayer();
            Level level = player.level();
            if (level.getBlockEntity(payload.pos()) instanceof MediaSource mediaBe) {
                if (!LockAccess.canOperate(player, level, payload.pos())) {
                    player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.mediapanelhologram.lock.denied"));
                    return;
                }
                mediaBe.setUrlAndMode(payload.url(), payload.mode());
                mediaBe.setQuality(payload.quality());
                if (mediaBe instanceof io.github.kiyo.mediapanelhologram.block.entity.PanelBlockEntity panelBe) {
                    if (panelBe.getBlockState().getValue(io.github.kiyo.mediapanelhologram.block.PanelBlock.FACING).getAxis()
                            == net.minecraft.core.Direction.Axis.Y) {
                        panelBe.setScreenUpRef(switch (player.getDirection()) {
                            case WEST -> 1;
                            case NORTH -> 2;
                            case EAST -> 3;
                            default -> 0;
                        });
                    }
                    io.github.kiyo.mediapanelhologram.block.panel.PanelGroupManager.propagateSettings(level, panelBe);
                }
                level.sendBlockUpdated(payload.pos(), level.getBlockState(payload.pos()), level.getBlockState(payload.pos()),
                        Block.UPDATE_CLIENTS);

                MediaBlockSyncPayload syncPayload =
                        new MediaBlockSyncPayload(payload.pos(), payload.url(), payload.mode(), payload.quality());
                if (level instanceof ServerLevel serverLevel) {
                    for (ServerPlayer serverPlayer : serverLevel.getServer().getPlayerList().getPlayers()) {
                        NetworkManager.sendToPlayer(serverPlayer, syncPayload);
                    }
                }
            }
        });
    }

    private static void handleMediaBlockSync(MediaBlockSyncPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            Level level = context.getPlayer().level();
            if (level.getBlockEntity(payload.pos()) instanceof MediaSource mediaBe) {
                mediaBe.setUrlAndMode(payload.url(), payload.mode());
                mediaBe.setQuality(payload.quality());
            }
        });
    }

    private static void handleHologramUpdate(HologramUpdatePayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            Player player = context.getPlayer();
            Level level = player.level();
            net.minecraft.core.BlockPos pos = payload.pos();
            if (!RemoteAccess.canReach(player, pos)) {
                return;
            }
            if (!LockAccess.canOperate(player, level, pos)) {
                player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.mediapanelhologram.lock.denied"));
                return;
            }
            if (level.getBlockEntity(pos) instanceof io.github.kiyo.mediapanelhologram.block.entity.MediaBlockEntity src) {
                io.github.kiyo.mediapanelhologram.block.HologramSettings settings = payload.settings().sanitized();
                src.setHologram(settings);
                net.minecraft.world.level.block.state.BlockState st = level.getBlockState(pos);
                level.sendBlockUpdated(pos, st, st, Block.UPDATE_CLIENTS);
                if (level instanceof ServerLevel serverLevel) {
                    HologramSyncPayload sync = new HologramSyncPayload(pos, settings);
                    for (ServerPlayer sp : serverLevel.getServer().getPlayerList().getPlayers()) {
                        NetworkManager.sendToPlayer(sp, sync);
                    }
                }
            }
        });
    }

    private static void handleHologramSync(HologramSyncPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            Level level = context.getPlayer().level();
            if (level.getBlockEntity(payload.pos()) instanceof io.github.kiyo.mediapanelhologram.block.entity.MediaBlockEntity src) {
                src.setHologram(payload.settings());
            }
        });
    }

    private ModNetworking() {
    }
}
