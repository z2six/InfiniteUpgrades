// File: src/main/java/org/z2six/infiniteupgrades/logic/Reputation.java

package org.z2six.infiniteupgrades.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.config.UpgradeServerConfig;

/**
 * Simple per-player reputation store, persisted in Player#getPersistentData().
 * Keys:
 *  iu_rep: {
 *    angel: double,
 *    demon: double
 *  }
 *
 * Clone is handled by RepEvents (PlayerEvent.Clone).
 */
public final class Reputation {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String ROOT = "iu_rep";
    private static final String ANGEL = "angel";
    private static final String DEMON = "demon";

    private Reputation() {}

    public static double get(Player p, RitualType side) {
        try {
            CompoundTag t = p.getPersistentData().getCompound(ROOT);
            String key = side == RitualType.ANGEL ? ANGEL : DEMON;
            return t.getDouble(key);
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    public static void set(Player p, RitualType side, double val) {
        try {
            int max = UpgradeServerConfig.snapshot().repMax;
            double clamped = clamp(val, -max, max);
            CompoundTag root = p.getPersistentData().getCompound(ROOT);
            String key = side == RitualType.ANGEL ? ANGEL : DEMON;
            root.putDouble(key, clamped);
            p.getPersistentData().put(ROOT, root);
        } catch (Throwable t) {
            LOG.error("[Reputation] set failed: {}", t.toString());
        }
    }

    public static void add(Player p, RitualType side, double delta) {
        set(p, side, get(p, side) + delta);
    }

    /** Apply attempt deltas and cross-coupling (server-side). */
    public static void applyAttemptDelta(ServerPlayer p, RitualType used, boolean success) {
        try {
            var s = UpgradeServerConfig.snapshot();
            double delta = success ? s.repDeltaSuccess : s.repDeltaFail;
            if (delta == 0.0) return;

            RitualType other = (used == RitualType.ANGEL) ? RitualType.DEMON : RitualType.ANGEL;

            add(p, used, delta);
            add(p, other, -delta * s.repCross);

            LOG.debug("[Reputation] attempt: used={} success={} -> rep(angel)={} rep(demon)={}",
                    used, success, get(p, RitualType.ANGEL), get(p, RitualType.DEMON));
        } catch (Throwable t) {
            LOG.error("[Reputation] applyAttemptDelta failed: {}", t.toString());
        }
    }

    /** Linear bonus from reputation; clamped. Positive rep => higher success chance. */
    public static double computeBonusFor(Player p, RitualType side) {
        try {
            var s = UpgradeServerConfig.snapshot();
            double rep = get(p, side); // [-max..+max]
            double perPt = s.repBonusPerPoint;
            double bonus = rep * perPt;
            double clamp = Math.max(0.0, s.repBonusClamp);
            if (bonus > clamp) bonus = clamp;
            if (bonus < -clamp) bonus = -clamp;
            return bonus;
        } catch (Throwable t) {
            LOG.error("[Reputation] computeBonusFor failed: {}", t.toString());
            return 0.0;
        }
    }

    public static void copyOnClone(Player original, Player clone) {
        try {
            CompoundTag oldRoot = original.getPersistentData().getCompound(ROOT);
            CompoundTag newRoot = clone.getPersistentData().getCompound(ROOT);
            newRoot.putDouble(ANGEL, oldRoot.getDouble(ANGEL));
            newRoot.putDouble(DEMON, oldRoot.getDouble(DEMON));
            clone.getPersistentData().put(ROOT, newRoot);
        } catch (Throwable t) {
            LOG.error("[Reputation] copyOnClone failed: {}", t.toString());
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
