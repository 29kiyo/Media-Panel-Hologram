package io.github.kiyo.mediapanelhologram.fabric.client;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Spike-only screen: renders one CEF browser into a texture.
 * Opened automatically in a dev environment when the env var MPH_MCEF_TEST=1 is set.
 */
public final class MCEFTestScreen extends Screen {
    private static final int W = 480;
    private static final int H = 270;

    private MCEFBrowser browser;
    private boolean loggedFirstFrame = false;

    public MCEFTestScreen() {
        MCEF.getLogger().info("MPH spike: MCEFTestScreen constructed");
        super(Component.literal("MCEF test"));
    }

    private void createBrowser() {
        String html = "<html><body style='margin:0;background:#203050;color:#fff;font-family:sans-serif'>"
                + "<div id=t style='font-size:64px'>0</div>"
                + "<div style='font-size:20px'>MCEF on Minecraft 26.2</div>"
                + "<script>let n=0;setInterval(function(){document.getElementById('t').textContent=++n},100)</script>"
                + "</body></html>";
        String url = "data:text/html;charset=utf-8,"
                + URLEncoder.encode(html, StandardCharsets.UTF_8).replace("+", "%20");
        browser = MCEF.createBrowser(url, false, W, H);
        MCEF.getLogger().info("MCEF test: browser created ({}x{})", W, H);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xC0101010); // solid fill, no blur (avoid nested-blur crash)
        if (browser == null) {
            createBrowser();
        }

        int x = (width - W) / 2;
        int y = (height - H) / 2;

        if (browser.isTextureReady()) {
            int texW = browser.getRenderer().getTextureWidth();
            int texH = browser.getRenderer().getTextureHeight();
            if (!loggedFirstFrame) {
                loggedFirstFrame = true;
                MCEF.getLogger().info("MCEF test: first frame ready, texture {}x{}", texW, texH);
            }
            graphics.blit(RenderPipelines.GUI_TEXTURED, browser.getTextureLocation(), x, y, 0f, 0f, W, H, texW, texH);
        } else {
            graphics.text(font, "waiting for first frame...", x, y, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() {
        if (browser != null) {
            browser.close();
            browser = null;
            MCEF.getLogger().info("MCEF test: browser closed");
        }
        super.onClose();
    }
}
