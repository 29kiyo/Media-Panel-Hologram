/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 */

package com.cinemamod.mcef.internal;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

public class MCEFDownloaderMenu extends Screen {
    private final Screen menu;

    public MCEFDownloaderMenu(Screen menu) {
        super(Component.literal("MCEF is downloading required libraries..."));
        this.menu = menu;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        extractBackground(graphics, mouseX, mouseY, partialTick);
        double cx = width / 2d;
        double cy = height / 2d;

        double progressBarHeight = 14;
        double progressBarWidth = width / 3d; // TODO: base off screen with (1/3 of screen)

        org.joml.Matrix3x2fStack pose = graphics.pose();

        /* Draw Progress Bar */
        pose.pushMatrix();
        pose.translate((float) (cx - progressBarWidth / 2d), (float) (cy - progressBarHeight / 2d));
        graphics.fill( // bar border
                0, 0,
                (int) progressBarWidth,
                (int) progressBarHeight,
                -1
        );
        graphics.fill( // bar padding
                2, 2,
                (int) progressBarWidth - 2,
                (int) progressBarHeight - 2,
                -16777215
        );
        graphics.fill( // bar bar
                4, 4,
                (int) ((progressBarWidth - 4) * MCEFDownloadListener.INSTANCE.getProgress()),
                (int) progressBarHeight - 4,
                -1
        );
        pose.popMatrix();

        String[] text = new String[]{
                MCEFDownloadListener.INSTANCE.getTask(),
                Math.round(MCEFDownloadListener.INSTANCE.getProgress() * 100) + "%",
        };

        /* Draw Text (absolute coordinates; same layout as upstream) */
        int oSet = ((font.lineHeight / 2) + ((font.lineHeight + 2) * (text.length + 2))) + 4;
        int baseX = (int) cx;
        int baseY = (int) (cy - oSet);

        // draw menu name
        graphics.text(
                font,
                ChatFormatting.GOLD + title.getString(),
                baseX + (int) -(font.width(title.getString()) / 2d), baseY,
                0xFFFFFFFF
        );
        // draw text
        int y = 0;
        int index = 0;
        for (String s : text) {
            if (index == 1) {
                y += font.lineHeight + 2;
            }
            y += font.lineHeight + 2;
            graphics.text(
                    font,
                    s,
                    baseX + (int) -(font.width(s) / 2d), baseY + y,
                    0xFFFFFFFF
            );
            index++;
        }

        // TODO: if listener.isFailed(), draw some "Failed to initialize MCEF" text with an "OK" button to proceed
    }

    @Override
    public void tick() {
        if (MCEFDownloadListener.INSTANCE.isDone() || MCEFDownloadListener.INSTANCE.isFailed()) {
            onClose();
            Minecraft.getInstance().setScreenAndShow(menu);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
