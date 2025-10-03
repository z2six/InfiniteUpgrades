package org.z2six.infiniteupgrades.core.config.sections;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Global tuning for step % bumps by level.
 *
 * This defines a simple “base” percent per upgrade and optional tier bumps at
 * specific levels. Attribute rules can still override per-attribute step sizes.
 *
 * Order of precedence (for step on a given attribute):
 *   1) Attribute rule per-level override (strongest)
 *   2) Attribute rule defaultStep
 *   3) (Only if your implementation consults this tuning) tuning.stepPercent + bonusSteps
 *      (This is a convenient global knob; attribute rules typically take priority.)
 *
 * Ritual multipliers are applied AFTER the step is resolved:
 *   final step = resolved_step × rituals.(angel|demon)StepMultiplier
 */
public final class TuningConfigSpec {

    public final ModConfigSpec.DoubleValue stepPercent;
    public final ModConfigSpec.ConfigValue<List<? extends String>> bonusStepsKV;

    private TuningConfigSpec(ModConfigSpec.Builder B) {
        B.push("tuning");

        stepPercent = B.comment(
                "Base percent per successful upgrade (fraction).",
                "Example: 0.05 = +5% per upgrade.",
                "If you are using per-attribute rules (recommended), those will usually override this."
        ).defineInRange("stepPercent", 0.01, 0.0, 10.0);

        bonusStepsKV = B.comment(
                "Extra bonus added at specific CURRENT levels (fraction). Format: \"level=bonus\".",
                "Example: [\"5=0.10\",\"10=0.10\"] → at +5 and +10, add +10% to that upgrade’s step."
        ).defineListAllowEmpty("bonusSteps", List.of(), o -> o instanceof String);

        B.pop();
    }

    public static TuningConfigSpec define(ModConfigSpec.Builder B) {
        return new TuningConfigSpec(B);
    }

    public static final class Snapshot {
        public final double stepPercent;
        public final Map<Integer, Double> bonusSteps;

        public Snapshot(double stepPercent, Map<Integer, Double> bonusSteps) {
            this.stepPercent = Math.max(0.0, stepPercent);
            this.bonusSteps = bonusSteps;
        }

        /** Percent increment (fraction) for *this* level-up (L -> L+1). */
        public double percentBonusForLevelUp(int currentLevel) {
            double inc = stepPercent;
            Double bump = bonusSteps.get(currentLevel);
            if (bump != null && bump > 0.0) inc += bump;
            return inc;
        }

        /** Aggregate percent up to target level (for completeness). */
        public double totalPercentAtLevel(int targetLevel) {
            if (targetLevel <= 0) return 0.0;
            double total = 0.0;
            for (int l = 0; l < targetLevel; l++) {
                total += percentBonusForLevelUp(l);
            }
            return total;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                Math.max(0.0, stepPercent.get()),
                parseLevelDoubleMap(bonusStepsKV.get())
        );
    }

    private static Map<Integer, Double> parseLevelDoubleMap(List<? extends String> lines) {
        Map<Integer, Double> out = new LinkedHashMap<>();
        if (lines == null) return out;
        for (Object o : lines) {
            if (!(o instanceof String s)) continue;
            String t = s.trim();
            if (t.isEmpty()) continue;
            int eq = t.indexOf('=');
            if (eq <= 0 || eq >= t.length() - 1) continue;
            try {
                int lvl = Integer.parseInt(t.substring(0, eq).trim());
                double val = Double.parseDouble(t.substring(eq + 1).trim());
                out.put(lvl, val);
            } catch (NumberFormatException ignored) {}
        }
        return Map.copyOf(out);
    }
}
