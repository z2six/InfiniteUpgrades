package org.z2six.infiniteupgrades.core.config.sections;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig.Direction;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig.StepType;

import java.util.*;

/**
 * User-facing attribute tuning.
 *
 * This section controls how EACH attribute upgrades:
 * - Direction (INCREASE or DECREASE)
 * - Step type (PERCENT or ADDITIVE)
 * - Default step size (+ per-level overrides)
 * - Caps, rounding, and a few special flags
 *
 * Also supports extra custom rules for ANY attribute id (including modded) via `attributesDynamic.rules`.
 */
public final class AttributesConfigSpec {

    // Lines that define additional rules (or override built-ins) in a simple one-line format
    public final ModConfigSpec.ConfigValue<List<? extends String>> customAttrRules;

    // Built-in sections for common attributes (as easy starting points)
    private final AttributeSection ATTACK_DAMAGE;
    private final AttributeSection ATTACK_SPEED;
    private final AttributeSection ARMOR;
    private final AttributeSection ARMOR_TOUGHNESS;
    private final AttributeSection KNOCKBACK_RESISTANCE;

    private AttributesConfigSpec(ModConfigSpec.Builder B) {
        // ====== Built-in examples / defaults ======
        ATTACK_DAMAGE = new AttributeSection(B, "minecraft", "generic", "attack_damage",
                true, 10, Direction.INCREASE, StepType.PERCENT, 0.05,
                List.of(), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                false, 0.0);

        ATTACK_SPEED = new AttributeSection(B, "minecraft", "generic", "attack_speed",
                true, 10, Direction.DECREASE, StepType.PERCENT, 0.05,
                List.of(), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                true, 0.0);

        ARMOR = new AttributeSection(B, "minecraft", "generic", "armor",
                true, 8, Direction.INCREASE, StepType.ADDITIVE, 1.0,
                List.of(), 0.0, 40.0,
                false, 0.0);

        ARMOR_TOUGHNESS = new AttributeSection(B, "minecraft", "generic", "armor_toughness",
                true, 6, Direction.INCREASE, StepType.ADDITIVE, 0.5,
                List.of(), 0.0, 40.0,
                false, 0.0);

        KNOCKBACK_RESISTANCE = new AttributeSection(B, "minecraft", "generic", "knockback_resistance",
                true, 4, Direction.INCREASE, StepType.PERCENT, 0.10,
                List.of(), 0.0, 1.0,
                false, 0.0);

        // ====== Dynamic rules (simple one-line format) ======
        B.push("attributesDynamic");
        customAttrRules = B.comment(
                "Add or override attribute rules with simple one-line entries.",
                "",
                "► How steps combine:",
                "   final_step_for_this_upgrade = (rule step here) × (ritual multiplier) .",
                "   - The 'rule step' comes from per-level override (if present) otherwise 'defaultStep'.",
                "   - Ritual multipliers are set in: rituals.angelStepMultiplier / rituals.demonStepMultiplier.",
                "",
                "► PERCENT vs ADDITIVE:",
                "   - PERCENT: 0.05 means +5% (or -5% if direction=DECREASE).",
                "   - ADDITIVE: 1.0 means +1.0 (or -1.0 if direction=DECREASE).",
                "",
                "► Format (semicolon-separated). The id can be the first token, or use id=...:",
                "   minecraft:generic.max_health; enabled=true; weight=8; direction=INCREASE; stepType=ADDITIVE; defaultStep=2.0; capMin=0; capMax=2048; applyToMagnitude=false; rounding=0.01; overrides=5:4.0,10:6.0",
                "",
                "► Tokens (all optional except id):",
                "   enabled, weight, direction(INCREASE|DECREASE), stepType(PERCENT|ADDITIVE), defaultStep,",
                "   overrides (comma list of L:value), capMin, capMax, applyToMagnitude(true/false), rounding",
                "",
                "► Examples:",
                "   1) minecraft:generic.max_health; stepType=ADDITIVE; defaultStep=2.0; capMin=0; capMax=2048",
                "      (Every upgrade adds 2 hearts unless capped; ritual multiplier still applies.)",
                "",
                "   2) coolmod:magic_damage; stepType=PERCENT; direction=INCREASE; defaultStep=0.03; rounding=0.01",
                "      (+3% per upgrade (× ritual multiplier), rounded to 0.01.)",
                "",
                "   3) minecraft:generic.attack_speed; stepType=PERCENT; direction=DECREASE; defaultStep=0.05; applyToMagnitude=true",
                "      (Treats negative bases correctly so it feels like “faster” in-game.)"
        ).defineListAllowEmpty("rules", List.of(), o -> o instanceof String);
        B.pop();
    }

    public static AttributesConfigSpec define(ModConfigSpec.Builder B) {
        return new AttributesConfigSpec(B);
    }

    /** Runtime snapshot used by the server logic. */
    public static final class Snapshot {
        public final List<UpgradeServerConfig.AttributeRuleConfig> rules;

        public Snapshot(List<UpgradeServerConfig.AttributeRuleConfig> rules) {
            this.rules = rules;
        }
    }

    public Snapshot snapshot() {
        List<UpgradeServerConfig.AttributeRuleConfig> attrs = new ArrayList<>();
        attrs.add(ATTACK_DAMAGE.toRuleConfig());
        attrs.add(ATTACK_SPEED.toRuleConfig());
        attrs.add(ARMOR.toRuleConfig());
        attrs.add(ARMOR_TOUGHNESS.toRuleConfig());
        attrs.add(KNOCKBACK_RESISTANCE.toRuleConfig());

        attrs.addAll(parseCustomRules(customAttrRules.get()));
        return new Snapshot(Collections.unmodifiableList(attrs));
    }

    // ---------- Internal (unchanged logic) ----------

    /** Produces a rule config for one attribute under attributes.{namespace}.{group}.{name}. */
    private static final class AttributeSection {
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

        AttributeSection(ModConfigSpec.Builder B, String namespace, String group, String name,
                         boolean defEnabled, int defWeight, Direction defDir,
                         StepType defStepType, double defStep,
                         List<String> defOverrides, double defMin, double defMax,
                         boolean defApplyToMag, double defRound) {
            this.ns = namespace; this.grp = group; this.name = name;

            B.push("attributes");
            B.push(namespace);
            B.push(group);
            B.push(name);

            enabled = B.comment(
                            "Enable or disable upgrades for this attribute entirely.")
                    .define("enabled", defEnabled);

            weight = B.comment(
                            "Random selection weight when only ONE attribute is chosen to upgrade (RANDOM mode).",
                            "Higher weight = chosen more often. Ignored if ALL attributes upgrade together.")
                    .defineInRange("weight", defWeight, 0, 1000);

            direction = B.comment(
                            "INCREASE → positive step (buff).  DECREASE → negative step (nerf).",
                            "Tip: For attack_speed, using DECREASE with applyToMagnitude=true makes the weapon",
                            "feel faster (because the base is negative).")
                    .defineEnum("direction", defDir);

            stepType = B.comment(
                            "PERCENT: step=0.05 → ±5%.  ADDITIVE: step=1.0 → ±1.0.")
                    .defineEnum("stepType", defStepType);

            defaultStep = B.comment(
                            "Default step size. If PERCENT, it is a fraction (0.05 = 5%). If ADDITIVE, it is absolute.")
                    .defineInRange("defaultStep", defStep, -1_000_000.0, 1_000_000.0);

            perLevelOverrides = B.comment(
                            "Overrides for *current level*. Format: \"level=value\". Example: [\"5=0.10\",\"10=0.15\"].",
                            "If set, the override value replaces defaultStep at that level. Otherwise defaultStep is used.")
                    .defineListAllowEmpty("perLevelOverrides", defOverrides, o -> o instanceof String);

            capMin = B.comment(
                            "Lower cap for the final value AFTER the step is applied.")
                    .defineInRange("capMin", defMin, -1_000_000.0, 1_000_000.0);

            capMax = B.comment(
                            "Upper cap for the final value AFTER the step is applied.")
                    .defineInRange("capMax", defMax, -1_000_000.0, 1_000_000.0);

            applyToMagnitude = B.comment(
                            "PERCENT only: apply percent to |value| magnitude (helps when the base is negative).")
                    .define("applyToMagnitude", defApplyToMag);

            rounding = B.comment(
                            "Round the final value to a grid. Example: 0.01 → two decimals. 0 = no rounding.")
                    .defineInRange("rounding", defRound, 0.0, 1_000_000.0);

            B.pop(); B.pop(); B.pop(); B.pop();
        }

        UpgradeServerConfig.AttributeRuleConfig toRuleConfig() {
            try {
                ResourceLocation id = ResourceLocation.parse(ns + ":" + grp + "." + name);
                return new UpgradeServerConfig.AttributeRuleConfig(
                        id,
                        enabled.get(),
                        weight.get(),
                        direction.get(),
                        stepType.get(),
                        defaultStep.get(),
                        parseLevelDoubleMap(perLevelOverrides.get()),
                        capMin.get(),
                        capMax.get(),
                        applyToMagnitude.get(),
                        rounding.get()
                );
            } catch (Throwable t) {
                // Fallback mirrors original
                ResourceLocation id = ResourceLocation.parse(ns + ":" + grp + "." + name);
                return new UpgradeServerConfig.AttributeRuleConfig(
                        id, true, 1, Direction.INCREASE, StepType.PERCENT, 0.05,
                        Map.of(), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, 0.0
                );
            }
        }

        private static Map<Integer, Double> parseLevelDoubleMap(@Nullable List<? extends String> lines) {
            Map<Integer, Double> out = new LinkedHashMap<>();
            if (lines == null) return out;
            for (Object o : lines) {
                if (!(o instanceof String s)) continue;
                String trimmed = s.trim();
                if (trimmed.isEmpty()) continue;
                int idx = trimmed.indexOf('=');
                if (idx <= 0 || idx >= trimmed.length() - 1) continue;
                try {
                    int lvl = Integer.parseInt(trimmed.substring(0, idx).trim());
                    double val = Double.parseDouble(trimmed.substring(idx + 1).trim());
                    out.put(lvl, val);
                } catch (NumberFormatException ignored) {}
            }
            return Collections.unmodifiableMap(out);
        }
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

    private static List<UpgradeServerConfig.AttributeRuleConfig> parseCustomRules(@Nullable List<? extends String> lines) {
        if (lines == null || lines.isEmpty()) return List.of();
        List<UpgradeServerConfig.AttributeRuleConfig> out = new ArrayList<>();
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
                out.add(new UpgradeServerConfig.AttributeRuleConfig(id, enabled, weight, direction, stepType, defaultStep,
                        perLevelOverrides, capMin, capMax, applyToMagnitude, rounding));
            } catch (Throwable ignored) {}
        }
        return List.copyOf(out);
    }
}
