// File: src/main/java/org/z2six/infiniteupgrades/feature/demon/client/render/DemonRenderer.java
package org.z2six.infiniteupgrades.feature.demon.client.render;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.z2six.infiniteupgrades.feature.demon.client.model.DemonModel;
import org.z2six.infiniteupgrades.feature.demon.entity.DemonEntity;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.util.Color;

/**
 * Demon renderer – identical fade profile/logic to AngelRenderer.
 */
public final class DemonRenderer extends GeoEntityRenderer<DemonEntity> {
    private static final Logger LOG = LogUtils.getLogger();

    private static final float FADE_START = 5.0f;   // <= 5 -> alpha = 1
    private static final float FADE_END   = 15.0f;  // >= 15 -> alpha = 0
    private static final float LERP_SPEED = 0.20f;  // smoothing each frame (0..1)

    private float lastAlpha = 1.0f;

    public DemonRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new DemonModel());
        this.shadowRadius = 0.5f;
        LOG.debug("[InfiniteUpgrades/DemonRenderer] Constructed (fade 5..15, lerp={})", LERP_SPEED);
    }

    @Override
    public ResourceLocation getTextureLocation(DemonEntity entity) {
        try {
            return super.getTextureLocation(entity);
        } catch (Throwable t) {
            LOG.error("[DemonRenderer] Failed to resolve demon texture; fallback", t);
            return ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "textures/entity/demon.png");
        }
    }

    @Override
    public RenderType getRenderType(DemonEntity animatable, ResourceLocation texture, MultiBufferSource bufferSource, float partialTick) {
        return RenderType.entityTranslucent(texture);
    }

    @Override
    public Color getRenderColor(DemonEntity animatable, float partialTick, int packedLight) {
        float target = 1.0f;
        try {
            target = computeTargetAlpha(animatable);
        } catch (Throwable t) {
            LOG.error("[DemonRenderer] computeTargetAlpha failed: {}", t.toString());
            target = 1.0f;
        }

        lastAlpha = lerp(lastAlpha, target, LERP_SPEED);
        if (lastAlpha < 0f) lastAlpha = 0f;
        if (lastAlpha > 1f) lastAlpha = 1f;

        return Color.ofRGBA(1f, 1f, 1f, lastAlpha);
    }

    private static float computeTargetAlpha(DemonEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return 1.0f;

        final double d = mc.player.distanceTo(entity);
        if (d <= FADE_START) return 1.0f;
        if (d >= FADE_END)   return 0.0f;

        float t = (float)((d - FADE_START) / (FADE_END - FADE_START));
        t = (float)Math.pow(t, 1.25);
        return 1.0f - t;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
