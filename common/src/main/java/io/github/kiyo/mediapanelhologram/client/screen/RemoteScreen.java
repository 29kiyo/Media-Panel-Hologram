package io.github.kiyo.mediapanelhologram.client.screen;

import java.util.ArrayList;
import java.util.List;

import dev.architectury.networking.NetworkManager;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import io.github.kiyo.mediapanelhologram.item.RemoteData;
import io.github.kiyo.mediapanelhologram.item.RemoteItem;
import io.github.kiyo.mediapanelhologram.item.RemoteTarget;
import io.github.kiyo.mediapanelhologram.network.RemoteEditPayload;
import io.github.kiyo.mediapanelhologram.registry.ModDataComponents;

/**
 * Remote control screen (client only). The held remote ItemStack is the
 * source of truth: every frame the list is read from it. Edits are sent to
 * the server (RemoteEditPayload) AND applied to the local stack at once.
 *
 * The list can change under us (the server drops targets whose block is
 * gone), so the highlighted row is remembered by dimension+pos, never by
 * row number, and every edit packet carries the target's pos.
 *
 * Step 4a: connected-screen list, operate-target checkbox, delete, rename,
 * scrollbar. Operation buttons come in step 4b.
 */
public class RemoteScreen extends Screen {
    private static final int W = 280;
    private static final int TOTAL_W = 400;
    private static final int CTRL_X = 288;
    private static final int[] RATES = {100, 150, 200, 50};
    private static final int ROW_H = 16;
    private static final int VISIBLE = 8;
    private static final int LIST_TOP = 24;
    private static final int PANEL_H = 214;
    private static final int TRACK_H = VISIBLE * ROW_H;
    private static final int BAR_ZONE_X = W - 10; // scrollbar click zone starts here (relative to left)
    private static final int BAR_DRAW_X = W - 8;
    private static final int BAR_DRAW_W = 4;

    private final InteractionHand hand;
    private final List<Renderable> ownWidgets = new ArrayList<>();
    private EditBox nameBox;
    private RemoteTarget highlightedRef = null;
    private boolean draggingBar = false;
    private int scroll = 0;
    private int left;
    private int top;

    public RemoteScreen(InteractionHand hand) {
        super(Component.translatable("gui.mediapanelhologram.remote.title"));
        this.hand = hand;
    }

    public static void openFrom(InteractionHand hand) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof RemoteScreen) {
            return;
        }
        mc.setScreenAndShow(new RemoteScreen(hand));
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
        this.left = (this.width - TOTAL_W) / 2;
        this.top = Math.max(4, (this.height - PANEL_H) / 2);

        int rowY = top + LIST_TOP + TRACK_H + 8;
        this.nameBox = addOwnWidget(new EditBox(this.font, left + 8, rowY, 170, 18,
                Component.translatable("gui.mediapanelhologram.remote.rename")));
        this.nameBox.setMaxLength(24);
        this.nameBox.setHint(Component.translatable("gui.mediapanelhologram.remote.name_hint"));
        addOwnWidget(Button.builder(Component.translatable("gui.mediapanelhologram.remote.rename"), b -> rename())
                .bounds(left + 182, rowY, 90, 18)
                .build());
        addOwnWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
                .bounds(left + TOTAL_W - 68, top + PANEL_H - 26, 60, 18)
                .build());
        syncBox();
        addControls();
        addOpenButtons();
    }

    private Button repeatButton;

    /** Repeat state of the screens a command would reach (checked + highlighted): 1 all on, 0 all off, 2 mixed/unknown. */
    private int repeatState() {
        RemoteData d = data();
        int hi = highlightedIndex(d);
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return 0;
        }
        int on = 0;
        int off = 0;
        for (int i = 0; i < d.targets().size(); i++) {
            RemoteTarget t = d.targets().get(i);
            if (!(t.selected() || i == hi) || !level.dimension().equals(t.dimension())) {
                continue;
            }
            if (level.getBlockEntity(t.pos()) instanceof io.github.kiyo.mediapanelhologram.block.entity.MediaSource s) {
                if (s.isRepeat()) {
                    on++;
                } else {
                    off++;
                }
            }
        }
        return on > 0 && off > 0 ? 2 : (on > 0 ? 1 : 0);
    }

    private Button settingsButton;
    private Button browserButton;

    private void addOpenButtons() {
        this.settingsButton = addOwnWidget(Button.builder(
                        Component.translatable("gui.mediapanelhologram.remote.settings"), b -> openSettings())
                .bounds(left + 8, top + PANEL_H - 26, 90, 18)
                .build());
        this.browserButton = addOwnWidget(Button.builder(
                        Component.translatable("gui.mediapanelhologram.remote.browser"), b -> openBrowser())
                .bounds(left + 102, top + PANEL_H - 26, 90, 18)
                .build());
    }

    /** The highlighted target's block entity if this client has it loaded in the same dimension, else null. */
    private io.github.kiyo.mediapanelhologram.block.entity.MediaSource highlightedSource() {
        RemoteData d = data();
        int idx = highlightedIndex(d);
        if (idx < 0) {
            return null;
        }
        RemoteTarget t = d.targets().get(idx);
        var level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().equals(t.dimension())) {
            return null;
        }
        return level.getBlockEntity(t.pos()) instanceof io.github.kiyo.mediapanelhologram.block.entity.MediaSource s ? s : null;
    }

    private void openSettings() {
        var src = highlightedSource();
        if (src != null && Minecraft.getInstance().player != null
                && !io.github.kiyo.mediapanelhologram.network.LockAccess.canOperate(Minecraft.getInstance().player, src.getLock())) {
            Minecraft.getInstance().player.sendOverlayMessage(
                    Component.translatable("message.mediapanelhologram.lock.denied"));
            return;
        }
        if (src != null) {
            NetworkManager.sendToServer(new io.github.kiyo.mediapanelhologram.network.RemoteOpenPayload(
                    hand == InteractionHand.OFF_HAND ? 1 : 0, src.getBlockPos()));
        }
    }

    private void openBrowser() {
        var src = highlightedSource();
        if (src != null && Minecraft.getInstance().player != null
                && !io.github.kiyo.mediapanelhologram.network.LockAccess.canOperate(Minecraft.getInstance().player, src.getLock())) {
            Minecraft.getInstance().player.sendOverlayMessage(
                    Component.translatable("message.mediapanelhologram.lock.denied"));
            return;
        }
        if (src != null && (src.getMode() == io.github.kiyo.mediapanelhologram.block.MediaMode.BROWSER
                || src.getMode() == io.github.kiyo.mediapanelhologram.block.MediaMode.VIDEO)) {
            io.github.kiyo.mediapanelhologram.browser.MediaBrowserOpenerProvider.open(src.getBlockPos());
        }
    }

    private int rateIdx = 0;

    private void addControls() {
        ctl(0, 0, "Play", io.github.kiyo.mediapanelhologram.network.RemoteCommand.PLAY_TOGGLE, 0);
        repeatButton = addOwnWidget(Button.builder(Component.literal("Repeat"), b -> {
                    int s = repeatState();
                    sendCommand(io.github.kiyo.mediapanelhologram.network.RemoteCommand.TOGGLE_REPEAT, s == 1 ? 0 : 1);
                })
                .bounds(left + CTRL_X + 54, top + LIST_TOP, 52, 18)
                .build());
        ctl(0, 1, "-30", io.github.kiyo.mediapanelhologram.network.RemoteCommand.SEEK_BY, -30);
        ctl(1, 1, "+30", io.github.kiyo.mediapanelhologram.network.RemoteCommand.SEEK_BY, 30);
        ctl(0, 2, "-10", io.github.kiyo.mediapanelhologram.network.RemoteCommand.SEEK_BY, -10);
        ctl(1, 2, "+10", io.github.kiyo.mediapanelhologram.network.RemoteCommand.SEEK_BY, 10);
        ctl(0, 3, "Vol-", io.github.kiyo.mediapanelhologram.network.RemoteCommand.VOLUME_BY, -10);
        ctl(1, 3, "Vol+", io.github.kiyo.mediapanelhologram.network.RemoteCommand.VOLUME_BY, 10);
        ctl(0, 4, "Mute", io.github.kiyo.mediapanelhologram.network.RemoteCommand.TOGGLE_MUTE, 0);
        addOwnWidget(Button.builder(Component.literal("Speed"), b -> {
                    rateIdx = (rateIdx + 1) % RATES.length;
                    sendCommand(io.github.kiyo.mediapanelhologram.network.RemoteCommand.SET_RATE, RATES[rateIdx]);
                })
                .bounds(left + CTRL_X + 54, top + LIST_TOP + 4 * 20, 52, 18)
                .build());
        ctl(0, 5, "Prev", io.github.kiyo.mediapanelhologram.network.RemoteCommand.PREV, 0);
        ctl(1, 5, "Next", io.github.kiyo.mediapanelhologram.network.RemoteCommand.NEXT, 0);
        ctl(0, 6, "Shuffle", io.github.kiyo.mediapanelhologram.network.RemoteCommand.TOGGLE_SHUFFLE, 0);
        ctl(1, 6, "Queue", io.github.kiyo.mediapanelhologram.network.RemoteCommand.TOGGLE_QUEUE, 0);
        ctl(0, 7, "CC", io.github.kiyo.mediapanelhologram.network.RemoteCommand.TOGGLE_CAPTIONS, 0);
        ctl(1, 7, "Quality", io.github.kiyo.mediapanelhologram.network.RemoteCommand.NEXT_QUALITY, 0);
    }

    private void ctl(int col, int row, String label, io.github.kiyo.mediapanelhologram.network.RemoteCommand cmd, int arg) {
        addOwnWidget(Button.builder(Component.literal(label), b -> sendCommand(cmd, arg))
                .bounds(left + CTRL_X + col * 54, top + LIST_TOP + row * 20, 52, 18)
                .build());
    }

    private void sendCommand(io.github.kiyo.mediapanelhologram.network.RemoteCommand cmd, int arg) {
        // Every checked screen, plus the highlighted row (checked or not).
        RemoteData d = data();
        int idx = highlightedIndex(d);
        boolean include = idx >= 0;
        net.minecraft.core.BlockPos target = include ? d.targets().get(idx).pos() : net.minecraft.core.BlockPos.ZERO;
        NetworkManager.sendToServer(new io.github.kiyo.mediapanelhologram.network.RemoteCommandPayload(
                hand == InteractionHand.OFF_HAND ? 1 : 0, cmd, arg, include, target));
    }

    private ItemStack stack() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return null;
        }
        ItemStack s = mc.player.getItemInHand(hand);
        return s.getItem() instanceof RemoteItem ? s : null;
    }

    private RemoteData data() {
        ItemStack s = stack();
        return s == null ? RemoteData.EMPTY : s.getOrDefault(ModDataComponents.REMOTE_DATA.get(), RemoteData.EMPTY);
    }

    private void applyLocal(RemoteData next) {
        ItemStack s = stack();
        if (s != null) {
            s.set(ModDataComponents.REMOTE_DATA.get(), next);
        }
    }

    private void send(RemoteEditPayload.Action action, int index, RemoteTarget t, String text, boolean flag) {
        NetworkManager.sendToServer(new RemoteEditPayload(hand == InteractionHand.OFF_HAND ? 1 : 0,
                action, index, t.pos(), text, flag));
    }

    /** Row number of the highlighted target in the current list, or -1. */
    private int highlightedIndex(RemoteData d) {
        if (highlightedRef == null) {
            return -1;
        }
        return d.indexOf(highlightedRef.dimension(), highlightedRef.pos());
    }

    private void syncBox() {
        RemoteData d = data();
        int idx = highlightedIndex(d);
        this.nameBox.setValue(idx >= 0 ? d.targets().get(idx).name() : "");
        this.nameBox.setEditable(idx >= 0);
    }

    private void rename() {
        RemoteData d = data();
        int idx = highlightedIndex(d);
        if (idx < 0) {
            return;
        }
        String name = this.nameBox.getValue().trim();
        if (name.isEmpty()) {
            return;
        }
        RemoteTarget t = d.targets().get(idx);
        send(RemoteEditPayload.Action.RENAME, idx, t, name, false);
        RemoteTarget renamed = new RemoteTarget(t.dimension(), t.pos(), name, t.selected());
        applyLocal(d.replace(idx, renamed));
        highlightedRef = renamed;
    }

    private void toggleSelected(int i) {
        RemoteData d = data();
        RemoteTarget t = d.targets().get(i);
        boolean next = !t.selected();
        send(RemoteEditPayload.Action.SET_SELECTED, i, t, "", next);
        applyLocal(d.replace(i, new RemoteTarget(t.dimension(), t.pos(), t.name(), next)));
    }

    private void remove(int i) {
        RemoteData d = data();
        RemoteTarget t = d.targets().get(i);
        send(RemoteEditPayload.Action.REMOVE, i, t, "", false);
        applyLocal(d.without(i));
        if (highlightedRef != null && highlightedRef.dimension().equals(t.dimension())
                && highlightedRef.pos().equals(t.pos())) {
            highlightedRef = null;
            syncBox();
        }
    }

    // ---- scrollbar geometry ----

    private int thumbH(int n) {
        return Math.max(14, TRACK_H * VISIBLE / n);
    }

    private void setScrollFromMouse(double my, int n) {
        int maxScroll = n - VISIBLE;
        if (maxScroll <= 0) {
            scroll = 0;
            return;
        }
        int th = thumbH(n);
        double ratio = (my - (top + LIST_TOP) - th / 2.0) / (TRACK_H - th);
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        scroll = (int) Math.round(ratio * maxScroll);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // No blur: an extractBackground blur can crash with "Can only blur once per frame".
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (stack() == null) {
            // The remote left the hand (dropped, swapped...). Close next tick, never mid-render.
            Minecraft.getInstance().execute(this::onClose);
            return;
        }
        RemoteData d = data();
        int n = d.targets().size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, n - VISIBLE)));
        if (highlightedRef != null && highlightedIndex(d) < 0) {
            // The highlighted target vanished (deleted elsewhere / block broken).
            highlightedRef = null;
            syncBox();
        }
        int hi = highlightedIndex(d);

        graphics.fill(left, top, left + TOTAL_W, top + PANEL_H, 0xE0101010);
        graphics.outline(left, top, TOTAL_W, PANEL_H, 0xFF4FD1C5);
        graphics.fill(left + W + 2, top + 8, left + W + 3, top + PANEL_H - 8, 0xFF2A5A55);
        graphics.text(this.font, this.title, left + 8, top + 8, 0xFFFFFFFF);
        String count = n + "/" + RemoteData.MAX_TARGETS;
        graphics.text(this.font, count, left + W - 8 - this.font.width(count), top + 8, 0xFFAAAAAA);

        if (n == 0) {
            graphics.text(this.font, Component.translatable("gui.mediapanelhologram.remote.empty_1"),
                    left + 8, top + LIST_TOP + 8, 0xFFAAAAAA);
            graphics.text(this.font, Component.translatable("gui.mediapanelhologram.remote.empty_2"),
                    left + 8, top + LIST_TOP + 22, 0xFFAAAAAA);
        }

        var level = Minecraft.getInstance().level;
        for (int r = 0; r < VISIBLE; r++) {
            int i = scroll + r;
            if (i >= n) {
                break;
            }
            RemoteTarget t = d.targets().get(i);
            int y = top + LIST_TOP + r * ROW_H;
            if (i == hi) {
                graphics.fill(left + 4, y, left + W - 10, y + ROW_H - 1, 0x6033AA99);
            }
            // operate-target checkbox
            graphics.outline(left + 8, y + 3, 10, 10, 0xFFFFFFFF);
            if (t.selected()) {
                graphics.fill(left + 10, y + 5, left + 16, y + 11, 0xFF4FD1C5);
            }
            graphics.text(this.font, this.font.plainSubstrByWidth(t.name(), 110), left + 24, y + 4, 0xFFFFFFFF);
            boolean sameDim = level != null && level.dimension().equals(t.dimension());
            String pos = t.pos().toShortString();
            graphics.text(this.font, this.font.plainSubstrByWidth(pos, 84), left + 140, y + 4,
                    sameDim ? 0xFFAAAAAA : 0xFFAA5555);
            graphics.text(this.font, "X", left + W - 24, y + 4, 0xFFFF7777);
        }

        if (n > VISIBLE) {
            int trackTop = top + LIST_TOP;
            graphics.fill(left + BAR_DRAW_X, trackTop, left + BAR_DRAW_X + BAR_DRAW_W, trackTop + TRACK_H, 0xFF2A2A2A);
            int th = thumbH(n);
            int ty = trackTop + (int) Math.round((TRACK_H - th) * (double) scroll / (n - VISIBLE));
            graphics.fill(left + BAR_DRAW_X, ty, left + BAR_DRAW_X + BAR_DRAW_W, ty + th,
                    draggingBar ? 0xFFB8FFF0 : 0xFF4FD1C5);
        }

        int rs = repeatState();
        repeatButton.setMessage(rs == 2 ? Component.translatable("gui.mediapanelhologram.remote.repeat_mixed")
                : Component.translatable("gui.mediapanelhologram.media_block.repeat",
                        Component.translatable(rs == 1 ? "gui.mediapanelhologram.hologram.on" : "gui.mediapanelhologram.hologram.off")));
        var hs = highlightedSource();
        settingsButton.active = hs != null;
        browserButton.active = hs != null
                && (hs.getMode() == io.github.kiyo.mediapanelhologram.block.MediaMode.BROWSER
                || hs.getMode() == io.github.kiyo.mediapanelhologram.block.MediaMode.VIDEO);

        for (Renderable widget : ownWidgets) {
            widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() != 0) {
            return false;
        }
        double mx = event.x();
        double my = event.y();
        RemoteData d = data();
        int n = d.targets().size();
        boolean inListY = my >= top + LIST_TOP && my < top + LIST_TOP + TRACK_H;

        if (n > VISIBLE && inListY && mx >= left + BAR_ZONE_X && mx < left + W - 2) {
            draggingBar = true;
            setScrollFromMouse(my, n);
            return true;
        }
        if (inListY && mx >= left + 4 && mx < left + BAR_ZONE_X) {
            int i = scroll + (int) ((my - (top + LIST_TOP)) / ROW_H);
            if (i < n) {
                if (mx >= left + 8 && mx < left + 20) {
                    toggleSelected(i);
                } else if (mx >= left + W - 28 && mx < left + W - 12) {
                    remove(i);
                } else {
                    // Clicking the highlighted row again clears the highlight.
                    RemoteTarget clicked = d.targets().get(i);
                    boolean same = highlightedRef != null && highlightedRef.dimension().equals(clicked.dimension())
                            && highlightedRef.pos().equals(clicked.pos());
                    highlightedRef = same ? null : clicked;
                    syncBox();
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingBar) {
            setScrollFromMouse(event.y(), data().targets().size());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingBar) {
            draggingBar = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int n = data().targets().size();
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY), Math.max(0, n - VISIBLE)));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.getFocused() == nameBox
                && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            rename();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
