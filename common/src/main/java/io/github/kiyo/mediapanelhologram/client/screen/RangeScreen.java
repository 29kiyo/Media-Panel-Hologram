package io.github.kiyo.mediapanelhologram.client.screen;

import java.util.ArrayList;
import java.util.List;

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

import io.github.kiyo.mediapanelhologram.block.MediaRange;
import io.github.kiyo.mediapanelhologram.block.entity.MediaSource;
import io.github.kiyo.mediapanelhologram.network.RangeUpdatePayload;

/**
 * Display range screen (slider + numeric box, 4..256 blocks). Dragging only updates the
 * client-side block entity; the server is told on slider release, on Enter-less typing when
 * the screen closes, and on Done. Widgets are drawn explicitly (same reason as MediaBlockScreen).
 */
@Environment(EnvType.CLIENT)
public class RangeScreen extends Screen {
    private static final int PANEL_W = 198;
    private static final int PANEL_H = 98;

    private final BlockPos pos;
    private final List<Renderable> ownWidgets = new ArrayList<>();
    private int range;
    private boolean dirty;
    private boolean updating;
    private int left;
    private int top;
    private RangeSlider slider;
    private EditBox box;

    public RangeScreen(BlockPos pos) {
        super(Component.translatable("gui.mediapanelhologram.range.title"));
        this.pos = pos;
        MediaSource src = source();
        this.range = src != null ? MediaRange.clamp(src.getRange()) : MediaRange.DEFAULT;
    }

    private MediaSource source() {
        var level = Minecraft.getInstance().level;
        return level != null && level.getBlockEntity(pos) instanceof MediaSource s ? s : null;
    }

    private void preview() {
        MediaSource src = source();
        if (src != null) {
            src.setRange(range);
        }
        dirty = true;
    }

    private void commit() {
        NetworkManager.sendToServer(new RangeUpdatePayload(pos, range));
        dirty = false;
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
        left = (this.width - PANEL_W) / 2;
        top = (this.height - PANEL_H) / 2;
        int x = left + 8;
        int y = top + 24;

        slider = addOwnWidget(new RangeSlider(x, y, 118));
        box = new EditBox(this.font, x + 122, y, 52, 20, Component.empty());
        box.setMaxLength(3);
        box.setValue(Integer.toString(range));
        box.setResponder(this::fromText);
        addOwnWidget(box);

        addOwnWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
                .bounds(x, top + 70, PANEL_W - 16, 20).build());
    }

    private void fromText(String s) {
        if (updating) {
            return;
        }
        int v;
        try {
            v = Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return;
        }
        range = MediaRange.clamp(v);
        slider.reload();
        preview();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(left, top, left + PANEL_W, top + PANEL_H, 0xC0101010);
        graphics.outline(left, top, PANEL_W, PANEL_H, 0xFF4FD1C5);
        graphics.text(this.font, this.title, left + 8, top + 8, 0xFFFFFF);
        graphics.text(this.font, Component.translatable("gui.mediapanelhologram.range.hint"),
                left + 8, top + 50, 0xAAAAAA);
        for (Renderable widget : ownWidgets) {
            widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
    }

    // No blur: the engine calls extractBackground before extractRenderState (see HologramScreen).
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        super.removed();
        if (dirty) {
            commit();
        }
    }

    private final class RangeSlider extends AbstractSliderButton {
        RangeSlider(int x, int y, int w) {
            super(x, y, w, 20, Component.empty(), 0.0);
            this.value = toSlider();
            updateMessage();
        }

        private double toSlider() {
            return (range - MediaRange.MIN) / (double) (MediaRange.MAX - MediaRange.MIN);
        }

        void reload() {
            this.value = toSlider();
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("gui.mediapanelhologram.range.range")
                    .append(Component.literal(": " + range)));
        }

        @Override
        protected void applyValue() {
            range = MediaRange.clamp((int) Math.round(MediaRange.MIN + this.value * (MediaRange.MAX - MediaRange.MIN)));
            updating = true;
            box.setValue(Integer.toString(range));
            updating = false;
            preview();
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
