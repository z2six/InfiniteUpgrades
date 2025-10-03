// File: src/main/java/org/z2six/infiniteupgrades/network/InfuseStartedS2C.java
package org.z2six.infiniteupgrades.feature.infusion.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;

/** S2C: server armed an infusion attempt; provides authoritative timing for client UI lock/animation. */
public record InfuseStartedS2C(int containerId, long endGameTime, int durationTicks) implements CustomPacketPayload {
    public static final Type<InfuseStartedS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "infuse_started"));

    public static final StreamCodec<FriendlyByteBuf, InfuseStartedS2C> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, InfuseStartedS2C::containerId,
            ByteBufCodecs.VAR_LONG, InfuseStartedS2C::endGameTime,
            ByteBufCodecs.VAR_INT, InfuseStartedS2C::durationTicks,
            InfuseStartedS2C::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
