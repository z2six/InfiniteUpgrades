// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/logic/AttemptRng.java
package org.z2six.infiniteupgrades.feature.infusion.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Deterministic "attempt" RNG:
 * - 100% server-driven, no client inputs.
 * - Mixes: world seed, world game time, ritual block pos, player UUID, menu-local attempt counter.
 * - Produces a stable 0..1 double for the single attempt decision.
 *
 * IMPORTANT:
 * You must call roll01(...) ONCE per attempt and cache the result in server state.
 */
public final class AttemptRng {

    // Distinct salts (valid hex constants)
    private static final long SALT_ROOT   = 0xC0FFEE_5EED_F00DL;
    private static final long SALT_ANGEL  = 0xA11CE10D_FC00L;  // "A11CE10D"
    private static final long SALT_DEMON  = 0xD3AD0B0E_BAADL;  // "D3AD0B0E"

    private AttemptRng() {}

    /**
     * Rolls a single uniform double in [0,1) for this infusion attempt.
     *
     * @param sp          server player (for UUID + level)
     * @param ritualPos   block pos of the ritual/sigil
     * @param ritual      ritual type (ANGEL/DEMON)
     * @param worldGameTime current level game time (ticks) at the moment of click
     * @param attemptCounter a strictly monotonic counter you increment per-menu per-attempt
     */
    public static double roll01(ServerPlayer sp,
                                BlockPos ritualPos,
                                RitualType ritual,
                                long worldGameTime,
                                long attemptCounter) {

        ServerLevel level = sp.serverLevel();
        long worldSeed = level.getSeed();

        long k = SALT_ROOT;

        // Mix world seed
        k ^= mix64(worldSeed);

        // Mix player identity (UUID most/least)
        k ^= mix64(sp.getUUID().getMostSignificantBits());
        k ^= mix64(sp.getUUID().getLeastSignificantBits());

        // Mix position
        long pos = ((long) ritualPos.getX() & 0x3FFFFFFL)
                | (((long) ritualPos.getY() & 0x3FFFFFFL) << 26)
                | (((long) ritualPos.getZ() & 0x3FFFFFFL) << 52);
        k ^= mix64(pos);

        // Mix ritual side salt
        k ^= mix64(ritual == RitualType.ANGEL ? SALT_ANGEL : SALT_DEMON);

        // Mix world time (server authoritative tick time)
        k ^= mix64(worldGameTime);

        // Mix the strictly increasing attempt counter (prevents "same tick" collisions)
        k ^= mix64(attemptCounter);

        // Final avalanche
        long x = mix64(k);

        // Convert to [0,1)
        return toUnitInterval(x);
    }

    // ---- helpers ----------------------------------------------------------

    /** 64-bit mixing function (variant of SplitMix64). */
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Convert 53 high bits to a uniform double in [0,1). */
    private static double toUnitInterval(long x) {
        // Keep the top 53 bits to match IEEE-754 mantissa precision
        long top53 = (x >>> 11) & ((1L << 53) - 1);
        return top53 / (double)(1L << 53);
    }
}
