// File: src/main/java/org/z2six/infiniteupgrades/config/UpgradeServerConfig.java
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
 * SERVER-side authoritative config for upgrades (+ souls).
 *
 * Structure:
 * - general:
 *     maxLevel: int
 *     upgradeMode: "RANDOM" | "ALL" (legacy/global; ritual-specific logic overrides this)
 *     nameColorRules: [...]
 * - chance:
 *     model: "FLAT_DECREMENT" | "EXPONENTIAL"
 *     startChance, decrementPerLevel, exponentialBase
 *     overrides: "level=chance"
 * - rituals:
 *     angelStepMultiplier, demonStepMultiplier
 * - reputation: ...
 * - attributes (defaults) + attributesDynamic.rules
 * - souls (NEW): server-authoritative soul-orb drop rules
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

    // name color rules
    public static final ModConfigSpec.ConfigValue<List<? extends String>> NAME_COLOR_RULES;

    // chance
    public static final ModConfigSpec.EnumValue<ChanceModelType> CHANCE_MODEL;
    public static final ModConfigSpec.DoubleValue CHANCE_START;
    public static final ModConfigSpec.DoubleValue CHANCE_DECREMENT;   // for FLAT
    public static final ModConfigSpec.DoubleValue CHANCE_EXP_BASE;    // for EXP
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CHANCE_OVERRIDES;

    // rituals
    public static final ModConfigSpec.DoubleValue RITUAL_ANGEL_STEP_MULT;
    public static final ModConfigSpec.DoubleValue RITUAL_DEMON_STEP_MULT;

    // reputation
    public static final ModConfigSpec.IntValue REP_MAX;
    public static final ModConfigSpec.DoubleValue REP_DELTA_SUCCESS;
    public static final ModConfigSpec.DoubleValue REP_DELTA_FAIL;
    public static final ModConfigSpec.DoubleValue REP_CROSS_COUPLING;
    public static final ModConfigSpec.DoubleValue REP_BONUS_PER_POINT;
    public static final ModConfigSpec.DoubleValue REP_BONUS_CLAMP;

    // user-defined dynamic attribute rules
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CUSTOM_ATTR_RULES;

    // ---- souls (NEW) ----
    public static final ModConfigSpec.BooleanValue SOULS_ENABLED;
    public static final ModConfigSpec.DoubleValue SOULS_DROP_CHANCE;
    public static final ModConfigSpec.DoubleValue SOULS_HP_RATIO;
    public static final ModConfigSpec.IntValue SOULS_MIN_UNITS_FOR_DROP;
    public static final ModConfigSpec.IntValue SOULS_LIFETIME_SECONDS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SOULS_TIER_UNITS; // "SMALL=1" etc.
    public static final ModConfigSpec.BooleanValue SOULS_ALLOW_PVP;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SOULS_WHITELIST_ENTITIES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SOULS_BLACKLIST_ENTITIES;

    public static final ModConfigSpec SPEC;

    // defaults (vanilla) attribute sections -------
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

    static {
        // ----- GENERAL -----
        B.push("general");
        GENERAL_MAX_LEVEL = B.comment("Maximum enhancement level.")
                .defineInRange("maxLevel", 20, 0, 1000);
        GENERAL_MODE = B.comment("Upgrade mode: RANDOM (pick one attribute) or ALL (apply all). (Legacy/global; ritual-specific logic overrides this)")
                .defineEnum("upgradeMode", UpgradeMode.RANDOM);

        NAME_COLOR_RULES = B.comment("Name color tiers by level. Formats: 'A-B=color', 'A+=color', or 'N=color'.",
                        "Default: [\"1-4=blue\",\"5-9=light_purple\",\"10+=gold\"]")
                .defineListAllowEmpty("nameColorRules",
                        List.of("1-4=blue", "5-9=light_purple", "10+=gold"),
                        o -> o instanceof String);
        B.pop();

        // ----- CHANCE -----
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

        // ----- RITUALS -----
        B.push("rituals");
        RITUAL_ANGEL_STEP_MULT = B.comment("Multiplier for Angel step size (applied to per-level rule step).")
                .defineInRange("angelStepMultiplier", 1.0, 0.0, 100.0);
        RITUAL_DEMON_STEP_MULT = B.comment("Multiplier for Demon step size (applied to per-level rule step).")
                .defineInRange("demonStepMultiplier", 1.5, 0.0, 100.0);
        B.pop();

        // ----- REPUTATION -----
        B.push("reputation");
        REP_MAX = B.comment("Absolute max magnitude of reputation for either side (symmetric).")
                .defineInRange("max", 100, 1, 100000);
        REP_DELTA_SUCCESS = B.comment("Reputation delta applied to the chosen side on SUCCESS.")
                .defineInRange("deltaOnSuccess", 0.1, -1000.0, 1000.0);
        REP_DELTA_FAIL = B.comment("Reputation delta applied to the chosen side on FAIL.")
                .defineInRange("deltaOnFail", 0.0, -1000.0, 1000.0);
        REP_CROSS_COUPLING = B.comment("Opposite side receives -delta*crossCoupling.")
                .defineInRange("crossCoupling", 1.0, 0.0, 1000.0);
        REP_BONUS_PER_POINT = B.comment("Bonus success chance per reputation point (e.g., 0.001 = +0.1% per point).")
                .defineInRange("bonusPerPoint", 0.001, -1.0, 1.0);
        REP_BONUS_CLAMP = B.comment("Clamp on absolute bonus from reputation (e.g., 0.20 = ±20%).")
                .defineInRange("bonusClamp", 0.20, 0.0, 1.0);
        B.pop();

        // ----- Dynamic rules input -----
        B.push("attributesDynamic");
        CUSTOM_ATTR_RULES = B.comment(
                "Add custom attribute rules (ANY attribute id) – one rule per line.",
                "Format (semicolon-separated; first token can be the id unless you use id=...):",
                "  minecraft:generic.max_health; enabled=true; weight=8; direction=INCREASE; stepType=ADDITIVE; defaultStep=1.0; capMin=0; capMax=2048; applyToMagnitude=false; rounding=0.0; overrides=5:2.0,10:5.0",
                "Tokens (all optional except id): enabled, weight, direction, stepType, defaultStep, overrides, capMin, capMax, applyToMagnitude, rounding",
                "Examples:",
                "  minecraft:generic.max_health; stepType=ADDITIVE; defaultStep=2.0; weight=6; capMin=0; capMax=2048",
                "  coolmod:magic_damage; stepType=PERCENT; direction=INCREASE; defaultStep=0.03; weight=12; rounding=0.01",
                "  minecraft:generic.attack_speed; stepType=PERCENT; direction=DECREASE; defaultStep=0.05; applyToMagnitude=true"
        ).defineListAllowEmpty("rules", List.of(), o -> o instanceof String);
        B.pop();

        // ----- SOULS (NEW) -----
        B.push("souls");
        SOULS_ENABLED = B.comment("Enable soul orb drops.")
                .define("enabled", true);
        SOULS_DROP_CHANCE = B.comment("Chance (0..1) that a soul orb drops when eligible.")
                .defineInRange("dropChance", 1.0, 0.0, 1.0);
        SOULS_HP_RATIO = B.comment("Units = floor(max_hp * hpToSoulsRatio). Largest tier <= units will drop.")
                .defineInRange("hpToSoulsRatio", 0.75, 0.0, 1000.0);
        SOULS_MIN_UNITS_FOR_DROP = B.comment("Minimum units required to drop any orb. If units < min, no drop.")
                .defineInRange("minUnitsForDrop", 1, 0, 1_000_000);
        SOULS_LIFETIME_SECONDS = B.comment("Orb lifetime in seconds.")
                .defineInRange("lifetimeSeconds", 30, 1, 3_600);
        SOULS_TIER_UNITS = B.comment("Tier unit mapping. Format: \"TIER=units\". TIER one of SMALL,MEDIUM,LARGE,EXTRA_LARGE.",
                        "Default: [\"SMALL=1\",\"MEDIUM=4\",\"LARGE=8\",\"EXTRA_LARGE=16\"]")
                .defineListAllowEmpty("tierUnits",
                        List.of("SMALL=1", "MEDIUM=4", "LARGE=8", "EXTRA_LARGE=16"),
                        o -> o instanceof String);
        SOULS_ALLOW_PVP = B.comment("Allow orb drops from player deaths (PvP or otherwise).")
                .define("allowPvP", false);
        SOULS_WHITELIST_ENTITIES = B.comment("Whitelist of entity IDs allowed to drop orbs. If non-empty, only listed IDs drop. Example: [\"minecraft:zombie\"]")
                .defineListAllowEmpty("whitelistEntities", List.of(), o -> o instanceof String);
        SOULS_BLACKLIST_ENTITIES = B.comment("Blacklist of entity IDs that must NOT drop orbs.")
                .defineListAllowEmpty("blacklistEntities", List.of(), o -> o instanceof String);
        B.pop();

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
        public final double exponentialBase;
        public final Map<Integer, Double> chanceOverrides;

        public final double angelStepMult;
        public final double demonStepMult;

        public final int repMax;
        public final double repDeltaSuccess;
        public final double repDeltaFail;
        public final double repCross;
        public final double repBonusPerPoint;
        public final double repBonusClamp;

        public final List<AttributeRuleConfig> attributes; // defaults + dynamic

        public final SoulsConfig souls; // NEW

        private Snapshot(UpgradeMode mode, int maxLevel, ChanceModelType cm,
                         double start, double dec, double expBase,
                         Map<Integer, Double> overrides,
                         double angelMult, double demonMult,
                         int repMax, double repSucc, double repFail, double repCross,
                         double repBonusPerPoint, double repBonusClamp,
                         List<AttributeRuleConfig> attrs,
                         SoulsConfig souls) {
            this.upgradeMode = mode;
            this.maxLevel = maxLevel;
            this.chanceModel = cm;
            this.startChance = start;
            this.decrementPerLevel = dec;
            this.exponentialBase = expBase;
            this.chanceOverrides = overrides;

            this.angelStepMult = angelMult;
            this.demonStepMult = demonMult;

            this.repMax = repMax;
            this.repDeltaSuccess = repSucc;
            this.repDeltaFail = repFail;
            this.repCross = repCross;
            this.repBonusPerPoint = repBonusPerPoint;
            this.repBonusClamp = repBonusClamp;

            this.attributes = attrs;
            this.souls = souls;
        }
    }

    public static final class SoulsConfig {
        public final boolean enabled;
        public final double dropChance;
        public final double hpToSoulsRatio;
        public final int minUnitsForDrop;
        public final int lifetimeSeconds;
        public final Map<String, Integer> tierUnits; // key = TIER name
        public final boolean allowPvP;
        public final Set<ResourceLocation> whitelist;
        public final Set<ResourceLocation> blacklist;

        public SoulsConfig(boolean enabled, double dropChance, double hpToSoulsRatio, int minUnitsForDrop,
                           int lifetimeSeconds, Map<String, Integer> tierUnits, boolean allowPvP,
                           Set<ResourceLocation> whitelist, Set<ResourceLocation> blacklist) {
            this.enabled = enabled;
            this.dropChance = dropChance;
            this.hpToSoulsRatio = hpToSoulsRatio;
            this.minUnitsForDrop = minUnitsForDrop;
            this.lifetimeSeconds = lifetimeSeconds;
            this.tierUnits = tierUnits;
            this.allowPvP = allowPvP;
            this.whitelist = whitelist;
            this.blacklist = blacklist;
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
            UpgradeMode mode = GENERAL_MODE.get();
            int maxLvl = GENERAL_MAX_LEVEL.get();

            ChanceModelType model = CHANCE_MODEL.get();
            double start = clamp01(CHANCE_START.get());
            double dec = clamp01(CHANCE_DECREMENT.get());
            double expBase = clamp01(CHANCE_EXP_BASE.get());
            Map<Integer, Double> overrides = parseLevelDoubleMap(CHANCE_OVERRIDES.get(), "chance.overrides");

            double angelMult = Math.max(0.0, RITUAL_ANGEL_STEP_MULT.get());
            double demonMult = Math.max(0.0, RITUAL_DEMON_STEP_MULT.get());

            int repMax = Math.max(1, REP_MAX.get());
            double repSucc = REP_DELTA_SUCCESS.get();
            double repFail = REP_DELTA_FAIL.get();
            double repCross = Math.max(0.0, REP_CROSS_COUPLING.get());
            double repBonusPerPoint = REP_BONUS_PER_POINT.get();
            double repBonusClamp = clamp01(REP_BONUS_CLAMP.get());

            List<AttributeRuleConfig> attrs = new ArrayList<>();
            attrs.add(ATTACK_DAMAGE.toRuleConfig());
            attrs.add(ATTACK_SPEED.toRuleConfig());
            attrs.add(ARMOR.toRuleConfig());
            attrs.add(ARMOR_TOUGHNESS.toRuleConfig());
            attrs.add(KNOCKBACK_RESISTANCE.toRuleConfig());

            // Append dynamic rules
            attrs.addAll(parseCustomRules(CUSTOM_ATTR_RULES.get()));

            SoulsConfig souls = buildSoulsConfig();

            return new Snapshot(mode, maxLvl, model, start, dec, expBase, overrides,
                    angelMult, demonMult,
                    repMax, repSucc, repFail, repCross, repBonusPerPoint, repBonusClamp,
                    Collections.unmodifiableList(attrs),
                    souls);
        } catch (Throwable t) {
            LOG.error("[UpgradeServerConfig] snapshot() failed; returning hardcoded defaults: {}", t.toString());
            return new Snapshot(
                    UpgradeMode.RANDOM,
                    20,
                    ChanceModelType.FLAT_DECREMENT,
                    1.0, 0.05, 0.95,
                    Map.of(1, 1.0, 2, 0.95),
                    1.0, 1.5,
                    100, 0.1, 0.0, 1.0, 0.001, 0.20,
                    List.of(
                            ATTACK_DAMAGE.fallback(),
                            ATTACK_SPEED.fallback(),
                            ARMOR.fallback(),
                            ARMOR_TOUGHNESS.fallback(),
                            KNOCKBACK_RESISTANCE.fallback()
                    ),
                    new SoulsConfig(
                            true, 1.0, 0.75, 1, 30,
                            Map.of("SMALL",1,"MEDIUM",4,"LARGE",8,"EXTRA_LARGE",16),
                            false, Set.of(), Set.of()
                    )
            );
        }
    }

    private static SoulsConfig buildSoulsConfig() {
        boolean enabled = SOULS_ENABLED.get();
        double dropChance = clamp01(SOULS_DROP_CHANCE.get());
        double hpRatio = Math.max(0.0, SOULS_HP_RATIO.get());
        int minUnits = Math.max(0, SOULS_MIN_UNITS_FOR_DROP.get());
        int lifeSec = Math.max(1, SOULS_LIFETIME_SECONDS.get());

        Map<String,Integer> tierUnits = parseTierUnits(SOULS_TIER_UNITS.get());
        if (tierUnits.isEmpty()) {
            tierUnits = Map.of("SMALL",1,"MEDIUM",4,"LARGE",8,"EXTRA_LARGE",16);
        } else {
            tierUnits = Collections.unmodifiableMap(tierUnits);
        }

        boolean allowPvP = SOULS_ALLOW_PVP.get();
        Set<ResourceLocation> white = parseIdSet(SOULS_WHITELIST_ENTITIES.get());
        Set<ResourceLocation> black = parseIdSet(SOULS_BLACKLIST_ENTITIES.get());

        return new SoulsConfig(enabled, dropChance, hpRatio, minUnits, lifeSec, tierUnits, allowPvP, white, black);
    }

    private static Map<String,Integer> parseTierUnits(@Nullable List<? extends String> list) {
        Map<String,Integer> out = new LinkedHashMap<>();
        if (list == null) return out;
        for (Object o : list) {
            if (!(o instanceof String s)) continue;
            String t = s.trim();
            int eq = t.indexOf('=');
            if (eq <= 0 || eq >= t.length()-1) continue;
            String key = t.substring(0, eq).trim().toUpperCase(Locale.ROOT);
            String val = t.substring(eq+1).trim();
            try {
                int n = Integer.parseInt(val);
                if (n > 0) out.put(key, n);
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private static Set<ResourceLocation> parseIdSet(@Nullable List<? extends String> list) {
        if (list == null || list.isEmpty()) return Set.of();
        Set<ResourceLocation> out = new LinkedHashSet<>();
        for (Object o : list) {
            if (!(o instanceof String s)) continue;
            String t = s.trim();
            if (t.isEmpty()) continue;
            try { out.add(ResourceLocation.parse(t)); } catch (Throwable ignored) {}
        }
        return Collections.unmodifiableSet(out);
    }

    private static Map<Integer, Double> parseOverridesKV(String s) {
        Map<Integer, Double> out = new LinkedHashMap<>();
        if (s == null || s.isBlank()) return out;
        String[] pairs = s.split(",");
        for (String p : pairs) {
            int idx = p.indexOf(':');
            if (idx <= 0 || idx >= p.length() - 1) continue;
            try {
                int lvl = Integer.parseInt(p.substring(0, idx).trim());
                double val = Double.parseDouble(p.substring(idx + 1).trim());
                out.put(lvl, val);
            } catch (NumberFormatException ignored) {}
        }
        return Collections.unmodifiableMap(out);
    }

    private static List<AttributeRuleConfig> parseCustomRules(@Nullable List<? extends String> lines) {
        if (lines == null || lines.isEmpty()) return List.of();
        List<AttributeRuleConfig> out = new ArrayList<>();
        for (Object o : lines) {
            if (!(o instanceof String raw)) continue;
            String line = raw.trim();
            if (line.isEmpty()) continue;

            boolean enabled = true;
            int weight = 1;
            Direction direction = Direction.INCREASE;
            StepType stepType = StepType.PERCENT;
            double defaultStep = 0.05;
            Map<Integer, Double> perLevelOverrides = Map.of();
            double capMin = Double.NEGATIVE_INFINITY;
            double capMax = Double.POSITIVE_INFINITY;
            boolean applyToMagnitude = false;
            double rounding = 0.0;

            String idStr = null;

            for (String tok : line.split(";")) {
                String t = tok.trim();
                if (t.isEmpty()) continue;

                // Non key=value token can serve as the id (first).
                if (!t.contains("=") && idStr == null) {
                    idStr = t;
                    continue;
                }

                int eq = t.indexOf('=');
                if (eq <= 0 || eq >= t.length() - 1) continue;
                String k = t.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String v = t.substring(eq + 1).trim();

                switch (k) {
                    case "id" -> idStr = v;
                    case "enabled" -> enabled = Boolean.parseBoolean(v);
                    case "weight" -> { try { weight = Math.max(0, Integer.parseInt(v)); } catch (NumberFormatException ignored) {} }
                    case "direction" -> { try { direction = Direction.valueOf(v.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) {} }
                    case "steptype" -> { try { stepType = StepType.valueOf(v.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) {} }
                    case "defaultstep" -> { try { defaultStep = Double.parseDouble(v); } catch (NumberFormatException ignored) {} }
                    case "overrides" -> perLevelOverrides = parseOverridesKV(v);
                    case "capmin" -> { try { capMin = Double.parseDouble(v); } catch (NumberFormatException ignored) {} }
                    case "capmax" -> { try { capMax = Double.parseDouble(v); } catch (NumberFormatException ignored) {} }
                    case "applytomagnitude" -> applyToMagnitude = Boolean.parseBoolean(v);
                    case "rounding" -> { try { rounding = Math.max(0.0, Double.parseDouble(v)); } catch (NumberFormatException ignored) {} }
                }
            }

            if (idStr == null || idStr.isEmpty()) continue;
            try {
                ResourceLocation id = ResourceLocation.parse(idStr);
                out.add(new AttributeRuleConfig(id, enabled, weight, direction, stepType, defaultStep,
                        perLevelOverrides, capMin, capMax, applyToMagnitude, rounding));
            } catch (Throwable ignored) {}
        }
        return List.copyOf(out);
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
    }

    // ---- Name color selection ------------------------------------------------------------------

    public static net.minecraft.ChatFormatting nameColorForLevel(int level) {
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

    public static int resolveSuffixColor(int level) {
        ChatFormatting f = nameColorForLevel(level);
        return switch (f) {
            case BLUE -> 0x5555FF;
            case LIGHT_PURPLE -> 0xFF55FF;
            case GOLD -> 0xFFAA00;
            case AQUA -> 0x55FFFF;
            case GREEN -> 0x55FF55;
            case DARK_GREEN -> 0x00AA00;
            case RED -> 0xFF5555;
            case WHITE -> 0xFFFFFF;
            default -> 0;
        };
    }

    private static @Nullable ChatFormatting matchColorRule(int level, String rule) {
        if (rule.isEmpty() || !rule.contains("=")) return null;
        String[] parts = rule.split("=", 2);
        String left = parts[0].trim();
        String colorName = parts[1].trim();

        ChatFormatting color = ChatFormatting.getByName(colorName);
        if (color == null) color = ChatFormatting.WHITE;

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

        if (left.endsWith("+")) {
            String aStr = left.substring(0, left.length() - 1).trim();
            try {
                int a = Integer.parseInt(aStr);
                if (level >= a) return color;
            } catch (NumberFormatException ignored) {}
            return null;
        }

        try {
            int n = Integer.parseInt(left);
            if (level == n) return color;
        } catch (NumberFormatException ignored) {}

        return null;
    }
}
