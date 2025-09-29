// File: src/main/java/org/z2six/infiniteupgrades/logic/Reputation.java
package org.z2six.infiniteupgrades.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.config.UpgradeServerConfig;

/**
 * Single per-player reputation, persisted in Player#getPersistentData().
 *
 * Storage format migration:
 *  - NEW: iu_rep { value: double }   (unified; negative=demon, positive=angel)
 *  - OLD: iu_rep { angel: double, demon: double }
 *        On first read, we convert to unified as (angel - demon), clamp to [-repMax..+repMax],
 *        write back to 'value', and remove old keys.
 *
 * Clone is handled by RepEvents (PlayerEvent.Clone).
 */
public final class Reputation {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String ROOT = "iu_rep";
    private static final String KEY_VALUE = "value";
    // Legacy keys (for migration)
    private static final String LEGACY_ANGEL = "angel";
    private static final String LEGACY_DEMON = "demon";

    private Reputation() {}

    /** Get unified reputation value in [-repMax..+repMax]. */
    public static double get(Player p) {
        try {
            CompoundTag root = p.getPersistentData().getCompound(ROOT);

            if (root.contains(KEY_VALUE)) {
                return root.getDouble(KEY_VALUE);
            }

            // Migration path: collapse legacy angel/demon into unified.
            double unified = 0.0;
            boolean migrated = false;

            if (root.contains(LEGACY_ANGEL) || root.contains(LEGACY_DEMON)) {
                double angel = root.getDouble(LEGACY_ANGEL);
                double demon = root.getDouble(LEGACY_DEMON);
                unified = angel - demon; // captures alignment if they were cross-coupled
                migrated = true;
            }

            // Clamp and persist as new format if we had legacy or no value
            int max = UpgradeServerConfig.snapshot().repMax;
            unified = clamp(unified, -max, max);
            root.putDouble(KEY_VALUE, unified);

            // Remove legacy keys if present
            if (migrated) {
                root.remove(LEGACY_ANGEL);
                root.remove(LEGACY_DEMON);
                LOG.debug("[Reputation] Migrated legacy angel/demon -> unified={} (max={})", unified, max);
            }

            p.getPersistentData().put(ROOT, root);
            return unified;
        } catch (Throwable t) {
            LOG.error("[Reputation] get failed: {}", t.toString());
            return 0.0;
        }
    }

    /** Set unified reputation value (clamped). */
    public static void set(Player p, double val) {
        try {
            int max = UpgradeServerConfig.snapshot().repMax;
            double clamped = clamp(val, -max, max);
            CompoundTag root = p.getPersistentData().getCompound(ROOT);
            root.putDouble(KEY_VALUE, clamped);
            p.getPersistentData().put(ROOT, root);
        } catch (Throwable t) {
            LOG.error("[Reputation] set failed: {}", t.toString());
        }
    }

    /** Add to unified reputation (clamped). */
    public static void add(Player p, double delta) {
        set(p, get(p) + delta);
    }

    /**
     * Apply attempt delta using unified reputation.
     * Positive delta increases alignment with ANGEL (value -> +),
     * Negative delta increases alignment with DEMON (value -> -).
     *
     * Config semantics preserved:
     *  - repDeltaSuccess (toward used side on success)
     *  - repDeltaFail    (toward used side on fail)
     * Cross-coupling is implicit with a single scalar.
     */
    public static void applyAttemptDelta(ServerPlayer p, RitualType used, boolean success) {
        try {
            var s = UpgradeServerConfig.snapshot();
            double base = success ? s.repDeltaSuccess : s.repDeltaFail;
            if (base == 0.0) return;

            // Sign toward the used side: ANGEL => +, DEMON => -
            double signed = (used == RitualType.ANGEL) ? base : -base;

            add(p, signed);

            LOG.debug("[Reputation] attempt: used={} success={} -> unified={}", used, success, get(p));
        } catch (Throwable t) {
            LOG.error("[Reputation] applyAttemptDelta failed: {}", t.toString());
        }
    }

    /**
     * Linear bonus from reputation; clamped. Positive unified favors ANGEL, negative favors DEMON.
     * Ritual perspective:
     *  - ANGEL: bonus =  (unified) * perPt
     *  - DEMON: bonus = -(unified) * perPt
     * Then clamp to ±repBonusClamp.
     */
    public static double computeBonusFor(Player p, RitualType side) {
        try {
            var s = UpgradeServerConfig.snapshot();
            double unified = get(p);
            double towardSide = (side == RitualType.ANGEL) ? unified : -unified;
            double bonus = towardSide * s.repBonusPerPoint;
            double clamp = Math.max(0.0, s.repBonusClamp);
            if (bonus > clamp) bonus = clamp;
            if (bonus < -clamp) bonus = -clamp;
            return bonus;
        } catch (Throwable t) {
            LOG.error("[Reputation] computeBonusFor failed: {}", t.toString());
            return 0.0;
        }
    }

    /** Copy unified value on clone (respawn/teleport). */
    public static void copyOnClone(Player original, Player clone) {
        try {
            double v = get(original);
            set(clone, v);
        } catch (Throwable t) {
            LOG.error("[Reputation] copyOnClone failed: {}", t.toString());
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
