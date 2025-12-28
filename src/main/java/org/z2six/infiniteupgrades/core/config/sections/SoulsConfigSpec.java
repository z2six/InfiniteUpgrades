// File: src/main/java/org/z2six/infiniteupgrades/core/config/sections/SoulsConfigSpec.java
// SoulsConfigSpec.java — server-authoritative soul drop + upgrade cost config (with permanent lifetime support)
package org.z2six.infiniteupgrades.core.config.sections;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.z2six.infiniteupgrades.core.config.ConfigParsing.*;

/**
 * Soul orb drop + soul-upgrade cost settings.
 */
public final class SoulsConfigSpec {

    // Basic toggles
    public final ModConfigSpec.BooleanValue enabled;
    public final ModConfigSpec.DoubleValue  dropChance;

    public final ModConfigSpec.EnumValue<SoulsDropModel> dropModel;

    // RATIO model knobs
    public final ModConfigSpec.DoubleValue  hpToSoulsRatio;
    public final ModConfigSpec.IntValue     minUnitsForDrop;
    public final ModConfigSpec.ConfigValue<List<? extends String>> tierUnitsKV;

    // HP_THRESHOLDS model knobs
    public final ModConfigSpec.ConfigValue<List<? extends String>> tierMinHeartsKV;

    public final ModConfigSpec.IntValue     lifetimeSeconds; // 0 = permanent (new behavior)
    public final ModConfigSpec.BooleanValue allowPvP;
    public final ModConfigSpec.ConfigValue<List<? extends String>> whitelistIds;
    public final ModConfigSpec.ConfigValue<List<? extends String>> blacklistIds;

    // Cosmetic/utility
    public final ModConfigSpec.BooleanValue spawnLights;
    public final ModConfigSpec.IntValue     lightRadiusBlocks;

    // Collection
    public final ModConfigSpec.IntValue     collectRangeBlocks;

    // ---------------- NEW: Upgrade soul-cost fields ----------------
    public final ModConfigSpec.DoubleValue  upgradeBaseCost;
    public final ModConfigSpec.DoubleValue  upgradeExponentialBase;
    public final ModConfigSpec.DoubleValue  upgradeExponentialScale;
    public final ModConfigSpec.ConfigValue<List<? extends String>> upgradeCostOverridesKV;
    // ----------------------------------------------------------------

    private SoulsConfigSpec(ModConfigSpec.Builder B) {
        B.push("souls");

        enabled     = B.comment(
                "Master switch: enable/disable soul orb drops."
        ).define("enabled", true);

        dropChance  = B.comment(
                "Chance (0..1) that a soul orb drops WHEN eligible by the chosen model and filters.",
                "1.0 = always drop when eligible, 0.25 = 25% chance when eligible."
        ).defineInRange("dropChance", 1.0, 0.0, 1.0);

        dropModel   = B.comment(
                "How we choose the tier to drop:",
                " - RATIO:       Convert victim max HP into 'units' (hpToSoulsRatio), pick tier by unit breakpoints.",
                " - HP_THRESHOLDS: Use victim max hearts (HP/2) against per-tier minimum heart thresholds."
        ).defineEnum("dropModel", SoulsDropModel.HP_THRESHOLDS);

        // ---- RATIO model ----
        hpToSoulsRatio    = B.comment(
                "[RATIO model] units = floor(max_hp × hpToSoulsRatio).",
                "Example: 40 HP with ratio=0.75 → floor(30) = 30 units."
        ).defineInRange("hpToSoulsRatio", 0.75, 0.0, 1000.0);

        minUnitsForDrop   = B.comment(
                "[RATIO model] If computed units < this, do not drop a soul."
        ).defineInRange("minUnitsForDrop", 1, 0, 1_000_000);

        tierUnitsKV       = B.comment(
                "[RATIO model] Tier thresholds in UNITS. Format: \"TIER=units\".",
                "We drop the LARGEST tier whose units <= computed units.",
                "Default: [\"SMALL=1\",\"MEDIUM=4\",\"LARGE=8\",\"EXTRA_LARGE=16\"]",
                "Also used as UNIT VALUE when collecting into the Soul Cage."
        ).defineListAllowEmpty("tierUnits",
                List.of("SMALL=1","MEDIUM=4","LARGE=8","EXTRA_LARGE=16"),
                o -> o instanceof String);

        // ---- HP_THRESHOLDS model ----
        tierMinHeartsKV   = B.comment(
                "[HP_THRESHOLDS model] Tier minimum HEARTS required.",
                "We drop the HIGHEST tier whose minHearts <= victim.maxHearts.",
                "Hearts = HP / 2. Default:",
                "[\"SMALL=0\",\"MEDIUM=10\",\"LARGE=20\",\"EXTRA_LARGE=40\"]"
        ).defineListAllowEmpty("tierMinHearts",
                List.of("SMALL=0","MEDIUM=10","LARGE=20","EXTRA_LARGE=40"),
                o -> o instanceof String);

        // ---- Misc ----
        lifetimeSeconds   = B.comment(
                "How long the orb lasts on the ground, in seconds.",
                "Set to 0 to make soul orbs permanent (they never despawn)."
        ).defineInRange("lifetimeSeconds", 30, 0, 3_600);

        allowPvP          = B.comment(
                "If true, players can drop orbs when they die (PvP or other causes)."
        ).define("allowPvP", false);

        whitelistIds      = B.comment(
                "Only these entity IDs are allowed to drop orbs (if list is NOT empty).",
                "If you put any entries here, only those will drop."
        ).defineListAllowEmpty("whitelistEntities", List.of(), o -> o instanceof String);

        blacklistIds      = B.comment(
                "Entities that must NOT drop orbs (overrides whitelist if both are set)."
        ).defineListAllowEmpty("blacklistEntities", List.of(), o -> o instanceof String);

        // ---- Collection ----
        collectRangeBlocks = B.comment(
                "Maximum distance at which a player WITH a Soul Cage attracts soul orbs.",
                "If multiple players are in range, the orb will pick the closest one and start homing.",
                "Once homing starts, the orb continues accelerating until picked up."
        ).defineInRange("collectRangeBlocks", 6, 1, 64);

        // ---------------- NEW: soul-upgrade cost section ----------------
        upgradeBaseCost = B.comment(
                "Base cost for upgrading from level L → L+1 before exponential/scale."
        ).defineInRange("upgradeBaseCost", 10.0, 0.0, 1_000_000.0);

        upgradeExponentialBase = B.comment(
                "Exponential curve base for soul cost.",
                "Effective cost(L→L+1) = baseCost * (expBase^L) * scale."
        ).defineInRange("upgradeExponentialBase", 1.25, 1.0, 5.0);

        upgradeExponentialScale = B.comment(
                "Extra scaling factor applied after exponential.",
                "Useful to quickly tune difficulty without changing base/expBase."
        ).defineInRange("upgradeExponentialScale", 1.0, 0.0, 1000.0);

        upgradeCostOverridesKV = B.comment(
                "Manual soul-cost overrides per level, format: \"LEVEL=COST\".",
                "Example: [\"1=5\",\"2=10\",\"10=1000\"]",
                "These override the exponential formula for the given level."
        ).defineListAllowEmpty("upgradeCostOverrides", List.of(), o -> o instanceof String);
        // -----------------------------------------------------------------

        B.pop();
    }

    public static SoulsConfigSpec define(ModConfigSpec.Builder B) {
        return new SoulsConfigSpec(B);
    }

    // snapshot container
    public static final class Snapshot {

        public final boolean enabled;
        public final double  dropChance;
        public final SoulsDropModel dropModel;

        public final double  hpToSoulsRatio;
        public final int     minUnitsForDrop;
        public final Map<String,Integer> tierUnits;

        public final Map<String,Double>  tierMinHearts;

        public final int     lifetimeSeconds; // 0 = permanent
        public final boolean allowPvP;
        public final Set<ResourceLocation> whitelist;
        public final Set<ResourceLocation> blacklist;

        public final boolean spawnLights;
        public final int     lightRadiusBlocks;

        public final int     collectRangeBlocks;

        // ---------------- NEW: Upgrade soul-cost fields ----------------
        public final double upgradeBaseCost;
        public final double upgradeExponentialBase;
        public final double upgradeExponentialScale;
        public final Map<Integer,Integer> upgradeCostOverrides;
        // ----------------------------------------------------------------

        public Snapshot(
                boolean enabled,
                double dropChance,
                SoulsDropModel dropModel,
                double hpToSoulsRatio,
                int minUnitsForDrop,
                Map<String,Integer> tierUnits,
                Map<String,Double> tierMinHearts,
                int lifetimeSeconds,
                boolean allowPvP,
                Set<ResourceLocation> whitelist,
                Set<ResourceLocation> blacklist,
                boolean spawnLights,
                int lightRadiusBlocks,
                int collectRangeBlocks,
                double upgradeBaseCost,
                double upgradeExponentialBase,
                double upgradeExponentialScale,
                Map<Integer,Integer> upgradeCostOverrides
        ) {
            this.enabled = enabled;
            this.dropChance = dropChance;
            this.dropModel = dropModel;
            this.hpToSoulsRatio = hpToSoulsRatio;
            this.minUnitsForDrop = minUnitsForDrop;
            this.tierUnits = tierUnits;
            this.tierMinHearts = tierMinHearts;
            this.lifetimeSeconds = lifetimeSeconds;
            this.allowPvP = allowPvP;
            this.whitelist = whitelist;
            this.blacklist = blacklist;
            this.spawnLights = spawnLights;
            this.lightRadiusBlocks = lightRadiusBlocks;
            this.collectRangeBlocks = collectRangeBlocks;

            this.upgradeBaseCost = upgradeBaseCost;
            this.upgradeExponentialBase = upgradeExponentialBase;
            this.upgradeExponentialScale = upgradeExponentialScale;
            this.upgradeCostOverrides = upgradeCostOverrides;
        }
    }

    public Snapshot snapshot() {

        double chance = clamp01(dropChance.get());

        Map<String,Integer> tu = parseTierUnits(tierUnitsKV.get());
        if (tu.isEmpty()) {
            tu = Map.of("SMALL", 1, "MEDIUM", 4, "LARGE", 8, "EXTRA_LARGE", 16);
        } else {
            tu = Collections.unmodifiableMap(tu);
        }

        Map<String,Double> tmh = parseTierMinHearts(tierMinHeartsKV.get());
        if (tmh.isEmpty()) {
            tmh = Map.of("SMALL", 0.0, "MEDIUM", 10.0, "LARGE", 20.0, "EXTRA_LARGE", 40.0);
        } else {
            tmh = Collections.unmodifiableMap(tmh);
        }

        // NEW: manual soul-upgrade overrides
        Map<Integer,Integer> overrides = parseLevelIntMap(upgradeCostOverridesKV.get());
        overrides = Collections.unmodifiableMap(overrides);

        return new Snapshot(
                enabled.get(),
                chance,
                dropModel.get(),
                Math.max(0.0, hpToSoulsRatio.get()),
                Math.max(0,   minUnitsForDrop.get()),
                tu,
                tmh,
                Math.max(0,   lifetimeSeconds.get()), // allow 0 = permanent
                allowPvP.get(),
                parseIdSet(whitelistIds.get()),
                parseIdSet(blacklistIds.get()),
                spawnLights.get(),
                Math.max(0, lightRadiusBlocks.get()),
                Math.max(1, collectRangeBlocks.get()),
                Math.max(0.0, upgradeBaseCost.get()),
                Math.max(1.0, upgradeExponentialBase.get()),
                Math.max(0.0, upgradeExponentialScale.get()),
                overrides
        );
    }

    // -------- local parser for "LEVEL=COST" -> Map<Integer,Integer> --------
    private static Map<Integer,Integer> parseLevelIntMap(List<? extends String> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyMap();

        Map<Integer,Integer> out = new LinkedHashMap<>();
        for (Object o : raw) {
            if (!(o instanceof String s)) continue;
            String line = s.trim();
            if (line.isEmpty()) continue;

            int eq = line.indexOf('=');
            if (eq <= 0 || eq >= line.length() - 1) continue;

            String left = line.substring(0, eq).trim();
            String right = line.substring(eq + 1).trim();
            if (left.isEmpty() || right.isEmpty()) continue;

            try {
                int level = Integer.parseInt(left);
                int cost = Integer.parseInt(right);
                if (level <= 0 || cost < 0) continue;
                out.put(level, cost);
            } catch (NumberFormatException ignored) {
                // ignore bad lines
            }
        }
        return out;
    }
}
