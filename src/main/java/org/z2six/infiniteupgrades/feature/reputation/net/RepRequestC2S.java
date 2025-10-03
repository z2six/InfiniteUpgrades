// File: src/main/java/org/z2six/infiniteupgrades/feature/reputation/net/RepRequestC2S.java

package org.z2six.infiniteupgrades.feature.reputation.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Empty C2S packet requesting a reputation snapshot for the local player.
 */
public enum RepRequestC2S implements CustomPacketPayload {
    INSTANCE;

    public static final Type<RepRequestC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("infiniteupgrades", "rep_request_c2s"));

    public static final StreamCodec<FriendlyByteBuf, RepRequestC2S> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
