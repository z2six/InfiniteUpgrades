// MainFile: src/main/java/org/z2six/infiniteupgrades/core/config/UpgradeServerConfig.java
package org.z2six.infiniteupgrades.core.config;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.event.config.ModConfigEvent;
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
 * File: src/main/java/org/z2six/infiniteupgrades/core/config/UpgradeServerConfig.java
 *
 * SERVER-side authoritative config orchestrator for upgrades (+ souls).
 *
 * Delegates each section to its own *ConfigSpec class. Public API and logic remain intact.
 *
 * NOTE: Client SFX volumes have been moved into UpgradeClientConfig (CLIENT).
 */
public final class UpgradeServerConfig {
    private static final Logger LOG = LogUtils.getLogger();

    public enum UpgradeMode { RANDOM, ALL }
    public enum ChanceModelType { FLAT_DECREMENT, EXPONENTIAL }
    public enum Direction { INCREASE, DECREASE }
    public enum StepType { PERCENT, ADDITIVE }

    // ---- SPEC ----
    private static final ForgeConfigSpec.Builder B = new ForgeConfigSpec.Builder();

    // Section specs
    private static GeneralConfigSpec    GENERAL_SPEC;
    private static ChanceConfigSpec     CHANCE_SPEC;
    private static RitualsConfigSpec    RITUALS_SPEC;
    private static ReputationConfigSpec REPUTATION_SPEC;
    private static AttributesConfigSpec ATTR_SPEC;
    private static SoulsConfigSpec      SOULS_SPEC;
    private static TuningConfigSpec     TUNING_SPEC;   // base step + bonuses + FINAL MULTIPLIERS

    public static final ForgeConfigSpec SPEC;

    static {
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
        public final double minChance;
        public final double exponentialBase;
        public final Map<Integer, Double> chanceOverrides;

        public final double angelStepMult;
        public final double demonStepMult;
        public final double infuseDelaySeconds;

        public final int repMax;
        public final double repDeltaSuccess;
        public final double repDeltaFail;
        public final double repBonusPerPoint;
        public final double repBonusClamp;

        public final List<AttributeRuleConfig> attributes;

        public final SoulsConfig souls;

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

        /** Percent bonus for the upcoming level-up (L → L+1). */
        public double percentBonusForLevelUp(int currentLevel) {
            return tuning != null ? tuning.percentBonusForLevelUp(currentLevel) : 0.05;
        }

        /** NEW: convenience delegator to tuning.finalMultiplier(id). */
        public double finalMultiplier(ResourceLocation id) {
            return (tuning != null) ? tuning.finalMultiplier(id) : 1.0;
        }
    }

    // ---------------------- SoulsConfig WITH NEW FIELDS ----------------------
    public static final class SoulsConfig {
        public final boolean enabled;
        public final double dropChance;
        public final SoulsDropModel dropModel;

        public final double hpToSoulsRatio;
        public final int    minUnitsForDrop;
        public final int    lifetimeSeconds;
        public final Map<String, Integer> tierUnits;

        public final Map<String, Double> tierMinHearts;

        public final boolean allowPvP;
        public final Set<ResourceLocation> whitelist;
        public final Set<ResourceLocation> blacklist;

        public final boolean spawnLights;
        public final int     lightRadiusBlocks;
        public final int     collectRangeBlocks;

        // ------------------ NEW FIELDS FOR UPGRADE COST ------------------
        public final double upgradeBaseCost;
        public final double upgradeExponentialBase;
        public final double upgradeExponentialScale;
        public final Map<Integer,Integer> upgradeCostOverrides;
        // -----------------------------------------------------------------

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
                           int lightRadiusBlocks,
                           int collectRangeBlocks,

                           // NEW
                           double upgradeBaseCost,
                           double upgradeExponentialBase,
                           double upgradeExponentialScale,
                           Map<Integer,Integer> upgradeCostOverrides) {

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
            this.collectRangeBlocks = Math.max(1, collectRangeBlocks);

            this.upgradeBaseCost = upgradeBaseCost;
            this.upgradeExponentialBase = upgradeExponentialBase;
            this.upgradeExponentialScale = upgradeExponentialScale;
            this.upgradeCostOverrides = upgradeCostOverrides;
        }

        // Needed by SoulOrbEntity
        public int tierUnitsOrDefault(String tierName, int def) {
            Integer v = tierUnits.get(tierName);
            return v != null && v > 0 ? v : def;
        }
    }

    public static final class AttributeRuleConfig {
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

    // ------------------ BUILD SNAPSHOT ------------------
    public static Snapshot snapshot() {
        try {
            GeneralConfigSpec.Snapshot gs = GENERAL_SPEC.snapshot();
            ChanceConfigSpec.Snapshot cs = CHANCE_SPEC.snapshot();
            TuningConfigSpec.Snapshot ts = TUNING_SPEC.snapshot();
            RitualsConfigSpec.Snapshot rs = RITUALS_SPEC.snapshot();
            ReputationConfigSpec.Snapshot reps = REPUTATION_SPEC.snapshot();
            AttributesConfigSpec.Snapshot as = ATTR_SPEC.snapshot();

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
                    ss.lightRadiusBlocks,
                    ss.collectRangeBlocks,

                    // NEW
                    ss.upgradeBaseCost,
                    ss.upgradeExponentialBase,
                    ss.upgradeExponentialScale,
                    ss.upgradeCostOverrides
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
            LOG.error("[UpgradeServerConfig] snapshot() failed: {}", t.toString());

            return new Snapshot(
                    UpgradeMode.RANDOM,
                    20,
                    ChanceModelType.FLAT_DECREMENT,
                    1.0, 0.05, 0.0,
                    0.95,
                    parseLevelDoubleMap(List.of("1=1.0","2=0.95"), "chance.overrides"),
                    1.0, 1.5,
                    3.0,
                    100, 0.1, 0.0,
                    0.001, 0.20,
                    List.of(),
                    new SoulsConfig(
                            true,
                            1.0,
                            org.z2six.infiniteupgrades.core.config.sections.SoulsDropModel.HP_THRESHOLDS,
                            0.75,
                            1,
                            30,
                            Map.of("SMALL",1,"MEDIUM",4,"LARGE",8,"EXTRA_LARGE",16),
                            Map.of("SMALL",0.0,"MEDIUM",10.0,"LARGE",20.0,"EXTRA_LARGE",40.0),
                            false,
                            Set.of(),
                            Set.of(),
                            true,
                            3,
                            6,
                            10.0,      // fallback upgradeBaseCost
                            1.1,       // fallback exponentialBase
                            1.0,       // fallback scale
                            Map.of()   // overrides
                    ),
                    // Fallback tuning (includes no final multipliers; equivalent to all 1.0)
                    new TuningConfigSpec.Snapshot(0.05, Map.of(), Map.of())
            );
        }
    }

    public static void onServerConfigReload(ModConfigEvent event) {
        LOG.debug("[UpgradeServerConfig] onServerConfigReload: {}", event.getConfig().getFileName());
    }

    public static ChatFormatting nameColorForLevel(int level) {
        return GENERAL_SPEC.nameColorForLevel(level);
    }

    public static int resolveSuffixColor(int level) {
        return GENERAL_SPEC.resolveSuffixColor(level);
    }
}
