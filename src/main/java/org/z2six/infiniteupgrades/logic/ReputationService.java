// MainFile: src/main/java/org/z2six/infiniteupgrades/logic/ReputationService.java
package org.z2six.infiniteupgrades.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.config.UpgradeServerConfig;

/**
 * ReputationService – persistent per-player reputation with Angel and Demon.
 *
 * Storage format (Player#getPersistentData):
 *   "iu_rep": {
 *      "angel": double,
 *      "demon": double
 *   }
 *
 * Invariants:
 *  - Default is 0.0 for both.
 *  - Values are clamped to [repMin..repMax] from server config snapshot.
 *  - bump(player, patron, gain): adds +gain to patron and -gain to the opposite.
 *
 * Defensive: never crashes; all methods guard against nulls and malformed tags.
 */
public final class ReputationService {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String ROOT_KEY = "iu_rep";
    private static final String KEY_ANGEL = "angel";
    private static final String KEY_DEMON = "demon";

    private ReputationService() {}

    /** Get current reputation for a patron (0.0 if missing). */
    public static double get(Player player, UpgradeService.Patron patron) {
        try {
            if (player == null) return 0.0;
            CompoundTag root = player.getPersistentData();
            if (root == null) return 0.0;
            CompoundTag rep = root.getCompound(ROOT_KEY);
            return switch (patron) {
                case ANGEL -> rep.getDouble(KEY_ANGEL);
                case DEMON -> rep.getDouble(KEY_DEMON);
            };
        } catch (Throwable t) {
            LOG.error("[ReputationService] get failed: {}", t.toString());
            return 0.0;
        }
    }

    /** Get both reputations (angel, demon) as a small record. */
    public static RepPair getBoth(Player player) {
        try {
            if (player == null) return new RepPair(0.0, 0.0);
            CompoundTag root = player.getPersistentData();
            CompoundTag rep = root.getCompound(ROOT_KEY);
            return new RepPair(rep.getDouble(KEY_ANGEL), rep.getDouble(KEY_DEMON));
        } catch (Throwable t) {
            LOG.error("[ReputationService] getBoth failed: {}", t.toString());
            return new RepPair(0.0, 0.0);
        }
    }

    /** Set reputation for a specific patron, clamped to config bounds. */
    public static void set(Player player, UpgradeService.Patron patron, double value) {
        try {
            if (player == null) return;
            UpgradeServerConfig.Snapshot s = UpgradeServerConfig.snapshot();
            double clamped = clamp(value, s.repMin, s.repMax);

            CompoundTag root = player.getPersistentData();
            CompoundTag rep = root.getCompound(ROOT_KEY);
            switch (patron) {
                case ANGEL -> rep.putDouble(KEY_ANGEL, clamped);
                case DEMON -> rep.putDouble(KEY_DEMON, clamped);
            }
            root.put(ROOT_KEY, rep);
        } catch (Throwable t) {
            LOG.error("[ReputationService] set failed: {}", t.toString());
        }
    }

    /**
     * Bump reputation for acting patron by +gain and the opposite by -gain.
     * Uses current config bounds and logs a concise before/after.
     */
    public static void bump(Player player, UpgradeService.Patron patron, double gain) {
        try {
            if (player == null) return;
            UpgradeServerConfig.Snapshot s = UpgradeServerConfig.snapshot();

            RepPair before = getBoth(player);
            double a = before.angel();
            double d = before.demon();

            if (patron == UpgradeService.Patron.ANGEL) {
                a = clamp(a + gain, s.repMin, s.repMax);
                d = clamp(d - gain, s.repMin, s.repMax);
            } else {
                d = clamp(d + gain, s.repMin, s.repMax);
                a = clamp(a - gain, s.repMin, s.repMax);
            }

            CompoundTag root = player.getPersistentData();
            CompoundTag rep = root.getCompound(ROOT_KEY);
            rep.putDouble(KEY_ANGEL, a);
            rep.putDouble(KEY_DEMON, d);
            root.put(ROOT_KEY, rep);

            if (LOG.isDebugEnabled()) {
                LOG.debug("[Reputation] patron={} gain={}  before(A={},D={})  after(A={},D={})  bounds=[{},{}]",
                        patron, round3(gain), round3(before.angel()), round3(before.demon()),
                        round3(a), round3(d), s.repMin, s.repMax);
            }
        } catch (Throwable t) {
            LOG.error("[ReputationService] bump failed: {}", t.toString());
        }
    }

    /** Utility record for returning both reps. */
    public record RepPair(double angel, double demon) {}

    private static double clamp(double v, double min, double max) {
        if (min > max) { double t = min; min = max; max = t; }
        return Math.max(min, Math.min(max, v));
    }

    private static double round3(double d) { return Math.round(d * 1000.0) / 1000.0; }
}
