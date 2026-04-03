// File: src/main/java/org/z2six/infiniteupgrades/core/config/sections/ChanceConfigSpec.java
package org.z2six.infiniteupgrades.core.config.sections;

import net.minecraftforge.common.ForgeConfigSpec;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig.ChanceModelType;

import java.util.List;
import java.util.Map;

import static org.z2six.infiniteupgrades.core.config.ConfigParsing.clamp01;
import static org.z2six.infiniteupgrades.core.config.ConfigParsing.parseLevelDoubleMap;

/**
 * User-friendly infusion success chance.
 *
 * You control how the base success chance changes as the item's +level increases.
 * (Reputation bonuses/penalties are applied on top elsewhere; see reputation.*)
 */
public final class ChanceConfigSpec {

    public final ForgeConfigSpec.EnumValue<ChanceModelType> model;
    public final ForgeConfigSpec.DoubleValue startChance;
    public final ForgeConfigSpec.DoubleValue decrementPerLevel;   // FLAT model
    public final ForgeConfigSpec.DoubleValue minChance;           // FLAT clamp
    public final ForgeConfigSpec.DoubleValue exponentialBase;     // EXP model
    public final ForgeConfigSpec.ConfigValue<List<? extends String>> overridesKV;

    private ChanceConfigSpec(ForgeConfigSpec.Builder B) {
        B.push("chance");

        model = B.comment(
                "Which curve to use for BASE success chance by level:",
                " - FLAT_DECREMENT: startChance − (decrementPerLevel × currentLevel), clamped at minChance.",
                " - EXP: startChance × (exponentialBase ^ currentLevel).",
                "",
                "Tip: You can also set exact values per level via 'overrides' below."
        ).defineEnum("model", ChanceModelType.FLAT_DECREMENT);

        startChance = B.comment(
                        "Chance for the first upgrade (+0 → +1). Range: 0..1",
                        "Examples: 1.0 = 100%, 0.75 = 75%, 0.50 = 50%")
                .defineInRange("startChance", 1.0, 0.0, 1.0);

        decrementPerLevel = B.comment(
                        "[FLAT_DECREMENT only] How much the chance drops each existing level.",
                        "Example: startChance=1.0, decrement=0.05 →",
                        "  +0→+1: 100%, +1→+2: 95%, +2→+3: 90%, ... then clamped at minChance.")
                .defineInRange("decrementPerLevel", 0.05, 0.0, 1.0);

        minChance = B.comment(
                        "[FLAT_DECREMENT only] Chance cannot drop below this floor.",
                        "Example: 0.10 means it will never be below 10%.")
                .defineInRange("minChance", 0.0, 0.0, 1.0);

        exponentialBase = B.comment(
                        "[EXP only] The multiplier per current level.",
                        "Example: startChance=1.0, base=0.95 →",
                        "  +0→+1: 100%, +1→+2: 95%, +2→+3: ~90.25%, etc.")
                .defineInRange("exponentialBase", 0.95, 0.0, 1.0);

        overridesKV = B.comment(
                "Exact per-level overrides. Format: \"level=fraction\" (0..1).",
                "These replace the model for that level if present.",
                "Examples: [\"1=1.0\",\"2=0.95\",\"10=0.25\"]"
        ).defineListAllowEmpty("overrides", List.of("1=1.0", "2=0.95"), o -> o instanceof String);

        B.pop();
    }

    public static ChanceConfigSpec define(ForgeConfigSpec.Builder B) {
        return new ChanceConfigSpec(B);
    }

    public static final class Snapshot {
        public final ChanceModelType model;
        public final double startChance;
        public final double decrementPerLevel;
        public final double minChance;
        public final double exponentialBase;
        public final Map<Integer, Double> overrides;

        public Snapshot(ChanceModelType model, double startChance, double dec, double minChance,
                        double expBase, Map<Integer, Double> overrides) {
            this.model = model;
            this.startChance = startChance;
            this.decrementPerLevel = dec;
            this.minChance = minChance;
            this.exponentialBase = expBase;
            this.overrides = overrides;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                model.get(),
                clamp01(startChance.get()),
                clamp01(decrementPerLevel.get()),
                clamp01(minChance.get()),
                clamp01(exponentialBase.get()),
                parseLevelDoubleMap(overridesKV.get(), "chance.overrides")
        );
    }
}
