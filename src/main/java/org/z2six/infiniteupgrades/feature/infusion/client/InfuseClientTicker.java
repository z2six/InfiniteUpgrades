// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/client/InfuseClientTicker.java
package org.z2six.infiniteupgrades.feature.infusion.client;

import net.minecraftforge.event.TickEvent;

/**
 * Ticks client-side to trigger SFX exactly at the flashing-start threshold.
 *
 * NOTE: This class no longer uses @EventBusSubscriber. It is wired up from
 * Infiniteupgrades.Client.clientSetup(...) via NeoForge.EVENT_BUS.addListener(...).
 */
// File path: src/main/java/org/z2six/infiniteupgrades/client/InfuseClientTicker.java
public final class InfuseClientTicker {
    private InfuseClientTicker() {}

    /** Registered as a listener with NeoForge.EVENT_BUS in client setup. */
    public static void onClientTick(TickEvent.ClientTickEvent evt) {
        if (evt.phase != TickEvent.Phase.END) return;
        InfuseClientEffects.clientTick();
    }
}
