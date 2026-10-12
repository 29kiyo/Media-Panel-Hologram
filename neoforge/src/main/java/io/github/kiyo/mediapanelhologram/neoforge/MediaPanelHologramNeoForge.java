package io.github.kiyo.mediapanelhologram.neoforge;

import dev.architectury.registry.client.rendering.BlockEntityRendererRegistry;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;

import io.github.kiyo.mediapanelhologram.MediaPanelHologram;
import io.github.kiyo.mediapanelhologram.browser.BrowserEngineProvider;
import io.github.kiyo.mediapanelhologram.browser.MediaBrowserOpenerProvider;
import io.github.kiyo.mediapanelhologram.client.renderer.MediaBlockEntityRenderer;
import io.github.kiyo.mediapanelhologram.client.screen.MediaBlockScreen;
import io.github.kiyo.mediapanelhologram.client.screen.MediaBrowserScreen;
import com.cinemamod.mcef.MCEF;
import io.github.kiyo.mediapanelhologram.mcefbridge.MCEFBrowserEngine;
import io.github.kiyo.mediapanelhologram.neoforge.client.MediaPanelHologramNeoForgeClientEvents;
import io.github.kiyo.mediapanelhologram.registry.ModBlockEntities;
import io.github.kiyo.mediapanelhologram.registry.ModMenuTypes;

@Mod(MediaPanelHologram.MOD_ID)
public final class MediaPanelHologramNeoForge {
    public MediaPanelHologramNeoForge() {
        // Run our common setup.
        MediaPanelHologram.init();

        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            // Registered immediately, unlike the renderer/screen registration
            // below: these only set static fields, no registry access.
            BrowserEngineProvider.setInstance(new MCEFBrowserEngine());
            MediaBrowserOpenerProvider.setInstance(MediaBrowserScreen::openFrom);
            io.github.kiyo.mediapanelhologram.item.RemoteOpenerProvider.setInstance(io.github.kiyo.mediapanelhologram.client.screen.RemoteScreen::openFrom);

            // Explicit CEF shutdown on client stop -- NeoForge counterpart of
            // the Fabric side's ClientLifecycleEvents.CLIENT_STOPPING handler.
            // See that side's Javadoc/comment for the full reasoning (user-
            // reported cef_server.exe processes + high CPU usage accumulating
            // in Task Manager across repeated play sessions, traced to
            // MCEF#initialize()'s JVM shutdown hook not always running during
            // iterative development). ClientStoppingEvent fires as part of
            // Minecraft's normal client shutdown path, mirroring Fabric's
            // CLIENT_STOPPING timing.
            // ClientStoppingEvent does NOT implement IModBusEvent (confirmed via
            // javap on its class declaration) -- unlike FMLClientSetupEvent/
            // RegisterMenuScreensEvent above, it belongs on the general game-wide
            // NeoForge.EVENT_BUS, not this mod's own per-mod event bus (an actual
            // run confirmed this the hard way: "Listener for event class
            // ClientStoppingEvent takes an argument that is not valid for this
            // bus" when registered on the mod bus).
            NeoForge.EVENT_BUS.addListener((ClientStoppingEvent event) -> {
                if (MCEF.isInitialized()) {
                    MCEF.shutdown();
                }
            });

            IEventBus eventBus = ModLoadingContext.get().getActiveContainer().getEventBus();

            // NOT using Architectury's MenuScreenRegistry.registerScreenFactory
            // here (unlike the Fabric side, which still does): confirmed via an
            // actual run that MediaBlockScreen's Shift+right-click GUI silently
            // never opened on NeoForge ("Failed to create screen for menu
            // type: mediapanelhologram:media_block", no exception, just a
            // WARN -- traced via javap into vanilla MenuScreens.getConstructor
            // returning null). Root cause: Architectury's NeoForge
            // MenuScreenRegistryImpl registers its RegisterMenuScreensEvent
            // listener via EventBusesHooks.whenAvailable(...), called from
            // inside our FMLClientSetupEvent listener below -- but
            // RegisterMenuScreensEvent (fired from vanilla MenuScreens.init(),
            // itself called by NeoForge's ClientHooks#initClientHooks) fires
            // BEFORE FMLClientSetupEvent on this NeoForge version, so a
            // listener only added once FMLClientSetupEvent starts running is
            // already too late -- the event was already posted and consumed
            // with nothing registered. Registering our own
            // RegisterMenuScreensEvent listener directly, right here in the
            // mod constructor (the earliest point this class runs), instead
            // avoids that ordering entirely.
            //
            // Calling ModMenuTypes.MEDIA_BLOCK.get() from inside THIS
            // listener is safe despite the NPE risk documented below for
            // FMLClientSetupEvent: RegisterMenuScreensEvent necessarily fires
            // only once registries are already populated (there would be
            // nothing for NeoForge to associate a menu screen with
            // otherwise), so by the time this listener runs, the entry is
            // guaranteed to be resolvable.
            eventBus.addListener((RegisterMenuScreensEvent event) ->
                    event.register(ModMenuTypes.MEDIA_BLOCK.get(), MediaBlockScreen::new));

            // IMPORTANT: unlike Fabric's DeferredRegister (which registers
            // immediately), Architectury's NeoForge Registrar implementation
            // defers actual registry population until NeoForge's own
            // RegisterEvent fires. Calling ModBlockEntities.MEDIA_BLOCK.get()
            // directly in this constructor threw
            // "NullPointerException: Registry Object not present:
            // mediapanelhologram:media_block" in an actual run, because the
            // entry isn't resolved yet at construction time. Client-only
            // renderer registration is therefore deferred to
            // FMLClientSetupEvent (fired well after RegisterEvent), obtained
            // via the mod's own event bus.
            eventBus.addListener((FMLClientSetupEvent event) ->
                    BlockEntityRendererRegistry.register(
                            ModBlockEntities.MEDIA_BLOCK.get(), MediaBlockEntityRenderer::new));
            eventBus.addListener((FMLClientSetupEvent event) ->
                    BlockEntityRendererRegistry.register(
                            ModBlockEntities.PANEL.get(), io.github.kiyo.mediapanelhologram.client.renderer.PanelBlockEntityRenderer::new));

            // See the matching Fabric-side comment: pruning stale browser
            // sessions must not happen inside extractRenderState (mid-frame),
            // or an already-queued draw for a session closed by another
            // block's extraction can crash with "Sampler ... has been
            // closed!". Once per tick avoids the race.
            dev.architectury.event.events.client.ClientTickEvent.CLIENT_POST.register(mc -> {
                io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager.pruneStale();
                io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager.processPendingCloses();
                io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager.processPendingCreations();
            });

            // Spike-only: dev-time MCEF test screen (see MediaPanelHologramNeoForgeClientEvents).
            // Guarded by the env var so it never registers outside the spike check.
            if ("1".equals(System.getenv("MPH_MCEF_TEST"))) {
                NeoForge.EVENT_BUS.register(MediaPanelHologramNeoForgeClientEvents.class);
            }
        }
    }
}
