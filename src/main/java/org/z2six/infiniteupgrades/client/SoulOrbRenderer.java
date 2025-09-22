// File: src/main/java/org/z2six/infiniteupgrades/client/SoulOrbRenderer.java
package org.z2six.infiniteupgrades.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.z2six.infiniteupgrades.world.SoulOrbEntity;

public final class SoulOrbRenderer extends EntityRenderer<SoulOrbEntity> {

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

    @Override
    public void render(SoulOrbEntity orb,
                       float entityYaw,
                       float partialTick,
                       PoseStack pose,
                       MultiBufferSource buffers,
                       int packedLight) {
        super.render(orb, entityYaw, partialTick, pose, buffers, packedLight);

        pose.pushPose();

        // Face the camera and flip so quad faces forward
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));

        // Size + gentle pulse
        float base = orb.baseQuadSize();
        float t = orb.tickCount + partialTick;
        float pulse = 0.85f + 0.15f * Mth.sin(t * 0.25f);
        float s = base * pulse;
        pose.scale(s, s, s);

        // Color by tier (RGBA in 0..1)
        float[] c = colorForTier(orb.getTier());
        int r = (int)(Mth.clamp(c[0], 0f, 1f) * 255f);
        int g = (int)(Mth.clamp(c[1], 0f, 1f) * 255f);
        int b = (int)(Mth.clamp(c[2], 0f, 1f) * 255f);
        int a = (int)(0.85f * 255f);

        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(WHITE_TEX));
        PoseStack.Pose last = pose.last();

        // FRONT (+Z)
        put(vc, last, -0.5f, -0.5f, 0f, 0f, 1f, r, g, b, a, packedLight, 0f, 0f,  1f);
        put(vc, last,  0.5f, -0.5f, 0f, 1f, 1f, r, g, b, a, packedLight, 0f, 0f,  1f);
        put(vc, last,  0.5f,  0.5f, 0f, 1f, 0f, r, g, b, a, packedLight, 0f, 0f,  1f);
        put(vc, last, -0.5f,  0.5f, 0f, 0f, 0f, r, g, b, a, packedLight, 0f, 0f,  1f);

        // BACK (-Z) — reversed UV so both faces look consistent
        put(vc, last, -0.5f,  0.5f, 0f, 1f, 0f, r, g, b, a, packedLight, 0f, 0f, -1f);
        put(vc, last,  0.5f,  0.5f, 0f, 0f, 0f, r, g, b, a, packedLight, 0f, 0f, -1f);
        put(vc, last,  0.5f, -0.5f, 0f, 0f, 1f, r, g, b, a, packedLight, 0f, 0f, -1f);
        put(vc, last, -0.5f, -0.5f, 0f, 1f, 1f, r, g, b, a, packedLight, 0f, 0f, -1f);

        pose.popPose();
    }

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
