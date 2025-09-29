// File: src/main/java/org/z2six/infiniteupgrades/network/ModNet.java

package org.z2six.infiniteupgrades.network;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.Infiniteupgrades;
import org.z2six.infiniteupgrades.client.screen.AngelDemonScreen;
import org.z2six.infiniteupgrades.logic.Reputation;
import org.z2six.infiniteupgrades.logic.RitualType;

/**
 * Network registration + helpers for reputation sync.
 */
@EventBusSubscriber(modid = Infiniteupgrades.MODID) // default is MOD bus; 'bus' parameter is deprecated
public final class ModNet {
    private static final Logger LOG = LogUtils.getLogger();

    private ModNet() {}

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        var reg = event.registrar(Infiniteupgrades.MODID);

        // Client -> Server: request snapshot
        reg.playToServer(RepRequestC2S.TYPE, RepRequestC2S.STREAM_CODEC, (payload, ctx) -> {
            var p = ctx.player();
            if (!(p instanceof ServerPlayer sp)) {
                return;
            }
            double a = Reputation.get(sp, RitualType.ANGEL);
            double d = Reputation.get(sp, RitualType.DEMON);
            PacketDistributor.sendToPlayer(sp, new RepSnapshotS2C(a, d));
        });

        // Server -> Client: deliver snapshot
        reg.playToClient(RepSnapshotS2C.TYPE, RepSnapshotS2C.STREAM_CODEC, (payload, ctx) -> {
            ctx.enqueueWork(() -> {
                try {
                    var mc = Minecraft.getInstance();
                    if (mc != null && mc.screen instanceof AngelDemonScreen sc) {
                        LOG.debug("[ModNet] S2C rep snapshot: angel={} demon={}", payload.angel(), payload.demon());
                        sc.acceptRepSnapshot(payload.angel(), payload.demon());
                    }
                } catch (Throwable t) {
                    LOG.error("[ModNet] Failed to apply rep snapshot on client", t);
                }
            });
        });
    }

    // --- Helpers ---

    /** Client call: request current reputation values from the server. */
    public static void requestRepSnapshot() {
        try {
            PacketDistributor.sendToServer(RepRequestC2S.INSTANCE);
        } catch (Throwable t) {
            LOG.error("[ModNet] Failed to send RepRequestC2S", t);
        }
    }

    /** Server call: push a fresh snapshot to a specific player. */
    public static void sendRepSnapshotTo(ServerPlayer sp) {
        try {
            double a = Reputation.get(sp, RitualType.ANGEL);
            double d = Reputation.get(sp, RitualType.DEMON);
            PacketDistributor.sendToPlayer(sp, new RepSnapshotS2C(a, d));
        } catch (Throwable t) {
            LOG.error("[ModNet] Failed to send RepSnapshotS2C to {}", sp, t);
        }
    }
}
