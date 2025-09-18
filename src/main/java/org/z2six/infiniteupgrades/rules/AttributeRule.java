// MainFile: src/main/java/org/z2six/infiniteupgrades/rules/AttributeRule.java
package org.z2six.infiniteupgrades.rules;

import net.minecraft.resources.ResourceLocation;
import org.z2six.infiniteupgrades.config.UpgradeServerConfig.Direction;
import org.z2six.infiniteupgrades.config.UpgradeServerConfig.StepType;

import java.util.Map;

/**
 * Immutable per-attribute rule used by RuleBook/UpgradeService.
 */
public final class AttributeRule {
    public final ResourceLocation id;
    public final boolean enabled;
    public final int weight;
    public final Direction direction;
    public final StepType stepType;
    public final double defaultStep;
    public final Map<Integer, Double> perLevelOverrides;
    public final double capMin;
    public final double capMax;
    public final boolean applyToMagnitude;
    public final double rounding; // 0 = none

    public AttributeRule(ResourceLocation id, boolean enabled, int weight, Direction direction,
                         StepType stepType, double defaultStep, Map<Integer, Double> perLevelOverrides,
                         double capMin, double capMax, boolean applyToMagnitude, double rounding) {
        this.id = id;
        this.enabled = enabled;
        this.weight = Math.max(0, weight);
        this.direction = direction;
        this.stepType = stepType;
        this.defaultStep = defaultStep;
        this.perLevelOverrides = perLevelOverrides;
        this.capMin = capMin;
        this.capMax = capMax;
        this.applyToMagnitude = applyToMagnitude;
        this.rounding = Math.max(0.0, rounding);
    }

    /** Step to use for *this* increment at current level L -> L+1. */
    public double stepForLevel(int currentLevel) {
        Double ov = perLevelOverrides.get(currentLevel);
        return ov != null ? ov : defaultStep;
    }

    /** Applies a single-level step to value, respecting direction, type, caps, and rounding. */
    public double applyOnce(double currentValue, int currentLevel) {
        double step = stepForLevel(currentLevel);
        double result = currentValue;

        switch (stepType) {
            case PERCENT -> {
                double magnitude = applyToMagnitude ? Math.abs(currentValue) : currentValue;
                double delta = magnitude * step;
                result = (direction == Direction.INCREASE) ? currentValue + delta : currentValue - delta;
            }
            case ADDITIVE -> {
                result = (direction == Direction.INCREASE) ? currentValue + step : currentValue - step;
            }
        }

        if (capMin <= capMax) {
            result = Math.max(capMin, Math.min(capMax, result));
        }
        if (rounding > 0.0) {
            result = Math.round(result / rounding) * rounding;
        }
        return result;
    }
}
