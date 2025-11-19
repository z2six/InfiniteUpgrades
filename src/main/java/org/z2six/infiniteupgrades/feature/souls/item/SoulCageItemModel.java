// MainFile: src/main/java/org/z2six/infiniteupgrades/feature/souls/item/SoulCageItemModel.java
package org.z2six.infiniteupgrades.feature.souls.item;

import net.minecraft.resources.ResourceLocation;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import software.bernie.geckolib.model.GeoModel;

public final class SoulCageItemModel extends GeoModel<SoulCageItem> {

    private static final ResourceLocation GEO =
            ResourceLocation.fromNamespaceAndPath(
                    Infiniteupgrades.MODID,
                    "geo/soul_cage.geo.json"
            );

    // Base texture for the Soul Cage (your 8x8 pixel-art texture)
    private static final ResourceLocation TEX_BASE =
            ResourceLocation.fromNamespaceAndPath(
                    Infiniteupgrades.MODID,
                    "textures/item/soul_cage.png"
            );

    // Required by GeckoLib's GeoModel in newer versions.
    // This can point to a minimal or even empty animation file if you don't use animations.
    private static final ResourceLocation ANIM =
            ResourceLocation.fromNamespaceAndPath(
                    Infiniteupgrades.MODID,
                    "animations/soul_cage.animation.json"
            );

    @Override
    public ResourceLocation getModelResource(SoulCageItem animatable) {
        return GEO;
    }

    @Override
    public ResourceLocation getTextureResource(SoulCageItem animatable) {
        return TEX_BASE;
    }

    @Override
    public ResourceLocation getAnimationResource(SoulCageItem animatable) {
        return ANIM;
    }
}
