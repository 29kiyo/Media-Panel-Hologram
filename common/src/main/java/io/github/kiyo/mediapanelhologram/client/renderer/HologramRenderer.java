package io.github.kiyo.mediapanelhologram.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;

import io.github.kiyo.mediapanelhologram.block.HologramSettings;

/**
 * Draws the hologram: a see-through quad that turns horizontally to face the
 * camera, with a fixed vertical tilt from the settings.
 *
 * RenderTypes.text: depth test ON (DepthStencilState.DEFAULT), BlendFunction.TRANSLUCENT,
 * vertex format POSITION_TEX_LIGHTMAP_COLOR (WORLD_TEXT_SNIPPET, verified via javap) ->
 * write position, uv, light (FULL_BRIGHT), color in that order. Normal/overlay would crash.
 * Opaque blocks in front of the hologram hide it. It is submitted through the normal
 * collector path: the alwaysOnTop phase clears depth to 0.0 first, so it cannot be used.
 * Known: translucent terrain (ice, water, glass) may overlap the hologram incorrectly.
 * Settings values are integers x1000 (blocks / degrees), opacity is percent.
 */
public final class HologramRenderer {
    private HologramRenderer() {
    }

    /** (cx,cy,cz) = hologram origin in the current pose (block-local coordinates). */
    public static void submit(Identifier texture, HologramSettings s, float cx, float cy, float cz,
            PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (texture == null || !s.holoOn()) {
            return;
        }
        final float w = s.width() / 1000f;
        final float h = s.effectiveHeight() / 1000f;
        final int alpha = Math.round(s.opacity() * 255f / 100f);
        poseStack.pushPose();
        poseStack.translate(cx + s.x() / 1000f, cy + s.y() / 1000f, cz + s.z() / 1000f);
        // Quad front is +Z; rotate Y by 180 - yRot to face the camera (horizontal only).
        poseStack.rotate(new Quaternionf().rotationY((float) Math.toRadians(180.0 - camera.yRot)));
        if (s.pitch() != 0) {
            // TENTATIVE sign: positive pitch = top edge tilts away. Confirm in-game with the GUI.
            poseStack.rotate(new Quaternionf().rotationX((float) Math.toRadians(-s.pitch() / 1000.0)));
        }
        net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer quad = (pose, consumer) -> {
            float x0 = -w / 2f;
            float x1 = w / 2f;
            float y0 = -h / 2f;
            float y1 = h / 2f;
            vertex(pose, consumer, x0, y0, 0f, 1f, alpha);
            vertex(pose, consumer, x1, y0, 1f, 1f, alpha);
            vertex(pose, consumer, x1, y1, 1f, 0f, alpha);
            vertex(pose, consumer, x0, y1, 0f, 0f, alpha);
        };
        net.minecraft.client.renderer.rendertype.RenderType type = RenderTypes.text(texture);
        collector.submitCustomGeometry(poseStack, type, quad);
        poseStack.popPose();
    }

    /**
     * Culling bounds for the block entity: the block cell plus the hologram.
     * NeoForge frustum-culls block entities with this box (see Phase 5 notes).
     * Origin is the block center; panels will pass their group center later.
     */
    public static AABB renderBounds(BlockPos pos, HologramSettings s) {
        AABB cell = new AABB(pos);
        if (!s.holoOn()) {
            return cell;
        }
        double cx = pos.getX() + 0.5 + s.x() / 1000.0;
        double cy = pos.getY() + 0.5 + s.y() / 1000.0;
        double cz = pos.getZ() + 0.5 + s.z() / 1000.0;
        double r = Math.hypot(s.width() / 2000.0, s.effectiveHeight() / 2000.0) + 0.1;
        return new AABB(
                Math.min(cell.minX, cx - r), Math.min(cell.minY, cy - r), Math.min(cell.minZ, cz - r),
                Math.max(cell.maxX, cx + r), Math.max(cell.maxY, cy + r), Math.max(cell.maxZ, cz + r));
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer consumer, float x, float y, float u, float v,
            int alpha) {
        consumer.addVertex(pose, x, y, 0f).setUv(u, v).setLight(LightCoordsUtil.FULL_BRIGHT).setColor(255, 255, 255, alpha);
    }
}
