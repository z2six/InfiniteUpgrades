package org.z2six.infiniteupgrades.feature.infusion.logic;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of upgrade tuning. All math for chance and step lives here.
 * You’ll read from Config.TUNING at runtime.
 */
public final class UpgradeTuning {
    public final double startChance;   // e.g., 1.00
    public final double chanceStep;    // e.g., 0.05
    public final double minChance;     // e.g., 0.00
    public final double stepPercent;   // e.g., 0.05 (+5% per level)
    public final int maxLevel;         // e.g., 20

    /** Tier bumps: current level L -> bonus stepPercent (only applied for L->L+1). */
    public final Map<Integer, Double> bonusSteps;

    /** Chance overrides per current level L (overrides arithmetic chance for L->L+1). */
    public final Map<Integer, Double> levelChanceOverrides;

    public UpgradeTuning(double startChance,
                         double chanceStep,
                         double minChance,
                         double stepPercent,
                         int maxLevel,
                         Map<Integer, Double> bonusSteps,
                         Map<Integer, Double> levelChanceOverrides) {
        this.startChance = clamp01(startChance);
        this.chanceStep = Math.max(0.0, chanceStep);
        this.minChance = clamp01(minChance);
        this.stepPercent = Math.max(0.0, stepPercent);
        this.maxLevel = Math.max(0, maxLevel);
        this.bonusSteps = bonusSteps == null ? Collections.emptyMap() : bonusSteps;
        this.levelChanceOverrides = levelChanceOverrides == null ? Collections.emptyMap() : levelChanceOverrides;
    }

    public static UpgradeTuning defaults() {
        return new UpgradeTuning(1.0, 0.05, 0.0, 0.05, 20,
                Collections.emptyMap(), Collections.emptyMap());
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * Chance to go from level L to L+1.
     * If override exists for L, returns override. Else arithmetic clamped at minChance.
     */
    public double chanceForNextLevel(int currentLevel) {
        Double ov = levelChanceOverrides.get(currentLevel);
        if (ov != null) return clamp01(ov);
        double c = startChance - currentLevel * chanceStep;
        return Math.max(minChance, clamp01(c));
    }

    /**
     * Percent MULTIPLIER to apply for the *new* level’s attribute scaling.
     * Base is (level+1) * stepPercent, but if a bonus is configured at current L,
     * we add that bonus to the per-level step for this increment only.
     *
     * Return value is a fraction (e.g. 0.10 for +10%).
     */
    public double percentBonusForLevelUp(int currentLevel) {
        // Default “step” for a single increment
        double increment = stepPercent;

        // If configured, add tier bump to this single increment (e.g., +0.10)
        Double bump = bonusSteps.get(currentLevel);
        if (bump != null) increment += Math.max(0.0, bump);

        return increment;
    }

    /**
     * Total percent to apply at *target level* (for preview side).
     * If you want the classic “+5% per level accumulated”, use: targetLevel * stepPercent,
     * with tier bumps added for each step you simulate.
     *
     * This helper accumulates properly from 0..(targetLevel-1).
     */
    public double totalPercentAtLevel(int targetLevel) {
        if (targetLevel <= 0) return 0.0;
        double total = 0.0;
        for (int l = 0; l < targetLevel; l++) {
            total += percentBonusForLevelUp(l);
        }
        return total;
    }

    @Override
    public String toString() {
        return "UpgradeTuning{" +
                "startChance=" + startChance +
                ", chanceStep=" + chanceStep +
                ", minChance=" + minChance +
                ", stepPercent=" + stepPercent +
                ", maxLevel=" + maxLevel +
                ", bonusSteps=" + Objects.toString(bonusSteps) +
                ", levelChanceOverrides=" + Objects.toString(levelChanceOverrides) +
                '}';
    }
}
