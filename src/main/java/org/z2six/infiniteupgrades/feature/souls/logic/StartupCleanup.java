// MainFile: src/main/java/org/z2six/infiniteupgrades/feature/souls/logic/StartupCleanup.java
package org.z2six.infiniteupgrades.feature.souls.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.feature.souls.entity.SoulOrbEntity;

/**
 * Startup cleanup.
 *
 * No EventBusSubscriber. Register from main mod constructor:
 *   NeoForge.EVENT_BUS.addListener(StartupCleanup::onServerStarted);
 *   NeoForge.EVENT_BUS.addListener(StartupCleanup::onLevelLoad);
 */
public final class StartupCleanup {
    private static final Logger LOG = LogUtils.getLogger();

    private StartupCleanup() {}

    public static void onServerStarted(ServerStartedEvent evt) {
        try {
            MinecraftServer server = evt.getServer();
            int total = 0;
            for (ServerLevel sl : server.getAllLevels()) {
                total += nukeLevel(sl, "server-started");
            }
            LOG.debug("[StartupCleanup] server-started: removedTotalSoulsInLoadedChunks={}", total);
        } catch (Throwable t) {
            LOG.error("[StartupCleanup] onServerStarted failed: {}", t.toString());
        }
    }

    public static void onLevelLoad(LevelEvent.Load evt) {
        try {
            if (evt.getLevel() instanceof ServerLevel sl) {
                int removed = nukeLevel(sl, "level-load");
                LOG.debug("[StartupCleanup] level-load: dim={}, removedSoulsInLoadedChunks={}",
                        sl.dimension().location(), removed);
            }
        } catch (Throwable t) {
            LOG.error("[StartupCleanup] onLevelLoad failed: {}", t.toString());
        }
    }

    private static int nukeLevel(ServerLevel sl, String reason) {
        int removedSouls = 0;

        try {
            AABB huge = new AABB(-3.0E7, -3.0E7, -3.0E7, 3.0E7, 3.0E7, 3.0E7);
            for (SoulOrbEntity orb : sl.getEntitiesOfClass(SoulOrbEntity.class, huge)) {
                try {
                    orb.discard();
                    removedSouls++;
                } catch (Throwable t) {
                    LOG.error("[StartupCleanup] discard failed in dim={} reason={} err={}",
                            sl.dimension().location(), reason, t.toString());
                }
            }
        } catch (Throwable t) {
            LOG.error("[StartupCleanup] nukeLevel failed dim={} reason={} err={}",
                    sl.dimension().location(), reason, t.toString());
        }

        LOG.debug("[StartupCleanup] nukeLevel: dim={}, reason={}, removedSouls={}",
                sl.dimension().location(), reason, removedSouls);

        return removedSouls;
    }
}
