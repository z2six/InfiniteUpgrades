// File: src/main/java/org/z2six/infiniteupgrades/logic/InfuseTimers.java
package org.z2six.infiniteupgrades.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.world.menu.AngelDemonMenu;

/**
 * GAME-bus tick listener that finalizes pending infusion attempts on the server.
 * Registered programmatically in Infiniteupgrades ctor via NeoForge.EVENT_BUS.addListener.
 */
public final class InfuseTimers {
    private static final Logger LOG = LogUtils.getLogger();

    private InfuseTimers() {}

    // Method signature matches the event type; no annotation needed when using addListener(...)
    public static void onLevelTick(LevelTickEvent.Post evt) {
        if (!(evt.getLevel() instanceof ServerLevel level)) return;

        long now = level.getGameTime();

        try {
            for (ServerPlayer sp : level.players()) {
                if (sp == null) continue;
                if (sp.containerMenu instanceof AngelDemonMenu menu) {
                    menu.serverTickPending(now, sp);
                }
            }
        } catch (Throwable t) {
            LOG.error("[InfuseTimers] onLevelTick failed: {}", t.toString());
        }
    }
}
