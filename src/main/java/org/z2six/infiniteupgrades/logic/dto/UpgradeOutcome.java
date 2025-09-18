// MainFile: src/main/java/org/z2six/infiniteupgrades/logic/dto/UpgradeOutcome.java
package org.z2six.infiniteupgrades.logic.dto;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;

public final class UpgradeOutcome {
    public final boolean success;
    public final int fromLevel;
    public final int toLevel;
    /** Attribute deltas applied on success: attrId -> delta (sign already applied). */
    public final Map<ResourceLocation, Double> deltas;

    public UpgradeOutcome(boolean success, int fromLevel, int toLevel, Map<ResourceLocation, Double> deltas) {
        this.success = success;
        this.fromLevel = fromLevel;
        this.toLevel = toLevel;
        this.deltas = deltas;
    }
}
