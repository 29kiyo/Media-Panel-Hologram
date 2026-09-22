package io.github.kiyo.mediapanelhologram.neoforge.client;

import com.cinemamod.mcef.MCEF;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spike-only: NeoForge counterpart of the Fabric client's END_CLIENT_TICK listener.
 * Registered on NeoForge.EVENT_BUS (game bus) only when MPH_MCEF_TEST=1,
 * see MediaPanelHologramNeoForge constructor.
 */
public final class MediaPanelHologramNeoForgeClientEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger("MediaPanelHologram");
    private static final AtomicBoolean OPENED = new AtomicBoolean(false);
    private static final AtomicInteger TICK_COUNTER = new AtomicInteger(0);

    private MediaPanelHologramNeoForgeClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        try {
            if (OPENED.get()) return;

            Minecraft mc = Minecraft.getInstance();
            int tick = TICK_COUNTER.incrementAndGet();
            boolean mcefInit = MCEF.isInitialized();
            boolean onTitle = mc.gui.screen() instanceof TitleScreen;

            if (tick % 20 == 1) {
                LOGGER.info("MPH spike: tick={} mcefInit={} onTitle={} screen={}",
                        tick, mcefInit, onTitle,
                        mc.gui.screen() == null ? "null" : mc.gui.screen().getClass().getSimpleName());
            }

            if (mcefInit && onTitle) {
                // Both conditions met: safe to open (not a transient mid-startup screen).
                OPENED.set(true);
                LOGGER.info("MPH spike: conditions met, opening test screen now");
                mc.setScreenAndShow(new MCEFTestScreen());
                return;
            }

            if (!mcefInit && tick % 20 == 0) {
                // CefInitMixin only advances (schedule init / show download progress) in
                // response to a setScreenAndShow call. Idle screens don't call it on their
                // own, so poke once a second by re-showing whatever screen is current.
                Screen current = mc.gui.screen();
                if (current != null) {
                    mc.setScreenAndShow(current);
                }
            }
        } catch (Throwable t) {
            LOGGER.error("MPH spike: exception in tick handler", t);
        }
    }
}
