// File: src/main/java/org/z2six/infiniteupgrades/feature/reputation/net/RepRequestC2S.java
package org.z2six.infiniteupgrades.feature.reputation.net;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Empty C2S packet requesting a reputation snapshot for the local player.
 */
public enum RepRequestC2S {
    INSTANCE;

    public static void encode(RepRequestC2S msg, FriendlyByteBuf buf) {
    }

    public static RepRequestC2S decode(FriendlyByteBuf buf) {
        return INSTANCE;
    }
}
