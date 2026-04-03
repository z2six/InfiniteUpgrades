// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/net/InfuseResultS2C.java
package org.z2six.infiniteupgrades.feature.infusion.net;

import net.minecraft.network.FriendlyByteBuf;

/** S2C: server finalized an infusion attempt; tells client to unlock UI and show result. */
public record InfuseResultS2C(int containerId, boolean success) {
    public static void encode(InfuseResultS2C msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.containerId);
        buf.writeBoolean(msg.success);
    }

    public static InfuseResultS2C decode(FriendlyByteBuf buf) {
        return new InfuseResultS2C(buf.readVarInt(), buf.readBoolean());
    }
}
