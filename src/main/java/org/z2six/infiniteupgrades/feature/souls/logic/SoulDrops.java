// File: src/main/java/org/z2six/infiniteupgrades/feature/souls/logic/SoulDrops.java
// SoulDrops.java — server-side drop logic with clumping (XP-Clumps-like) and robust logging
package org.z2six.infiniteupgrades.feature.souls.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.feature.souls.entity.SoulOrbEntity;

import java.util.Locale;
import java.util.Map;

/**
 * Drop logic with:
 *  - Tier selection (ratio or thresholds).
 *  - Clumping within a small radius (~5 blocks) to massively reduce entity count under mob farms.
 *  - Lifetime pulled from server-authoritative config (0 = permanent).
 */
@EventBusSubscriber(modid = Infiniteupgrades.MODID)
public final class SoulDrops {
    private static final Logger LOG = LogUtils.getLogger();

    // logging verbosity switches
    private static final boolean LOG_DECISIONS = true;
    private static final boolean LOG_SUCCESS   = true;

    private static final double RAND_H_RANGE = 0.40; // ±0.20 block

    /** Clumping search radius in blocks (sphere-ish via AABB). */
    private static final double CLUMP_RADIUS = 5.0;

    private static double hoverOffsetY(LivingEntity le) {
        double byHalf = Math.max(0.5, le.getBbHeight() * 0.5);
        double byFeet = 1.0;
        double raw = Math.max(byHalf, byFeet);
        return Math.min(raw, 2.0);
    }

    @SubscribeEvent
    public static void onLivingDrops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent evt) {
        try {
            final LivingEntity victim = evt.getEntity();
            final Level lvl = victim.level();
            if (lvl.isClientSide) return;

            final String dim = lvl.dimension().location().toString();
            final ResourceLocation entId = victim.getType().builtInRegistryHolder().key().location();

            if (LOG_DECISIONS) {
                LOG.info("[SoulDrops] trigger: dim={}, entity={}, id={}, pos=({},{},{}), bbH={}, maxHP={}",
                        dim, victim.getClass().getSimpleName(), entId,
                        fmt(victim.getX()), fmt(victim.getY()), fmt(victim.getZ()),
                        fmt(victim.getBbHeight()), fmt(victim.getMaxHealth()));
            }

            UpgradeServerConfig.Snapshot snap = UpgradeServerConfig.snapshot();
            UpgradeServerConfig.SoulsConfig cfg = snap.souls;

            if (!cfg.enabled) {
                if (LOG_DECISIONS) LOG.info("[SoulDrops] skip: souls disabled");
                return;
            }

            // PvP gating
            if (victim instanceof Player && !cfg.allowPvP) {
                if (LOG_DECISIONS) LOG.info("[SoulDrops] skip: victim is player and allowPvP=false");
                return;
            }

            // Entity whitelist/blacklist
            if (!cfg.whitelist.isEmpty() && !cfg.whitelist.contains(entId)) {
                if (LOG_DECISIONS) LOG.info("[SoulDrops] skip: {} not in whitelist", entId);
                return;
            }
            if (cfg.blacklist.contains(entId)) {
                if (LOG_DECISIONS) LOG.info("[SoulDrops] skip: {} is blacklisted", entId);
                return;
            }

            // Roll chance
            RandomSource r = victim.getRandom();
            double roll = r.nextDouble();
            if (LOG_DECISIONS) {
                LOG.info("[SoulDrops] chance: roll={}, threshold={}",
                        fmt(roll), fmt(cfg.dropChance));
            }
            if (roll > cfg.dropChance) {
                if (LOG_DECISIONS) LOG.info("[SoulDrops] skip: chance roll failed");
                return;
            }

            // Choose tier (model can be ratio or thresholds; logic centralized here)
            SoulOrbEntity.Tier tier = selectTier(cfg, victim.getMaxHealth());
            if (tier == null) {
                if (LOG_DECISIONS) LOG.info("[SoulDrops] skip: no tier matched");
                return;
            }

            int lifetimeTicks = Math.max(0, cfg.lifetimeSeconds) * 20; // 0 => permanent

            // Position: around victim's feet + safe hover offset
            Vec3 anchor = victim.position();
            double dx = (r.nextDouble() - 0.5) * RAND_H_RANGE;
            double dz = (r.nextDouble() - 0.5) * RAND_H_RANGE;
            double y = anchor.y + hoverOffsetY(victim);
            Vec3 at = new Vec3(anchor.x + dx, y, anchor.z + dz);

            // Units for the newly generated soul (before clumping)
            int newUnits = tierUnitsValue(cfg, tier);

            // -------- CLUMPING: combine with any nearby soul entities within CLUMP_RADIUS ----------
            int combinedUnits = newUnits;
            int absorbed = 0;

            if (lvl instanceof ServerLevel sl) {
                AABB box = new AABB(
                        at.x - CLUMP_RADIUS, at.y - CLUMP_RADIUS, at.z - CLUMP_RADIUS,
                        at.x + CLUMP_RADIUS, at.y + CLUMP_RADIUS, at.z + CLUMP_RADIUS
                );

                for (SoulOrbEntity other : sl.getEntitiesOfClass(SoulOrbEntity.class, box)) {
                    // (Optional small distance check to be stricter than the AABB corners)
                    double dist2 = other.position().distanceToSqr(at);
                    if (dist2 <= (CLUMP_RADIUS * CLUMP_RADIUS)) {
                        combinedUnits += Math.max(0, other.getStoredUnits());
                        other.discard();
                        absorbed++;
                    }
                }
            }

            // Decide visual tier for the combined units using current tier-unit thresholds
            SoulOrbEntity.Tier combinedTier = selectTierByUnits(cfg, combinedUnits);

            if (LOG_SUCCESS) {
                LOG.info("[SoulDrops] SPAWN(clumped): dim={}, entityId={}, entityType={}, baseTier={}, newUnits={}, absorbedOrbs={}, combinedUnits={}, combinedTier={}, lifeTicks={}, spawn=({},{},{}), anchor=({},{},{}), dx={}, dz={}",
                        dim, victim.getId(), entId, tier, newUnits, absorbed, combinedUnits, combinedTier, lifetimeTicks,
                        fmt(at.x), fmt(at.y), fmt(at.z),
                        fmt(anchor.x), fmt(anchor.y), fmt(anchor.z),
                        fmt(dx), fmt(dz));
            }

            // Spawn the combined orb
            var orb = new SoulOrbEntity(lvl, at, combinedTier, lifetimeTicks);
            orb.setHoverBaseY(y);
            orb.setStoredUnits(combinedUnits); // critical: store total units for collection credit
            lvl.addFreshEntity(orb);

            // Visibility ping: small sparkle cluster at spawn
            if (lvl instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 12, 0.05, 0.05, 0.05, 0.02);
            }

        } catch (Throwable t) {
            LOG.error("[SoulDrops] onLivingDrops failed: {}", t.toString());
        }
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.3f", v); }

    // ---- Tier selection helpers (mirror of your existing semantics) ----

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
                    // defaults fallback
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

    /** Value in units for a visual tier, based on current config's thresholds (with sensible defaults). */
    private static int tierUnitsValue(UpgradeServerConfig.SoulsConfig cfg, SoulOrbEntity.Tier tier) {
        return switch (tier) {
            case SMALL       -> cfg.tierUnitsOrDefault("SMALL", 1);
            case MEDIUM      -> cfg.tierUnitsOrDefault("MEDIUM", 4);
            case LARGE       -> cfg.tierUnitsOrDefault("LARGE", 8);
            case EXTRA_LARGE -> cfg.tierUnitsOrDefault("EXTRA_LARGE", 16);
        };
    }

    /** Choose the display tier for a given combined unit count, using the tierUnits thresholds. */
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
            // fallback ordering if map was empty
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

    private SoulDrops() {}
}
