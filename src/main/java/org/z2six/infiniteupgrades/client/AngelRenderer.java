// MainFile: src/main/java/org/z2six/infiniteupgrades/client/AngelRenderer.java
package org.z2six.infiniteupgrades.client;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.world.AngelEntity;

public class AngelRenderer extends MobRenderer<AngelEntity, HumanoidModel<AngelEntity>> {
    private static final Logger LOG = LogUtils.getLogger();
    private static final ResourceLocation ZOMBIE_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/zombie/zombie.png");

    public AngelRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.ZOMBIE)), 0.5f);
        LOG.debug("[AngelRenderer] Constructed");
    }

    @Override
    public ResourceLocation getTextureLocation(AngelEntity entity) {
        return ZOMBIE_TEXTURE;
    }

    @Override
    public void render(AngelEntity entity, float entityYaw, float partialTick, PoseStack pose, MultiBufferSource buffer, int packedLight) {
        // We don’t touch rotation here—entity yaw is driven on the client in AngelEntity#tick()
        super.render(entity, entityYaw, partialTick, pose, buffer, packedLight);
    }
}
