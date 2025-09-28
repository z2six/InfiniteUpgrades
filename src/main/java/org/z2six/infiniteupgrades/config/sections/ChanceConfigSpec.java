// File: src/main/java/org/z2six/infiniteupgrades/config/sections/ChanceConfigSpec.java
package org.z2six.infiniteupgrades.config.sections;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.z2six.infiniteupgrades.config.UpgradeServerConfig.ChanceModelType;

import java.util.List;
import java.util.Map;

import static org.z2six.infiniteupgrades.config.parsing.ConfigParsing.clamp01;
import static org.z2six.infiniteupgrades.config.parsing.ConfigParsing.parseLevelDoubleMap;

/** Chance section: model + numeric knobs + overrides. */
public final class ChanceConfigSpec {

    public final ModConfigSpec.EnumValue<ChanceModelType> model;
    public final ModConfigSpec.DoubleValue startChance;
    public final ModConfigSpec.DoubleValue decrementPerLevel;   // for FLAT
    public final ModConfigSpec.DoubleValue minChance;           // NEW: clamp floor for FLAT
    public final ModConfigSpec.DoubleValue exponentialBase;     // for EXP
    public final ModConfigSpec.ConfigValue<List<? extends String>> overridesKV;

    private ChanceConfigSpec(ModConfigSpec.Builder B) {
        B.push("chance");
        model = B.comment("Chance model type.")
                .defineEnum("model", ChanceModelType.FLAT_DECREMENT);
        startChance = B.comment("Starting chance (0..1) for +0->+1.")
                .defineInRange("startChance", 1.0, 0.0, 1.0);
        decrementPerLevel = B.comment("Chance decrement per existing level (FLAT model).")
                .defineInRange("decrementPerLevel", 0.05, 0.0, 1.0);
        minChance = B.comment("Minimum clamped chance (FLAT model).")
                .defineInRange("minChance", 0.0, 0.0, 1.0);
        exponentialBase = B.comment("Exponential base for per-level chance (EXP model). Example: 0.95")
                .defineInRange("exponentialBase", 0.95, 0.0, 1.0);
        overridesKV = B.comment("Chance overrides per current level, format: \"level=chance\".")
                .defineListAllowEmpty("overrides", List.of("1=1.0", "2=0.95"), o -> o instanceof String);
        B.pop();
    }

    public static ChanceConfigSpec define(ModConfigSpec.Builder B) {
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
