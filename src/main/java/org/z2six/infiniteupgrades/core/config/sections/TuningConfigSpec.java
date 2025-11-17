// MainFile: src/main/java/org/z2six/infiniteupgrades/core/config/sections/TuningConfigSpec.java
package org.z2six.infiniteupgrades.core.config.sections;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;

import java.util.*;

/**
 * Global tuning for step % bumps by level + per-stat final multipliers.
 *
 * This defines:
 *  - A simple “base” percent per upgrade and optional tier bumps at specific levels.
 *  - A new map of FINAL MULTIPLIERS (id -> double), applied at the very end to the step
 *    for a specific attribute/stat id. This lets server owners scale any single stat
 *    (including custom ones like "infiniteupgrades:block_speed") without changing core math.
 *
 * Order of precedence (for step on a given attribute):
 *   1) Attribute rule per-level override (strongest)
 *   2) Attribute rule defaultStep
 *   3) (Optional) this tuning.stepPercent + bonusSteps
 *   4) Ritual multipliers
 *   5) **NEW** finalMultipliers[id]  ← applied LAST to the computed step
 *
 * Notes:
 *  - If a stat id is not present in finalMultipliers, it uses 1.0 (no change).
 *  - Ids must be fully-qualified ResourceLocations, e.g.:
 *        minecraft:generic.attack_damage=0.90
 *        infiniteupgrades:block_speed=0.65
 */
public final class TuningConfigSpec {
    private static final Logger LOG = LogUtils.getLogger();

    public final ModConfigSpec.DoubleValue stepPercent;
    public final ModConfigSpec.ConfigValue<List<? extends String>> bonusStepsKV;

    // NEW: final per-stat multipliers (id=double), default 1.0 if missing
    public final ModConfigSpec.ConfigValue<List<? extends String>> finalMultipliersKV;

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

        finalMultipliersKV = B.comment(
                "Final per-stat multipliers applied at the very end of step calculation.",
                "Format: [\"<id>=<mult>\", ...] where <id> is a ResourceLocation (e.g. minecraft:generic.attack_damage).",
                "Examples:",
                "  - \"minecraft:generic.attack_damage=0.9\"      (reduce damage steps by 10%)",
                "  - \"infiniteupgrades:block_speed=0.5\"        (halve Block Speed steps)",
                "Omitted ids default to 1.0 (no change)."
        ).defineListAllowEmpty("finalMultipliers", List.of(
                // (no defaults; show examples above in comments)
        ), o -> o instanceof String);

        B.pop();
    }

    public static TuningConfigSpec define(ModConfigSpec.Builder B) {
        return new TuningConfigSpec(B);
    }

    public static final class Snapshot {
        public final double stepPercent;
        public final Map<Integer, Double> bonusSteps;

        // NEW: final per-stat multipliers (immutable)
        public final Map<ResourceLocation, Double> finalMultipliers;

        public Snapshot(double stepPercent,
                        Map<Integer, Double> bonusSteps,
                        Map<ResourceLocation, Double> finalMultipliers) {
            this.stepPercent = Math.max(0.0, stepPercent);
            this.bonusSteps = bonusSteps;
            this.finalMultipliers = finalMultipliers;
        }

        /** Percent increment (fraction) for *this* level-up (L -> L+1). */
        public double percentBonusForLevelUp(int currentLevel) {
            double inc = stepPercent;
            Double bump = bonusSteps.get(currentLevel);
            if (bump != null && bump > 0.0) inc += bump;
            return inc;
        }

        /** Total aggregated percent up to target level (for completeness). */
        public double totalPercentAtLevel(int targetLevel) {
            if (targetLevel <= 0) return 0.0;
            double total = 0.0;
            for (int l = 0; l < targetLevel; l++) {
                total += percentBonusForLevelUp(l);
            }
            return total;
        }

        /** Final multiplier for a stat id (ResourceLocation). */
        public double finalMultiplier(ResourceLocation id) {
            if (id == null) return 1.0;
            Double m = finalMultipliers.get(id);
            return (m != null && m > 0.0) ? m : 1.0;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                Math.max(0.0, stepPercent.get()),
                parseLevelDoubleMap(bonusStepsKV.get()),
                parseIdDoubleMap(finalMultipliersKV.get())
        );
    }

    // ---------------- Parsing helpers (local, defensive) ----------------

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

    private static Map<ResourceLocation, Double> parseIdDoubleMap(List<? extends String> lines) {
        Map<ResourceLocation, Double> out = new LinkedHashMap<>();
        if (lines == null) return Map.of();
        for (Object o : lines) {
            if (!(o instanceof String raw)) continue;
            String s = raw.trim();
            if (s.isEmpty()) continue;
            int eq = s.indexOf('=');
            if (eq <= 0 || eq >= s.length() - 1) continue;

            String idStr = s.substring(0, eq).trim();
            String vStr  = s.substring(eq + 1).trim();

            try {
                ResourceLocation id = ResourceLocation.parse(idStr);
                double mult = Double.parseDouble(vStr);
                if (mult <= 0.0) {
                    // Guard against non-positive multipliers; skip but log once
                    LOG.debug("[TuningConfigSpec] Ignoring non-positive final multiplier {} for id={}", vStr, idStr);
                    continue;
                }
                out.put(id, mult);
            } catch (Throwable t) {
                // Robust to malformed lines; skip and continue
                LOG.debug("[TuningConfigSpec] Failed parsing finalMultipliers entry '{}': {}", s, t.toString());
            }
        }
        return Map.copyOf(out);
    }
}
