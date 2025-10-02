// File: src/main/java/org/z2six/infiniteupgrades/network/EarlyOutcomeS2C.java
package org.z2six.infiniteupgrades.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.infiniteupgrades.Infiniteupgrades;

/** Optional "early outcome" hint for client-side animations (no gameplay effect). */
public record EarlyOutcomeS2C(boolean willSucceed) implements CustomPacketPayload {

    public static final Type<EarlyOutcomeS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "early_outcome_s2c"));

    public static final StreamCodec<FriendlyByteBuf, EarlyOutcomeS2C> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, EarlyOutcomeS2C::willSucceed,
                    EarlyOutcomeS2C::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
