// MainFile: src/main/java/org/z2six/infiniteupgrades/client/AngelRenderer.java
package org.z2six.infiniteupgrades.client;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.Infiniteupgrades;
import org.z2six.infiniteupgrades.world.AngelEntity;

public class AngelRenderer extends MobRenderer<AngelEntity, HumanoidModel<AngelEntity>> {
    private static final Logger LOG = LogUtils.getLogger();
    private static final ResourceLocation ZOMBIE_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/zombie/zombie.png");

    public AngelRenderer(EntityRendererProvider.Context ctx) {
        // Use a vanilla humanoid model baked from the zombie layer (works for our placeholder)
        super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.ZOMBIE)), 0.5f);
        LOG.debug("[AngelRenderer] Constructed");
    }

    @Override
    public ResourceLocation getTextureLocation(AngelEntity entity) {
        return ZOMBIE_TEXTURE;
    }

    @Override
    public void render(AngelEntity entity, float entityYaw, float partialTick, PoseStack pose, MultiBufferSource buffer, int packedLight) {
        try {
            float time = (entity.tickCount + partialTick);
            float bob = Mth.sin(time * AngelEntity.BOB_SPEED + (entity.getId() * 0.37f)) * AngelEntity.BOB_AMPLITUDE;

            pose.pushPose();
            pose.translate(0.0, bob, 0.0);

            // Face the local camera (client-only)
            var erd = Minecraft.getInstance().getEntityRenderDispatcher();
            double dx = erd.camera.getPosition().x - entity.getX();
            double dz = erd.camera.getPosition().z - entity.getZ();
            float faceYaw = (float)(Mth.atan2(dz, dx) * (180f / Math.PI)) - 90f;
            float correction = faceYaw - entityYaw;
            pose.mulPose(Axis.YP.rotationDegrees(correction));

            super.render(entity, entityYaw, partialTick, pose, buffer, packedLight);
            pose.popPose();
        } catch (Throwable t) {
            LOG.error("[AngelRenderer] render failed: {}", t.toString());
        }
    }
}
