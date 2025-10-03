// File: src/main/java/org/z2six/infiniteupgrades/core/net/ModNet.java
package org.z2six.infiniteupgrades.core.net;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.z2six.infiniteupgrades.feature.infusion.client.InfuseClientEffects;
import org.z2six.infiniteupgrades.feature.infusion.client.screen.AngelDemonScreen;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.feature.infusion.logic.PendingStore;
import org.z2six.infiniteupgrades.feature.infusion.net.*;
import org.z2six.infiniteupgrades.feature.reputation.logic.Reputation;
import org.z2six.infiniteupgrades.feature.reputation.net.RepRequestC2S;
import org.z2six.infiniteupgrades.feature.reputation.net.RepSnapshotS2C;
import org.z2six.infiniteupgrades.feature.infusion.menu.AngelDemonMenu;

@EventBusSubscriber(modid = Infiniteupgrades.MODID)
public final class ModNet {
    private static final Logger LOG = LogUtils.getLogger();

    private ModNet() {}

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        var reg = event.registrar(Infiniteupgrades.MODID);

        // -------- Reputation --------
        reg.playToServer(RepRequestC2S.TYPE, RepRequestC2S.STREAM_CODEC, (payload, ctx) -> {
            var p = ctx.player();
            if (!(p instanceof ServerPlayer sp)) return;
            double unified = Reputation.get(sp);
            int repMax = Math.max(1, UpgradeServerConfig.snapshot().repMax);
            PacketDistributor.sendToPlayer(sp, new RepSnapshotS2C(unified, repMax));
        });

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

        // -------- Infusion lifecycle --------
        reg.playToClient(InfuseStartedS2C.TYPE, InfuseStartedS2C.STREAM_CODEC, (payload, ctx) -> {
            ctx.enqueueWork(() -> {
                try {
                    var mc = Minecraft.getInstance();
                    if (mc != null && mc.player != null && mc.player.containerMenu instanceof AngelDemonMenu menu) {
                        menu.clientOnInfuseStarted(payload.endGameTime(), payload.durationTicks());
                        LOG.debug("[ModNet] S2C InfuseStarted: endTime={} durationTicks={}", payload.endGameTime(), payload.durationTicks());
                    }
                    // NEW: provide timing to global client effects (GUI open or closed)
                    InfuseClientEffects.onInfuseStarted(payload.endGameTime(), payload.durationTicks());
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
                    // NEW: clear any scheduled sound/animation state
                    InfuseClientEffects.reset();
                } catch (Throwable t) {
                    LOG.error("[ModNet] Failed to apply InfuseResultS2C on client", t);
                }
            });
        });

        // -------- Pending state / resume --------
        reg.playToServer(PendingPollC2S.TYPE, PendingPollC2S.STREAM_CODEC, (payload, ctx) -> {
            var p = ctx.player();
            if (!(p instanceof ServerPlayer sp)) return;

            var s = PendingStore.read(sp);
            if (sp.containerMenu instanceof AngelDemonMenu menu) {
                long now = sp.level().getGameTime();
                boolean outcomeKnown = false;
                if (s.active() && s.duration() > 0) {
                    int finaleTicks = Math.max(10, Math.min(80, (int) Math.round(s.duration() * 0.15)));
                    long finaleThreshold = s.end() - Math.max(1, finaleTicks);
                    outcomeKnown = (now >= finaleThreshold && now < s.end());
                }
                PacketDistributor.sendToPlayer(sp, new PendingStateS2C(
                        menu.containerId,
                        s.active(),
                        s.end(),
                        s.duration(),
                        outcomeKnown,
                        s.success()
                ));
            }
        });

        reg.playToClient(PendingStateS2C.TYPE, PendingStateS2C.STREAM_CODEC, (payload, ctx) -> {
            ctx.enqueueWork(() -> {
                var mc = Minecraft.getInstance();
                if (mc != null && mc.player != null && mc.player.containerMenu instanceof AngelDemonMenu menu) {
                    menu.clientOnPendingState(payload);
                }
                // NOTE: we don't call InfuseClientEffects here because PendingState is a resume snapshot;
                // InfuseStarted/EarlyOutcome already seed the effect scheduler.
            });
        });

        // Early outcome (visual + timing for client effects)
        reg.playToClient(EarlyOutcomeS2C.TYPE, EarlyOutcomeS2C.STREAM_CODEC, (payload, ctx) -> {
            ctx.enqueueWork(() -> {
                try {
                    var mc = Minecraft.getInstance();
                    if (mc != null && mc.player != null && mc.player.containerMenu instanceof AngelDemonMenu menu) {
                        menu.clientOnEarlyOutcome(payload.willSucceed());
                    }
                    // NEW: inform global client effects so they can schedule sound exactly at flashing start
                    InfuseClientEffects.onOutcomeKnown(payload.willSucceed());
                } catch (Throwable t) {
                    LOG.error("[ModNet] Failed to apply EarlyOutcomeS2C on client", t);
                }
            });
        });
    }

    // -------- Public helpers --------
    public static void requestRepSnapshot() {
        try { PacketDistributor.sendToServer(RepRequestC2S.INSTANCE); }
        catch (Throwable t) { LOG.error("[ModNet] Failed to send RepRequestC2S", t); }
    }

    public static void sendRepSnapshotTo(ServerPlayer sp) {
        try {
            double unified = Reputation.get(sp);
            int repMax = Math.max(1, UpgradeServerConfig.snapshot().repMax);
            PacketDistributor.sendToPlayer(sp, new RepSnapshotS2C(unified, repMax));
        } catch (Throwable t) {
            LOG.error("[ModNet] Failed to send RepSnapshotS2C to {}", sp, t);
        }
    }

    public static void sendInfuseStartedTo(ServerPlayer sp, int containerId, long endGameTime, int durationTicks) {
        try { PacketDistributor.sendToPlayer(sp, new InfuseStartedS2C(containerId, endGameTime, durationTicks)); }
        catch (Throwable t) { LOG.error("[ModNet] Failed to send InfuseStartedS2C to {}", sp, t); }
    }

    public static void sendInfuseResultTo(ServerPlayer sp, int containerId, boolean success) {
        try { PacketDistributor.sendToPlayer(sp, new InfuseResultS2C(containerId, success)); }
        catch (Throwable t) { LOG.error("[ModNet] Failed to send InfuseResultS2C to {}", sp, t); }
    }

    public static void requestPendingState() {
        try { PacketDistributor.sendToServer(PendingPollC2S.INSTANCE); }
        catch (Throwable t) { LOG.error("[ModNet] Failed to send PendingPollC2S", t); }
    }

    public static void sendEarlyOutcomeTo(ServerPlayer sp, boolean willSucceed) {
        try { PacketDistributor.sendToPlayer(sp, new EarlyOutcomeS2C(willSucceed)); }
        catch (Throwable t) { LOG.error("[ModNet] Failed to send EarlyOutcomeS2C to {}", sp, t); }
    }
}
