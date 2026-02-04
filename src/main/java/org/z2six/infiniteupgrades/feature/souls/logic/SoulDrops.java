// MainFile: src/main/java/org/z2six/infiniteupgrades/feature/souls/logic/SoulDrops.java
// SoulDrops.java — server-side drop logic with strict player-range gating and REAL clumping (XP-Clumps-like)
package org.z2six.infiniteupgrades.feature.souls.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.feature.souls.entity.SoulOrbEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HARD REQUIREMENTS implemented here:
 *  - #2: Souls ONLY drop if any player is within 30 blocks of the death location. Otherwise: no drop.
 *  - #4: If any existing souls are within 3 blocks, do NOT spawn a new orb. Merge into one orb (XP-Clumps style).
 *
 * Registered from Infiniteupgrades main mod constructor (NeoForge.EVENT_BUS.addListener).
 */
public final class SoulDrops {
    private static final Logger LOG = LogUtils.getLogger();

    // logging verbosity switches
    private static final boolean LOG_DECISIONS   = true;
    private static final boolean LOG_SUCCESS     = true;
    private static final boolean LOG_CLUMP_DEBUG = true;

    private static final double RAND_H_RANGE = 0.40; // ±0.20 block

    // HARD RULES
    private static final double PLAYER_NEARBY_RADIUS = 30.0;
    private static final double PLAYER_NEARBY_RADIUS_SQR = PLAYER_NEARBY_RADIUS * PLAYER_NEARBY_RADIUS;

    private static final double CLUMP_RADIUS = 3.0;
    private static final double CLUMP_RADIUS_SQR = CLUMP_RADIUS * CLUMP_RADIUS;

    private SoulDrops() {}

    private static double hoverOffsetY(LivingEntity le) {
        double byHalf = Math.max(0.5, le.getBbHeight() * 0.5);
        double byFeet = 1.0;
        double raw = Math.max(byHalf, byFeet);
        return Math.min(raw, 2.0);
    }

    // Registered as a listener: NeoForge.EVENT_BUS.addListener(SoulDrops::onLivingDrops);
    public static void onLivingDrops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent evt) {
        try {
            final LivingEntity victim = evt.getEntity();
            final Level lvl = victim.level();
            if (lvl.isClientSide) return;

            final String dim = lvl.dimension().location().toString();
            final ResourceLocation entId = victim.getType().builtInRegistryHolder().key().location();

            if (LOG_DECISIONS) {
                LOG.debug("[SoulDrops] trigger: dim={}, entity={}, id={}, pos=({},{},{}), bbH={}, maxHP={}",
                        dim, victim.getClass().getSimpleName(), entId,
                        fmt(victim.getX()), fmt(victim.getY()), fmt(victim.getZ()),
                        fmt(victim.getBbHeight()), fmt(victim.getMaxHealth()));
            }

            UpgradeServerConfig.Snapshot snap = UpgradeServerConfig.snapshot();
            UpgradeServerConfig.SoulsConfig cfg = snap.souls;

            if (!cfg.enabled) {
                if (LOG_DECISIONS) LOG.debug("[SoulDrops] skip: souls disabled");
                return;
            }

            // PvP gating
            if (victim instanceof Player && !cfg.allowPvP) {
                if (LOG_DECISIONS) LOG.debug("[SoulDrops] skip: victim is player and allowPvP=false");
                return;
            }

            // Entity whitelist/blacklist
            if (!cfg.whitelist.isEmpty() && !cfg.whitelist.contains(entId)) {
                if (LOG_DECISIONS) LOG.debug("[SoulDrops] skip: {} not in whitelist", entId);
                return;
            }
            if (cfg.blacklist.contains(entId)) {
                if (LOG_DECISIONS) LOG.debug("[SoulDrops] skip: {} is blacklisted", entId);
                return;
            }

            if (!(lvl instanceof ServerLevel sl)) {
                if (LOG_DECISIONS) LOG.warn("[SoulDrops] skip: server level missing (unexpected). dim={}", dim);
                return;
            }

            // HARD REQUIREMENT #2: only drop if any player is within 30 blocks.
            final Vec3 deathPos = victim.position();
            if (!isAnyPlayerWithin(sl, deathPos, PLAYER_NEARBY_RADIUS_SQR)) {
                if (LOG_DECISIONS) {
                    LOG.debug("[SoulDrops] skip: no player within {} blocks at deathPos=({},{},{}), dim={}",
                            (int)PLAYER_NEARBY_RADIUS, fmt(deathPos.x), fmt(deathPos.y), fmt(deathPos.z), dim);
                }
                return;
            }

            // Roll chance
            RandomSource r = victim.getRandom();
            double roll = r.nextDouble();
            if (LOG_DECISIONS) {
                LOG.debug("[SoulDrops] chance: roll={}, threshold={}", fmt(roll), fmt(cfg.dropChance));
            }
            if (roll > cfg.dropChance) {
                if (LOG_DECISIONS) LOG.debug("[SoulDrops] skip: chance roll failed");
                return;
            }

            // Choose tier
            SoulOrbEntity.Tier tier = selectTier(cfg, victim.getMaxHealth());
            if (tier == null) {
                if (LOG_DECISIONS) LOG.debug("[SoulDrops] skip: no tier matched");
                return;
            }

            int lifetimeTicks = Math.max(0, cfg.lifetimeSeconds) * 20; // 0 => permanent

            // Position
            Vec3 anchor = victim.position();
            double dx = (r.nextDouble() - 0.5) * RAND_H_RANGE;
            double dz = (r.nextDouble() - 0.5) * RAND_H_RANGE;
            double y = anchor.y + hoverOffsetY(victim);
            Vec3 at = new Vec3(anchor.x + dx, y, anchor.z + dz);

            // Units for the newly generated soul (before clumping)
            int newUnits = tierUnitsValue(cfg, tier);

            // HARD REQUIREMENT #4: clump within 3 blocks, XP-Clumps style.
            AABB clumpBox = new AABB(
                    at.x - CLUMP_RADIUS, at.y - CLUMP_RADIUS, at.z - CLUMP_RADIUS,
                    at.x + CLUMP_RADIUS, at.y + CLUMP_RADIUS, at.z + CLUMP_RADIUS
            );

            List<SoulOrbEntity> nearby = sl.getEntitiesOfClass(SoulOrbEntity.class, clumpBox);
            if (!nearby.isEmpty()) {
                SoulOrbEntity keep = null;
                double keepDist2 = Double.MAX_VALUE;

                List<SoulOrbEntity> inSphere = new ArrayList<>();
                for (SoulOrbEntity orb : nearby) {
                    if (orb == null) continue;
                    double d2 = orb.position().distanceToSqr(at);
                    if (d2 <= CLUMP_RADIUS_SQR) {
                        inSphere.add(orb);
                        if (d2 < keepDist2) {
                            keepDist2 = d2;
                            keep = orb;
                        }
                    }
                }

                if (keep != null && !inSphere.isEmpty()) {
                    int absorbedOrbs = 0;

                    // IMPORTANT: start from the NEW units, then add all existing within sphere (including keep)
                    int combinedUnits = Math.max(0, newUnits);
                    for (SoulOrbEntity orb : inSphere) {
                        if (orb == null) continue;
                        combinedUnits += Math.max(0, orb.getStoredUnits());
                    }

                    // discard all but keep
                    for (SoulOrbEntity orb : inSphere) {
                        if (orb == null) continue;
                        if (orb == keep) continue;
                        try {
                            orb.discard();
                            absorbedOrbs++;
                        } catch (Throwable t) {
                            LOG.error("[SoulDrops] clump discard failed: {}", t.toString());
                        }
                    }

                    SoulOrbEntity.Tier combinedTier = selectTierByUnits(cfg, combinedUnits);
                    keep.setStoredUnits(combinedUnits);
                    keep.setTier(combinedTier);
                    keep.refreshLifetimeFromNow(lifetimeTicks);

                    if (LOG_SUCCESS) {
                        LOG.debug("[SoulDrops] CLUMP-MERGE: dim={}, victimType={}, victimId={}, keptOrbId={}, keepDist2={}, baseTier={}, newUnits={}, mergedExistingOrbs={}, combinedUnits={}, combinedTier={}, lifeTicks={}, dropPos=({},{},{}))",
                                dim, entId, victim.getId(),
                                keep.getId(), fmt(keepDist2),
                                tier, newUnits,
                                absorbedOrbs, combinedUnits, combinedTier,
                                lifetimeTicks,
                                fmt(at.x), fmt(at.y), fmt(at.z));
                    }

                    if (LOG_CLUMP_DEBUG) {
                        LOG.debug("[SoulDrops] CLUMP-DEBUG: nearbyBoxCount={}, inSphereCount={}, absorbed={}, keptOrbId={}",
                                nearby.size(), inSphere.size(), absorbedOrbs, keep.getId());
                    }

                    sl.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 8, 0.05, 0.05, 0.05, 0.02);
                    return; // no new spawn
                }
            }

            // No merge target: spawn a new orb
            if (LOG_SUCCESS) {
                LOG.debug("[SoulDrops] SPAWN: dim={}, victimId={}, victimType={}, baseTier={}, units={}, lifeTicks={}, spawn=({},{},{}), anchor=({},{},{}), dx={}, dz={}",
                        dim, victim.getId(), entId, tier, newUnits, lifetimeTicks,
                        fmt(at.x), fmt(at.y), fmt(at.z),
                        fmt(anchor.x), fmt(anchor.y), fmt(anchor.z),
                        fmt(dx), fmt(dz));
            }

            var orb = new SoulOrbEntity(lvl, at, tier, lifetimeTicks);
            orb.setHoverBaseY(y);
            orb.setStoredUnits(newUnits);
            lvl.addFreshEntity(orb);

            sl.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 12, 0.05, 0.05, 0.05, 0.02);
        } catch (Throwable t) {
            LOG.error("[SoulDrops] onLivingDrops failed: {}", t.toString());
        }
    }

    private static boolean isAnyPlayerWithin(ServerLevel sl, Vec3 pos, double radiusSqr) {
        try {
            final double r = Math.sqrt(Math.max(0.0, radiusSqr));
            AABB box = new AABB(
                    pos.x - r, pos.y - r, pos.z - r,
                    pos.x + r, pos.y + r, pos.z + r
            );
            List<Player> players = sl.getEntitiesOfClass(Player.class, box, EntitySelector.NO_SPECTATORS);
            for (Player p : players) {
                if (p == null) continue;
                if (!p.isAlive()) continue;
                if (p.position().distanceToSqr(pos) <= radiusSqr) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // safe: block drop if the check fails
            LOG.error("[SoulDrops] isAnyPlayerWithin failed; blocking soul drop. err={}", t.toString());
            return false;
        }
        return false;
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.3f", v); }

    // ---- Tier selection helpers ----

    private static SoulOrbEntity.Tier selectTier(UpgradeServerConfig.SoulsConfig cfg, double maxHp) {
        switch (cfg.dropModel) {
            case HP_THRESHOLDS -> {
                double hearts = Math.max(0.0, maxHp) * 0.5;
                SoulOrbEntity.Tier best = null;
                double bestMin = -1.0;
                for (Map.Entry<String, Double> e : cfg.tierMinHearts.entrySet()) {
                    SoulOrbEntity.Tier t = parseTier(e.getKey());
                    if (t == null) continue;
                    double minH = Math.max(0.0, e.getValue());
                    if (hearts + 1e-6 >= minH && minH >= bestMin) {
                        if (minH > bestMin || (best != null && order(t) > order(best))) {
                            bestMin = minH; best = t;
                        }
                    }
                }
                if (best == null) {
                    var defaults = Map.of(
                            SoulOrbEntity.Tier.EXTRA_LARGE, 40.0,
                            SoulOrbEntity.Tier.LARGE,      20.0,
                            SoulOrbEntity.Tier.MEDIUM,     10.0,
                            SoulOrbEntity.Tier.SMALL,       0.0
                    );
                    for (var e : defaults.entrySet().stream()
                            .sorted((a,b) -> Double.compare(b.getValue(), a.getValue()))
                            .toList()) {
                        if (hearts >= e.getValue()) return e.getKey();
                    }
                }
                return best;
            }
            case RATIO -> {
                double unitsD = Math.floor(Math.max(0.0, maxHp) * cfg.hpToSoulsRatio);
                int units = (int) unitsD;
                if (units < cfg.minUnitsForDrop) return null;

                SoulOrbEntity.Tier best = null;
                int bestVal = -1;
                for (Map.Entry<String,Integer> e : cfg.tierUnits.entrySet()) {
                    SoulOrbEntity.Tier t = parseTier(e.getKey());
                    if (t == null) continue;
                    int val = Math.max(0, e.getValue());
                    if (val <= units && val >= bestVal) {
                        if (val > bestVal || (best != null && order(t) > order(best))) {
                            bestVal = val; best = t;
                        }
                    }
                }
                if (best == null) {
                    var defaults = Map.of(
                            SoulOrbEntity.Tier.EXTRA_LARGE, 16,
                            SoulOrbEntity.Tier.LARGE,       8,
                            SoulOrbEntity.Tier.MEDIUM,      4,
                            SoulOrbEntity.Tier.SMALL,       1
                    );
                    for (var entry : defaults.entrySet().stream()
                            .sorted(Map.Entry.<SoulOrbEntity.Tier,Integer>comparingByValue().reversed())
                            .toList()) {
                        if (entry.getValue() <= units) return entry.getKey();
                    }
                }
                return best;
            }
        }
        return null;
    }

    private static int tierUnitsValue(UpgradeServerConfig.SoulsConfig cfg, SoulOrbEntity.Tier tier) {
        return switch (tier) {
            case SMALL       -> cfg.tierUnitsOrDefault("SMALL", 1);
            case MEDIUM      -> cfg.tierUnitsOrDefault("MEDIUM", 4);
            case LARGE       -> cfg.tierUnitsOrDefault("LARGE", 8);
            case EXTRA_LARGE -> cfg.tierUnitsOrDefault("EXTRA_LARGE", 16);
        };
    }

    private static SoulOrbEntity.Tier selectTierByUnits(UpgradeServerConfig.SoulsConfig cfg, int units) {
        SoulOrbEntity.Tier best = SoulOrbEntity.Tier.SMALL;
        int bestVal = -1;
        for (Map.Entry<String,Integer> e : cfg.tierUnits.entrySet()) {
            SoulOrbEntity.Tier t = parseTier(e.getKey());
            if (t == null) continue;
            int val = Math.max(0, e.getValue());
            if (val <= units && val >= bestVal) {
                if (val > bestVal || order(t) > order(best)) {
                    bestVal = val; best = t;
                }
            }
        }
        if (bestVal < 0) {
            if (units >= 16) return SoulOrbEntity.Tier.EXTRA_LARGE;
            if (units >= 8)  return SoulOrbEntity.Tier.LARGE;
            if (units >= 4)  return SoulOrbEntity.Tier.MEDIUM;
            return SoulOrbEntity.Tier.SMALL;
        }
        return best;
    }

    private static SoulOrbEntity.Tier parseTier(String name) {
        if (name == null) return null;
        String n = name.trim().toUpperCase(Locale.ROOT);
        try { return SoulOrbEntity.Tier.valueOf(n); }
        catch (IllegalArgumentException ex) { return null; }
    }

    private static int order(SoulOrbEntity.Tier t) {
        return switch (t) {
            case EXTRA_LARGE -> 4;
            case LARGE       -> 3;
            case MEDIUM      -> 2;
            case SMALL       -> 1;
        };
    }
}
