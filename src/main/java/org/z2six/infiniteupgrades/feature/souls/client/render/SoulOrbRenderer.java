// file: src/main/java/org/z2six/infiniteupgrades/client/SoulOrbRenderer.java
package org.z2six.infiniteupgrades.feature.souls.client.render;

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
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import org.z2six.infiniteupgrades.feature.souls.entity.SoulOrbEntity;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public final class SoulOrbRenderer extends EntityRenderer<SoulOrbEntity> {

    private static final Logger LOG = LogUtils.getLogger();

    // Turn off per-frame spam
    private static final boolean LOG_RENDER = false;
    private static final int     LOG_EVERY  = 20; // ticks per entity

    // Fade during the last 10 seconds
    private static final int FADE_WINDOW_TICKS = 200;

    // Fallback white (we tint it)
    private static final ResourceLocation WHITE_TEX =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/misc/white.png");

    // Optional soul textures
    private static final ResourceLocation SOUL_SMALL =
            ResourceLocation.fromNamespaceAndPath("infiniteupgrades", "textures/entity/soul_orb/soul_small.png");
    private static final ResourceLocation SOUL_MEDIUM =
            ResourceLocation.fromNamespaceAndPath("infiniteupgrades", "textures/entity/soul_orb/soul_medium.png");
    private static final ResourceLocation SOUL_LARGE =
            ResourceLocation.fromNamespaceAndPath("infiniteupgrades", "textures/entity/soul_orb/soul_large.png");
    private static final ResourceLocation SOUL_XL =
            ResourceLocation.fromNamespaceAndPath("infiniteupgrades", "textures/entity/soul_orb/soul_extra_large.png");

    // Cache: hasTexture per tier (null = not checked yet)
    private static final Map<SoulOrbEntity.Tier, Boolean> HAS_TEX = new EnumMap<>(SoulOrbEntity.Tier.class);

    public SoulOrbRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0f;
    }

    @Override
    public ResourceLocation getTextureLocation(SoulOrbEntity entity) {
        ResourceLocation rl = textureFor(entity.getTier());
        if (hasTexture(entity.getTier(), rl)) return rl;
        return WHITE_TEX;
    }

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

        // Face camera & flip (billboard)
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));

        // Size + gentle pulse
        float base = orb.baseQuadSize();
        float t = orb.tickCount + partialTick;
        float pulse = 0.88f + 0.12f * Mth.sin(t * 0.25f);
        float s = base * pulse;
        pose.scale(s, s, s);

        // Fullbright so they pop in dark areas
        final int fullBright = 0xF000F0;

        // Fade factor (1 → 0 over last 10s)
        final float fade = orb.fadeFactor(partialTick, FADE_WINDOW_TICKS);

        SoulOrbEntity.Tier tier = orb.getTier();
        ResourceLocation tex = textureFor(tier);

        if (hasTexture(tier, tex)) {
            // Emissive textured billboard (single face -> no z-fight)
            VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucentEmissive(tex));
            PoseStack.Pose last = pose.last();

            int r = 255, g = 255, b = 255;
            int a = (int)(Mth.clamp(0.95f * fade, 0f, 1f) * 255f);

            // FRONT (+Z) ONLY
            put(vc, last, -0.5f, -0.5f, 0f, 0f, 1f, r, g, b, a, fullBright, 0f, 0f,  1f);
            put(vc, last,  0.5f, -0.5f, 0f, 1f, 1f, r, g, b, a, fullBright, 0f, 0f,  1f);
            put(vc, last,  0.5f,  0.5f, 0f, 1f, 0f, r, g, b, a, fullBright, 0f, 0f,  1f);
            put(vc, last, -0.5f,  0.5f, 0f, 0f, 0f, r, g, b, a, fullBright, 0f, 0f,  1f);
        } else {
            // Fallback: emissive tinted quad with WHITE_TEX
            float[] c = colorForTier(tier);
            int r = (int)(Mth.clamp(c[0], 0f, 1f) * 255f);
            int g = (int)(Mth.clamp(c[1], 0f, 1f) * 255f);
            int b = (int)(Mth.clamp(c[2], 0f, 1f) * 255f);
            int a = (int)(Mth.clamp(0.90f * fade, 0f, 1f) * 255f);

            VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucentEmissive(WHITE_TEX));
            PoseStack.Pose last = pose.last();

            // FRONT (+Z) ONLY
            put(vc, last, -0.5f, -0.5f, 0f, 0f, 1f, r, g, b, a, fullBright, 0f, 0f,  1f);
            put(vc, last,  0.5f, -0.5f, 0f, 1f, 1f, r, g, b, a, fullBright, 0f, 0f,  1f);
            put(vc, last,  0.5f,  0.5f, 0f, 1f, 0f, r, g, b, a, fullBright, 0f, 0f,  1f);
            put(vc, last, -0.5f,  0.5f, 0f, 0f, 0f, r, g, b, a, fullBright, 0f, 0f,  1f);
        }

        pose.popPose();
    }

    // ------------------------------------------------------------------------------------------------

    private static ResourceLocation textureFor(SoulOrbEntity.Tier t) {
        return switch (t) {
            case SMALL       -> SOUL_SMALL;
            case MEDIUM      -> SOUL_MEDIUM;
            case LARGE       -> SOUL_LARGE;
            case EXTRA_LARGE -> SOUL_XL;
        };
    }

    private static boolean hasTexture(SoulOrbEntity.Tier t, ResourceLocation rl) {
        Boolean cached = HAS_TEX.get(t);
        if (cached != null) return cached;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            HAS_TEX.put(t, Boolean.FALSE);
            return false;
        }
        ResourceManager rm = mc.getResourceManager();
        boolean present = rm.getResource(rl).isPresent();
        HAS_TEX.put(t, present);

        if (LOG_RENDER) {
            LOG.info("[SoulOrbRenderer] texture check: tier={}, rl={}, present={}", t, rl, present);
        }
        return present;
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

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.3f", v); }
}
