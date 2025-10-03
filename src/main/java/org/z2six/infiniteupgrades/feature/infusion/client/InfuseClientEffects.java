// File: src/main/java/org/z2six/infiniteupgrades/client/InfuseClientEffects.java
package org.z2six.infiniteupgrades.feature.infusion.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.registry.ModSounds;

/**
 * Client-global infusion effects: plays SFX exactly when fill finishes (flashing begins),
 * regardless of GUI state.
 */
public final class InfuseClientEffects {
    private static final Logger LOG = LogUtils.getLogger();

    private static long endGameTime = -1L;
    private static int  durationTicks = 0;

    private static boolean outcomeKnown = false;
    private static boolean willSucceed = false;

    private static boolean soundPlayed = false;

    private InfuseClientEffects() {}

    /** Called on S2C InfuseStarted. */
    public static void onInfuseStarted(long endTime, int duration) {
        endGameTime = endTime;
        durationTicks = Math.max(0, duration);
        soundPlayed = false;
        // If outcome was known earlier and we’re already past flashing, play right away.
        tryPlayIfDue();
    }

    /** Called on S2C EarlyOutcome (can be sent immediately or later by the server). */
    public static void onOutcomeKnown(boolean success) {
        outcomeKnown = true;
        willSucceed = success;
        tryPlayIfDue();
    }

    /** Called on S2C InfuseResult or if pending cancels; clears state. */
    public static void reset() {
        endGameTime = -1L;
        durationTicks = 0;
        outcomeKnown = false;
        willSucceed = false;
        soundPlayed = false;
    }

    /** Invoked every client tick (registered in client setup). */
    static void clientTick() {
        tryPlayIfDue();
    }

    /** Play exactly at flashing start (when fill phase completes). */
    private static void tryPlayIfDue() {
        if (!outcomeKnown) return;
        if (endGameTime < 0 || durationTicks <= 0) return;
        if (soundPlayed) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) return;

        long now = mc.level.getGameTime();
        long start = endGameTime - durationTicks;

        if (now < start) return;

        // When does flashing start? start + fillTicks
        int fillTicks = ProgressPhases.ticksUntilFlashingStart(durationTicks);
        long flashingStart = start + fillTicks;

        if (now >= flashingStart) {
            var evt = willSucceed ? ModSounds.INFUSE_SUCCESS.get() : ModSounds.INFUSE_FAIL.get();
            mc.level.playLocalSound(
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    evt, SoundSource.PLAYERS, 1.0f, 1.0f, false
            );
            soundPlayed = true;
        }
    }
}
