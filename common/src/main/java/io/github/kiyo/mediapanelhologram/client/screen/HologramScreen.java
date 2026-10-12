package io.github.kiyo.mediapanelhologram.client.screen;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.ToIntFunction;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import dev.architectury.networking.NetworkManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import io.github.kiyo.mediapanelhologram.block.HologramSettings;
import io.github.kiyo.mediapanelhologram.block.entity.MediaBlockEntity;
import io.github.kiyo.mediapanelhologram.network.HologramUpdatePayload;

/**
 * Hologram settings screen for a Media Block. Deliberately paints NO world
 * background (no blur) so the hologram stays visible while adjusting.
 * Dragging only updates the client-side block entity (live preview); the
 * server is told on slider release, on button presses, and when the screen
 * closes. Widgets are drawn explicitly (same reason as MediaBlockScreen).
 */
@Environment(EnvType.CLIENT)
public class HologramScreen extends Screen {
    private static final int PANEL_W = 198;
    private static final int LEFT = 6;
    private static final int TOP = 4;

    private final BlockPos pos;
    private final List<Renderable> ownWidgets = new ArrayList<>();
    private HologramSettings cur;
    private boolean dirty;
    private Button holoButton;
    private Button ratioButton;
    private Row heightRow;
    private final List<Row> rows = new ArrayList<>();
    private int panelH = 200;

    public HologramScreen(BlockPos pos) {
        super(Component.translatable("gui.mediapanelhologram.hologram.title"));
        this.pos = pos;
        MediaBlockEntity be = entity();
        this.cur = be != null ? be.getHologram() : HologramSettings.DEFAULT;
    }

    private MediaBlockEntity entity() {
        var level = Minecraft.getInstance().level;
        return level != null && level.getBlockEntity(pos) instanceof MediaBlockEntity be ? be : null;
    }

    private void preview() {
        MediaBlockEntity be = entity();
        if (be != null) {
            be.setHologram(cur);
        }
        dirty = true;
        updateCamera();
    }

    private void commit() {
        NetworkManager.sendToServer(new HologramUpdatePayload(pos, cur));
        dirty = false;
    }

    private <T extends Renderable & net.minecraft.client.gui.components.events.GuiEventListener
            & net.minecraft.client.gui.narration.NarratableEntry> T addOwnWidget(T widget) {
        T added = this.addRenderableWidget(widget);
        this.ownWidgets.add(added);
        return added;
    }

    private static Component onOff(boolean on) {
        return Component.translatable(on ? "gui.mediapanelhologram.hologram.on" : "gui.mediapanelhologram.hologram.off");
    }

    private Component holoLabel() {
        return Component.translatable("gui.mediapanelhologram.hologram.toggle", onOff(cur.holoOn()));
    }

    private Component ratioLabel() {
        return Component.translatable("gui.mediapanelhologram.hologram.ratio", Component.translatable(
                cur.autoRatio() ? "gui.mediapanelhologram.hologram.auto" : "gui.mediapanelhologram.hologram.manual"));
    }

    private Component markerLabel() {
        return Component.translatable("gui.mediapanelhologram.hologram.marker", Component.translatable(
                cur.marker() ? "gui.mediapanelhologram.hologram.on" : "gui.mediapanelhologram.hologram.off"));
    }

    @Override
    protected void init() {
        super.init();
        startCamera();
        this.ownWidgets.clear();
        this.rows.clear();
        int x = LEFT + 8;
        int y = TOP + 22;

        holoButton = addOwnWidget(Button.builder(holoLabel(), b -> {
            cur = cur.withHoloOn(!cur.holoOn());
            preview();
            commit();
            b.setMessage(holoLabel());
        }).bounds(x, y, 88, 20).build());
        ratioButton = addOwnWidget(Button.builder(ratioLabel(), b -> {
            cur = cur.withAutoRatio(!cur.autoRatio());
            heightRow.refreshActive();
            preview();
            commit();
            b.setMessage(ratioLabel());
        }).bounds(x + 92, y, 90, 20).build());
        y += 22;

        addOwnWidget(Button.builder(markerLabel(), b -> {
            cur = cur.withMarker(!cur.marker());
            commit();
            b.setMessage(markerLabel());
        }).bounds(x, y, PANEL_W - 16, 20).build());
        y += 22;

        addRow(x, y, "x", -HologramSettings.POS_MAX, HologramSettings.POS_MAX, 100, false, false,
                HologramSettings::x, HologramSettings::withX);
        y += 22;
        addRow(x, y, "y", -HologramSettings.POS_MAX, HologramSettings.POS_MAX, 100, false, false,
                HologramSettings::y, HologramSettings::withY);
        y += 22;
        addRow(x, y, "z", -HologramSettings.POS_MAX, HologramSettings.POS_MAX, 100, false, false,
                HologramSettings::z, HologramSettings::withZ);
        y += 22;
        addRow(x, y, "width", HologramSettings.SIZE_MIN, HologramSettings.SIZE_MAX, 100, false, false,
                HologramSettings::width, HologramSettings::withWidth);
        y += 22;
        heightRow = addRow(x, y, "height", HologramSettings.SIZE_MIN, HologramSettings.SIZE_MAX, 100, false, true,
                HologramSettings::height, HologramSettings::withHeight);
        y += 22;
        addRow(x, y, "pitch", -HologramSettings.PITCH_MAX, HologramSettings.PITCH_MAX, 500, false, false,
                HologramSettings::pitch, HologramSettings::withPitch);
        y += 22;
        addRow(x, y, "opacity", HologramSettings.OPACITY_MIN, HologramSettings.OPACITY_MAX, 1, true, false,
                HologramSettings::opacity, HologramSettings::withOpacity);
        y += 22;

        addOwnWidget(Button.builder(Component.translatable("gui.mediapanelhologram.hologram.reset"), b -> resetValues())
                .bounds(x, y, PANEL_W - 16, 20).build());
        y += 22;
        addOwnWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
                .bounds(x, y, PANEL_W - 16, 20).build());
        panelH = y + 20 + 6 - TOP;
        heightRow.refreshActive();
    }

    private Row addRow(int x, int y, String key, int min, int max, int step, boolean percent, boolean needsManual,
            ToIntFunction<HologramSettings> getter, BiFunction<HologramSettings, Integer, HologramSettings> setter) {
        Row row = new Row(x, y, 118, "gui.mediapanelhologram.hologram." + key, min, max, step, percent, needsManual,
                getter, setter);
        addOwnWidget(row);
        rows.add(row);
        EditBox box = new EditBox(this.font, x + 122, y, 60, 20, Component.empty());
        box.setMaxLength(12);
        row.box = box;
        row.syncBox();
        box.setResponder(row::fromText);
        addOwnWidget(box);
        return row;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(LEFT, TOP, LEFT + PANEL_W, TOP + panelH, 0xC0101010);
        graphics.outline(LEFT, TOP, PANEL_W, panelH, 0xFF4FD1C5);
        graphics.text(this.font, this.title, LEFT + 8, TOP + 8, 0xFFFFFF);
        for (Renderable widget : ownWidgets) {
            widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
    }

    /** Restores position/size/tilt/opacity/ratio mode; keeps hologram ON/OFF and screen ON/OFF. */
    private void resetValues() {
        HologramSettings d = HologramSettings.DEFAULT;
        cur = new HologramSettings(cur.screenOn(), cur.holoOn(), d.x(), d.y(), d.z(), d.width(), d.height(),
                d.pitch(), d.opacity(), d.autoRatio(), cur.marker());
        for (Row r : rows) {
            r.reload();
        }
        ratioButton.setMessage(ratioLabel());
        heightRow.refreshActive();
        orbitYaw = initialYaw;
        orbitPitch = initialPitch;
        orbitDist = initialDist;
        preview();
        commit();
    }

    // The engine calls extractBackground before extractRenderState; the default draws the blur.
    // Leave it empty so the hologram stays visible while adjusting.
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    }

    // Keep the camera on the hologram centre while this screen is open. Only the view turns;
    // the player's position is untouched. Stays at the last direction after closing.
    @Override
    public void tick() {
        super.tick();
        updateCamera();
    }

    // ---- Orbit camera: a client-only dummy Marker (never added to the level) becomes the
    // camera entity, so the view leaves the body and follows the hologram centre.
    private net.minecraft.world.entity.Marker camDummy;
    private float orbitYaw;
    private float orbitPitch;
    private double orbitDist = 4.0;
    // View state right after the screen opened; Reset values restores it.
    private float initialYaw;
    private float initialPitch;
    private double initialDist = 4.0;

    private void startCamera() {
        if (camDummy != null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        camDummy = new net.minecraft.world.entity.Marker(net.minecraft.world.entity.EntityTypes.MARKER, mc.level);
        orbitYaw = mc.player.getYRot();
        orbitPitch = mc.player.getXRot();
        orbitDist = Math.max(2.0, Math.min(20.0, cur.width() / 1000.0 * 1.5));
        initialYaw = orbitYaw;
        initialPitch = orbitPitch;
        initialDist = orbitDist;
        updateCamera();
        mc.setCameraEntity(camDummy);
    }

    private void stopCamera() {
        Minecraft mc = Minecraft.getInstance();
        if (camDummy != null && mc.player != null) {
            mc.setCameraEntity(mc.player);
        }
        camDummy = null;
    }

    private void updateCamera() {
        if (camDummy == null) {
            return;
        }
        double cx = pos.getX() + 0.5 + cur.x() / 1000.0;
        double cy = pos.getY() + 0.5 + cur.y() / 1000.0;
        double cz = pos.getZ() + 0.5 + cur.z() / 1000.0;
        double yaw = Math.toRadians(orbitYaw);
        double pit = Math.toRadians(orbitPitch);
        double dx = -Math.sin(yaw) * Math.cos(pit);
        double dy = -Math.sin(pit);
        double dz = Math.cos(yaw) * Math.cos(pit);
        camDummy.snapTo(cx - dx * orbitDist, cy - dy * orbitDist, cz - dz * orbitDist, orbitYaw, orbitPitch);
        camDummy.setOldPosAndRot();
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (super.mouseDragged(event, dx, dy)) {
            return true;
        }
        orbitYaw += (float) (dx * 0.4);
        orbitPitch = Math.max(-85f, Math.min(85f, orbitPitch + (float) (dy * 0.4)));
        updateCamera();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        orbitDist = Math.max(1.0, Math.min(512.0, orbitDist * Math.pow(0.9, scrollY)));
        updateCamera();
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        super.removed();
        stopCamera();
        if (dirty) {
            commit();
        }
    }

    /** Slider (0.1 steps) paired with a numeric box (0.001 steps). Values are the x1000 ints of HologramSettings. */
    private final class Row extends AbstractSliderButton {
        private final String key;
        private final int min;
        private final int max;
        private final int step;
        private final double power;
        private final boolean percent;
        private final boolean needsManual;
        private final ToIntFunction<HologramSettings> getter;
        private final BiFunction<HologramSettings, Integer, HologramSettings> setter;
        EditBox box;
        private boolean updating;

        Row(int x, int y, int w, String key, int min, int max, int step, boolean percent, boolean needsManual,
                ToIntFunction<HologramSettings> getter, BiFunction<HologramSettings, Integer, HologramSettings> setter) {
            super(x, y, w, 20, Component.empty(), 0.0);
            this.key = key;
            this.min = min;
            this.max = max;
            this.step = step;
            this.power = curveFor(key);
            this.percent = percent;
            this.needsManual = needsManual;
            this.getter = getter;
            this.setter = setter;
            this.value = norm(getter.applyAsInt(cur));
            updateMessage();
        }

        private double norm(int v) {
            return toSlider(v);
        }

        private static double curveFor(String fullKey) {
            String k = fullKey.substring(fullKey.lastIndexOf('.') + 1);
            return switch (k) {
                case "x", "y", "z", "width", "height" -> 3.0;
                default -> 1.0;
            };
        }

        private static double clamp01(double d) {
            return Math.max(0.0, Math.min(1.0, d));
        }

        private double toSlider(int v) {
            if (power == 1.0) {
                return clamp01((v - min) / (double) (max - min));
            }
            if (min < 0) {
                double u = Math.signum((double) v) * Math.pow(Math.abs(v) / (double) max, 1.0 / power);
                return clamp01((u + 1.0) / 2.0);
            }
            return clamp01(Math.pow(clamp01((v - min) / (double) (max - min)), 1.0 / power));
        }

        private int fromSlider(double t) {
            if (power == 1.0) {
                return (int) Math.round(min + t * (max - min));
            }
            if (min < 0) {
                double u = t * 2.0 - 1.0;
                return (int) Math.round(Math.signum(u) * Math.pow(Math.abs(u), power) * max);
            }
            return (int) Math.round(min + Math.pow(t, power) * (max - min));
        }

        private String fmt(int v) {
            return percent ? Integer.toString(v) : BigDecimal.valueOf(v, 3).stripTrailingZeros().toPlainString();
        }

        void syncBox() {
            if (box != null) {
                updating = true;
                box.setValue(fmt(getter.applyAsInt(cur)));
                updating = false;
            }
        }

        void reload() {
            this.value = norm(getter.applyAsInt(cur));
            updateMessage();
            syncBox();
        }

        void refreshActive() {
            boolean on = !(needsManual && cur.autoRatio());
            this.active = on;
            if (box != null) {
                box.active = on;
            }
        }

        void fromText(String s) {
            if (updating || (needsManual && cur.autoRatio())) {
                return;
            }
            int v;
            try {
                v = percent ? Integer.parseInt(s.trim()) : (int) Math.round(Double.parseDouble(s.trim()) * 1000.0);
            } catch (NumberFormatException e) {
                return;
            }
            v = Math.max(min, Math.min(max, v));
            cur = setter.apply(cur, v);
            this.value = norm(v);
            updateMessage();
            preview();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(key).append(Component.literal(": " + fmt(getter.applyAsInt(cur)))));
        }

        @Override
        protected void applyValue() {
            int raw = fromSlider(this.value);
            int v = (int) Math.round(raw / (double) step) * step;
            v = Math.max(min, Math.min(max, v));
            cur = setter.apply(cur, v);
            preview();
            syncBox();
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            super.onRelease(event);
            commit();
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            boolean handled = super.keyPressed(event);
            if (handled) {
                commit();
            }
            return handled;
        }
    }
}
