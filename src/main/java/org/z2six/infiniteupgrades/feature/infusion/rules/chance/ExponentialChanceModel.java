// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/rules/chance/ExponentialChanceModel.java
package org.z2six.infiniteupgrades.feature.infusion.rules.chance;

import java.util.Map;

public final class ExponentialChanceModel implements ChanceModel {
    private final double start;
    private final double base;
    private final Map<Integer, Double> overrides;

    public ExponentialChanceModel(double start, double base, Map<Integer, Double> overrides) {
        this.start = clamp01(start);
        this.base = Math.max(0.0, Math.min(1.0, base));
        this.overrides = overrides == null ? Map.of() : overrides;
    }

    @Override
    public double chanceForNextLevel(int currentLevel) {
        Double ov = overrides.get(currentLevel);
        if (ov != null) return clamp01(ov);
        double c = start * Math.pow(base, currentLevel);
        return clamp01(c);
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
