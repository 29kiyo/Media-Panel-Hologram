package io.github.kiyo.mediapanelhologram.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

import io.github.kiyo.mediapanelhologram.block.MediaMode;
import io.github.kiyo.mediapanelhologram.block.entity.MediaBlockEntity;
import io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager;
import io.github.kiyo.mediapanelhologram.browser.IBrowserSession;

/**
 * Renders the camouflaged block's appearance on top of the media block, AND
 * the embedded-browser texture for actual media content (Phase4,
 * "MCEFの導入・埋め込みブラウザレンダリング実装" task).
 *
 * TABS REMOVED: see MediaBlockEntity's Javadoc. resolveContentUrl once
 * again just returns MediaBlockEntity's plain url field for every mode.
 *
 * See development_status.md Phase4 notes for the full trace of every issue
 * found via actual runs (RenderShape/double-draw, getOcclusionShape/solid
 * black+lighting, light-emission mirroring) for the CAMOUFLAGE half of this
 * class.
 *
 * DESIGN DECISION (confirmed with user): the browser-texture quad is drawn
 * for EVERY MediaMode (IMAGE/VIDEO/MUSIC/BROWSER) via the same code path --
 * the only thing that will eventually differ per mode is what HTML/URL
 * resolveContentUrl() hands to the browser engine.
 *
 * TEXTURE SIZE / FACE / BRIGHTNESS (all placeholders, revisit later):
 * - Fixed 960x540 texture (see ClientBrowserSessionManager); no per-block
 *   resolution control yet.
 * - South face (z=1) hardcoded: MediaBlock has no FACING property yet --
 *   "床/天井/壁設置時の向き自動調整" (phase4_design.md) is the follow-up.
 * - Drawn full-bright regardless of ambient light; not user-confirmed.
 * - UV mapping confirmed correct via an actual run (no vertical flip
 *   needed).
 *
 * Client-only class kept in common (per common/build.gradle's own comment
 * about using Fabric Loader's @Environment annotations) and guarded
 * accordingly so it's never loaded on a dedicated server.
 */
@Environment(EnvType.CLIENT)
public class MediaBlockEntityRenderer implements BlockEntityRenderer<MediaBlockEntity, MediaBlockEntityRenderer.State> {
    private static final float BROWSER_QUAD_Z_OFFSET = 1.001f;

    private final BlockModelResolver blockModelResolver;

    public MediaBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.blockModelResolver = context.blockModelResolver();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(MediaBlockEntity blockEntity, State state, float partialTick, Vec3 cameraPos,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderState.extractBase(blockEntity, state, crumblingOverlay);

        ClientBrowserSessionManager.pruneStale();

        BlockState camouflageState = blockEntity.hasCamouflage()
                ? ((net.minecraft.world.item.BlockItem) blockEntity.getCamouflageItem().getItem())
                        .getBlock().defaultBlockState()
                : null;

        if (camouflageState == null) {
            state.modelRenderState.clear();
            state.camouflageLightCoords = 0;
        } else {
            blockModelResolver.update(state.modelRenderState, camouflageState, BlockDisplayContext.create());

            Level level = blockEntity.getLevel();
            if (level != null) {
                int ambientLight = LightCoordsUtil.getLightCoords(level, blockEntity.getBlockPos());
                state.camouflageLightCoords =
                        LightCoordsUtil.lightCoordsWithEmission(ambientLight, camouflageState.getLightEmission());
            } else {
                state.camouflageLightCoords = LightCoordsUtil.FULL_BRIGHT;
            }

            var tintLayers = state.modelRenderState.tintLayers();
            tintLayers.clear();
            Level tintLevel = blockEntity.getLevel();
            if (tintLevel instanceof ClientLevel clientLevel) {
                for (BlockTintSource tintSource : Minecraft.getInstance().getBlockColors().getTintSources(camouflageState)) {
                    tintLayers.add(tintSource.colorInWorld(camouflageState, clientLevel, blockEntity.getBlockPos()));
                }
            }
        }

        state.browserTextureLocation = null;
        if (camouflageState == null) {
            String url = resolveContentUrl(blockEntity);
            IBrowserSession session = ClientBrowserSessionManager.getOrCreate(blockEntity.getBlockPos(), url);
            if (session != null && session.isTextureReady()) {
                state.browserTextureLocation = session.getTextureLocation();
            }
        }
    }

    /**
     * What to hand the browser engine for this block's current mode. Every
     * MediaMode resolves to the same thing (the raw stored URL, loaded as a
     * top-level navigation) -- see this class's Javadoc for where per-mode
     * HTML wrapping should be added later. Public so MediaBrowserScreen (a
     * different package) can reuse it verbatim.
     */
    public static String resolveContentUrl(MediaBlockEntity blockEntity) {
        MediaMode mode = blockEntity.getMode();
        String url = blockEntity.getUrl();
        return mode == null ? url : url;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraRenderState) {
        if (!state.modelRenderState.isEmpty()) {
            state.modelRenderState.submit(poseStack, collector, state.camouflageLightCoords, OverlayTexture.NO_OVERLAY, 0);
        }

        if (state.browserTextureLocation != null) {
            Identifier texture = state.browserTextureLocation;
            collector.submitCustomGeometry(poseStack, RenderTypes.text(texture), (pose, consumer) ->
                    drawBrowserQuad(pose, consumer));
        }
    }

    private static void drawBrowserQuad(PoseStack.Pose pose, VertexConsumer consumer) {
        float z = BROWSER_QUAD_Z_OFFSET;
        int light = LightCoordsUtil.FULL_BRIGHT;
        consumer.addVertex(pose, 0f, 0f, z).setColor(255, 255, 255, 255).setUv(0f, 1f).setLight(light).setOverlay(OverlayTexture.NO_OVERLAY);
        consumer.addVertex(pose, 1f, 0f, z).setColor(255, 255, 255, 255).setUv(1f, 1f).setLight(light).setOverlay(OverlayTexture.NO_OVERLAY);
        consumer.addVertex(pose, 1f, 1f, z).setColor(255, 255, 255, 255).setUv(1f, 0f).setLight(light).setOverlay(OverlayTexture.NO_OVERLAY);
        consumer.addVertex(pose, 0f, 1f, z).setColor(255, 255, 255, 255).setUv(0f, 0f).setLight(light).setOverlay(OverlayTexture.NO_OVERLAY);
    }

    public static class State extends BlockEntityRenderState {
        final BlockModelRenderState modelRenderState = new BlockModelRenderState();
        int camouflageLightCoords;
        @Nullable
        Identifier browserTextureLocation;
    }
}
