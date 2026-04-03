// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/net/EarlyOutcomeS2C.java
package org.z2six.infiniteupgrades.feature.infusion.net;

import net.minecraft.network.FriendlyByteBuf;

/** Optional "early outcome" hint for client-side animations (no gameplay effect). */
public record EarlyOutcomeS2C(boolean willSucceed) {
    public static void encode(EarlyOutcomeS2C msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.willSucceed);
    }

    public static EarlyOutcomeS2C decode(FriendlyByteBuf buf) {
        return new EarlyOutcomeS2C(buf.readBoolean());
    }
}
