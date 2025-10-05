// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/net/InfuseStartedS2C.java
package org.z2six.infiniteupgrades.feature.infusion.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.z2six.infiniteupgrades.feature.infusion.logic.RitualType;

/** S2C: server armed an infusion attempt; provides authoritative timing for client UI lock/animation. */
public record InfuseStartedS2C(int containerId, long endGameTime, int durationTicks, RitualType ritual)
        implements CustomPacketPayload {

    public static final Type<InfuseStartedS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "infuse_started"));

    // Codec for RitualType via ordinal
    public static final StreamCodec<FriendlyByteBuf, RitualType> RITUAL_CODEC =
            StreamCodec.of(
                    (buf, value) -> buf.writeVarInt(value.ordinal()),
                    buf -> {
                        int ord = buf.readVarInt();
                        RitualType[] vals = RitualType.values();
                        return vals[Math.max(0, Math.min(ord, vals.length - 1))];
                    }
            );

    public static final StreamCodec<FriendlyByteBuf, InfuseStartedS2C> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,  InfuseStartedS2C::containerId,
            ByteBufCodecs.VAR_LONG, InfuseStartedS2C::endGameTime,
            ByteBufCodecs.VAR_INT,  InfuseStartedS2C::durationTicks,
            RITUAL_CODEC,           InfuseStartedS2C::ritual,
            InfuseStartedS2C::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
