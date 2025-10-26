// File: src/main/java/org/z2six/infiniteupgrades/feature/souls/item/SoulCageItemRenderer.java
package org.z2six.infiniteupgrades.feature.souls.item;

import software.bernie.geckolib.renderer.GeoItemRenderer;

public final class SoulCageItemRenderer extends GeoItemRenderer<SoulCageItem> {
    public SoulCageItemRenderer() {
        super(new SoulCageItemModel());
        // No extra layers needed—vanilla animates the texture via soul_cage.png.mcmeta
    }
}
