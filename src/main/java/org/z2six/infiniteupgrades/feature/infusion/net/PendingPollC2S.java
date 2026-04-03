// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/net/PendingPollC2S.java
package org.z2six.infiniteupgrades.feature.infusion.net;

import net.minecraft.network.FriendlyByteBuf;

/** Empty C->S ping asking for the current pending infusion state. */
public enum PendingPollC2S {
    INSTANCE;

    public static void encode(PendingPollC2S msg, FriendlyByteBuf buf) {
    }

    public static PendingPollC2S decode(FriendlyByteBuf buf) {
        return INSTANCE;
    }
}
