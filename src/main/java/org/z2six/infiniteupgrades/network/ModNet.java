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
import org.z2six.infiniteupgrades.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.logic.Reputation;
import org.z2six.infiniteupgrades.world.menu.AngelDemonMenu;

/**
 * Network registration + helpers for reputation sync and infusion S2C notifications.
 */
@EventBusSubscriber(modid = Infiniteupgrades.MODID) // default MOD bus; 'bus' param deprecated on NeoForge 1.21.1
public final class ModNet {
    private static final Logger LOG = LogUtils.getLogger();

    private ModNet() {}

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        var reg = event.registrar(Infiniteupgrades.MODID);

        // --- Reputation messages ---

        // Client -> Server: request snapshot
        reg.playToServer(RepRequestC2S.TYPE, RepRequestC2S.STREAM_CODEC, (payload, ctx) -> {
            var p = ctx.player();
            if (!(p instanceof ServerPlayer sp)) {
                return;
            }
            double unified = Reputation.get(sp);
            int repMax = Math.max(1, UpgradeServerConfig.snapshot().repMax);
            PacketDistributor.sendToPlayer(sp, new RepSnapshotS2C(unified, repMax));
        });

        // Server -> Client: deliver snapshot
        reg.playToClient(RepSnapshotS2C.TYPE, RepSnapshotS2C.STREAM_CODEC, (payload, ctx) -> {
            ctx.enqueueWork(() -> {
                try {
                    var mc = Minecraft.getInstance();
                    if (mc != null && mc.screen instanceof AngelDemonScreen sc) {
                        LOG.debug("[ModNet] S2C rep snapshot: unified={} repMax={}", payload.unified(), payload.repMax());
                        sc.acceptRepSnapshot(payload.unified(), payload.repMax());
                    }
                } catch (Throwable t) {
                    LOG.error("[ModNet] Failed to apply rep snapshot on client", t);
                }
            });
        });

        // --- Infusion S2C messages (timer + result) ---

        reg.playToClient(InfuseStartedS2C.TYPE, InfuseStartedS2C.STREAM_CODEC, (payload, ctx) -> {
            ctx.enqueueWork(() -> {
                try {
                    var mc = Minecraft.getInstance();
                    if (mc != null && mc.player != null && mc.player.containerMenu instanceof AngelDemonMenu menu) {
                        // We purposefully do not rely on containerId here; one container at a time per player.
                        menu.clientOnInfuseStarted(payload.endGameTime(), payload.durationTicks());
                        LOG.debug("[ModNet] S2C InfuseStarted: endTime={} durationTicks={}", payload.endGameTime(), payload.durationTicks());
                    }
                } catch (Throwable t) {
                    LOG.error("[ModNet] Failed to apply InfuseStartedS2C on client", t);
                }
            });
        });

        reg.playToClient(InfuseResultS2C.TYPE, InfuseResultS2C.STREAM_CODEC, (payload, ctx) -> {
            ctx.enqueueWork(() -> {
                try {
                    var mc = Minecraft.getInstance();
                    if (mc != null && mc.player != null && mc.player.containerMenu instanceof AngelDemonMenu menu) {
                        menu.clientOnInfuseResult(payload.success());
                        LOG.debug("[ModNet] S2C InfuseResult: success={}", payload.success());
                    }
                } catch (Throwable t) {
                    LOG.error("[ModNet] Failed to apply InfuseResultS2C on client", t);
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
            double unified = Reputation.get(sp);
            int repMax = Math.max(1, UpgradeServerConfig.snapshot().repMax);
            PacketDistributor.sendToPlayer(sp, new RepSnapshotS2C(unified, repMax));
        } catch (Throwable t) {
            LOG.error("[ModNet] Failed to send RepSnapshotS2C to {}", sp, t);
        }
    }

    /** Server call: notify client that an infusion attempt has started with a delay. */
    public static void sendInfuseStartedTo(ServerPlayer sp, int containerId, long endGameTime, int durationTicks) {
        try {
            PacketDistributor.sendToPlayer(sp, new InfuseStartedS2C(containerId, endGameTime, durationTicks));
        } catch (Throwable t) {
            LOG.error("[ModNet] Failed to send InfuseStartedS2C to {}", sp, t);
        }
    }

    /** Server call: notify client that the infusion attempt finalized. */
    public static void sendInfuseResultTo(ServerPlayer sp, int containerId, boolean success) {
        try {
            PacketDistributor.sendToPlayer(sp, new InfuseResultS2C(containerId, success));
        } catch (Throwable t) {
            LOG.error("[ModNet] Failed to send InfuseResultS2C to {}", sp, t);
        }
    }
}
