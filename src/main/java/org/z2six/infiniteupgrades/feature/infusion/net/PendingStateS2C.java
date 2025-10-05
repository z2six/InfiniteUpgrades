// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/net/PendingStateS2C.java
package org.z2six.infiniteupgrades.feature.infusion.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;

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
) implements CustomPacketPayload {

    public static final Type<PendingStateS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "pending_state_s2c"));

    public static final StreamCodec<FriendlyByteBuf, PendingStateS2C> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,  PendingStateS2C::containerId,
                    ByteBufCodecs.BOOL,     PendingStateS2C::active,
                    ByteBufCodecs.VAR_LONG, PendingStateS2C::endGameTime,
                    ByteBufCodecs.VAR_INT,  PendingStateS2C::durationTicks,
                    ByteBufCodecs.BOOL,     PendingStateS2C::outcomeKnown,
                    ByteBufCodecs.BOOL,     PendingStateS2C::willSucceed,
                    PendingStateS2C::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
