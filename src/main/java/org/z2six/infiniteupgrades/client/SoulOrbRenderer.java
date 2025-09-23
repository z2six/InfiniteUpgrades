package org.z2six.infiniteupgrades.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.z2six.infiniteupgrades.world.SoulOrbEntity;

import java.util.Locale;

public final class SoulOrbRenderer extends EntityRenderer<SoulOrbEntity> {

    private static final Logger LOG = LogUtils.getLogger();

    private static final boolean LOG_RENDER = true;
    private static final int     LOG_EVERY  = 20; // ticks per entity

    private static final ResourceLocation WHITE_TEX =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/misc/white.png");

    public SoulOrbRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0f;
    }

    @Override
    public ResourceLocation getTextureLocation(SoulOrbEntity entity) {
        return WHITE_TEX;
    }

    // We’ll let vanilla frustum checks run, but we’ll LOG what it would do.
    @Override
    public boolean shouldRender(SoulOrbEntity e, Frustum frustum, double camX, double camY, double camZ) {
        boolean result = super.shouldRender(e, frustum, camX, camY, camZ);
        if (LOG_RENDER && ((e.tickCount + (int)e.getId()) % LOG_EVERY == 0)) {
            double dx = e.getX() - camX, dy = e.getY() - camY, dz = e.getZ() - camZ;
            double dist2 = dx*dx + dy*dy + dz*dz;
            LOG.info("[SoulOrbRenderer] shouldRender: id={}, result={}, cam=({},{},{}), pos=({},{},{}), dist2={}",
                    e.getId(), result, fmt(camX), fmt(camY), fmt(camZ),
                    fmt(e.getX()), fmt(e.getY()), fmt(e.getZ()), fmt(dist2));
        }
        return result;
    }

    @Override
    public void render(SoulOrbEntity orb,
                       float entityYaw,
                       float partialTick,
                       PoseStack pose,
                       MultiBufferSource buffers,
                       int packedLightFromWorld) {
        super.render(orb, entityYaw, partialTick, pose, buffers, packedLightFromWorld);

        if (LOG_RENDER && ((orb.tickCount + (int)orb.getId()) % LOG_EVERY == 0)) {
            var cam = Minecraft.getInstance().gameRenderer.getMainCamera();
            var camP = cam.getPosition();
            LOG.info("[SoulOrbRenderer] render-call: id={}, tier={}, pos=({},{},{}), cam=({},{},{}), baseSize={}, light={}, dim={}",
                    orb.getId(), orb.getTier(),
                    fmt(orb.getX()), fmt(orb.getY()), fmt(orb.getZ()),
                    fmt(camP.x), fmt(camP.y), fmt(camP.z),
                    orb.baseQuadSize(), packedLightFromWorld,
                    orb.level().dimension().location());
        }

        pose.pushPose();

        // Billboard & flip (same as original working pipeline)
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));

        // Size + gentle pulse
        float base = orb.baseQuadSize();
        float t = orb.tickCount + partialTick;
        float pulse = 0.88f + 0.12f * Mth.sin(t * 0.25f);
        float s = base * pulse;
        pose.scale(s, s, s);

        // Tier tint (RGBA 0..255)
        float[] c = colorForTier(orb.getTier());
        int r = (int)(Mth.clamp(c[0], 0f, 1f) * 255f);
        int g = (int)(Mth.clamp(c[1], 0f, 1f) * 255f);
        int b = (int)(Mth.clamp(c[2], 0f, 1f) * 255f);
        int a = (int)(0.90f * 255f);

        // Force fullbright
        final int fullBright = 0xF000F0;

        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(WHITE_TEX));
        PoseStack.Pose last = pose.last();

        // FRONT (+Z)
        put(vc, last, -0.5f, -0.5f, 0f, 0f, 1f, r, g, b, a, fullBright, 0f, 0f,  1f);
        put(vc, last,  0.5f, -0.5f, 0f, 1f, 1f, r, g, b, a, fullBright, 0f, 0f,  1f);
        put(vc, last,  0.5f,  0.5f, 0f, 1f, 0f, r, g, b, a, fullBright, 0f, 0f,  1f);
        put(vc, last, -0.5f,  0.5f, 0f, 0f, 0f, r, g, b, a, fullBright, 0f, 0f,  1f);

        // BACK (-Z)
        put(vc, last, -0.5f,  0.5f, 0f, 1f, 0f, r, g, b, a, fullBright, 0f, 0f, -1f);
        put(vc, last,  0.5f,  0.5f, 0f, 0f, 0f, r, g, b, a, fullBright, 0f, 0f, -1f);
        put(vc, last,  0.5f, -0.5f, 0f, 0f, 1f, r, g, b, a, fullBright, 0f, 0f, -1f);
        put(vc, last, -0.5f, -0.5f, 0f, 1f, 1f, r, g, b, a, fullBright, 0f, 0f, -1f);

        pose.popPose();
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.3f", v); }

    private static void put(VertexConsumer vc,
                            PoseStack.Pose pose,
                            float x, float y, float z,
                            float u, float v,
                            int r, int g, int b, int a,
                            int light,
                            float nx, float ny, float nz) {
        vc.addVertex(pose, x, y, z)
                .setColor(r, g, b, a)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(nx, ny, nz);
        // no endVertex() on 1.21.x
    }

    private static float[] colorForTier(SoulOrbEntity.Tier tier) {
        return switch (tier) {
            case SMALL       -> new float[]{0.55f, 0.95f, 1.00f}; // aqua
            case MEDIUM      -> new float[]{0.75f, 0.55f, 1.00f}; // violet
            case LARGE       -> new float[]{1.00f, 0.78f, 0.30f}; // amber
            case EXTRA_LARGE -> new float[]{1.00f, 0.40f, 0.30f}; // red-orange
        };
    }
}
