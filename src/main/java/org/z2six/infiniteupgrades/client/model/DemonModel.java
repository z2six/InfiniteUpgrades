package org.z2six.infiniteupgrades.client.model;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.Infiniteupgrades;
import org.z2six.infiniteupgrades.world.DemonEntity;
import software.bernie.geckolib.model.GeoModel;

/**
 * Binds the demon's Blockbench exports (geo/anim/texture).
 * Uses your files at:
 *  - assets/infiniteupgrades/geo/demon.geo.json
 *  - assets/infiniteupgrades/animations/demon.animation.json
 *  - assets/infiniteupgrades/textures/entity/demon.png
 */
public final class DemonModel extends GeoModel<DemonEntity> {

    private static final Logger LOG = LogUtils.getLogger();

    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "geo/demon.geo.json");
    private static final ResourceLocation ANIM =
            ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "animations/demon.animation.json");
    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "textures/entity/demon.png");

    @Override
    public ResourceLocation getModelResource(DemonEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(DemonEntity animatable) {
        return TEX;
    }

    @Override
    public ResourceLocation getAnimationResource(DemonEntity animatable) {
        return ANIM;
    }
}
