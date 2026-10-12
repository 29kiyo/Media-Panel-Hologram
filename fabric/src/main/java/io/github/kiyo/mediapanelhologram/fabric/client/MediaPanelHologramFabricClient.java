package io.github.kiyo.mediapanelhologram.fabric.client;

import com.cinemamod.mcef.MCEF;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.registry.client.rendering.BlockEntityRendererRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.architectury.registry.client.gui.MenuScreenRegistry;

import io.github.kiyo.mediapanelhologram.browser.BrowserEngineProvider;
import io.github.kiyo.mediapanelhologram.browser.MediaBrowserOpenerProvider;
import io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager;
import io.github.kiyo.mediapanelhologram.client.renderer.MediaBlockEntityRenderer;
import io.github.kiyo.mediapanelhologram.client.screen.MediaBlockScreen;
import io.github.kiyo.mediapanelhologram.client.screen.MediaBrowserScreen;
import io.github.kiyo.mediapanelhologram.mcefbridge.MCEFBrowserEngine;
import io.github.kiyo.mediapanelhologram.registry.ModBlockEntities;
import io.github.kiyo.mediapanelhologram.registry.ModMenuTypes;

public final class MediaPanelHologramFabricClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("MediaPanelHologram");

    @Override
    public void onInitializeClient() {
        // Registered first, unconditionally: these only set static fields
        // (no registry access), so there's no NeoForge-style deferred-
        // registration ordering concern here (contrast with the renderer/
        // screen registration below on the NeoForge side).
        BrowserEngineProvider.setInstance(new MCEFBrowserEngine());
        MediaBrowserOpenerProvider.setInstance(MediaBrowserScreen::openFrom);
        io.github.kiyo.mediapanelhologram.item.RemoteOpenerProvider.setInstance(io.github.kiyo.mediapanelhologram.client.screen.RemoteScreen::openFrom);

        // Explicit CEF shutdown on client stop (user-reported issue: cef_server.exe
        // processes and high CPU usage accumulated in Task Manager across repeated
        // play sessions). MCEF#initialize() already registers a JVM shutdown hook
        // (Runtime.getRuntime().addShutdownHook, see MCEF.java) that calls
        // CefUtil.shutdown() on normal JVM exit -- but that only fires if the JVM
        // itself actually terminates. During iterative development (repeatedly
        // stopping/restarting :fabric:runClient from an IDE or via a killed
        // Gradle daemon, rather than letting Minecraft's own quit path run to
        // completion) that hook can be skipped entirely, leaving CEF's child
        // processes (jcef_helper.exe / cef_server.exe) running detached. Calling
        // MCEF.shutdown() here, on Fabric's own CLIENT_STOPPING lifecycle event
        // (fired as part of Minecraft.stop()'s normal shutdown path, before JVM
        // exit), closes that gap for the ordinary "quit the game normally" case;
        // it does not help if the process is killed externally (task-killed,
        // IDE-stopped, or crashed) -- those cases have no code path to run at all.
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            if (MCEF.isInitialized()) {
                MCEF.shutdown();
            }
        });

        // Always-on client setup (not gated by the MCEF spike env var below).
        BlockEntityRendererRegistry.register(ModBlockEntities.MEDIA_BLOCK.get(), MediaBlockEntityRenderer::new);
        BlockEntityRendererRegistry.register(ModBlockEntities.PANEL.get(), io.github.kiyo.mediapanelhologram.client.renderer.PanelBlockEntityRenderer::new);
        // Pruning stale browser sessions is deliberately NOT done inside
        // extractRenderState (per-BlockEntity, mid-frame): closing a session
        // there can race with another MediaBlock's already-queued draw for
        // that same session, since GPU submission for the whole frame happens
        // after every block's extractRenderState has run. That race produced
        // an actual "Sampler ... has been closed!" crash on NeoForge when
        // placing a block facing up/down (see development_status.md). Doing
        // it once per tick, between frames, avoids the race entirely.
        ClientTickEvent.CLIENT_POST.register(mc -> {
            ClientBrowserSessionManager.pruneStale();
            ClientBrowserSessionManager.processPendingCloses();
            ClientBrowserSessionManager.processPendingCreations();
        });
        MenuScreenRegistry.registerScreenFactory(ModMenuTypes.MEDIA_BLOCK.get(), MediaBlockScreen::new);

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
                        opened[0] = true;
                        LOGGER.info("MPH spike: conditions met, opening test screen now");
                        Minecraft.getInstance().setScreenAndShow(new MCEFTestScreen());
                        return;
                    }

                    if (!mcefInit && tickCounter[0] % 20 == 0) {
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
