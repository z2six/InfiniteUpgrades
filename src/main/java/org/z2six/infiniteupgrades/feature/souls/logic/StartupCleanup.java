// File: src/main/java/org/z2six/infiniteupgrades/feature/souls/logic/StartupCleanup.java
package org.z2six.infiniteupgrades.feature.souls.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.z2six.infiniteupgrades.feature.souls.entity.SoulOrbEntity;

/**
 * Nukes all souls and their lights when the server starts and whenever a server level loads.
 * Works for both singleplayer (integrated server) and dedicated servers.
 */
@EventBusSubscriber(modid = Infiniteupgrades.MODID)
public final class StartupCleanup {
    private static final Logger LOG = LogUtils.getLogger();

    private StartupCleanup() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent evt) {
        MinecraftServer server = evt.getServer();
        for (ServerLevel sl : server.getAllLevels()) {
            nukeLevel(sl, "server-started");
        }
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load evt) {
        if (evt.getLevel() instanceof ServerLevel sl) {
            nukeLevel(sl, "level-load");
        }
    }

    private static void nukeLevel(ServerLevel sl, String reason) {
        // 1) Remove any lingering soul entities from our mod
        int removedSouls = 0;
        // Big AABB to cover the whole loaded area; this is cheap because it only scans loaded chunks.
        AABB huge = new AABB(-3.0E7, -3.0E7, -3.0E7, 3.0E7, 3.0E7, 3.0E7);
        for (SoulOrbEntity orb : sl.getEntitiesOfClass(SoulOrbEntity.class, huge)) {
            orb.discard();
            removedSouls++;
        }
    }
}
