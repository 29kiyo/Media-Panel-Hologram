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
import io.github.kiyo.mediapanelhologram.block.entity.MediaSource;
import io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager;
import io.github.kiyo.mediapanelhologram.browser.IBrowserSession;
import io.github.kiyo.mediapanelhologram.block.PanelBlock;
import io.github.kiyo.mediapanelhologram.block.entity.PanelBlockEntity;

/**
 * Draws a panel group as ONE 16:9 screen. Every panel draws only its own
 * cell: a black fill over the whole cell (letterbox) plus the part of the
 * browser picture that overlaps the cell. The screen is the largest 16:9
 * rectangle that fits inside the group, centered. URL / mode / quality come
 * from the group's representative panel (the one at groupOrigin), and all
 * panels of a group share one session keyed by that position.
 *
 * Screen-right / screen-up world directions per facing come from
 * MediaBlockEntityRenderer#applyScreenOrientation (see rightOf/upOf).
 *
 * KNOWN LIMITS: settings are not carried over when the representative
 * changes (merge/split/break); redstone off is not reflected.
 */
@net.fabricmc.api.Environment(net.fabricmc.api.EnvType.CLIENT)
public class PanelBlockEntityRenderer implements BlockEntityRenderer<PanelBlockEntity, PanelBlockEntityRenderer.State> {
    private static final java.util.Map<net.minecraft.core.BlockPos, Integer> READY_FRAME_COUNT =
            new java.util.HashMap<>();
    private static final int WARM_UP_FRAMES = 5;
    private static final float SURFACE_EPSILON = 0.001f;
    private static final float VIDEO_EXTRA_Z = 0.001f;

    public PanelBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    // World direction of the screen's right edge, as {x, y, z}.
    private static int[] rightOf(Direction facing, int screenUp) {
        int s = screenUp & 3;
        return switch (facing) {
            case SOUTH -> new int[] {1, 0, 0};
            case WEST -> new int[] {0, 0, 1};
            case NORTH -> new int[] {-1, 0, 0};
            case EAST -> new int[] {0, 0, -1};
            case UP -> switch (s) {
                case 0 -> new int[] {-1, 0, 0};
                case 1 -> new int[] {0, 0, -1};
                case 2 -> new int[] {1, 0, 0};
                default -> new int[] {0, 0, 1};
            };
            default -> switch (s) { // DOWN
                case 0 -> new int[] {1, 0, 0};
                case 1 -> new int[] {0, 0, 1};
                case 2 -> new int[] {-1, 0, 0};
                default -> new int[] {0, 0, -1};
            };
        };
    }

    // World direction of the screen's top edge, as {x, y, z}.
    private static int[] upOf(Direction facing, int screenUp) {
        if (facing != Direction.UP && facing != Direction.DOWN) {
            return new int[] {0, 1, 0};
        }
        return switch (screenUp & 3) {
            case 0 -> new int[] {0, 0, 1};
            case 1 -> new int[] {-1, 0, 0};
            case 2 -> new int[] {0, 0, -1};
            default -> new int[] {1, 0, 0};
        };
    }

    @Override
    public boolean shouldRender(PanelBlockEntity blockEntity, Vec3 cameraPos) {
        double limit = Math.max(64.0, blockEntity.getRange()
                + io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager.HYSTERESIS
                + Math.max(blockEntity.getGroupWidth(), blockEntity.getGroupHeight()));
        return Vec3.atCenterOf(blockEntity.getBlockPos()).closerThan(cameraPos, limit);
    }

    @Override
    public void extractRenderState(PanelBlockEntity blockEntity, State state, float partialTick, Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        if (!io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager.perfEnabled()) {
            extractRenderState0(blockEntity, state, partialTick, cameraPos, crumblingOverlay);
            return;
        }
        long t0 = System.nanoTime();
        try {
            extractRenderState0(blockEntity, state, partialTick, cameraPos, crumblingOverlay);
        } finally {
            io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager.perfRecord(true, System.nanoTime() - t0);
        }
    }

    private void extractRenderState0(PanelBlockEntity blockEntity, State state, float partialTick, Vec3 cameraPos,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderState.extractBase(blockEntity, state, crumblingOverlay);
        state.browserTextureLocation = null;
        state.hasVideo = false;
        state.facing = blockEntity.getBlockState().getValue(PanelBlock.FACING);
        state.screenUp = blockEntity.getBlockState().getValue(PanelBlock.SCREEN_UP);
        state.surfaceZ = blockEntity.getThickness() / 16f + SURFACE_EPSILON;

        net.minecraft.world.level.Level level = blockEntity.getLevel();
        if (level == null) {
            return;
        }
        net.minecraft.core.BlockPos pos = blockEntity.getBlockPos();
        if (blockEntity.isInvalidShape() || blockEntity.isGroupOff()) {
            READY_FRAME_COUNT.remove(pos);
            return;
        }
        net.minecraft.core.BlockPos origin = blockEntity.getGroupOrigin();
        if (!(level.getBlockEntity(origin) instanceof PanelBlockEntity rep)
                || !rep.getGroupOrigin().equals(origin)) {
            READY_FRAME_COUNT.remove(pos);
            return;
        }

        state.screenUp = rep.getScreenUpRef() >= 0 ? rep.getScreenUpRef()
                : rep.getBlockState().getValue(PanelBlock.SCREEN_UP);
        String url = MediaBlockEntityRenderer.resolveContentUrl(rep);
        IBrowserSession session = ClientBrowserSessionManager.getOrCreate(rep.getSessionKey(), url);
        if (session == null || !session.isTextureReady()) {
            READY_FRAME_COUNT.remove(pos);
            return;
        }
        int count = READY_FRAME_COUNT.getOrDefault(pos, 0);
        if (count < WARM_UP_FRAMES) {
            READY_FRAME_COUNT.put(pos, count + 1);
            return;
        }
        state.browserTextureLocation = session.getTextureLocation();

        // Group extent along each world axis (a/b axes: Y-facing X,Z; Z-facing X,Y; X-facing Y,Z).
        int gw = blockEntity.getGroupWidth();
        int gh = blockEntity.getGroupHeight();
        int extX, extY, extZ;
        switch (state.facing.getAxis()) {
            case Y -> { extX = gw; extZ = gh; extY = 1; }
            case Z -> { extX = gw; extY = gh; extZ = 1; }
            default -> { extY = gw; extZ = gh; extX = 1; }
        }
        int dx = pos.getX() - origin.getX();
        int dy = pos.getY() - origin.getY();
        int dz = pos.getZ() - origin.getZ();

        int[] r = rightOf(state.facing, state.screenUp);
        int[] u = upOf(state.facing, state.screenUp);
        int wl = Math.abs(r[0]) * extX + Math.abs(r[1]) * extY + Math.abs(r[2]) * extZ;
        int hl = Math.abs(u[0]) * extX + Math.abs(u[1]) * extY + Math.abs(u[2]) * extZ;
        int dotR = r[0] * dx + r[1] * dy + r[2] * dz;
        int dotU = u[0] * dx + u[1] * dy + u[2] * dz;
        int col = (r[0] + r[1] + r[2]) < 0 ? wl - 1 + dotR : dotR;
        int row = (u[0] + u[1] + u[2]) < 0 ? hl - 1 + dotU : dotU;

        // Largest centered 16:9 rectangle inside the group (group coords, in blocks).
        float sw, sh;
        if ((float) wl / hl >= 16f / 9f) {
            sh = hl;
            sw = hl * 16f / 9f;
        } else {
            sw = wl;
            sh = wl * 9f / 16f;
        }
        float sx0 = (wl - sw) / 2f;
        float sy0 = (hl - sh) / 2f;

        // Overlap of this cell [col,col+1]x[row,row+1] with the screen.
        float gx0 = Math.max(col, sx0);
        float gx1 = Math.min(col + 1, sx0 + sw);
        float gy0 = Math.max(row, sy0);
        float gy1 = Math.min(row + 1, sy0 + sh);
        if (gx1 <= gx0 || gy1 <= gy0) {
            return;
        }
        state.hasVideo = true;
        state.vx0 = gx0 - col;
        state.vx1 = gx1 - col;
        state.vy0 = gy0 - row;
        state.vy1 = gy1 - row;
        state.uL = (gx0 - sx0) / sw;
        state.uR = (gx1 - sx0) / sw;
        state.vTop = 1f - (gy1 - sy0) / sh;
        state.vBottom = 1f - (gy0 - sy0) / sh;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraRenderState) {
        if (!io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager.perfEnabled()) {
            submit0(state, poseStack, collector, cameraRenderState);
            return;
        }
        long t0 = System.nanoTime();
        try {
            submit0(state, poseStack, collector, cameraRenderState);
        } finally {
            io.github.kiyo.mediapanelhologram.client.browser.ClientBrowserSessionManager.perfRecord(false, System.nanoTime() - t0);
        }
    }

    private void submit0(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraRenderState) {
        if (state.browserTextureLocation == null) {
            return;
        }
        final State s = state;
        poseStack.pushPose();
        MediaBlockEntityRenderer.applyScreenOrientation(poseStack, state.facing, state.screenUp);
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutCull(state.browserTextureLocation),
                (pose, consumer) -> {
                    // Letterbox: whole cell black (texture tinted to black).
                    quad(pose, consumer, 0f, 0f, 1f, 1f, s.surfaceZ, 0f, 1f, 1f, 0f, 0);
                    if (s.hasVideo) {
                        quad(pose, consumer, s.vx0, s.vy0, s.vx1, s.vy1, s.surfaceZ + VIDEO_EXTRA_Z,
                                s.uL, s.uR, s.vBottom, s.vTop, 255);
                    }
                });
        poseStack.popPose();
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer consumer, float x0, float y0, float x1, float y1,
            float z, float uL, float uR, float vB, float vT, int rgb) {
        int light = LightCoordsUtil.FULL_BRIGHT;
        consumer.addVertex(pose, x0, y0, z).setColor(rgb, rgb, rgb, 255).setUv(uL, vB).setLight(light).setOverlay(OverlayTexture.NO_OVERLAY).setNormal(pose, 0f, 0f, 1f);
        consumer.addVertex(pose, x1, y0, z).setColor(rgb, rgb, rgb, 255).setUv(uR, vB).setLight(light).setOverlay(OverlayTexture.NO_OVERLAY).setNormal(pose, 0f, 0f, 1f);
        consumer.addVertex(pose, x1, y1, z).setColor(rgb, rgb, rgb, 255).setUv(uR, vT).setLight(light).setOverlay(OverlayTexture.NO_OVERLAY).setNormal(pose, 0f, 0f, 1f);
        consumer.addVertex(pose, x0, y1, z).setColor(rgb, rgb, rgb, 255).setUv(uL, vT).setLight(light).setOverlay(OverlayTexture.NO_OVERLAY).setNormal(pose, 0f, 0f, 1f);
    }

    public static class State extends BlockEntityRenderState {
        @org.jetbrains.annotations.Nullable
        Identifier browserTextureLocation;
        Direction facing = Direction.UP;
        int screenUp;
        float surfaceZ;
        boolean hasVideo;
        float vx0, vy0, vx1, vy1;
        float uL, uR, vTop, vBottom;
    }
}
