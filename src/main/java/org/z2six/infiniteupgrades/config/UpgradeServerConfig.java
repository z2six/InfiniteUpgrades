// MainFile: src/main/java/org/z2six/infiniteupgrades/config/UpgradeServerConfig.java
package org.z2six.infiniteupgrades.config;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.*;

/**
 * SERVER-side authoritative config for upgrades.
 *
 * Structure:
 * - general:
 *     maxLevel: int
 *     upgradeMode: "RANDOM" | "ALL"
 * - chance:
 *     model: "FLAT_DECREMENT" | "EXPONENTIAL"
 *     startChance: double (0..1)
 *     decrementPerLevel: double (0..1)   [for FLAT]
 *     exponentialBase: double (0..1)     [for EXPONENTIAL, e.g. 0.95]
 *     overrides: list of "level=chance"  (applies at current level L for L->L+1)
 * - nameSuffix:
 *     tiers: list of "min-max:#RRGGBB" (color per +N range)
 * - attributes:
 *     each attribute gets its own section under attributes.<namespace>.<group>.<name>.* with:
 *       enabled: boolean
 *       weight: int
 *       direction: "INCREASE"|"DECREASE"
 *       stepType: "PERCENT"|"ADDITIVE"
 *       defaultStep: double
 *       perLevelOverrides: list of "level=step"
 *       capMin: double (optional)
 *       capMax: double (optional)
 *       applyToMagnitude: boolean  (true for attack_speed)
 *       rounding: double (e.g., 0.01, 0.1)  (0 = no rounding)
 *
 * Notes:
 * - We ship with sane defaults for 5 vanilla attributes.
 * - Parsing is defensive; we log and continue.
 */
public final class UpgradeServerConfig {
    private static final Logger LOG = LogUtils.getLogger();

    public enum UpgradeMode { RANDOM, ALL }
    public enum ChanceModelType { FLAT_DECREMENT, EXPONENTIAL }
    public enum Direction { INCREASE, DECREASE }
    public enum StepType { PERCENT, ADDITIVE }

    // ---- SPEC ----
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    // general
    public static final ModConfigSpec.IntValue GENERAL_MAX_LEVEL;
    public static final ModConfigSpec.EnumValue<UpgradeMode> GENERAL_MODE;

    // chance
    public static final ModConfigSpec.EnumValue<ChanceModelType> CHANCE_MODEL;
    public static final ModConfigSpec.DoubleValue CHANCE_START;
    public static final ModConfigSpec.DoubleValue CHANCE_DECREMENT;   // for FLAT
    public static final ModConfigSpec.DoubleValue CHANCE_EXP_BASE;    // for EXP
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CHANCE_OVERRIDES;

    // nameSuffix tiers (server controls color of " +N")
    public static final ModConfigSpec.ConfigValue<List<? extends String>> NAME_SUFFIX_TIERS;

    // attributes.<namespace>.<group>.<name> blocks (5 defaults)
    public static final AttributeSection ATTACK_DAMAGE =
            new AttributeSection(B, "minecraft", "generic", "attack_damage",
                    true, 10, Direction.INCREASE, StepType.PERCENT, 0.05,
                    List.of(), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                    false, 0.0);

    public static final AttributeSection ATTACK_SPEED =
            new AttributeSection(B, "minecraft", "generic", "attack_speed",
                    true, 10, Direction.DECREASE, StepType.PERCENT, 0.05,
                    List.of(), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                    true, 0.0);

    public static final AttributeSection ARMOR =
            new AttributeSection(B, "minecraft", "generic", "armor",
                    true, 8, Direction.INCREASE, StepType.ADDITIVE, 1.0,
                    List.of(), 0.0, 40.0,
                    false, 0.0);

    public static final AttributeSection ARMOR_TOUGHNESS =
            new AttributeSection(B, "minecraft", "generic", "armor_toughness",
                    true, 6, Direction.INCREASE, StepType.ADDITIVE, 0.5,
                    List.of(), 0.0, 40.0,
                    false, 0.0);

    public static final AttributeSection KNOCKBACK_RESISTANCE =
            new AttributeSection(B, "minecraft", "generic", "knockback_resistance",
                    true, 4, Direction.INCREASE, StepType.PERCENT, 0.10,
                    List.of(), 0.0, 1.0,
                    false, 0.0);

    public static final ModConfigSpec SPEC;

    static {
        B.push("general");
        GENERAL_MAX_LEVEL = B.comment("Maximum enhancement level.")
                .defineInRange("maxLevel", 20, 0, 1000);
        GENERAL_MODE = B.comment("Upgrade mode: RANDOM (pick one attribute) or ALL (apply all).")
                .defineEnum("upgradeMode", UpgradeMode.RANDOM);
        B.pop();

        B.push("chance");
        CHANCE_MODEL = B.comment("Chance model type.")
                .defineEnum("model", ChanceModelType.FLAT_DECREMENT);
        CHANCE_START = B.comment("Starting chance (0..1) for +0->+1.")
                .defineInRange("startChance", 1.0, 0.0, 1.0);
        CHANCE_DECREMENT = B.comment("Chance decrement per existing level (FLAT model).")
                .defineInRange("decrementPerLevel", 0.05, 0.0, 1.0);
        CHANCE_EXP_BASE = B.comment("Exponential base for per-level chance (EXP model). Example: 0.95")
                .defineInRange("exponentialBase", 0.95, 0.0, 1.0);
        CHANCE_OVERRIDES = B.comment("Chance overrides per current level, format: \"level=chance\".")
                .defineListAllowEmpty("overrides", List.of("1=1.0", "2=0.95"), o -> o instanceof String);
        B.pop();

        B.push("nameSuffix");
        NAME_SUFFIX_TIERS = B.comment("Color tiers for the +N name suffix. Format: \"min-max:#RRGGBB\"")
                .defineListAllowEmpty(
                        "tiers",
                        List.of("1-4:#55FFFF", "5-9:#AA00FF", "10-999:#FFAA00"),
                        o -> o instanceof String
                );
        B.pop();

        // Attribute sections are pushed within their ctors.

        SPEC = B.build();
    }

    private UpgradeServerConfig() {}

    // ---- Snapshot model ----

    public static final class SuffixTier {
        public final int min;
        public final int max;
        public final int colorRGB; // 0xRRGGBB
        public SuffixTier(int min, int max, int rgb) {
            this.min = Math.max(0, min);
            this.max = Math.max(this.min, max);
            this.colorRGB = rgb;
        }
        public boolean matches(int level) { return level >= min && level <= max; }
    }

    public static final class Snapshot {
        public final UpgradeMode upgradeMode;
        public final int maxLevel;
        public final ChanceModelType chanceModel;
        public final double startChance;
        public final double decrementPerLevel;
        public final double exponentialBase;
        public final Map<Integer, Double> chanceOverrides;

        public final List<AttributeRuleConfig> attributes; // parsed rules
        public final List<SuffixTier> suffixTiers;

        private Snapshot(UpgradeMode mode, int maxLevel, ChanceModelType cm,
                         double start, double dec, double expBase,
                         Map<Integer, Double> overrides,
                         List<AttributeRuleConfig> attrs,
                         List<SuffixTier> tiers) {
            this.upgradeMode = mode;
            this.maxLevel = maxLevel;
            this.chanceModel = cm;
            this.startChance = start;
            this.decrementPerLevel = dec;
            this.exponentialBase = expBase;
            this.chanceOverrides = overrides;
            this.attributes = attrs;
            this.suffixTiers = tiers;
        }
    }

    public static final class AttributeRuleConfig {
        public final ResourceLocation id; // attribute RL like minecraft:generic.attack_damage
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
            UpgradeMode mode = GENERAL_MODE.get();
            int maxLvl = GENERAL_MAX_LEVEL.get();

            ChanceModelType model = CHANCE_MODEL.get();
            double start = clamp01(CHANCE_START.get());
            double dec = clamp01(CHANCE_DECREMENT.get());
            double expBase = clamp01(CHANCE_EXP_BASE.get());
            Map<Integer, Double> overrides = parseLevelDoubleMap(CHANCE_OVERRIDES.get(), "chance.overrides");

            List<AttributeRuleConfig> attrs = new ArrayList<>();
            // Include our 5 defaults; you can add more sections by creating more AttributeSection instances.
            attrs.add(ATTACK_DAMAGE.toRuleConfig());
            attrs.add(ATTACK_SPEED.toRuleConfig());
            attrs.add(ARMOR.toRuleConfig());
            attrs.add(ARMOR_TOUGHNESS.toRuleConfig());
            attrs.add(KNOCKBACK_RESISTANCE.toRuleConfig());

            List<SuffixTier> tiers = parseSuffixTiers(NAME_SUFFIX_TIERS.get());

            return new Snapshot(mode, maxLvl, model, start, dec, expBase, overrides,
                    Collections.unmodifiableList(attrs),
                    Collections.unmodifiableList(tiers));
        } catch (Throwable t) {
            LOG.error("[UpgradeServerConfig] snapshot() failed; returning hardcoded defaults: {}", t.toString());
            return new Snapshot(
                    UpgradeMode.RANDOM,
                    20,
                    ChanceModelType.FLAT_DECREMENT,
                    1.0, 0.05, 0.95,
                    Map.of(1, 1.0, 2, 0.95),
                    List.of(
                            ATTACK_DAMAGE.fallback(),
                            ATTACK_SPEED.fallback(),
                            ARMOR.fallback(),
                            ARMOR_TOUGHNESS.fallback(),
                            KNOCKBACK_RESISTANCE.fallback()
                    ),
                    parseSuffixTiers(List.of("1-4:#55FFFF", "5-9:#AA00FF", "10-999:#FFAA00"))
            );
        }
    }

    private static Map<Integer, Double> parseLevelDoubleMap(@Nullable List<? extends String> lines, String label) {
        Map<Integer, Double> out = new LinkedHashMap<>();
        if (lines == null) return out;
        for (Object o : lines) {
            if (!(o instanceof String s)) continue;
            String trimmed = s.trim();
            if (trimmed.isEmpty()) continue;
            int idx = trimmed.indexOf('=');
            if (idx <= 0 || idx >= trimmed.length() - 1) {
                LOG.error("[UpgradeServerConfig] Invalid {} entry (no '='): {}", label, s);
                continue;
            }
            try {
                int lvl = Integer.parseInt(trimmed.substring(0, idx).trim());
                double val = Double.parseDouble(trimmed.substring(idx + 1).trim());
                out.put(lvl, val);
            } catch (NumberFormatException nfe) {
                LOG.error("[UpgradeServerConfig] Invalid {} number {} ({})", label, s, nfe.toString());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static List<SuffixTier> parseSuffixTiers(@Nullable List<? extends String> lines) {
        List<SuffixTier> out = new ArrayList<>();
        if (lines == null) return out;
        for (Object o : lines) {
            if (!(o instanceof String s)) continue;
            String t = s.trim();
            if (t.isEmpty()) continue;
            // "min-max:#RRGGBB"
            int dash = t.indexOf('-');
            int colon = t.indexOf(':');
            if (dash <= 0 || colon <= dash + 1 || colon >= t.length() - 1) {
                LOG.error("[UpgradeServerConfig] Invalid nameSuffix tier '{}'", s);
                continue;
            }
            try {
                int min = Integer.parseInt(t.substring(0, dash).trim());
                int max = Integer.parseInt(t.substring(dash + 1, colon).trim());
                String hex = t.substring(colon + 1).trim();
                if (hex.startsWith("#")) hex = hex.substring(1);
                int rgb = (int)Long.parseLong(hex, 16);
                // normalize to 0xRRGGBB
                rgb &= 0xFFFFFF;
                out.add(new SuffixTier(min, max, rgb));
            } catch (Throwable nfe) {
                LOG.error("[UpgradeServerConfig] Failed parsing suffix tier '{}': {}", s, nfe.toString());
            }
        }
        return out;
    }

    public static int resolveSuffixColor(int level) {
        try {
            List<SuffixTier> tiers = snapshot().suffixTiers;
            for (SuffixTier t : tiers) {
                if (t.matches(level)) return t.colorRGB;
            }
        } catch (Throwable ignored) {}
        return 0; // 0 means "no explicit color", caller may fall back
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }

    // Helper to create attribute sections with nested keys
    public static final class AttributeSection {
        private final String ns;
        private final String grp;
        private final String name;

        private final ModConfigSpec.BooleanValue enabled;
        private final ModConfigSpec.IntValue weight;
        private final ModConfigSpec.EnumValue<Direction> direction;
        private final ModConfigSpec.EnumValue<StepType> stepType;
        private final ModConfigSpec.DoubleValue defaultStep;
        private final ModConfigSpec.ConfigValue<List<? extends String>> perLevelOverrides;
        private final ModConfigSpec.DoubleValue capMin;
        private final ModConfigSpec.DoubleValue capMax;
        private final ModConfigSpec.BooleanValue applyToMagnitude;
        private final ModConfigSpec.DoubleValue rounding;

        public AttributeSection(ModConfigSpec.Builder B, String namespace, String group, String name,
                                boolean defEnabled, int defWeight, Direction defDir,
                                StepType defStepType, double defStep,
                                List<String> defOverrides, double defMin, double defMax,
                                boolean defApplyToMag, double defRound) {
            this.ns = namespace; this.grp = group; this.name = name;

            B.push("attributes");
            B.push(namespace);
            B.push(group);
            B.push(name);

            enabled = B.comment("Enable rule for " + namespace + ":" + group + "." + name)
                    .define("enabled", defEnabled);
            weight = B.comment("Random selection weight (ignored if mode=ALL).")
                    .defineInRange("weight", defWeight, 0, 1000);
            direction = B.comment("INCREASE or DECREASE.")
                    .defineEnum("direction", defDir);
            stepType = B.comment("PERCENT or ADDITIVE.")
                    .defineEnum("stepType", defStepType);
            defaultStep = B.comment("Default step (fraction for PERCENT, absolute for ADDITIVE).")
                    .defineInRange("defaultStep", defStep, -1_000_000.0, 1_000_000.0);
            perLevelOverrides = B.comment("Overrides per current level, format: \"level=value\".")
                    .defineListAllowEmpty("perLevelOverrides", defOverrides, o -> o instanceof String);
            capMin = B.comment("Minimum cap (after applying steps).")
                    .defineInRange("capMin", defMin, -1_000_000.0, 1_000_000.0);
            capMax = B.comment("Maximum cap (after applying steps).")
                    .defineInRange("capMax", defMax, -1_000_000.0, 1_000_000.0);
            applyToMagnitude = B.comment("If true, apply percent to |value| magnitude (useful for attack_speed).")
                    .define("applyToMagnitude", defApplyToMag);
            rounding = B.comment("Rounding quantum (e.g. 0.01). 0 = no rounding.")
                    .defineInRange("rounding", defRound, 0.0, 1_000_000.0);

            B.pop(); B.pop(); B.pop(); B.pop();
        }

        public AttributeRuleConfig toRuleConfig() {
            try {
                // Expect e.g. minecraft:generic.attack_damage
                ResourceLocation id = ResourceLocation.parse(ns + ":" + grp + "." + name);
                return new AttributeRuleConfig(
                        id,
                        enabled.get(),
                        weight.get(),
                        direction.get(),
                        stepType.get(),
                        defaultStep.get(),
                        parseLevelDoubleMap(perLevelOverrides.get(), "attributes." + id + ".perLevelOverrides"),
                        capMin.get(),
                        capMax.get(),
                        applyToMagnitude.get(),
                        rounding.get()
                );
            } catch (Throwable t) {
                LOG.error("[UpgradeServerConfig] toRuleConfig failed for {}:{}.{}, using fallback: {}",
                        ns, grp, name, t.toString());
                return fallback();
            }
        }

        public AttributeRuleConfig fallback() {
            ResourceLocation id = ResourceLocation.parse(ns + ":" + grp + "." + name);
            return new AttributeRuleConfig(
                    id, true, 1, Direction.INCREASE, StepType.PERCENT, 0.05,
                    Map.of(), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, 0.0
            );
        }
    }

    // NeoForge automatically calls our listener if we subscribe in our main mod. Expose a helper.
    public static void onServerConfigReload(ModConfigEvent event) {
        // Nothing special needed; callers will ask for snapshot() when needed.
        LOG.debug("[UpgradeServerConfig] onServerConfigReload: {}", event.getConfig().getFileName());
    }
}
