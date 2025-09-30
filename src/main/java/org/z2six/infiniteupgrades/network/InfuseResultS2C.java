// File: src/main/java/org/z2six/infiniteupgrades/network/InfuseResultS2C.java
package org.z2six.infiniteupgrades.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.infiniteupgrades.Infiniteupgrades;

/** S2C: server finalized an infusion attempt; tells client to unlock UI and show result. */
public record InfuseResultS2C(int containerId, boolean success) implements CustomPacketPayload {
    public static final Type<InfuseResultS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "infuse_result"));

    public static final StreamCodec<FriendlyByteBuf, InfuseResultS2C> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, InfuseResultS2C::containerId,
            ByteBufCodecs.BOOL, InfuseResultS2C::success,
            InfuseResultS2C::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
