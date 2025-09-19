// MainFile: src/main/java/org/z2six/infiniteupgrades/config/UpgradeServerConfig.java
package org.z2six.infiniteupgrades.config;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
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
 *     upgradeMode: "RANDOM" | "ALL"            (deprecated; will be superseded by patron mechanics)
 *     nameColorRules: list of ranges mapping to colors, e.g.
 *         ["1-4=blue","5-9=light_purple","10+=gold"]
 *
 *     -- NEW (Batch 1) --
 *     angelAllMult: double    (multiplier applied to per-step magnitude when acting as ANGEL "apply all")
 *     demonRandomMult: double (multiplier applied when acting as DEMON "random one")
 *
 * - reputation:
 *     gainPerUpgrade: double  (default 0.1)
 *     min: int                (default -100)
 *     max: int                (default +100)
 *     effectScale: double     (default 1.0; scales the strength of reputation on failure chance)
 *
 * - chance:
 *     model: "FLAT_DECREMENT" | "EXPONENTIAL"
 *     startChance: double (0..1)
 *     decrementPerLevel: double (0..1)   [for FLAT]
 *     exponentialBase: double (0..1)     [for EXPONENTIAL, e.g. 0.95]
 *     overrides: list of "level=chance"  (applies at current level L for L->L+1)
 *
 * - attributes: see AttributeSection
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
    /** Deprecated: kept for backward compatibility until patron routing is fully wired. */
    public static final ModConfigSpec.EnumValue<UpgradeMode> GENERAL_MODE;

    // NEW: patron multipliers (tuning how strong each path is)
    public static final ModConfigSpec.DoubleValue GENERAL_ANGEL_ALL_MULT;
    public static final ModConfigSpec.DoubleValue GENERAL_DEMON_RANDOM_MULT;

    // name color rules
    public static final ModConfigSpec.ConfigValue<List<? extends String>> NAME_COLOR_RULES;

    // reputation (new)
    public static final ModConfigSpec.DoubleValue REP_GAIN_PER_UPGRADE;
    public static final ModConfigSpec.IntValue REP_MIN;
    public static final ModConfigSpec.IntValue REP_MAX;
    public static final ModConfigSpec.DoubleValue REP_EFFECT_SCALE;

    // chance
    public static final ModConfigSpec.EnumValue<ChanceModelType> CHANCE_MODEL;
    public static final ModConfigSpec.DoubleValue CHANCE_START;
    public static final ModConfigSpec.DoubleValue CHANCE_DECREMENT;   // for FLAT
    public static final ModConfigSpec.DoubleValue CHANCE_EXP_BASE;    // for EXP
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CHANCE_OVERRIDES;

    // attributes.<namespace>.<path> blocks (our 5 defaults)
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

        GENERAL_MODE = B.comment("(Deprecated) Upgrade mode: RANDOM (pick one attribute) or ALL (apply all). " +
                        "Kept for backward compatibility; patron (Angel/Demon) will override this later.")
                .defineEnum("upgradeMode", UpgradeMode.RANDOM);

        GENERAL_ANGEL_ALL_MULT = B.comment("Multiplier applied to per-step magnitude when acting as ANGEL (apply all attributes).")
                .defineInRange("angelAllMult", 0.25, 0.0, 1000.0);
        GENERAL_DEMON_RANDOM_MULT = B.comment("Multiplier applied when acting as DEMON (random single attribute).")
                .defineInRange("demonRandomMult", 1.0, 0.0, 1000.0);

        NAME_COLOR_RULES = B.comment("Name color tiers by level. Formats: 'A-B=color', 'A+=color', or 'N=color'.",
                        "Default: [\"1-4=blue\",\"5-9=light_purple\",\"10+=gold\"]")
                .defineListAllowEmpty("nameColorRules",
                        List.of("1-4=blue", "5-9=light_purple", "10+=gold"),
                        o -> o instanceof String);
        B.pop();

        B.push("reputation");
        REP_GAIN_PER_UPGRADE = B.comment("Reputation gained with the acting patron per upgrade attempt (and lost with the opposite).")
                .defineInRange("gainPerUpgrade", 0.1, -1000.0, 1000.0);
        REP_MIN = B.comment("Minimum reputation bound.")
                .defineInRange("min", -100, -1_000_000, 1_000_000);
        REP_MAX = B.comment("Maximum reputation bound.")
                .defineInRange("max", 100, -1_000_000, 1_000_000);
        REP_EFFECT_SCALE = B.comment("Scales the strength of reputation when reducing (or increasing) failure chance. 1.0 = default.")
                .defineInRange("effectScale", 1.0, 0.0, 1000.0);
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

        SPEC = B.build();
    }

    private UpgradeServerConfig() {}

    // ---- SNAPSHOT ----
    public static final class Snapshot {
        // legacy
        public final UpgradeMode upgradeMode;

        public final int maxLevel;

        // patron multipliers
        public final double angelAllMult;
        public final double demonRandomMult;

        // rep
        public final double repGainPerUpgrade;
        public final int repMin;
        public final int repMax;
        public final double repEffectScale;

        // chance
        public final ChanceModelType chanceModel;
        public final double startChance;
        public final double decrementPerLevel;
        public final double exponentialBase;
        public final Map<Integer, Double> chanceOverrides;

        public final List<AttributeRuleConfig> attributes;

        private Snapshot(
                UpgradeMode mode, int maxLevel,
                double angelAllMult, double demonRandomMult,
                double repGain, int repMin, int repMax, double repScale,
                ChanceModelType cm, double start, double dec, double expBase, Map<Integer, Double> overrides,
                List<AttributeRuleConfig> attrs
        ) {
            this.upgradeMode = mode;
            this.maxLevel = maxLevel;

            this.angelAllMult = angelAllMult;
            this.demonRandomMult = demonRandomMult;

            this.repGainPerUpgrade = repGain;
            this.repMin = repMin;
            this.repMax = repMax;
            this.repEffectScale = repScale;

            this.chanceModel = cm;
            this.startChance = start;
            this.decrementPerLevel = dec;
            this.exponentialBase = expBase;
            this.chanceOverrides = overrides;

            this.attributes = attrs;
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
            UpgradeMode mode = GENERAL_MODE.get();
            int maxLvl = GENERAL_MAX_LEVEL.get();

            double angelMult = Math.max(0.0, GENERAL_ANGEL_ALL_MULT.get());
            double demonMult = Math.max(0.0, GENERAL_DEMON_RANDOM_MULT.get());

            double repGain = REP_GAIN_PER_UPGRADE.get();
            int repMinV = REP_MIN.get();
            int repMaxV = REP_MAX.get();
            if (repMinV > repMaxV) {
                // auto-correct and warn
                LOG.error("[UpgradeServerConfig] reputation min({}) > max({}), swapping.", repMinV, repMaxV);
                int tmp = repMinV; repMinV = repMaxV; repMaxV = tmp;
            }
            double repScale = Math.max(0.0, REP_EFFECT_SCALE.get());

            ChanceModelType model = CHANCE_MODEL.get();
            double start = clamp01(CHANCE_START.get());
            double dec = clamp01(CHANCE_DECREMENT.get());
            double expBase = clamp01(CHANCE_EXP_BASE.get());
            Map<Integer, Double> overrides = parseLevelDoubleMap(CHANCE_OVERRIDES.get(), "chance.overrides");

            List<AttributeRuleConfig> attrs = new ArrayList<>();
            attrs.add(ATTACK_DAMAGE.toRuleConfig());
            attrs.add(ATTACK_SPEED.toRuleConfig());
            attrs.add(ARMOR.toRuleConfig());
            attrs.add(ARMOR_TOUGHNESS.toRuleConfig());
            attrs.add(KNOCKBACK_RESISTANCE.toRuleConfig());

            return new Snapshot(
                    mode, maxLvl,
                    angelMult, demonMult,
                    repGain, repMinV, repMaxV, repScale,
                    model, start, dec, expBase, overrides,
                    Collections.unmodifiableList(attrs)
            );
        } catch (Throwable t) {
            LOG.error("[UpgradeServerConfig] snapshot() failed; returning hardcoded defaults: {}", t.toString());
            return new Snapshot(
                    UpgradeMode.RANDOM,
                    20,
                    0.25, 1.0,
                    0.1, -100, 100, 1.0,
                    ChanceModelType.FLAT_DECREMENT,
                    1.0, 0.05, 0.95,
                    Map.of(1, 1.0, 2, 0.95),
                    List.of(
                            ATTACK_DAMAGE.fallback(),
                            ATTACK_SPEED.fallback(),
                            ARMOR.fallback(),
                            ARMOR_TOUGHNESS.fallback(),
                            KNOCKBACK_RESISTANCE.fallback()
                    )
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

    public static void onServerConfigReload(ModConfigEvent event) {
        LOG.debug("[UpgradeServerConfig] onServerConfigReload: {}", event.getConfig().getFileName());
        // No special action; methods read from SPEC live values.
    }

    // ---- Name color selection ------------------------------------------------------------------

    /**
     * Returns a ChatFormatting color for displaying the "+N" level suffix.
     * Driven by general.nameColorRules entries:
     *  - "A-B=color"  inclusive range
     *  - "A+=color"   A and above
     *  - "N=color"    single level
     *
     * Valid colors are ChatFormatting names, e.g. "blue", "light_purple", "gold", etc.
     * Fallback if no rule matches: WHITE.
     */
    public static ChatFormatting nameColorForLevel(int level) {
        try {
            List<? extends String> rules = NAME_COLOR_RULES.get();
            if (rules != null) {
                for (Object o : rules) {
                    if (!(o instanceof String s)) continue;
                    ChatFormatting col = matchColorRule(level, s.trim());
                    if (col != null) return col;
                }
            }
        } catch (Throwable t) {
            LOG.error("[UpgradeServerConfig] nameColorForLevel failed: {}", t.toString());
        }
        return ChatFormatting.WHITE;
    }

    /**
     * RGB helper for places that want raw color ints (e.g., setting a text style directly).
     * Uses the same rules as nameColorForLevel.
     * Returns 0 if no color chosen (caller may apply a default).
     */
    public static int resolveSuffixColor(int level) {
        ChatFormatting f = nameColorForLevel(level);
        // Convert a few common colors; for others, just return 0 to let caller default.
        // You can expand this map if you want exact RGB per ChatFormatting.
        return switch (f) {
            case BLUE -> 0x5555FF;
            case LIGHT_PURPLE -> 0xFF55FF;
            case GOLD -> 0xFFAA00;
            case AQUA -> 0x55FFFF;
            case GREEN -> 0x55FF55;
            case DARK_GREEN -> 0x00AA00;
            case RED -> 0xFF5555;
            case WHITE -> 0xFFFFFF;
            default -> 0; // let caller fall back
        };
    }

    private static @Nullable ChatFormatting matchColorRule(int level, String rule) {
        if (rule.isEmpty() || !rule.contains("=")) return null;
        String[] parts = rule.split("=", 2);
        String left = parts[0].trim();
        String colorName = parts[1].trim();

        ChatFormatting color = ChatFormatting.getByName(colorName);
        if (color == null) color = ChatFormatting.WHITE;

        // "A-B"
        int dash = left.indexOf('-');
        if (dash > 0) {
            String aStr = left.substring(0, dash).trim();
            String bStr = left.substring(dash + 1).trim();
            try {
                int a = Integer.parseInt(aStr);
                int b = Integer.parseInt(bStr);
                if (level >= a && level <= b) return color;
            } catch (NumberFormatException ignored) {}
            return null;
        }

        // "A+"
        if (left.endsWith("+")) {
            String aStr = left.substring(0, left.length() - 1).trim();
            try {
                int a = Integer.parseInt(aStr);
                if (level >= a) return color;
            } catch (NumberFormatException ignored) {}
            return null;
        }

        // "N"
        try {
            int n = Integer.parseInt(left);
            if (level == n) return color;
        } catch (NumberFormatException ignored) {}

        return null;
    }
}
