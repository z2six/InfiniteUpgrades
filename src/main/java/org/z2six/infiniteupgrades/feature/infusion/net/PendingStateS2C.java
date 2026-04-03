// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/net/PendingStateS2C.java
package org.z2six.infiniteupgrades.feature.infusion.net;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Authoritative snapshot of the current pending infusion state.
 * Allows client to resume lock/animation and optionally branch visuals when outcome is known early.
 */
public record PendingStateS2C(
        int containerId,
        boolean active,
        long endGameTime,
        int durationTicks,
        boolean outcomeKnown,
        boolean willSucceed
) {
    public static void encode(PendingStateS2C msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.containerId);
        buf.writeBoolean(msg.active);
        buf.writeVarLong(msg.endGameTime);
        buf.writeVarInt(msg.durationTicks);
        buf.writeBoolean(msg.outcomeKnown);
        buf.writeBoolean(msg.willSucceed);
    }

    public static PendingStateS2C decode(FriendlyByteBuf buf) {
        return new PendingStateS2C(
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readVarLong(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readBoolean()
        );
    }
}
