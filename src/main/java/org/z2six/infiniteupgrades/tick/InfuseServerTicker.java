// File: src/main/java/org/z2six/infiniteupgrades/tick/InfuseServerTicker.java
package org.z2six.infiniteupgrades.tick;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.logic.PendingStore;
import org.z2six.infiniteupgrades.network.ModNet;
import org.z2six.infiniteupgrades.world.menu.AngelDemonMenu;

/**
 * Server tick hook that finalizes pending infusions and handles early-outcome hints.
 * If you also have InfuseTimers, keep only one of these tickers active.
 */
public final class InfuseServerTicker {
    private static final Logger LOG = LogUtils.getLogger();

    private InfuseServerTicker() {}

    @SubscribeEvent
    public static void onLevelTick(ServerTickEvent.Post evt) {
        var server = evt.getServer();
        if (server == null) return;

        ServerLevel overworld = server.overworld();
        if (overworld == null) return;

        long now = overworld.getGameTime();

        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            try {
                // Early-outcome visual hint (optional)
                var s = PendingStore.read(sp);
                if (s.active() && s.duration() > 0) {
                    int finaleTicks = Math.max(10, Math.min(80, (int) Math.round(s.duration() * 0.15)));
                    long finaleThreshold = s.end() - Math.max(1, finaleTicks);
                    if (now >= finaleThreshold && now < s.end()) {
                        ModNet.sendEarlyOutcomeTo(sp, s.success());
                    }
                }

                // Finalize if ready
                Boolean success = PendingStore.finalizeIfReady(sp, now);
                if (success == null) continue;

                if (sp.containerMenu instanceof AngelDemonMenu menu) {
                    menu.serverPullResultFromAttachment();
                    ModNet.sendInfuseResultTo(sp, menu.containerId, success);
                } else {
                    if (success) {
                        LOG.debug("[InfuseServerTicker] finalize: success for {}, menu closed", sp.getGameProfile().getName());
                    } else {
                        LOG.debug("[InfuseServerTicker] finalize: fail for {}, menu closed", sp.getGameProfile().getName());
                    }
                }
            } catch (Throwable t) {
                LOG.error("[InfuseServerTicker] finalize loop failed for {}", sp.getGameProfile().getName(), t);
            }
        }
    }
}
