// File: src/main/java/org/z2six/infiniteupgrades/network/RepSnapshotS2C.java

package org.z2six.infiniteupgrades.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Snapshot of reputation values for Angel & Demon.
 */
public record RepSnapshotS2C(double angel, double demon) implements CustomPacketPayload {

    public static final Type<RepSnapshotS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("infiniteupgrades", "rep_snapshot_s2c"));

    public static final StreamCodec<FriendlyByteBuf, RepSnapshotS2C> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.DOUBLE, RepSnapshotS2C::angel,
                    ByteBufCodecs.DOUBLE, RepSnapshotS2C::demon,
                    RepSnapshotS2C::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
