// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/net/InfuseStartedS2C.java
package org.z2six.infiniteupgrades.feature.infusion.net;

import net.minecraft.network.FriendlyByteBuf;
import org.z2six.infiniteupgrades.feature.infusion.logic.RitualType;

/** S2C: server armed an infusion attempt; provides authoritative timing for client UI lock/animation. */
public record InfuseStartedS2C(int containerId, long endGameTime, int durationTicks, RitualType ritual) {
    public static void encode(InfuseStartedS2C msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.containerId);
        buf.writeVarLong(msg.endGameTime);
        buf.writeVarInt(msg.durationTicks);
        buf.writeVarInt(msg.ritual.ordinal());
    }

    public static InfuseStartedS2C decode(FriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        long endGameTime = buf.readVarLong();
        int durationTicks = buf.readVarInt();
        int ord = buf.readVarInt();
        RitualType[] values = RitualType.values();
        RitualType ritual = values[Math.max(0, Math.min(ord, values.length - 1))];
        return new InfuseStartedS2C(containerId, endGameTime, durationTicks, ritual);
    }
}
