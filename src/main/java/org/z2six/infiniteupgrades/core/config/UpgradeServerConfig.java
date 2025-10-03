// File: src/main/java/org/z2six/infiniteupgrades/core/config/UpgradeServerConfig.java
package org.z2six.infiniteupgrades.core.config;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.config.sections.AttributesConfigSpec;
import org.z2six.infiniteupgrades.core.config.sections.ChanceConfigSpec;
import org.z2six.infiniteupgrades.core.config.sections.GeneralConfigSpec;
import org.z2six.infiniteupgrades.core.config.sections.ReputationConfigSpec;
import org.z2six.infiniteupgrades.core.config.sections.RitualsConfigSpec;
import org.z2six.infiniteupgrades.core.config.sections.SoulsConfigSpec;
import org.z2six.infiniteupgrades.core.config.sections.SoulsDropModel;
import org.z2six.infiniteupgrades.core.config.sections.TuningConfigSpec;

import java.util.*;

import static org.z2six.infiniteupgrades.core.config.ConfigParsing.parseLevelDoubleMap;

/**
 * SERVER-side authoritative config orchestrator for upgrades (+ souls).
 *
 * Delegates each section to its own *ConfigSpec class. Public API and logic remain intact.
 */
public final class UpgradeServerConfig {
    private static final Logger LOG = LogUtils.getLogger();

    public enum UpgradeMode { RANDOM, ALL }
    public enum ChanceModelType { FLAT_DECREMENT, EXPONENTIAL }
    public enum Direction { INCREASE, DECREASE }
    public enum StepType { PERCENT, ADDITIVE }

    // ---- SPEC ----
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    // Section specs
    private static GeneralConfigSpec    GENERAL_SPEC;
    private static ChanceConfigSpec     CHANCE_SPEC;
    private static RitualsConfigSpec    RITUALS_SPEC;
    private static ReputationConfigSpec REPUTATION_SPEC;
    private static AttributesConfigSpec ATTR_SPEC;
    private static SoulsConfigSpec      SOULS_SPEC;
    private static TuningConfigSpec     TUNING_SPEC;   // preview stepPercent + bonusSteps

    public static final ModConfigSpec SPEC;

    static {
        // Attach all sections to the single builder (key paths preserved)
        GENERAL_SPEC    = GeneralConfigSpec.define(B);
        CHANCE_SPEC     = ChanceConfigSpec.define(B);
        TUNING_SPEC     = TuningConfigSpec.define(B);
        RITUALS_SPEC    = RitualsConfigSpec.define(B);
        REPUTATION_SPEC = ReputationConfigSpec.define(B);
        ATTR_SPEC       = AttributesConfigSpec.define(B);
        SOULS_SPEC      = SoulsConfigSpec.define(B);

        SPEC = B.build();
    }

    private UpgradeServerConfig() {}

    // ---- SNAPSHOT ----
    public static final class Snapshot {
        public final UpgradeMode upgradeMode;
        public final int maxLevel;

        public final ChanceModelType chanceModel;
        public final double startChance;
        public final double decrementPerLevel;
        public final double minChance;           // minimum floor for chance models
        public final double exponentialBase;
        public final Map<Integer, Double> chanceOverrides;

        public final double angelStepMult;
        public final double demonStepMult;
        public final double infuseDelaySeconds;  // NEW: server-authoritative infuse timer

        // Unified reputation (no cross-coupling in unified model)
        public final int repMax;
        public final double repDeltaSuccess;
        public final double repDeltaFail;
        public final double repBonusPerPoint;
        public final double repBonusClamp;

        public final List<AttributeRuleConfig> attributes; // defaults + dynamic

        public final SoulsConfig souls;

        // server-authoritative preview tuning (client preview reads from this)
        public final TuningConfigSpec.Snapshot tuning;

        private Snapshot(UpgradeMode mode, int maxLevel,
                         ChanceModelType cm, double start, double dec, double min,
                         double expBase, Map<Integer, Double> overrides,
                         double angelMult, double demonMult, double infuseDelaySeconds,
                         int repMax, double repSucc, double repFail,
                         double repBonusPerPoint, double repBonusClamp,
                         List<AttributeRuleConfig> attrs,
                         SoulsConfig souls,
                         TuningConfigSpec.Snapshot tuning) {
            this.upgradeMode = mode;
            this.maxLevel = maxLevel;

            this.chanceModel = cm;
            this.startChance = start;
            this.decrementPerLevel = dec;
            this.minChance = min;
            this.exponentialBase = expBase;
            this.chanceOverrides = overrides;

            this.angelStepMult = angelMult;
            this.demonStepMult = demonMult;
            this.infuseDelaySeconds = Math.max(0.0, infuseDelaySeconds);

            this.repMax = repMax;
            this.repDeltaSuccess = repSucc;
            this.repDeltaFail = repFail;
            this.repBonusPerPoint = repBonusPerPoint;
            this.repBonusClamp = repBonusClamp;

            this.attributes = attrs;
            this.souls = souls;
            this.tuning = tuning;
        }

        /** Client preview helper: percent (fraction) for L->L+1 according to server tuning. */
        public double percentBonusForLevelUp(int currentLevel) {
            return tuning != null ? tuning.percentBonusForLevelUp(currentLevel) : 0.05;
        }
    }

    public static final class SoulsConfig {
        public final boolean enabled;
        public final double dropChance;

        // selection model
        public final SoulsDropModel dropModel;

        // RATIO model fields (legacy)
        public final double hpToSoulsRatio;
        public final int    minUnitsForDrop;
        public final int    lifetimeSeconds;
        public final Map<String, Integer> tierUnits; // key = TIER name

        // HP_THRESHOLDS model fields
        public final Map<String, Double> tierMinHearts; // key = TIER name, value = min hearts

        public final boolean allowPvP;
        public final Set<ResourceLocation> whitelist;
        public final Set<ResourceLocation> blacklist;

        // Light controls
        public final boolean spawnLights;
        public final int     lightRadiusBlocks;

        public SoulsConfig(boolean enabled,
                           double dropChance,
                           SoulsDropModel dropModel,
                           double hpToSoulsRatio,
                           int minUnitsForDrop,
                           int lifetimeSeconds,
                           Map<String, Integer> tierUnits,
                           Map<String, Double> tierMinHearts,
                           boolean allowPvP,
                           Set<ResourceLocation> whitelist,
                           Set<ResourceLocation> blacklist,
                           boolean spawnLights,
                           int lightRadiusBlocks) {
            this.enabled = enabled;
            this.dropChance = dropChance;
            this.dropModel = dropModel;
            this.hpToSoulsRatio = hpToSoulsRatio;
            this.minUnitsForDrop = minUnitsForDrop;
            this.lifetimeSeconds = lifetimeSeconds;
            this.tierUnits = tierUnits;
            this.tierMinHearts = tierMinHearts;
            this.allowPvP = allowPvP;
            this.whitelist = whitelist;
            this.blacklist = blacklist;
            this.spawnLights = spawnLights;
            this.lightRadiusBlocks = Math.max(0, lightRadiusBlocks);
        }

        public int tierUnitsOrDefault(String tierName, int def) {
            Integer v = tierUnits.get(tierName);
            return v != null && v > 0 ? v : def;
        }
    }

    public static final class AttributeRuleConfig {
        public final ResourceLocation id; // attribute RL
        public final boolean enabled;
        public final int weight;
        public final Direction direction;
        public final StepType stepType;
        public final double defaultStep;
        public final Map<Integer, Double> perLevelOverrides;
        public final double capMin;
        public final double capMax;
        public final boolean applyToMagnitude;
        public final double rounding;

        public AttributeRuleConfig(ResourceLocation id, boolean enabled, int weight, Direction direction,
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
    }

    /** Builds a live snapshot (defensive, logs, never throws). */
    public static Snapshot snapshot() {
        try {
            // General
            GeneralConfigSpec.Snapshot gs = GENERAL_SPEC.snapshot();

            // Chance
            ChanceConfigSpec.Snapshot cs = CHANCE_SPEC.snapshot();

            // Tuning (server preview)
            TuningConfigSpec.Snapshot ts = TUNING_SPEC.snapshot();

            // Rituals
            RitualsConfigSpec.Snapshot rs = RITUALS_SPEC.snapshot();

            // Reputation (unified)
            ReputationConfigSpec.Snapshot reps = REPUTATION_SPEC.snapshot();

            // Attributes
            AttributesConfigSpec.Snapshot as = ATTR_SPEC.snapshot();

            // Souls
            SoulsConfigSpec.Snapshot ss = SOULS_SPEC.snapshot();
            SoulsConfig souls = new SoulsConfig(
                    ss.enabled,
                    ss.dropChance,
                    ss.dropModel,
                    ss.hpToSoulsRatio,
                    ss.minUnitsForDrop,
                    ss.lifetimeSeconds,
                    ss.tierUnits,
                    ss.tierMinHearts,
                    ss.allowPvP,
                    ss.whitelist,
                    ss.blacklist,
                    ss.spawnLights,
                    ss.lightRadiusBlocks
            );

            return new Snapshot(
                    gs.upgradeMode,
                    gs.maxLevel,
                    cs.model,
                    cs.startChance,
                    cs.decrementPerLevel,
                    cs.minChance,
                    cs.exponentialBase,
                    cs.overrides,
                    rs.angelStepMult,
                    rs.demonStepMult,
                    rs.infuseDelaySeconds,
                    reps.repMax,
                    reps.repDeltaSuccess,
                    reps.repDeltaFail,
                    reps.repBonusPerPoint,
                    reps.repBonusClamp,
                    as.rules,
                    souls,
                    ts
            );
        } catch (Throwable t) {
            LOG.error("[UpgradeServerConfig] snapshot() failed; returning hardcoded defaults: {}", t.toString());
            // Fallback mirrors original values closely (with unified reputation).
            return new Snapshot(
                    UpgradeMode.RANDOM,
                    20,
                    ChanceModelType.FLAT_DECREMENT,
                    1.0, 0.05, 0.0,     // start, decrement, minChance
                    0.95,               // exponentialBase
                    parseLevelDoubleMap(List.of("1=1.0", "2=0.95"), "chance.overrides"),
                    1.0, 1.5,           // angel, demon
                    3.0,                // infuseDelaySeconds (default)
                    100, 0.1, 0.0,      // repMax, deltaSuccess, deltaFail
                    0.001, 0.20,        // bonusPerPoint, bonusClamp
                    List.of(
                            new AttributeRuleConfig(ResourceLocation.parse("minecraft:generic.attack_damage"), true, 1, Direction.INCREASE, StepType.PERCENT, 0.05, Map.of(), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, 0.0),
                            new AttributeRuleConfig(ResourceLocation.parse("minecraft:generic.attack_speed"),  true, 1, Direction.INCREASE, StepType.PERCENT, 0.05, Map.of(), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, 0.0),
                            new AttributeRuleConfig(ResourceLocation.parse("minecraft:generic.armor"),         true, 1, Direction.INCREASE, StepType.PERCENT, 0.05, Map.of(), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, 0.0),
                            new AttributeRuleConfig(ResourceLocation.parse("minecraft:generic.armor_toughness"), true, 1, Direction.INCREASE, StepType.PERCENT, 0.05, Map.of(), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, 0.0),
                            new AttributeRuleConfig(ResourceLocation.parse("minecraft:generic.knockback_resistance"), true, 1, Direction.INCREASE, StepType.PERCENT, 0.05, Map.of(), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, 0.0)
                    ),
                    new SoulsConfig(
                            true,                      // enabled
                            1.0,                       // dropChance
                            SoulsDropModel.HP_THRESHOLDS, // dropModel
                            0.75,                      // hpToSoulsRatio
                            1,                         // minUnitsForDrop
                            30,                        // lifetimeSeconds
                            Map.of("SMALL",1,"MEDIUM",4,"LARGE",8,"EXTRA_LARGE",16), // tierUnits
                            Map.of("SMALL",0.0,"MEDIUM",10.0,"LARGE",20.0,"EXTRA_LARGE",40.0), // tierMinHearts
                            false,                     // allowPvP
                            Set.of(),                  // whitelist
                            Set.of(),                  // blacklist
                            true,                      // spawnLights (default)
                            3                          // lightRadiusBlocks (default)
                    ),
                    new TuningConfigSpec.Snapshot(0.05, Map.of()) // stepPercent, bonusSteps
            );
        }
    }

    public static void onServerConfigReload(ModConfigEvent event) {
        LOG.debug("[UpgradeServerConfig] onServerConfigReload: {}", event.getConfig().getFileName());
    }

    // ---- Name color selection (delegates to General section to keep API stable) -----------------

    public static ChatFormatting nameColorForLevel(int level) {
        return GENERAL_SPEC.nameColorForLevel(level);
    }

    public static int resolveSuffixColor(int level) {
        return GENERAL_SPEC.resolveSuffixColor(level);
    }
}
