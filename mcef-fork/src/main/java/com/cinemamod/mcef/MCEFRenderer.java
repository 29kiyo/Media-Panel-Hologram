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

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;
import java.util.UUID;

import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL12.*;

public class MCEFRenderer {
    private final boolean transparent;
    // Owned by this renderer: closed on resize and in cleanup().
    private GpuTexture texture;
    private int textureWidth = 0;
    private int textureHeight = 0;
    /**
     * ACTUAL-RUN BUG FIX (not upstream): true only once a fence queued via
     * RenderSystem.queueFencedTask, at the moment a texture/sampler is
     * (re)created, has actually completed -- i.e. the GPU has confirmed the
     * new resources are usable, not just that the CPU-side calls that
     * created them returned. Without this, isTextureReady() could report
     * true (texture/directTexture/textureRegistered all non-null) for a
     * brand new sampler the very same frame it was created, before the GPU
     * had actually finished setting it up -- observed as a recurring, actual
     * "GL_INVALID_OPERATION ... Not a valid sampler name" error every time a
     * new browser session's texture first appeared (switching to browser
     * mode, replacing a block, etc.), regardless of which render type or
     * thread-dispatch fix was tried around it. volatile: read from the
     * render thread (isTextureReady) and (per queueFencedTask's own
     * contract) the fence callback may run on a different thread.
     */
    private volatile boolean textureConfirmedReady = false;

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
        return texture != null && textureRegistered && directTexture != null && textureConfirmedReady;
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
        if (packedBuf != null) {
            MemoryUtil.memFree(packedBuf);
            packedBuf = null;
        }
    }

    // Dev-only (MPH_PERF=1): upload cost on the render thread (onPaint is dispatched there).
    private static final boolean PERF = "1".equals(System.getenv("MPH_PERF"));
    private static long perfFullCalls, perfFullNanos, perfPartCalls, perfPartNanos, perfBytes, perfLastLog;

    private static void perfRecord(boolean full, long nanos, long bytes) {
        if (full) {
            perfFullCalls++;
            perfFullNanos += nanos;
        } else {
            perfPartCalls++;
            perfPartNanos += nanos;
        }
        perfBytes += bytes;
        long now = System.nanoTime();
        if (perfLastLog == 0) {
            perfLastLog = now;
        }
        if (now - perfLastLog >= 1_000_000_000L) {
            org.slf4j.LoggerFactory.getLogger("MPH-PERF").info(
                    "paint full={} calls/{} us, partial={} calls/{} us, uploadedMB={}",
                    perfFullCalls, perfFullNanos / 1000, perfPartCalls, perfPartNanos / 1000, perfBytes >> 20);
            perfFullCalls = perfFullNanos = perfPartCalls = perfPartNanos = perfBytes = 0;
            perfLastLog = now;
        }
    }

    protected void onPaint(ByteBuffer buffer, int width, int height) {
        if (!PERF) {
            onPaintFull0(buffer, width, height);
            return;
        }
        long t0 = System.nanoTime();
        try {
            onPaintFull0(buffer, width, height);
        } finally {
            perfRecord(true, System.nanoTime() - t0, (long) width * height * 4);
        }
    }

    protected void onPaint(ByteBuffer buffer, int x, int y, int width, int height) {
        if (!PERF) {
            onPaintPart0(buffer, x, y, width, height);
            return;
        }
        long t0 = System.nanoTime();
        try {
            onPaintPart0(buffer, x, y, width, height);
        } finally {
            perfRecord(false, System.nanoTime() - t0, (long) width * height * 4);
        }
    }

    private void onPaintFull0(ByteBuffer buffer, int width, int height) {
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

            // Not ready until the fence below actually fires -- see the
            // textureConfirmedReady field's Javadoc.
            textureConfirmedReady = false;

            // Point the wrapper at the new texture (closes its old view) before closing the old texture.
            if (directTexture != null) {
                directTexture.setTexture(texture);
            }
            if (old != null) {
                old.close();
            }
            com.mojang.blaze3d.systems.RenderSystem.queueFencedTask(() -> textureConfirmedReady = true);
        }

        if (texture instanceof GlTexture glTexture) {
            GlStateManager._bindTexture(glTexture.glId());
            GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, width);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);

            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                    GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        } else if (texture != null) {
            // Non-OpenGL backend (Vulkan): see onPaintRegion.
            onPaintRegion(buffer, width, 0, 0, 0, 0, width, height);
        }
    }

    /** True when the browser texture is an OpenGL texture (the original upload path applies). */
    public boolean usesGl() {
        return texture instanceof GlTexture;
    }

    // Reused staging buffer for the non-GL path (RGBA, tightly packed). Freed in cleanup().
    private ByteBuffer packedBuf;

    /**
     * Non-OpenGL (Vulkan) upload. CommandEncoder#writeToTexture has no row-stride/skip
     * parameters and expects RGBA byte order, while CEF delivers BGRA with the full-frame
     * row length. So the region is copied into a tightly packed buffer with R/B swapped.
     * src must be a direct buffer whose rows are srcWidth pixels wide.
     */
    public void onPaintRegion(ByteBuffer src, int srcWidth, int srcX, int srcY,
                              int dstX, int dstY, int w, int h) {
        if (texture == null || src == null) {
            return;
        }
        // Clamp to the texture bounds.
        if (dstX < 0 || dstY < 0 || dstX + w > textureWidth || dstY + h > textureHeight || w <= 0 || h <= 0) {
            int cx = Math.max(dstX, 0), cy = Math.max(dstY, 0);
            int cw = Math.min(dstX + w, textureWidth) - cx;
            int ch = Math.min(dstY + h, textureHeight) - cy;
            srcX += cx - dstX;
            srcY += cy - dstY;
            dstX = cx;
            dstY = cy;
            w = cw;
            h = ch;
            if (w <= 0 || h <= 0) {
                return;
            }
        }
        int bytes = w * h * 4;
        if (packedBuf == null || packedBuf.capacity() < bytes) {
            if (packedBuf != null) {
                MemoryUtil.memFree(packedBuf);
            }
            packedBuf = MemoryUtil.memAlloc(bytes);
        }
        long srcBase = MemoryUtil.memAddress(src);
        long dst = MemoryUtil.memAddress0(packedBuf);
        for (int row = 0; row < h; row++) {
            long s = srcBase + ((long) (srcY + row) * srcWidth + srcX) * 4L;
            for (int col = 0; col < w; col++) {
                int v = MemoryUtil.memGetInt(s);
                // memory bytes B,G,R,A -> R,G,B,A (little-endian ints)
                v = (v & 0xFF00FF00) | ((v & 0xFF) << 16) | ((v >>> 16) & 0xFF);
                MemoryUtil.memPutInt(dst, v);
                s += 4;
                dst += 4;
            }
        }
        packedBuf.position(0);
        packedBuf.limit(bytes);
        RenderSystem.getDevice().createCommandEncoder()
                .writeToTexture(texture, packedBuf, 0, 0, dstX, dstY, w, h);
    }

    private void onPaintPart0(ByteBuffer buffer, int x, int y, int width, int height) {
        if (texture instanceof GlTexture glTexture) {
            GlStateManager._bindTexture(glTexture.glId());
            glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height, GL_BGRA,
                    GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        }
    }
}
