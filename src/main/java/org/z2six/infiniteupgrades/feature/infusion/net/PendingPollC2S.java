// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/net/PendingPollC2S.java
package org.z2six.infiniteupgrades.feature.infusion.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;

/** Empty C->S ping asking for the current pending infusion state. */
public enum PendingPollC2S implements CustomPacketPayload {
    INSTANCE;

    public static final Type<PendingPollC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "pending_poll_c2s"));

    public static final StreamCodec<FriendlyByteBuf, PendingPollC2S> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
