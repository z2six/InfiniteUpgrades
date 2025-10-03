// File: src/main/java/org/z2six/infiniteupgrades/logic/SoulDrops.java
package org.z2six.infiniteupgrades.feature.souls.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.feature.souls.light.SoulLightService;
import org.z2six.infiniteupgrades.feature.souls.entity.SoulOrbEntity;

import java.util.Locale;
import java.util.Map;

/**
 * Unchanged drop logic; added robust light registration using SoulLightService and light config control.
 */
@EventBusSubscriber(modid = Infiniteupgrades.MODID)
public final class SoulDrops {
    private static final Logger LOG = LogUtils.getLogger();

    // logging verbosity switches
    private static final boolean LOG_DECISIONS = true;
    private static final boolean LOG_SUCCESS   = true;

    private static final double RAND_H_RANGE = 0.40; // ±0.20 block

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

            int lifetimeTicks = Math.max(1, cfg.lifetimeSeconds) * 20;

            // Position: around victim's feet + safe hover offset
            Vec3 anchor = victim.position();
            double dx = (r.nextDouble() - 0.5) * RAND_H_RANGE;
            double dz = (r.nextDouble() - 0.5) * RAND_H_RANGE;
            double y = anchor.y + hoverOffsetY(victim);
            Vec3 at = new Vec3(anchor.x + dx, y, anchor.z + dz);

            // Log spawn decision
            if (LOG_SUCCESS) {
                LOG.info("[SoulDrops] SPAWN: dim={}, entityId={}, entityType={}, tier={}, lifeTicks={}, spawn=({},{},{}), anchor=({},{},{}), dx={}, dz={}",
                        dim, victim.getId(), entId, tier, lifetimeTicks,
                        fmt(at.x), fmt(at.y), fmt(at.z),
                        fmt(anchor.x), fmt(anchor.y), fmt(anchor.z),
                        fmt(dx), fmt(dz));
            }

            // Spawn the orb
            var orb = new SoulOrbEntity(lvl, at, tier, lifetimeTicks);
            orb.setHoverBaseY(y);
            lvl.addFreshEntity(orb);

            // Register & place its static light (server side only) per config
            if (lvl instanceof ServerLevel sl) {
                if (cfg.spawnLights && cfg.lightRadiusBlocks > 0) {
                    final int lightLevel = Math.min(15, cfg.lightRadiusBlocks + 1); // radius≈level-1
                    final BlockPos posForLight = BlockPos.containing(at.x, at.y, at.z);
                    final long expiresAt = sl.getGameTime() + lifetimeTicks + 5L;

                    SoulLightService.get(sl).registerAndPlace(sl, posForLight, lightLevel, expiresAt, orb.getUUID());
                    orb.setLightAnchor(posForLight);
                }
            }

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
