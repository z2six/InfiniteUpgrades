// File: src/main/java/org/z2six/infiniteupgrades/feature/angel/client/model/AngelModel.java
package org.z2six.infiniteupgrades.feature.angel.client.model;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.z2six.infiniteupgrades.feature.angel.entity.AngelEntity;
import software.bernie.geckolib.model.GeoModel;

/**
 * Binds the angel's Blockbench exports (geo/anim/texture).
 * Uses your files at:
 *  - assets/infiniteupgrades/geo/angel.geo.json
 *  - assets/infiniteupgrades/animations/angel.animation.json
 *  - assets/infiniteupgrades/textures/entity/angel.png
 */
public final class AngelModel extends GeoModel<AngelEntity> {

    private static final Logger LOG = LogUtils.getLogger();

    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "geo/angel.geo.json");
    private static final ResourceLocation ANIM =
            ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "animations/angel.animation.json");
    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "textures/entity/angel.png");

    @Override
    public ResourceLocation getModelResource(AngelEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(AngelEntity animatable) {
        return TEX;
    }

    @Override
    public ResourceLocation getAnimationResource(AngelEntity animatable) {
        return ANIM;
    }
}
