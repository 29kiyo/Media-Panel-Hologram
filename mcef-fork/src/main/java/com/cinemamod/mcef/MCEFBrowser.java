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

import com.cinemamod.mcef.listeners.MCEFCursorChangeListener;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.callback.CefDragData;
import org.cef.event.CefKeyEvent;
import org.cef.event.CefMouseEvent;
import org.cef.event.CefMouseWheelEvent;
import org.cef.misc.CefCursorType;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCString;

import java.awt.*;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * An instance of an "Off-screen rendered" Chromium web browser.
 * Complete with a renderer, keyboard and mouse inputs, optional
 * browser control shortcuts, cursor handling, drag & drop support.
 */
public class MCEFBrowser extends CefBrowserOsr {
    /**
     * The renderer for the browser.
     */
    private final MCEFRenderer renderer;
    /**
     * Stores information about drag & drop.
     */
    private final MCEFDragContext dragContext = new MCEFDragContext();
    /**
     * A listener that defines that happens when a cursor changes in the browser.
     * E.g. when you've hovered over a button, an input box, are selecting text, etc...
     * A default listener is created in the constructor that sets the cursor type to
     * the appropriate cursor based on the event.
     */
    private MCEFCursorChangeListener cursorChangeListener;
    /**
     * Whether MCEF should mimic the controls of a typical web browser.
     * E.g. CTRL+R for reload, CTRL+Left for back, CTRL+Right for forward, etc...
     */
    private boolean browserControls = true;
    /**
     * Used to track when a full repaint should occur.
     */
    private int lastWidth = 0, lastHeight = 0;
    /**
     * A bitset representing what mouse buttons are currently pressed.
     * CEF is a bit odd and implements mouse buttons as a part of modifier flags.
     */
    private int btnMask = 0;
    /**
     * ACTUAL-RUN BUG FIX (not upstream): set true at the very start of
     * close(). CEF's own render thread can still have an onPaint (or a
     * cursor-change -> setCursor) call in flight -- or already queued via
     * Minecraft#execute, per the main-thread-dispatch fix elsewhere in this
     * class -- at the moment close() runs. Without this flag, such a call
     * can run AFTER renderer.cleanup() has already closed the GPU
     * texture/sampler, touching already-closed GL objects. Observed as an
     * actual "GL_INVALID_OPERATION ... Not a valid sampler name" error
     * specifically right after breaking a MediaBlock. volatile since it's
     * read from CEF's thread and written from the main thread.
     */
    private volatile boolean closed = false;

    // Data relating to popups and graphics
    // Marked as protected in-case a mod wants to extend MCEFBrowser and override the repaint logic
    protected ByteBuffer popupGraphics;
    protected Rectangle popupSize;
    protected boolean showPopup = false;
    protected boolean popupDrawn = false;

    public MCEFBrowser(MCEFClient client, String url, boolean transparent) {
        super(client.getHandle(), url, transparent, null);
        renderer = new MCEFRenderer(transparent);
        cursorChangeListener = (cefCursorID) -> setCursor(CefCursorType.fromId(cefCursorID));

        Minecraft.getInstance().submit(renderer::initialize);
    }

    public MCEFRenderer getRenderer() {
        return renderer;
    }
    
    /**
     * Convenience method to get the Identifier for this browser's texture.
     * This can be used directly with GuiGraphics rendering methods.
     * 
     * @return The Identifier for this browser's texture, or null if not initialized
     */
    public Identifier getTextureLocation() {
        return renderer != null ? renderer.getTextureLocation() : null;
    }
    
    /**
     * Check if the browser's texture is ready for rendering.
     * 
     * @return true if the texture is initialized and ready to be rendered
     */
    public boolean isTextureReady() {
        return renderer != null && renderer.isTextureReady();
    }

    public MCEFCursorChangeListener getCursorChangeListener() {
        return cursorChangeListener;
    }

    public void setCursorChangeListener(MCEFCursorChangeListener cursorChangeListener) {
        this.cursorChangeListener = cursorChangeListener;
    }

    public boolean usingBrowserControls() {
        return browserControls;
    }

    /**
     * Enabling browser controls tells MCEF to mimic the behavior of an actual browser.
     * CTRL+R for reload, CTRL+Left for back, CTRL+Right for forward, etc...
     *
     * @param browserControls whether browser controls should be enabled
     * @return the browser instance
     */
    public MCEFBrowser useBrowserControls(boolean browserControls) {
        this.browserControls = browserControls;
        return this;
    }

    public MCEFDragContext getDragContext() {
        return dragContext;
    }

    // Popups
    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
        super.onPopupShow(browser, show);
        showPopup = show;
        if (!show) popupDrawn = false;
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
        super.onPopupSize(browser, size);
        popupSize = size;
        this.popupGraphics = ByteBuffer.allocateDirect(
                size.width * size.height * 4
        );
    }

    // Graphics
    // Dev-only (MPH_PERF=1): count CEF onPaint arrivals BEFORE any early return / thread hop.
    private static final boolean PERF_ENTRY = "1".equals(System.getenv("MPH_PERF"));
    private static long perfEntryCalls, perfEntryRects, perfEntryPixels, perfEntryLast;
    // Dev-only (MPH_PERF=1): which branch of onPaint the arrivals take.
    private static final long[] PB = new long[3];
    private static long perfBranchLast;
    private static synchronized void perfBranch(int i, Object tex) {
        PB[i]++;
        long now = System.nanoTime();
        if (perfBranchLast == 0) {
            perfBranchLast = now;
        }
        if (now - perfBranchLast >= 1_000_000_000L) {
            org.slf4j.LoggerFactory.getLogger("MPH-PERF").info(
                    "onPaint branches: passedThreadCheck={} noTextureReturn={} partialLoop={} (texture class={})",
                    PB[0], PB[1], PB[2], tex == null ? "-" : tex.getClass().getName());
            PB[0] = PB[1] = PB[2] = 0;
            perfBranchLast = now;
        }
    }

    private static synchronized void perfEntry(boolean popup, int rects, int w, int h) {
        perfEntryCalls++;
        perfEntryRects += rects;
        perfEntryPixels += (long) w * h;
        long now = System.nanoTime();
        if (perfEntryLast == 0) {
            perfEntryLast = now;
        }
        if (now - perfEntryLast >= 1_000_000_000L) {
            org.slf4j.LoggerFactory.getLogger("MPH-PERF").info(
                    "cef-onPaint arrivals={} rects={} framePixelsMP={} (thread={})",
                    perfEntryCalls, perfEntryRects, perfEntryPixels / 1_000_000, Thread.currentThread().getName());
            perfEntryCalls = perfEntryRects = perfEntryPixels = 0;
            perfEntryLast = now;
        }
    }

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        if (PERF_ENTRY) {
            perfEntry(popup, dirtyRects.length, width, height);
        }
        // nothing to update
        if (dirtyRects.length == 0)
            return;

        // ACTUAL-RUN BUG FIX (not upstream): see the `closed` field's
        // Javadoc. Must be checked again after the main-thread dispatch
        // below too, in case close() runs while this call is queued.
        if (closed) {
            return;
        }

        // ACTUAL-RUN BUG FIX (not upstream): this callback fires from CEF's
        // own render thread, not Minecraft's. Every branch below makes
        // direct GL calls (GlStateManager._bindTexture/_pixelStore, plus
        // texture creation inside renderer.onPaint), which are only safe on
        // Minecraft's render thread. Calling them off-thread raced with
        // Minecraft's own draw of this same texture and produced two
        // observed failures on NeoForge: an actual crash
        // ("IllegalStateException: Sampler ... has been closed!") and,
        // separately, a flood of "GL_INVALID_OPERATION ... Not a valid
        // sampler name" debug-log spam. Re-dispatching onto the main thread
        // via Minecraft#execute (queued, run between ticks) keeps every GL
        // call here on the thread that owns the GL context. The buffer
        // itself is a direct ByteBuffer CEF won't reuse before the next
        // onPaint, so capturing it in the lambda is safe.
        if (!RenderSystem.isOnRenderThread()) {
            Minecraft.getInstance().execute(() ->
                    onPaint(browser, popup, dirtyRects, buffer, width, height));
            return;
        }
        if (closed) {
            return;
        }

        if (PERF_ENTRY) {
            perfBranch(0, null);
        }
        if (!popup) {
            if (lastWidth != width || lastHeight != height) {
                lastWidth = width;
                lastHeight = height;
                // upload full texture
                // this also sets up the texture size and creates the texture
                renderer.onPaint(buffer, width, height);
            } else if (!renderer.usesGl()) {
                paintNonGl(dirtyRects, buffer, width);
            } else {
                if (PERF_ENTRY) {
                    perfBranch(renderer.getTextureID() == 0 ? 1 : 2, renderer.getTexture());
                }
                if (renderer.getTextureID() == 0) return;
                GlStateManager._bindTexture(renderer.getTextureID());
                GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, width);
                for (Rectangle dirtyRect : dirtyRects) {
                    GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, dirtyRect.x);
                    GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, dirtyRect.y);
                    renderer.onPaint(buffer, dirtyRect.x, dirtyRect.y, dirtyRect.width, dirtyRect.height);
                }
                if ((popupDrawn || showPopup) && popupSize != null) {
                    // interpret where the popup was as a dirty rect
                    if (!showPopup) {
                        // if the popup is not visible, just draw the contents of the buffer
                        GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, popupSize.width);
                        GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, popupSize.height);
                        renderer.onPaint(buffer, popupSize.x, popupSize.y, popupSize.width, popupSize.height);
                        popupGraphics = null;
                        popupSize = null;
                    } else if (popupDrawn) {
                        // else, a use copy of the popup graphics, as it needs to remain visible
                        // and for some reason that I do not for the life of me understand, chromium does not seem to keep this data in memory outside of the paint loop, meaning it has to be copied around, which wastes performance
                        GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, popupSize.width);
                        GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
                        GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);
                        renderer.onPaint(popupGraphics, popupSize.x, popupSize.y, popupSize.width, popupSize.height);
                    }
                }
            }
        } else {
            if (renderer.getTextureID() == 0) return;
            GlStateManager._bindTexture(renderer.getTextureID());
            int start = buffer.capacity();
            int end = 0;
            for (Rectangle dirtyRect : dirtyRects) {
                GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, popupSize.width);
                GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, dirtyRect.x);
                GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, dirtyRect.y);
                renderer.onPaint(buffer, popupSize.x + dirtyRect.x, popupSize.y + dirtyRect.y, dirtyRect.width, dirtyRect.height);

                int rectStart = (dirtyRect.x + ((dirtyRect.y) * popupSize.width)) << 2;
                if (rectStart < start) start = rectStart;

                int rectEnd = ((dirtyRect.x + dirtyRect.width) + ((dirtyRect.y + popupSize.height) * dirtyRect.width)) << 2;
                if (rectEnd > end) end = rectEnd;
            }
            if (start < 0) start = 0;
            if (end > buffer.capacity()) end = buffer.capacity();

            if (end > start) {
                // TODO: check if it's more performant to go for row-wise copies or if it's better to just copy the updated region
                if (this.popupGraphics != null) {
                    long addrFrom = MemoryUtil.memAddress(buffer);
                    long addrTo = MemoryUtil.memAddress(popupGraphics);
                    MemoryUtil.memCopy(
                            addrFrom + start,
                            addrTo + start,
                            (end - start)
                    );
                }
            }

            popupDrawn = true;
        }
    }

    /** Non-OpenGL (Vulkan) partial update; popups are not supported on this path yet. */
    private void paintNonGl(Rectangle[] dirtyRects, ByteBuffer buffer, int width) {
        for (Rectangle r : dirtyRects) {
            renderer.onPaintRegion(buffer, width, r.x, r.y, r.x, r.y, r.width, r.height);
        }
    }

    public void resize(int width, int height) {
        browser_rect_.setBounds(0, 0, width, height);
        wasResized(width, height);
    }

    // Inputs
    public void sendKeyPress(int keyCode, long scanCode, int modifiers) {
        if (browserControls) {
            if (modifiers == GLFW_MOD_CONTROL) {
                if (keyCode == GLFW_KEY_R) {
                    reload();
                    return;
                } else if (keyCode == GLFW_KEY_EQUAL) {
                    return; // zoom is CSS-based per session (MPH)
                } else if (keyCode == GLFW_KEY_MINUS) {
                    return; // zoom is CSS-based per session (MPH)
                } else if (keyCode == GLFW_KEY_0) {
                    return; // zoom is CSS-based per session (MPH)
                }
            } else if (modifiers == GLFW_MOD_ALT) {
                if (keyCode == GLFW_KEY_LEFT && canGoBack()) {
                    goBack();
                    return;
                } else if (keyCode == GLFW_KEY_RIGHT && canGoForward()) {
                    goForward();
                    return;
                }
            }
        }

        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_PRESS, keyCode, (char) keyCode, modifiers);
        e.scancode = scanCode;
        sendKeyEvent(e);
    }

    public void sendKeyRelease(int keyCode, long scanCode, int modifiers) {
        if (browserControls) {
            if (modifiers == GLFW_MOD_CONTROL) {
                if (keyCode == GLFW_KEY_R) return;
                else if (keyCode == GLFW_KEY_EQUAL) return;
                else if (keyCode == GLFW_KEY_MINUS) return;
                else if (keyCode == GLFW_KEY_0) return;
            } else if (modifiers == GLFW_MOD_ALT) {
                if (keyCode == GLFW_KEY_LEFT && canGoBack()) return;
                else if (keyCode == GLFW_KEY_RIGHT && canGoForward()) return;
            }
        }

        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_RELEASE, keyCode, (char) keyCode, modifiers);
        e.scancode = scanCode;
        sendKeyEvent(e);
    }

    public void sendKeyTyped(char c, int modifiers) {
        if (browserControls) {
            if (modifiers == GLFW_MOD_CONTROL) {
                if ((int) c == GLFW_KEY_R) return;
                else if ((int) c == GLFW_KEY_EQUAL) return;
                else if ((int) c == GLFW_KEY_MINUS) return;
                else if ((int) c == GLFW_KEY_0) return;
            } else if (modifiers == GLFW_MOD_ALT) {
                if ((int) c == GLFW_KEY_LEFT && canGoBack()) return;
                else if ((int) c == GLFW_KEY_RIGHT && canGoForward()) return;
            }
        }

        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_TYPE, c, c, modifiers);
        sendKeyEvent(e);
    }

    public void sendMouseMove(int mouseX, int mouseY) {
        CefMouseEvent e = new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, mouseX, mouseY, 0, 0, dragContext.getVirtualModifiers(btnMask));
        sendMouseEvent(e);

        if (dragContext.isDragging())
            this.dragTargetDragOver(new Point(mouseX, mouseY), 0, dragContext.getMask());
    }

    // TODO: it may be necessary to add modifiers here
    public void sendMousePress(int mouseX, int mouseY, int button) {
        // for some reason, middle and right are swapped in MC
        if (button == 1) button = 2;
        else if (button == 2) button = 1;

        if (button == 0) btnMask |= CefMouseEvent.BUTTON1_MASK;
        else if (button == 1) btnMask |= CefMouseEvent.BUTTON2_MASK;
        else if (button == 2) btnMask |= CefMouseEvent.BUTTON3_MASK;

        CefMouseEvent e = new CefMouseEvent(GLFW_PRESS, mouseX, mouseY, 1, button, btnMask);
        sendMouseEvent(e);
    }

    // TODO: it may be necessary to add modifiers here
    public void sendMouseRelease(int mouseX, int mouseY, int button) {
        // For some reason, middle and right are swapped in MC
        if (button == 1) button = 2;
        else if (button == 2) button = 1;

        if (button == 0 && (btnMask & CefMouseEvent.BUTTON1_MASK) != 0) btnMask ^= CefMouseEvent.BUTTON1_MASK;
        else if (button == 1 && (btnMask & CefMouseEvent.BUTTON2_MASK) != 0) btnMask ^= CefMouseEvent.BUTTON2_MASK;
        else if (button == 2 && (btnMask & CefMouseEvent.BUTTON3_MASK) != 0) btnMask ^= CefMouseEvent.BUTTON3_MASK;

        CefMouseEvent e = new CefMouseEvent(GLFW_RELEASE, mouseX, mouseY, 1, button, btnMask);
        sendMouseEvent(e);

        // drag&drop
        if (dragContext.isDragging()) {
            if (button == 0) {
                finishDragging(mouseX, mouseY);
            }
        }
    }

    // TODO: smooth scrolling
    public void sendMouseWheel(int mouseX, int mouseY, double amount, int modifiers) {
        if (browserControls) {
            if ((modifiers & GLFW_MOD_CONTROL) != 0) {
                return; // zoom is CSS-based per session (MPH)
            }
        }

        // macOS generally has a slow scroll speed that feels more natural with their magic mice / trackpads
        if (!MCEFPlatform.getPlatform().isMacOS()) {
            // This removes the feeling of "smooth scroll"
            if (amount < 0) {
                amount = Math.floor(amount);
            } else {
                amount = Math.ceil(amount);
            }

            // This feels about equivalent to chromium with smooth scrolling disabled -ds58
            amount = amount * 3;
        }

        CefMouseWheelEvent e = new CefMouseWheelEvent(CefMouseWheelEvent.WHEEL_UNIT_SCROLL, mouseX, mouseY, amount, modifiers);
        sendMouseWheelEvent(e);
    }

    // Drag & drop
    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        dragContext.startDragging(dragData, mask);
        this.dragTargetDragEnter(dragContext.getDragData(), new Point(x, y), btnMask, dragContext.getMask());
        // Indicates to CEF to not handle the drag event natively
        // reason: native drag handling doesn't work with off screen rendering
        return false;
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {
        if (dragContext.updateCursor(operation))
            // If the cursor to display for the drag event changes, then update the cursor
            this.onCursorChange(this, dragContext.getVirtualCursor(dragContext.getActualCursor()));

        super.updateDragCursor(browser, operation);
    }

    // Expose drag & drop functions
    public void startDragging(CefDragData dragData, int mask, int x, int y) { // Overload since the JCEF method requires a browser, which then goes unused
        startDragging(dragData, mask, x, y);
    }

    public void finishDragging(int x, int y) {
        dragTargetDrop(new Point(x, y), btnMask);
        dragTargetDragLeave();
        dragContext.stopDragging();
        this.onCursorChange(this, dragContext.getActualCursor());
    }

    public void cancelDrag() {
        dragTargetDragLeave();
        dragContext.stopDragging();
        this.onCursorChange(this, dragContext.getActualCursor());
    }

    // Closing
    public void close() {
        // Set first: see the `closed` field's Javadoc. Everything below
        // this line must be able to assume no further onPaint/setCursor
        // call will touch the renderer's GL objects after this point.
        closed = true;
        renderer.cleanup();
        cursorChangeListener.onCursorChange(0);
        super.close(true);
    }

    @Override
    protected void finalize() throws Throwable {
        Minecraft.getInstance().submit(renderer::cleanup);
        super.finalize();
    }

    // Cursor handling
    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        cursorType = dragContext.getVirtualCursor(cursorType);
        cursorChangeListener.onCursorChange(cursorType);
        return super.onCursorChange(browser, cursorType);
    }

    public void setCursor(CefCursorType cursorType) {
        // ACTUAL-RUN BUG FIX (not upstream): see the `closed` field's
        // Javadoc -- same race as onPaint's, checked before AND after the
        // main-thread dispatch below.
        if (closed) {
            return;
        }
        // ACTUAL-RUN BUG FIX (not upstream): same reasoning as onPaint's --
        // this can be reached from CEF's own thread via the cursor-change
        // callback chain, but glfwSetInputMode/glfwSetCursor are only safe
        // on the thread that owns the GLFW window (Minecraft's main
        // thread). Observed as intermittent stuck/free mouse cursor during
        // normal gameplay, including right after placing a block (i.e. not
        // limited to the already-documented close() cursor-desync case).
        if (!RenderSystem.isOnRenderThread()) {
            Minecraft.getInstance().execute(() -> setCursor(cursorType));
            return;
        }
        if (closed) {
            return;
        }
        // Not upstream: with no screen open the page must not touch the GLFW cursor mode
        // (it freed the cursor and froze the view while just looking at a looping video).
        if (Minecraft.getInstance().gui.screen() == null) {
            return;
        }
        if (cursorType == CefCursorType.NONE) {
            GLFW.glfwSetInputMode(Minecraft.getInstance().getWindow().handle(), GLFW_CURSOR, GLFW_CURSOR_HIDDEN);
        } else {
            GLFW.glfwSetInputMode(Minecraft.getInstance().getWindow().handle(), GLFW_CURSOR, GLFW_CURSOR_NORMAL);
            long ch = MCEF.getGLFWCursorHandle(cursorType);
            if (ch != 0L) {
                GLFW.glfwSetCursor(Minecraft.getInstance().getWindow().handle(), ch);
            }
        }
    }
}
