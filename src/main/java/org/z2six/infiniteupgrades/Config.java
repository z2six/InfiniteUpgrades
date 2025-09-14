package org.z2six.infiniteupgrades;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Central config. Adds "upgrade tuning" knobs with simple, robust parsing.
 *
 * - Arithmetic knobs:
 *   startChance: base success chance for +0 -> +1 (0..1)
 *   chanceStep:  decrease per existing level (0..1)
 *   minChance:   clamp floor (0..1)
 *   stepPercent: attribute bonus per new level (0..1); e.g. 0.05 = +5% per level
 *   maxLevel:    cap on enhancement level
 *
 * - Tier bumps (bonusSteps): entries like "5=0.10" => at level 5->6, add +10% (0.10) instead of stepPercent
 *   This is ADDITIVE to the base "stepPercent" *for that single level-up only* (so a “bigger” level).
 *
 * - Per-level chance overrides (levelChanceOverrides): entries like "1=1.00","2=0.95" etc.
 *   If present for current level L, it overrides the arithmetic chance for L->L+1.
 *
 * All parsing is defensive and never crashes the game.
 */
@EventBusSubscriber(modid = Infiniteupgrades.MODID)
public class Config {
    // ---- Example/template section you already had (kept) ---------------------------------------
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("example.logDirtBlock", true);

    private static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("example.magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("example.magicNumberIntroduction", "The magic number is... ");

    private static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("example.items", List.of("minecraft:iron_ingot"), Config::validateItemName);

    // ---- Upgrade tuning -----------------------------------------------------------------------
    private static final ModConfigSpec.DoubleValue START_CHANCE = BUILDER
            .comment("Base success chance (0..1) for +0 -> +1.")
            .defineInRange("tuning.startChance", 1.00, 0.0, 1.0);

    private static final ModConfigSpec.DoubleValue CHANCE_STEP = BUILDER
            .comment("Chance decrease per *existing* level (0..1). Example: 0.05 = -5% per level.")
            .defineInRange("tuning.chanceStep", 0.05, 0.0, 1.0);

    private static final ModConfigSpec.DoubleValue MIN_CHANCE = BUILDER
            .comment("Minimum clamped chance (0..1).")
            .defineInRange("tuning.minChance", 0.00, 0.0, 1.0);

    private static final ModConfigSpec.DoubleValue STEP_PERCENT = BUILDER
            .comment("Attribute bonus per level (0..1). Example: 0.05 = +5% per level.")
            .defineInRange("tuning.stepPercent", 0.05, 0.0, 10.0);

    private static final ModConfigSpec.IntValue MAX_LEVEL = BUILDER
            .comment("Maximum enhancement level the system will preview/allow.")
            .defineInRange("tuning.maxLevel", 20, 0, 1000);

    // Entries like "5=0.10", "10=0.10", meaning: at L->L+1 when L is 5 or 10, add +10% instead of normal step.
    private static final ModConfigSpec.ConfigValue<List<? extends String>> BONUS_STEPS = BUILDER
            .comment("Tier bumps. Format: \"level=bonusPercent\". Example: [\"5=0.10\",\"10=0.10\"].")
            .defineListAllowEmpty("tuning.bonusSteps", List.of(), o -> o instanceof String);

    // Entries like "1=1.00", "2=0.95" overriding chance per current level L for L->L+1.
    private static final ModConfigSpec.ConfigValue<List<? extends String>> LEVEL_CHANCE_OVERRIDES = BUILDER
            .comment("Per-level chance overrides. Format: \"level=chance\". Example: [\"1=1.00\",\"2=0.95\"].")
            .defineListAllowEmpty("tuning.levelChanceOverrides", List.of(), o -> o instanceof String);

    static final ModConfigSpec SPEC = BUILDER.build();

    // ---- Values snapshot (read-only fields) ---------------------------------------------------
    public static boolean logDirtBlock;
    public static int magicNumber;
    public static String magicNumberIntroduction;
    public static Set<Item> items;

    /** Parsed, ready-to-use tuning (thread-safe snapshot). */
    public static volatile org.z2six.infiniteupgrades.tuning.UpgradeTuning TUNING =
            org.z2six.infiniteupgrades.tuning.UpgradeTuning.defaults();

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName
                && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        try {
            // Old example/template section
            logDirtBlock = LOG_DIRT_BLOCK.get();
            magicNumber = MAGIC_NUMBER.get();
            magicNumberIntroduction = MAGIC_NUMBER_INTRODUCTION.get();
            items = ITEM_STRINGS.get().stream()
                    .map(s -> BuiltInRegistries.ITEM.get(ResourceLocation.parse(s)))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            // Parse tuning into an immutable helper
            double start = clamp01(START_CHANCE.get());
            double step  = clamp01(CHANCE_STEP.get());
            double min   = clamp01(MIN_CHANCE.get());
            double stepPct = Math.max(0.0, STEP_PERCENT.get());
            int maxLvl  = Math.max(0, MAX_LEVEL.get());

            Map<Integer, Double> bonus = parseDoubleByLevelMap(BONUS_STEPS.get(), "bonusSteps");
            Map<Integer, Double> overrides = parseDoubleByLevelMap(LEVEL_CHANCE_OVERRIDES.get(), "levelChanceOverrides");

            TUNING = new org.z2six.infiniteupgrades.tuning.UpgradeTuning(
                    start, step, min, stepPct, maxLvl, bonus, overrides
            );

        } catch (Throwable t) {
            System.err.println("[InfiniteUpgrades/Config] Failed to load config safely: " + t);
            // Keep previous snapshot if parsing fails
        }
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * Parse list entries like "5=0.10" (level -> double).
     * Ignores invalid lines; logs to stderr (defensive).
     */
    private static Map<Integer, Double> parseDoubleByLevelMap(List<? extends String> lines, String label) {
        Map<Integer, Double> out = new LinkedHashMap<>();
        if (lines == null) return out;
        for (Object o : lines) {
            if (!(o instanceof String s)) continue;
            String trimmed = s.trim();
            if (trimmed.isEmpty()) continue;

            int idx = trimmed.indexOf('=');
            if (idx <= 0 || idx >= trimmed.length() - 1) {
                System.err.println("[InfiniteUpgrades/Config] Invalid " + label + " entry (no '='): " + s);
                continue;
            }
            String left = trimmed.substring(0, idx).trim();
            String right = trimmed.substring(idx + 1).trim();

            try {
                int level = Integer.parseInt(left);
                if (level < 0) {
                    System.err.println("[InfiniteUpgrades/Config] Invalid " + label + " level < 0: " + s);
                    continue;
                }
                double val = Double.parseDouble(right);
                out.put(level, val);
            } catch (NumberFormatException nfe) {
                System.err.println("[InfiniteUpgrades/Config] Invalid " + label + " number: " + s + " (" + nfe + ")");
            }
        }
        return Collections.unmodifiableMap(out);
    }
}
