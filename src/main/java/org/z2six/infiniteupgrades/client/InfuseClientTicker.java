// File: src/main/java/org/z2six/infiniteupgrades/client/InfuseClientTicker.java
package org.z2six.infiniteupgrades.client;

import net.neoforged.neoforge.client.event.ClientTickEvent;

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
    public static void onClientTick(ClientTickEvent.Post evt) {
        InfuseClientEffects.clientTick();
    }
}
