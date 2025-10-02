// File: src/main/java/org/z2six/infiniteupgrades/logic/InfuseTimers.java
package org.z2six.infiniteupgrades.logic;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.network.ModNet;
import org.z2six.infiniteupgrades.world.menu.AngelDemonMenu;

/**
 * Server tick hook that finalizes pending infusions.
 * Registered from Infiniteupgrades ctor: NeoForge.EVENT_BUS.addListener(InfuseTimers::onLevelTick)
 */
public final class InfuseTimers {
    private static final Logger LOG = LogUtils.getLogger();

    private InfuseTimers() {}

    @SubscribeEvent
    public static void onLevelTick(ServerTickEvent.Post evt) {
        var server = evt.getServer();
        if (server == null) return;

        // Use overworld gameTime as the canonical clock (all dimensions tick together in SP/typical servers).
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        long now = overworld.getGameTime();

        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            try {
                Boolean success = PendingStore.finalizeIfReady(sp, now);
                if (success == null) continue; // not ready

                // If their menu is open, mirror attachment->menu slot2 immediately
                if (sp.containerMenu instanceof AngelDemonMenu menu) {
                    menu.serverPullResultFromAttachment();
                    // Tell client to unlock/stop anim
                    ModNet.sendInfuseResultTo(sp, menu.containerId, success);
                } else {
                    // Even if the menu isn't open, it's fine — result sits in the attachment until next open.
                    if (success) {
                        LOG.debug("[InfuseTimers] finalize: success for {}, menu closed", sp.getGameProfile().getName());
                    } else {
                        LOG.debug("[InfuseTimers] finalize: fail for {}, menu closed", sp.getGameProfile().getName());
                    }
                }
            } catch (Throwable t) {
                LOG.error("[InfuseTimers] finalize loop failed for {}", sp.getGameProfile().getName(), t);
            }
        }
    }
}
