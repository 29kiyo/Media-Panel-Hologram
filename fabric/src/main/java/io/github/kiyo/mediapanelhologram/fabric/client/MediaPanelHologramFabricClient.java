package io.github.kiyo.mediapanelhologram.fabric.client;

import com.cinemamod.mcef.MCEF;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MediaPanelHologramFabricClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("MediaPanelHologram");

    @Override
    public void onInitializeClient() {
        boolean dev = FabricLoader.getInstance().isDevelopmentEnvironment();
        String envFlag = System.getenv("MPH_MCEF_TEST");
        LOGGER.info("MPH spike: onInitializeClient called. dev={}, MPH_MCEF_TEST={}", dev, envFlag);

        if (dev && "1".equals(envFlag)) {
            LOGGER.info("MPH spike: registering END_CLIENT_TICK listener");
            final boolean[] opened = {false};
            final int[] tickCounter = {0};
            ClientTickEvents.END_CLIENT_TICK.register(mc -> {
                try {
                    if (opened[0]) return;
                    tickCounter[0]++;
                    boolean mcefInit = MCEF.isInitialized();
                    boolean onTitle = mc.gui.screen() instanceof TitleScreen;

                    if (tickCounter[0] % 20 == 1) {
                        LOGGER.info("MPH spike: tick={} mcefInit={} onTitle={} screen={}",
                                tickCounter[0], mcefInit, onTitle,
                                mc.gui.screen() == null ? "null" : mc.gui.screen().getClass().getSimpleName());
                    }

                    if (mcefInit && onTitle) {
                        // Both conditions met: safe to open (not a transient mid-startup screen).
                        opened[0] = true;
                        LOGGER.info("MPH spike: conditions met, opening test screen now");
                        Minecraft.getInstance().setScreenAndShow(new MCEFTestScreen());
                        return;
                    }

                    if (!mcefInit && tickCounter[0] % 20 == 0) {
                        // CefInitMixin only advances (schedule init / show download progress) in
                        // response to a setScreenAndShow call. Idle screens don't call it on their
                        // own, so poke once a second by re-showing whatever screen is current.
                        // MCEFDownloaderMenu.tick() drives its own transition back once done, so
                        // this poke only matters before the download menu appears.
                        Screen current = mc.gui.screen();
                        if (current != null) {
                            Minecraft.getInstance().setScreenAndShow(current);
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.error("MPH spike: exception in tick handler", t);
                }
            });
        }
    }
}
