package org.z2six.infiniteupgrades.config.sections;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.z2six.infiniteupgrades.config.parsing.ConfigParsing.*;

/**
 * Soul orb drop settings (friendly edition).
 *
 * You can enable/disable drops, choose how tiers are picked, clamp who can drop,
 * and even spawn light at the drop location.
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

    public final ModConfigSpec.IntValue     lifetimeSeconds;
    public final ModConfigSpec.BooleanValue allowPvP;
    public final ModConfigSpec.ConfigValue<List<? extends String>> whitelistIds;
    public final ModConfigSpec.ConfigValue<List<? extends String>> blacklistIds;

    // Cosmetic/utility
    public final ModConfigSpec.BooleanValue spawnLights;
    public final ModConfigSpec.IntValue     lightRadiusBlocks;

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
                "Default: [\"SMALL=1\",\"MEDIUM=4\",\"LARGE=8\",\"EXTRA_LARGE=16\"]"
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
                "How long the orb lasts on the ground, in seconds."
        ).defineInRange("lifetimeSeconds", 30, 1, 3_600);

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

        // ---- Optional light ----
        spawnLights = B.comment(
                "If true, spawn a small light where the soul orb appears (cosmetic)."
        ).define("spawnLights", true);

        lightRadiusBlocks = B.comment(
                "Approximate radius of the placed light (in blocks).",
                "0 = no light. The block light level is roughly radius+1 (max 15)."
        ).defineInRange("lightRadiusBlocks", 3, 0, 14);

        B.pop();
    }

    public static SoulsConfigSpec define(ModConfigSpec.Builder B) {
        return new SoulsConfigSpec(B);
    }

    public static final class Snapshot {
        public final boolean enabled;
        public final double  dropChance;
        public final SoulsDropModel dropModel;

        // RATIO
        public final double  hpToSoulsRatio;
        public final int     minUnitsForDrop;
        public final Map<String,Integer> tierUnits;

        // HP_THRESHOLDS
        public final Map<String,Double>  tierMinHearts;

        public final int     lifetimeSeconds;
        public final boolean allowPvP;
        public final Set<ResourceLocation> whitelist;
        public final Set<ResourceLocation> blacklist;

        // Lights
        public final boolean spawnLights;
        public final int     lightRadiusBlocks;

        public Snapshot(boolean enabled, double dropChance, SoulsDropModel dropModel,
                        double hpToSoulsRatio, int minUnitsForDrop, Map<String,Integer> tierUnits,
                        Map<String,Double> tierMinHearts,
                        int lifetimeSeconds, boolean allowPvP,
                        Set<ResourceLocation> whitelist, Set<ResourceLocation> blacklist,
                        boolean spawnLights, int lightRadiusBlocks) {
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
        }
    }

    public Snapshot snapshot() {
        double chance = clamp01(dropChance.get());

        Map<String,Integer> tu = parseTierUnits(tierUnitsKV.get());
        if (tu.isEmpty()) tu = Map.of("SMALL",1,"MEDIUM",4,"LARGE",8,"EXTRA_LARGE",16);
        else tu = Collections.unmodifiableMap(tu);

        Map<String,Double> tmh = parseTierMinHearts(tierMinHeartsKV.get());
        if (tmh.isEmpty()) tmh = Map.of("SMALL",0.0,"MEDIUM",10.0,"LARGE",20.0,"EXTRA_LARGE",40.0);
        else tmh = Collections.unmodifiableMap(tmh);

        return new Snapshot(
                enabled.get(),
                chance,
                dropModel.get(),
                Math.max(0.0, hpToSoulsRatio.get()),
                Math.max(0,   minUnitsForDrop.get()),
                tu,
                tmh,
                Math.max(1,   lifetimeSeconds.get()),
                allowPvP.get(),
                parseIdSet(whitelistIds.get()),
                parseIdSet(blacklistIds.get()),
                spawnLights.get(),
                Math.max(0, lightRadiusBlocks.get())
        );
    }
}
