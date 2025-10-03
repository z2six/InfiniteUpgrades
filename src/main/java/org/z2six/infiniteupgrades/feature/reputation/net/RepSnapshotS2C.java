// File: src/main/java/org/z2six/infiniteupgrades/network/RepSnapshotS2C.java
package org.z2six.infiniteupgrades.feature.reputation.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Snapshot of unified reputation value and server repMax.
 * - unified: [-repMax .. +repMax]
 * - repMax: absolute cap for magnitude (>=1)
 */
public record RepSnapshotS2C(double unified, int repMax) implements CustomPacketPayload {

    public static final Type<RepSnapshotS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("infiniteupgrades", "rep_snapshot_s2c"));

    public static final StreamCodec<FriendlyByteBuf, RepSnapshotS2C> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.DOUBLE, RepSnapshotS2C::unified,
                    ByteBufCodecs.VAR_INT, RepSnapshotS2C::repMax,
                    RepSnapshotS2C::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
