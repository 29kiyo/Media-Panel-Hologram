package io.github.kiyo.mediapanelhologram.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.Direction;
import org.joml.Quaternionf;
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
    // See the WARM-UP FRAME SKIP comment in extractRenderState for why this
    // exists. A simple presence-set of block positions whose texture has
    // already had its one skipped warm-up frame.
    private static final java.util.Map<net.minecraft.core.BlockPos, Integer> READY_FRAME_COUNT =
            new java.util.WeakHashMap<>();
    /** Frames to wait after isTextureReady() first flips true before actually drawing. See extractRenderState. */
    private static final int WARM_UP_FRAMES = 5;
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


        BlockState camouflageState = blockEntity.getCamouflageState();

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
        boolean skipThisFrame = false;
        state.facing = blockEntity.getBlockState()
                .getValue(io.github.kiyo.mediapanelhologram.block.MediaBlock.FACING);
        state.screenUp = blockEntity.getBlockState()
                .getValue(io.github.kiyo.mediapanelhologram.block.MediaBlock.SCREEN_UP_PROPERTY);
        if (camouflageState == null) {
            String url = resolveContentUrl(blockEntity);
            IBrowserSession session = ClientBrowserSessionManager.getOrCreate(blockEntity.getBlockPos(), url);
            if (session != null && session.isTextureReady()) {
                // WARM-UP FRAME SKIP: an actual-run GL error ("Not a valid
                // sampler name", continuous) was traced to the very first
                // frame a session's texture becomes ready. CefRenderUpdateMixin
                // pumps CEF's message loop at the HEAD of GameRenderer#render,
                // on the render thread, so the first onPaint (which creates the
                // GPU texture/sampler via MCEFDirectTexture#setTexture) can run
                // synchronously just before this same frame's world render --
                // too soon for the freshly created sampler to be safely bound
                // yet. Tracked per-BlockPos (not per-State, since State appears
                // to be re-created often) so the skip only happens once, the
                // first time isTextureReady() flips true for that block.
                var pos = blockEntity.getBlockPos();
                int count = READY_FRAME_COUNT.getOrDefault(pos, 0);
                if (count < WARM_UP_FRAMES) {
                    READY_FRAME_COUNT.put(pos, count + 1);
                    skipThisFrame = true;
                } else {
                    state.browserTextureLocation = session.getTextureLocation();
                }
            } else {
                READY_FRAME_COUNT.remove(blockEntity.getBlockPos());
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
        String url = blockEntity.getUrl();
        if (blockEntity.getMode() == MediaMode.BROWSER && (url == null || url.isEmpty())) {
            // No URL set yet in browser mode: open a sensible default
            // instead of a blank page, matching the Home-button convention.
            return "https://www.google.com";
        }
        if (blockEntity.getMode() == MediaMode.VIDEO) {
            String embed = io.github.kiyo.mediapanelhologram.browser.VideoEmbed.toEmbedUrl(url);
            if (embed != null) {
                return embed;
            }
        }
        return url;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraRenderState) {
        if (!state.modelRenderState.isEmpty()) {
            state.modelRenderState.submit(poseStack, collector, state.camouflageLightCoords, OverlayTexture.NO_OVERLAY, 0);
        }

        if (state.browserTextureLocation != null) {
            Identifier texture = state.browserTextureLocation;
            poseStack.pushPose();
            applyScreenOrientation(poseStack, state.facing, state.screenUp);
            // Render-type history (both actual-run bugs, both only
            // reproduced with a live game, not from reading docs):
            // 1) RenderTypes.text (meant for font glyph atlases) produced a
            //    continuous "GL_INVALID_OPERATION ... Not a valid sampler
            //    name" debug-log flood just from a MediaBlock's browser face
            //    being on screen -- text's pipeline apparently expects an
            //    extra sampler binding that a plain non-font texture doesn't
            //    provide.
            // 2) RenderTypes.entityTranslucent looked like the right generic
            //    single-texture replacement, but the SAME sampler-name error
            //    came back as soon as two MediaBlocks (any FACING, doesn't
            //    have to differ) were on screen at once -- entityTranslucent's
            //    blending/sort path doesn't play well with MCEF's per-block
            //    dynamic textures shown more than one at a time.
            // entityCutoutCull(Identifier) -- alpha-tested, no blending -- has
            // neither problem. Browser content is opaque in practice, so
            // losing smooth alpha blending (a page's translucent overlay, if
            // any, becomes fully opaque or fully invisible per-pixel instead)
            // is an acceptable trade here.
            collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutCull(texture), (pose, consumer) ->
                    drawBrowserQuad(pose, consumer));
            poseStack.popPose();
        }
    }

    private static final Direction[] SCREEN_UP_DIRECTIONS =
            { Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST };

    /**
     * The quad is authored for the SOUTH face (viewed from +Z: +X right, +Y up).
     * Rotate it around the block center so it lands on the FACING face.
     * For UP/DOWN, screenUp picks which horizontal direction the screen's top edge points to.
     */
    private static void applyScreenOrientation(PoseStack poseStack, Direction facing, int screenUp) {
        poseStack.translate(0.5f, 0.5f, 0.5f);
        if (facing == Direction.UP || facing == Direction.DOWN) {
            float upYaw = Direction.getYRot(SCREEN_UP_DIRECTIONS[screenUp & 3]);
            if (facing == Direction.UP) {
                poseStack.mulPose(new Quaternionf().rotationY((float) Math.toRadians(180.0f - upYaw)));
                poseStack.mulPose(new Quaternionf().rotationX((float) Math.toRadians(-90.0)));
            } else {
                poseStack.mulPose(new Quaternionf().rotationY((float) Math.toRadians(-upYaw)));
                poseStack.mulPose(new Quaternionf().rotationX((float) Math.toRadians(90.0)));
            }
        } else {
            poseStack.mulPose(new Quaternionf().rotationY((float) Math.toRadians(-Direction.getYRot(facing))));
        }
        poseStack.translate(-0.5f, -0.5f, -0.5f);
    }

    private static void drawBrowserQuad(PoseStack.Pose pose, VertexConsumer consumer) {
        float z = BROWSER_QUAD_Z_OFFSET;
        int light = LightCoordsUtil.FULL_BRIGHT;
        // entityTranslucent's vertex format requires a normal per vertex
        // (missing it crashed with "IllegalStateException: Missing elements
        // in vertex" -- confirmed on both loaders). The quad faces +Z in its
        // own local space (before applyScreenOrientation rotates the whole
        // pose), so that's the normal here too.
        consumer.addVertex(pose, 0f, 0f, z).setColor(255, 255, 255, 255).setUv(0f, 1f).setLight(light).setOverlay(OverlayTexture.NO_OVERLAY).setNormal(pose, 0f, 0f, 1f);
        consumer.addVertex(pose, 1f, 0f, z).setColor(255, 255, 255, 255).setUv(1f, 1f).setLight(light).setOverlay(OverlayTexture.NO_OVERLAY).setNormal(pose, 0f, 0f, 1f);
        consumer.addVertex(pose, 1f, 1f, z).setColor(255, 255, 255, 255).setUv(1f, 0f).setLight(light).setOverlay(OverlayTexture.NO_OVERLAY).setNormal(pose, 0f, 0f, 1f);
        consumer.addVertex(pose, 0f, 1f, z).setColor(255, 255, 255, 255).setUv(0f, 0f).setLight(light).setOverlay(OverlayTexture.NO_OVERLAY).setNormal(pose, 0f, 0f, 1f);
    }

    public static class State extends BlockEntityRenderState {
        final BlockModelRenderState modelRenderState = new BlockModelRenderState();
        int camouflageLightCoords;
        @Nullable
        Identifier browserTextureLocation;
        Direction facing = Direction.SOUTH;
        int screenUp;
    }
}
