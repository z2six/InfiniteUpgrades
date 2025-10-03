// File: src/main/java/org/z2six/infiniteupgrades/logic/InfuseTimers.java
package org.z2six.infiniteupgrades.logic;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.client.ProgressPhases; // <-- NEW: reuse same math
import org.z2six.infiniteupgrades.network.ModNet;
import org.z2six.infiniteupgrades.world.menu.AngelDemonMenu;

public final class InfuseTimers {
    private static final Logger LOG = LogUtils.getLogger();

    private InfuseTimers() {}

    @SubscribeEvent
    public static void onLevelTick(ServerTickEvent.Post evt) {
        var server = evt.getServer();
        if (server == null) return;

        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        long now = overworld.getGameTime();

        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            try {
                var s = PendingStore.read(sp);

                // --- EARLY OUTCOME HINT exactly at flashing start (fill complete) ---
                if (s.active() && s.duration() > 0 && !s.hintSent()) {
                    int beforeEnd = ProgressPhases.ticksBeforeEndAtFlashingStart(s.duration());
                    long flashingStart = s.end() - Math.max(1, beforeEnd);

                    if (now >= flashingStart && now < s.end()) {
                        ModNet.sendEarlyOutcomeTo(sp, s.success());
                        PendingStore.markHintSent(sp);
                        LOG.debug("[InfuseTimers] EarlyOutcome @flashingStart sent to {} (willSucceed={})",
                                sp.getGameProfile().getName(), s.success());
                    }
                }

                // --- FINALIZE IF READY ---
                Boolean success = PendingStore.finalizeIfReady(sp, now);
                if (success == null) continue;

                if (sp.containerMenu instanceof AngelDemonMenu menu) {
                    menu.serverPullResultFromAttachment();
                    ModNet.sendInfuseResultTo(sp, menu.containerId, success);
                } else {
                    LOG.debug("[InfuseTimers] finalize: {} for {} (menu closed)",
                            success ? "success" : "fail", sp.getGameProfile().getName());
                }
            } catch (Throwable t) {
                LOG.error("[InfuseTimers] finalize loop failed for {}", sp.getGameProfile().getName(), t);
            }
        }
    }
}
