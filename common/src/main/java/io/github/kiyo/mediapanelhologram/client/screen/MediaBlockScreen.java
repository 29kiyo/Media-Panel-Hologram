package io.github.kiyo.mediapanelhologram.client.screen;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import dev.architectury.networking.NetworkManager;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import io.github.kiyo.mediapanelhologram.block.MediaMode;
import io.github.kiyo.mediapanelhologram.block.entity.MediaBlockEntity;
import io.github.kiyo.mediapanelhologram.block.menu.MediaBlockMenu;
import io.github.kiyo.mediapanelhologram.network.MediaBlockUpdatePayload;

/**
 * URL registration + mode switching GUI (Phase4 tasks). Opened via
 * Shift+right-click on a Media Block (see MediaBlock#useItemOn).
 *
 * RENDERING PIPELINE NOTE (MC 26.2, Submit-based): confirmed by an actual
 * run that overriding extractRenderState(GuiGraphicsExtractor, int, int,
 * float) WITHOUT calling super does NOT auto-draw the widgets registered via
 * addRenderableWidget -- the GUI showed only the manually-drawn
 * fill/outline/title, with the EditBox and Buttons completely invisible.
 * The previous revision's assumption (that some other final wrapper method
 * draws `renderables` independently of this override) was WRONG.
 *
 * Fix: track our own widgets in `ownWidgets` as they're added, and loop over
 * them explicitly at the end of extractRenderState, calling each one's own
 * extractRenderState (Renderable's sole method, confirmed via javap) after
 * the backdrop is painted so z-order is backdrop-then-widgets. super's
 * extractRenderState is deliberately NOT called: AbstractContainerScreen's
 * version draws a slot-inventory background texture sized to
 * DEFAULT_IMAGE_WIDTH/HEIGHT that doesn't match this GUI (no slots, no
 * matching texture asset was made for this screen in Phase2), so this class
 * owns 100% of its own drawing instead.
 */
@Environment(EnvType.CLIENT)
public class MediaBlockScreen extends AbstractContainerScreen<MediaBlockMenu> {
    private static final int WIDTH = 200;
    private static final int HEIGHT = 164;

    private final List<Renderable> ownWidgets = new ArrayList<>();

    private EditBox urlBox;
    private MediaMode selectedMode;
    private io.github.kiyo.mediapanelhologram.block.QualityPreset selectedQuality;
    private Button qualityButton;

    public MediaBlockScreen(MediaBlockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        MediaBlockEntity mediaBe = menu.getBlockEntity();
        this.selectedMode = mediaBe != null ? mediaBe.getMode() : MediaMode.IMAGE;
        this.selectedQuality = mediaBe != null ? mediaBe.getQuality() : io.github.kiyo.mediapanelhologram.block.QualityPreset.DEFAULT;
    }

    /** addRenderableWidget still handles click routing/focus/narration; this
     * additionally remembers the widget so extractRenderState can draw it
     * explicitly (see class Javadoc for why that's necessary here). */
    private <T extends Renderable & net.minecraft.client.gui.components.events.GuiEventListener
            & net.minecraft.client.gui.narration.NarratableEntry> T addOwnWidget(T widget) {
        T added = this.addRenderableWidget(widget);
        this.ownWidgets.add(added);
        return added;
    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = (this.width - this.imageWidth) / 2;
        this.topPos = (this.height - this.imageHeight) / 2;
        this.ownWidgets.clear();

        MediaBlockEntity mediaBe = this.menu.getBlockEntity();
        String initialUrl = mediaBe != null ? mediaBe.getUrl() : "";

        this.urlBox = addOwnWidget(new EditBox(this.font,
                leftPos + 10, topPos + 24, imageWidth - 20, 18,
                Component.translatable("gui.mediapanelhologram.media_block.url")));
        this.urlBox.setMaxLength(256);
        this.urlBox.setValue(initialUrl);

        MediaMode[] modes = MediaMode.values();
        int gap = 6;
        int buttonWidth = (imageWidth - 20 - (modes.length - 1) * gap) / modes.length;
        int buttonY = topPos + 50;
        for (int i = 0; i < modes.length; i++) {
            MediaMode mode = modes[i];
            addOwnWidget(Button.builder(Component.translatable(mode.translationKey()), b -> {
                        this.selectedMode = mode;
                        sendUpdate();
                    })
                    .bounds(leftPos + 10 + i * (buttonWidth + gap), buttonY, buttonWidth, 20)
                    .build());
        }

        addOwnWidget(Button.builder(
                        Component.translatable("gui.mediapanelhologram.media_block.set_url"), b -> sendUpdate())
                .bounds(leftPos + 10, topPos + 80, imageWidth - 20, 20)
                .build());

        this.qualityButton = addOwnWidget(Button.builder(qualityLabel(), b -> {
                    this.selectedQuality = this.selectedQuality.next();
                    this.qualityButton.setMessage(qualityLabel());
                    sendUpdate();
                })
                .bounds(leftPos + 10, topPos + 104, imageWidth - 20, 20)
                .build());

        addOwnWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
                .bounds(leftPos + 10, topPos + imageHeight - 30, imageWidth - 20, 20)
                .build());
    }

    private Component qualityLabel() {
        return Component.translatable("gui.mediapanelhologram.media_block.quality", this.selectedQuality.label());
    }

    private void sendUpdate() {
        NetworkManager.sendToServer(new MediaBlockUpdatePayload(this.menu.getBlockPos(), this.urlBox.getValue(), this.selectedMode, this.selectedQuality));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        this.extractBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xC0101010);
        graphics.outline(leftPos, topPos, imageWidth, imageHeight, 0xFF4FD1C5);
        graphics.text(this.font, this.title, leftPos + 10, topPos + 8, 0xFFFFFF);

        for (Renderable widget : ownWidgets) {
            widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // No player-inventory slots in this menu; title is drawn in
        // extractRenderState above instead of the default label position.
    }
}
