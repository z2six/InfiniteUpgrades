// MainFile: src/main/java/org/z2six/infiniteupgrades/rules/chance/FlatDecrementChanceModel.java
package org.z2six.infiniteupgrades.rules.chance;

import java.util.Map;

public final class FlatDecrementChanceModel implements ChanceModel {
    private final double start;
    private final double decrement;
    private final Map<Integer, Double> overrides;

    public FlatDecrementChanceModel(double start, double decrement, Map<Integer, Double> overrides) {
        this.start = clamp01(start);
        this.decrement = Math.max(0.0, decrement);
        this.overrides = overrides == null ? Map.of() : overrides;
    }

    @Override
    public double chanceForNextLevel(int currentLevel) {
        Double ov = overrides.get(currentLevel);
        if (ov != null) return clamp01(ov);
        double c = start - currentLevel * decrement;
        return Math.max(0.0, Math.min(1.0, c));
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
