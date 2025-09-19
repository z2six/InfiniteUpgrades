// MainFile: src/main/java/org/z2six/infiniteupgrades/client/AngelRenderer.java
package org.z2six.infiniteupgrades.client.render;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.Infiniteupgrades;
import org.z2six.infiniteupgrades.client.model.AngelModel;
import org.z2six.infiniteupgrades.world.AngelEntity;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.util.Color;

/**
 * // MainFile: AngelRenderer.java
 * Smooth distance-based fade using GeckoLib's color hook.
 * - Fully opaque at <= 5 blocks
 * - Smooth fade from 5..15 blocks
 * - Fully invisible at >= 15 blocks
 * Defensive logs; no render state leakage.
 */
public final class AngelRenderer extends GeoEntityRenderer<AngelEntity> {
    private static final Logger LOG = LogUtils.getLogger();

    // Fade profile per request
    private static final float FADE_START = 5.0f;   // <= 5 -> alpha = 1
    private static final float FADE_END   = 15.0f;  // >= 15 -> alpha = 0
    private static final float LERP_SPEED = 0.20f;  // smoothing each frame (0..1)

    // Smoothed alpha cache per renderer instance
    private float lastAlpha = 1.0f;

    public AngelRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new AngelModel());
        this.shadowRadius = 0.5f;
        LOG.debug("[InfiniteUpgrades/AngelRenderer] Constructed (fade 5..15, lerp={})", LERP_SPEED);
    }

    @Override
    public ResourceLocation getTextureLocation(AngelEntity entity) {
        try {
            return super.getTextureLocation(entity);
        } catch (Throwable t) {
            LOG.error("[AngelRenderer] Failed to resolve angel texture; fallback", t);
            return ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "textures/entity/angel.png");
        }
    }

    /** Translucent so alpha from getRenderColor() is honored. */
    @Override
    public RenderType getRenderType(AngelEntity animatable,
                                    ResourceLocation texture,
                                    MultiBufferSource bufferSource,
                                    float partialTick) {
        return RenderType.entityTranslucent(texture);
    }

    /** Feed RGBA (with smoothed alpha) into GeckoLib’s pipeline. */
    @Override
    public Color getRenderColor(AngelEntity animatable, float partialTick, int packedLight) {
        float target = 1.0f;
        try {
            target = computeTargetAlpha(animatable);
        } catch (Throwable t) {
            LOG.error("[AngelRenderer] computeTargetAlpha failed: {}", t.toString());
            target = 1.0f;
        }

        // Smooth toward target (no popping)
        lastAlpha = lerp(lastAlpha, target, LERP_SPEED);
        if (lastAlpha < 0f) lastAlpha = 0f;
        if (lastAlpha > 1f) lastAlpha = 1f;

        return Color.ofRGBA(1f, 1f, 1f, lastAlpha);
    }

    // --- helpers ---

    /** Map [FADE_START..FADE_END] -> [1..0], clamped; uses player distance to the entity. */
    private static float computeTargetAlpha(AngelEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return 1.0f;

        final double d = mc.player.distanceTo(entity);
        if (d <= FADE_START) return 1.0f;  // fully visible within 5
        if (d >= FADE_END)   return 0.0f;  // invisible beyond 15

        // Normalize to 0..1 over the 5..15 range, then invert for alpha
        float t = (float)((d - FADE_START) / (FADE_END - FADE_START)); // 0 at 5m, 1 at 15m
        // Gentle easing so it feels natural (tweak exponent to taste)
        t = (float)Math.pow(t, 1.25);
        return 1.0f - t; // 1 at 5m -> 0 at 15m
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
