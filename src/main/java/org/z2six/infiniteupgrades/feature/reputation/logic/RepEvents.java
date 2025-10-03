// File: src/main/java/org/z2six/infiniteupgrades/feature/reputation/logic/RepEvents.java

package org.z2six.infiniteupgrades.feature.reputation.logic;

import com.mojang.logging.LogUtils;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

/**
 * GAME-bus listener; registered in Infiniteupgrades ctor via NeoForge.EVENT_BUS.addListener.
 */
public final class RepEvents {
    private static final Logger LOG = LogUtils.getLogger();

    private RepEvents() {}

    // Method signature matches the event type; no annotation needed when using addListener(...)
    public static void onPlayerClone(PlayerEvent.Clone evt) {
        try {
            if (evt.getOriginal() == null || evt.getEntity() == null) return;
            Reputation.copyOnClone(evt.getOriginal(), evt.getEntity());
            LOG.debug("[RepEvents] Copied reputation on clone (wasDeath={})", evt.isWasDeath());
        } catch (Throwable t) {
            LOG.error("[RepEvents] onPlayerClone failed: {}", t.toString());
        }
    }
}
