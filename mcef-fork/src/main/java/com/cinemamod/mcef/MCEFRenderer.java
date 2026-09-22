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

package com.cinemamod.mcef;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.lwjgl.opengl.GL12.*;

public class MCEFRenderer {
    private final boolean transparent;
    // Owned by this renderer: closed on resize and in cleanup().
    private GpuTexture texture;
    private int textureWidth = 0;
    private int textureHeight = 0;

    // Identifier under which the wrapper texture is registered with the TextureManager
    private Identifier textureLocation;
    private MCEFDirectTexture directTexture;
    private boolean textureRegistered = false;

    protected MCEFRenderer(boolean transparent) {
        this.transparent = transparent;
        String uniqueId = UUID.randomUUID().toString().toLowerCase().replace("-", "");
        this.textureLocation = Identifier.fromNamespaceAndPath("mcef", "browser_" + uniqueId);
    }

    public void initialize() {
        directTexture = new MCEFDirectTexture();
        Minecraft.getInstance().getTextureManager().register(textureLocation, directTexture);
        textureRegistered = true;
    }

    public GpuTexture getTexture() {
        return texture;
    }

    /**
     * Identifier registered with the TextureManager that points to the browser's texture.
     */
    public Identifier getTextureLocation() {
        return textureLocation;
    }

    public boolean isTextureReady() {
        return texture != null && textureRegistered && directTexture != null;
    }

    public int getTextureID() {
        if (texture instanceof GlTexture) {
            return ((GlTexture) texture).glId();
        }
        return 0;
    }

    public int getTextureWidth() {
        return textureWidth;
    }

    public int getTextureHeight() {
        return textureHeight;
    }

    public boolean isTransparent() {
        return transparent;
    }

    protected void cleanup() {
        // Release view (and forget the texture) in the wrapper first, then the texture we own.
        if (directTexture != null) {
            directTexture.close();
        }
        if (textureRegistered && textureLocation != null) {
            Minecraft.getInstance().getTextureManager().release(textureLocation);
            textureRegistered = false;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
    }

    protected void onPaint(ByteBuffer buffer, int width, int height) {
        if (texture == null || textureWidth != width || textureHeight != height) {
            GpuTexture old = texture;

            texture = RenderSystem.getDevice().createTexture(
                    "MCEF Browser Texture " + width + "x" + height,
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA8_UNORM,
                    width,
                    height,
                    1, // depthOrLayers
                    1  // mipLevels
            );
            textureWidth = width;
            textureHeight = height;

            // Point the wrapper at the new texture (closes its old view) before closing the old texture.
            if (directTexture != null) {
                directTexture.setTexture(texture);
            }
            if (old != null) {
                old.close();
            }
        }

        if (texture instanceof GlTexture glTexture) {
            GlStateManager._bindTexture(glTexture.glId());
            GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, width);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);

            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                    GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        }
    }

    protected void onPaint(ByteBuffer buffer, int x, int y, int width, int height) {
        if (texture instanceof GlTexture glTexture) {
            GlStateManager._bindTexture(glTexture.glId());
            glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height, GL_BGRA,
                    GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        }
    }
}
