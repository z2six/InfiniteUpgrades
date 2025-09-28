// File: src/main/java/org/z2six/infiniteupgrades/config/sections/attributes/AttributesConfigSpec.java
package org.z2six.infiniteupgrades.config.sections;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;
import org.z2six.infiniteupgrades.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.config.UpgradeServerConfig.Direction;
import org.z2six.infiniteupgrades.config.UpgradeServerConfig.StepType;

import java.util.*;

/**
 * Attributes section:
 * - Defines default attribute rules under "attributes.{namespace}.{group}.{name}"
 * - Defines dynamic rules under "attributesDynamic.rules"
 * - Produces a unified list of AttributeRuleConfig (same class as original).
 *
 * Logic preserved from the original UpgradeServerConfig.
 */
public final class AttributesConfigSpec {

    // Dynamic rule input
    public final ModConfigSpec.ConfigValue<List<? extends String>> customAttrRules;

    // Default sections (same as before)
    private final AttributeSection ATTACK_DAMAGE;
    private final AttributeSection ATTACK_SPEED;
    private final AttributeSection ARMOR;
    private final AttributeSection ARMOR_TOUGHNESS;
    private final AttributeSection KNOCKBACK_RESISTANCE;

    private AttributesConfigSpec(ModConfigSpec.Builder B) {
        // Default attribute sections (exact parameters preserved)
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

        // Dynamic rules input (same path/format)
        B.push("attributesDynamic");
        customAttrRules = B.comment(
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
    }

    public static AttributesConfigSpec define(ModConfigSpec.Builder B) {
        return new AttributesConfigSpec(B);
    }

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

    // --------- Internal: identical logic to original AttributeSection & dynamic rule parsing ---------

    /** Mirrors original nested AttributeSection; produces UpgradeServerConfig.AttributeRuleConfig. */
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
                ResourceLocation id = ResourceLocation.parse(ns + ":" + grp + "." + name);
                // Fallback mirrors original .fallback()
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
                out.add(new UpgradeServerConfig.AttributeRuleConfig(id, enabled, weight, direction, stepType, defaultStep,
                        perLevelOverrides, capMin, capMax, applyToMagnitude, rounding));
            } catch (Throwable ignored) {}
        }
        return List.copyOf(out);
    }
}
