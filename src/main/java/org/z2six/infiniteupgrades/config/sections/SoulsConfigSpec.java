// File: src/main/java/org/z2six/infiniteupgrades/config/sections/SoulsConfigSpec.java
package org.z2six.infiniteupgrades.config.sections;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.z2six.infiniteupgrades.config.parsing.ConfigParsing.*;

/**
 * Isolated "souls" section definition and snapshot builder.
 * This keeps 100% of existing logic intact, just moved out of UpgradeServerConfig.
 */
public final class SoulsConfigSpec {

    // Spec entries
    public final ModConfigSpec.BooleanValue enabled;
    public final ModConfigSpec.DoubleValue  dropChance;

    public final ModConfigSpec.EnumValue<SoulsDropModel> dropModel;

    // RATIO model
    public final ModConfigSpec.DoubleValue  hpToSoulsRatio;
    public final ModConfigSpec.IntValue     minUnitsForDrop;
    public final ModConfigSpec.ConfigValue<List<? extends String>> tierUnitsKV;

    // HP_THRESHOLDS model
    public final ModConfigSpec.ConfigValue<List<? extends String>> tierMinHeartsKV;

    public final ModConfigSpec.IntValue     lifetimeSeconds;
    public final ModConfigSpec.BooleanValue allowPvP;
    public final ModConfigSpec.ConfigValue<List<? extends String>> whitelistIds;
    public final ModConfigSpec.ConfigValue<List<? extends String>> blacklistIds;

    // NEW: Light controls
    public final ModConfigSpec.BooleanValue spawnLights;
    public final ModConfigSpec.IntValue     lightRadiusBlocks;

    private SoulsConfigSpec(ModConfigSpec.Builder B) {
        B.push("souls");

        enabled     = B.comment("Enable soul orb drops.").define("enabled", true);
        dropChance  = B.comment("Chance (0..1) that a soul orb drops when eligible.")
                .defineInRange("dropChance", 1.0, 0.0, 1.0);

        dropModel   = B.comment("How tiers are chosen: RATIO (legacy) or HP_THRESHOLDS (by hearts).")
                .defineEnum("dropModel", SoulsDropModel.HP_THRESHOLDS);

        hpToSoulsRatio    = B.comment("RATIO model only: units = floor(max_hp * hpToSoulsRatio).")
                .defineInRange("hpToSoulsRatio", 0.75, 0.0, 1000.0);
        minUnitsForDrop   = B.comment("RATIO model only: if units < this, no drop occurs.")
                .defineInRange("minUnitsForDrop", 1, 0, 1_000_000);
        tierUnitsKV       = B.comment("RATIO model only: tier unit mapping. Format: \"TIER=units\".",
                        "Largest tier with unitValue <= units will drop.",
                        "Default: [\"SMALL=1\",\"MEDIUM=4\",\"LARGE=8\",\"EXTRA_LARGE=16\"]")
                .defineListAllowEmpty("tierUnits",
                        List.of("SMALL=1","MEDIUM=4","LARGE=8","EXTRA_LARGE=16"),
                        o -> o instanceof String);

        tierMinHeartsKV   = B.comment("HP_THRESHOLDS model only: minimum *hearts* required per tier.",
                        "We pick the HIGHEST tier whose minHearts <= victim.maxHearts.",
                        "Hearts = HP / 2. Defaults:",
                        "[\"SMALL=0\",\"MEDIUM=10\",\"LARGE=20\",\"EXTRA_LARGE=40\"]")
                .defineListAllowEmpty("tierMinHearts",
                        List.of("SMALL=0","MEDIUM=10","LARGE=20","EXTRA_LARGE=40"),
                        o -> o instanceof String);

        lifetimeSeconds   = B.comment("Orb lifetime in seconds.")
                .defineInRange("lifetimeSeconds", 30, 1, 3_600);
        allowPvP          = B.comment("Allow orb drops from player deaths (PvP or otherwise).")
                .define("allowPvP", false);
        whitelistIds      = B.comment("Whitelist of entity IDs allowed to drop orbs. If non-empty, only listed IDs drop.")
                .defineListAllowEmpty("whitelistEntities", List.of(), o -> o instanceof String);
        blacklistIds      = B.comment("Blacklist of entity IDs that must NOT drop orbs.")
                .defineListAllowEmpty("blacklistEntities", List.of(), o -> o instanceof String);

        // NEW: lights
        spawnLights = B.comment("If true, spawn a light at each soul's spawn position.")
                .define("spawnLights", true);
        lightRadiusBlocks = B.comment(
                        "Approximate light radius in blocks around the soul's light. 0 = no light.",
                        "The placed light level is roughly radius+1 (clamped to 15).")
                .defineInRange("lightRadiusBlocks", 3, 0, 14);

        B.pop();
    }

    /** Called from UpgradeServerConfig's static init to attach this section to the single SPEC builder. */
    public static SoulsConfigSpec define(ModConfigSpec.Builder B) {
        return new SoulsConfigSpec(B);
    }

    /** Immutable snapshot consumed by UpgradeServerConfig (adapted into its SoulsConfig). */
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

        // NEW: lights
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

    /** Build a live snapshot (uses same logic as the original single-file version). */
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
