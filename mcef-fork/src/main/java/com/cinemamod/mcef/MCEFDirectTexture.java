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

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;

import java.util.OptionalDouble;

/**
 * Wraps a GpuTexture owned by {@link MCEFRenderer} so it can be registered with Minecraft's TextureManager
 * and drawn like any other texture. This class owns only the texture view; the GpuTexture itself is
 * owned (and closed) by the renderer.
 */
public class MCEFDirectTexture extends AbstractTexture {
    private int width;
    private int height;

    public MCEFDirectTexture() {
    }

    /**
     * Points this wrapper at a texture owned by someone else. The previous view (if any) is closed.
     * The previous texture must be closed by its owner AFTER this call.
     */
    public void setTexture(GpuTexture newTexture) {
        closeView();
        if (newTexture == null) {
            this.texture = null;
            this.width = 0;
            this.height = 0;
            return;
        }
        var device = RenderSystem.getDevice();
        this.texture = newTexture;
        this.textureView = device.createTextureView(newTexture);
        if (this.sampler == null) {
            this.sampler = device.createSampler(
                    AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR, FilterMode.LINEAR,
                    1, OptionalDouble.empty());
        }
        this.width = newTexture.getWidth(0);
        this.height = newTexture.getHeight(0);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    private void closeView() {
        if (this.textureView != null) {
            this.textureView.close();
            this.textureView = null;
        }
    }

    private void closeSampler() {
        // createSampler() returns a NEW sampler every call, so we own it.
        if (this.sampler != null) {
            this.sampler.close();
            this.sampler = null;
        }
    }

    /**
     * Called by close(); safe to call more than once. Closes the view and the sampler created here.
     * Never closes the texture itself (owned by MCEFRenderer).
     */
    @Override
    protected void releaseTextures() {
        closeView();
        closeSampler();
        this.texture = null;
    }
}
