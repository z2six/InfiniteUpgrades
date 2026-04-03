// File: src/main/java/org/z2six/infiniteupgrades/feature/reputation/net/RepSnapshotS2C.java
package org.z2six.infiniteupgrades.feature.reputation.net;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Snapshot of unified reputation value and server repMax.
 * - unified: [-repMax .. +repMax]
 * - repMax: absolute cap for magnitude (>=1)
 */
public record RepSnapshotS2C(double unified, int repMax) {
    public static void encode(RepSnapshotS2C msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.unified);
        buf.writeVarInt(msg.repMax);
    }

    public static RepSnapshotS2C decode(FriendlyByteBuf buf) {
        return new RepSnapshotS2C(buf.readDouble(), buf.readVarInt());
    }
}
