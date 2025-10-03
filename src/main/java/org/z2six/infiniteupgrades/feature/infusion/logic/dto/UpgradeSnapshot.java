// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/logic/dto/UpgradeSnapshot.java
package org.z2six.infiniteupgrades.feature.infusion.logic.dto;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class UpgradeSnapshot {
    public static final class Line {
        public final ResourceLocation attributeId;
        public final String displayName;
        public final double base;    // current value
        public final double current; // same as base for v1 (placeholder for future composites)
        public final double max;     // projected cap at max level

        public Line(ResourceLocation id, String displayName, double base, double current, double max) {
            this.attributeId = id;
            this.displayName = displayName;
            this.base = base;
            this.current = current;
            this.max = max;
        }
    }

    public final int currentLevel;
    public final int maxLevel;
    public final int chancePermille;
    public final List<Line> lines;
    public final ItemStack preview; // optional convenience

    public UpgradeSnapshot(int currentLevel, int maxLevel, int chancePermille, List<Line> lines, ItemStack preview) {
        this.currentLevel = currentLevel;
        this.maxLevel = maxLevel;
        this.chancePermille = chancePermille;
        this.lines = lines;
        this.preview = preview;
    }
}
