package io.github.kiyo.mediapanelhologram.client.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dev.architectury.networking.NetworkManager;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import io.github.kiyo.mediapanelhologram.block.LockData;
import io.github.kiyo.mediapanelhologram.network.LockAccess;
import io.github.kiyo.mediapanelhologram.network.LockUpdatePayload;

/**
 * Lock / unlock a screen and pick which players may operate it. Everything is
 * read each frame from the client's synced BlockEntity; every change goes to the
 * server (LockUpdatePayload), which validates it again. Widgets are kept in
 * ownWidgets and drawn explicitly (same reason as the other screens).
 */
@Environment(EnvType.CLIENT)
public class LockScreen extends Screen {
    private static final int W = 200;
    private static final int H = 196;
    private static final int ROW_H = 14;
    private static final int VISIBLE = 6;
    private static final int LIST_TOP = 74;

    private record Row(UUID id, String name, boolean allowed, boolean online) {
    }

    private final BlockPos pos;
    private final List<Renderable> ownWidgets = new ArrayList<>();
    private Button lockButton;
    private int left;
    private int top;
    private int scroll = 0;

    public LockScreen(BlockPos pos) {
        super(Component.translatable("gui.mediapanelhologram.lock.title"));
        this.pos = pos;
    }

    private LockData lock() {
        var level = Minecraft.getInstance().level;
        return level == null ? LockData.NONE : LockAccess.lockOf(level, pos);
    }

    private boolean canManage(LockData lock) {
        var p = Minecraft.getInstance().player;
        return p != null && LockAccess.canManage(p, lock);
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
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;
        this.lockButton = addOwnWidget(Button.builder(Component.empty(), b -> {
                    LockData l = lock();
                    var p = Minecraft.getInstance().player;
                    if (p == null) {
                        return;
                    }
                    NetworkManager.sendToServer(new LockUpdatePayload(pos,
                            l.locked() ? LockUpdatePayload.Action.UNLOCK : LockUpdatePayload.Action.LOCK, p.getUUID()));
                })
                .bounds(left + 10, top + 36, W - 20, 20)
                .build());
        addOwnWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
                .bounds(left + 10, top + H - 26, W - 20, 18)
                .build());
    }

    private List<Row> buildRows(LockData lock) {
        List<Row> online = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        var conn = Minecraft.getInstance().getConnection();
        if (conn != null) {
            for (PlayerInfo pi : conn.getOnlinePlayers()) {
                UUID id = pi.getProfile().id();
                if (lock.isOwner(id) || !seen.add(id)) {
                    continue;
                }
                online.add(new Row(id, pi.getProfile().name(), lock.isAllowed(id), true));
            }
        }
        online.sort(Comparator.comparing(r -> r.name().toLowerCase()));
        List<Row> rows = new ArrayList<>(online);
        for (LockData.Entry e : lock.allowed()) {
            if (!seen.contains(e.id())) {
                rows.add(new Row(e.id(), e.name(), true, false));
            }
        }
        return rows;
    }

    // The engine calls extractBackground before extractRenderState; the default draws the blur.
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        LockData lock = lock();
        boolean manage = canManage(lock);
        lockButton.setMessage(Component.translatable(lock.locked()
                ? "gui.mediapanelhologram.lock.unlock" : "gui.mediapanelhologram.lock.lock"));
        lockButton.active = !lock.locked() || manage;

        graphics.fill(left, top, left + W, top + H, 0xE0101010);
        graphics.outline(left, top, W, H, 0xFF2A5A55);
        graphics.text(this.font, this.title, left + 10, top + 8, 0xFFFFFFFF);
        graphics.text(this.font, lock.locked()
                ? Component.translatable("gui.mediapanelhologram.lock.status_locked", lock.ownerName())
                : Component.translatable("gui.mediapanelhologram.lock.status_unlocked"),
                left + 10, top + 22, lock.locked() ? 0xFFFFCC66 : 0xFFAAAAAA);

        if (lock.locked()) {
            graphics.text(this.font, Component.translatable("gui.mediapanelhologram.lock.allowed_header"),
                    left + 10, top + 62, 0xFFAAAAAA);
            List<Row> rows = buildRows(lock);
            scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - VISIBLE)));
            String offline = Component.translatable("gui.mediapanelhologram.lock.offline").getString();
            for (int r = 0; r < VISIBLE; r++) {
                int i = scroll + r;
                if (i >= rows.size()) {
                    break;
                }
                Row row = rows.get(i);
                int y = top + LIST_TOP + r * ROW_H;
                boolean hover = manage && mouseX >= left + 10 && mouseX < left + W - 10
                        && mouseY >= y && mouseY < y + ROW_H;
                if (hover) {
                    graphics.fill(left + 10, y, left + W - 10, y + ROW_H - 1, 0x6033AA99);
                }
                String text = (row.allowed() ? "[x] " : "[ ] ") + row.name() + (row.online() ? "" : " " + offline);
                graphics.text(this.font, this.font.plainSubstrByWidth(text, W - 28), left + 14, y + 3,
                        row.allowed() ? 0xFF4FD1C5 : (manage ? 0xFFFFFFFF : 0xFF777777));
            }
            if (rows.isEmpty()) {
                graphics.text(this.font, Component.translatable("gui.mediapanelhologram.lock.nobody"),
                        left + 14, top + LIST_TOP + 3, 0xFF777777);
            }
            if (!manage) {
                graphics.text(this.font, Component.translatable("gui.mediapanelhologram.lock.view_only"),
                        left + 10, top + LIST_TOP + VISIBLE * ROW_H + 4, 0xFFFF8888);
            }
        }
        for (Renderable widget : ownWidgets) {
            widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        LockData lock = lock();
        if (!lock.locked() || !canManage(lock) || event.buttonInfo().button() != 0) {
            return false;
        }
        double mx = event.x();
        double my = event.y();
        if (mx < left + 10 || mx >= left + W - 10 || my < top + LIST_TOP || my >= top + LIST_TOP + VISIBLE * ROW_H) {
            return false;
        }
        List<Row> rows = buildRows(lock);
        int i = scroll + (int) ((my - (top + LIST_TOP)) / ROW_H);
        if (i < 0 || i >= rows.size()) {
            return false;
        }
        Row row = rows.get(i);
        NetworkManager.sendToServer(new LockUpdatePayload(pos,
                row.allowed() ? LockUpdatePayload.Action.REMOVE : LockUpdatePayload.Action.ADD, row.id()));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int n = buildRows(lock()).size();
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY), Math.max(0, n - VISIBLE)));
        return true;
    }
}
