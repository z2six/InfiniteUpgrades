// MainFile: src/main/java/org/z2six/infiniteupgrades/client/render/DemonRenderer.java
package org.z2six.infiniteupgrades.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.Infiniteupgrades;
import org.z2six.infiniteupgrades.world.DemonEntity;

/**
 * Minimal renderer placeholder.
 * If your Angel uses GeckoLib, feel free to swap this to your Geo renderer pattern in next step.
 */
public class DemonRenderer extends EntityRenderer<DemonEntity> {
    private static final Logger LOG = LogUtils.getLogger();
    private static final ResourceLocation TEX = ResourceLocation.fromNamespaceAndPath(
            Infiniteupgrades.MODID, "textures/entity/demon.png");

    public DemonRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public ResourceLocation getTextureLocation(DemonEntity entity) {
        return TEX;
    }

    @Override
    public void render(DemonEntity entity, float entityYaw, float partialTicks, PoseStack pose, MultiBufferSource buf, int packedLight) {
        try {
            // Placeholder: we don't draw a model here.
            // In the next step, plug in the same geometry/animation pipeline as AngelRenderer.
            // For now, do nothing (invisible) but keep the class present so registration compiles later.
        } catch (Throwable t) {
            LOG.error("[DemonRenderer] render failed: {}", t.toString());
        }
    }
}
