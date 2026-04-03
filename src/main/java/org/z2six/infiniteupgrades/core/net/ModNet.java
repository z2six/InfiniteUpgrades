// File: src/main/java/org/z2six/infiniteupgrades/core/net/ModNet.java
package org.z2six.infiniteupgrades.core.net;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.feature.infusion.client.InfuseClientEffects;
import org.z2six.infiniteupgrades.feature.infusion.client.screen.AngelDemonScreen;
import org.z2six.infiniteupgrades.feature.infusion.logic.PendingStore;
import org.z2six.infiniteupgrades.feature.infusion.logic.RitualType;
import org.z2six.infiniteupgrades.feature.infusion.menu.AngelDemonMenu;
import org.z2six.infiniteupgrades.feature.infusion.net.EarlyOutcomeS2C;
import org.z2six.infiniteupgrades.feature.infusion.net.InfuseResultS2C;
import org.z2six.infiniteupgrades.feature.infusion.net.InfuseStartedS2C;
import org.z2six.infiniteupgrades.feature.infusion.net.PendingPollC2S;
import org.z2six.infiniteupgrades.feature.infusion.net.PendingStateS2C;
import org.z2six.infiniteupgrades.feature.reputation.logic.Reputation;
import org.z2six.infiniteupgrades.feature.reputation.net.RepRequestC2S;
import org.z2six.infiniteupgrades.feature.reputation.net.RepSnapshotS2C;

public final class ModNet {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String PROTOCOL_VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(Infiniteupgrades.MODID, "main"))
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            .clientAcceptedVersions(PROTOCOL_VERSION::equals)
            .serverAcceptedVersions(PROTOCOL_VERSION::equals)
            .simpleChannel();

    private static boolean initialized;

    private ModNet() {}

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        CHANNEL.messageBuilder(RepRequestC2S.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RepRequestC2S::encode)
                .decoder(RepRequestC2S::decode)
                .consumerMainThread((payload, ctxSupplier) -> {
                    ServerPlayer sp = ctxSupplier.get().getSender();
                    if (sp == null) {
                        return;
                    }
                    double unified = Reputation.get(sp);
                    int repMax = Math.max(1, UpgradeServerConfig.snapshot().repMax);
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new RepSnapshotS2C(unified, repMax));
                })
                .add();

        CHANNEL.messageBuilder(RepSnapshotS2C.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RepSnapshotS2C::encode)
                .decoder(RepSnapshotS2C::decode)
                .consumerMainThread((payload, ctxSupplier) -> {
                    try {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc != null && mc.screen instanceof AngelDemonScreen sc) {
                            LOG.debug("[ModNet] S2C rep snapshot: unified={} repMax={}", payload.unified(), payload.repMax());
                            sc.acceptRepSnapshot(payload.unified(), payload.repMax());
                        }
                    } catch (Throwable t) {
                        LOG.error("[ModNet] Failed to apply rep snapshot on client", t);
                    }
                })
                .add();

        CHANNEL.messageBuilder(InfuseStartedS2C.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(InfuseStartedS2C::encode)
                .decoder(InfuseStartedS2C::decode)
                .consumerMainThread((payload, ctxSupplier) -> {
                    try {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc != null && mc.player != null && mc.player.containerMenu instanceof AngelDemonMenu menu) {
                            menu.clientOnInfuseStarted(payload.endGameTime(), payload.durationTicks());
                            LOG.debug("[ModNet] S2C InfuseStarted: endTime={} durationTicks={} ritual={}",
                                    payload.endGameTime(), payload.durationTicks(), payload.ritual());
                        }
                        InfuseClientEffects.onInfuseStarted(payload.endGameTime(), payload.durationTicks(), payload.ritual());
                    } catch (Throwable t) {
                        LOG.error("[ModNet] Failed to apply InfuseStartedS2C on client", t);
                    }
                })
                .add();

        CHANNEL.messageBuilder(InfuseResultS2C.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(InfuseResultS2C::encode)
                .decoder(InfuseResultS2C::decode)
                .consumerMainThread((payload, ctxSupplier) -> {
                    try {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc != null && mc.player != null && mc.player.containerMenu instanceof AngelDemonMenu menu) {
                            menu.clientOnInfuseResult(payload.success());
                            LOG.debug("[ModNet] S2C InfuseResult: success={}", payload.success());
                        }
                        InfuseClientEffects.onInfuseFinalized();
                    } catch (Throwable t) {
                        LOG.error("[ModNet] Failed to apply InfuseResultS2C on client", t);
                    }
                })
                .add();

        CHANNEL.messageBuilder(PendingPollC2S.class, 4, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PendingPollC2S::encode)
                .decoder(PendingPollC2S::decode)
                .consumerMainThread((payload, ctxSupplier) -> {
                    ServerPlayer sp = ctxSupplier.get().getSender();
                    if (sp == null) {
                        return;
                    }

                    var s = PendingStore.read(sp);
                    if (sp.containerMenu instanceof AngelDemonMenu menu) {
                        long now = sp.level().getGameTime();
                        boolean outcomeKnown = false;
                        if (s.active() && s.duration() > 0) {
                            int finaleTicks = Math.max(10, Math.min(80, (int) Math.round(s.duration() * 0.15)));
                            long finaleThreshold = s.end() - Math.max(1, finaleTicks);
                            outcomeKnown = now >= finaleThreshold && now < s.end();
                        }
                        CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new PendingStateS2C(
                                menu.containerId,
                                s.active(),
                                s.end(),
                                s.duration(),
                                outcomeKnown,
                                s.success()
                        ));
                    }
                })
                .add();

        CHANNEL.messageBuilder(PendingStateS2C.class, 5, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PendingStateS2C::encode)
                .decoder(PendingStateS2C::decode)
                .consumerMainThread((payload, ctxSupplier) -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null && mc.player != null && mc.player.containerMenu instanceof AngelDemonMenu menu) {
                        menu.clientOnPendingState(payload);
                    }
                })
                .add();

        CHANNEL.messageBuilder(EarlyOutcomeS2C.class, 6, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(EarlyOutcomeS2C::encode)
                .decoder(EarlyOutcomeS2C::decode)
                .consumerMainThread((payload, ctxSupplier) -> {
                    try {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc != null && mc.player != null && mc.player.containerMenu instanceof AngelDemonMenu menu) {
                            menu.clientOnEarlyOutcome(payload.willSucceed());
                        }
                        InfuseClientEffects.onOutcomeKnown(payload.willSucceed());
                    } catch (Throwable t) {
                        LOG.error("[ModNet] Failed to apply EarlyOutcomeS2C on client", t);
                    }
                })
                .add();
    }

    public static void requestRepSnapshot() {
        try {
            CHANNEL.sendToServer(RepRequestC2S.INSTANCE);
        } catch (Throwable t) {
            LOG.error("[ModNet] Failed to send RepRequestC2S", t);
        }
    }

    public static void sendRepSnapshotTo(ServerPlayer sp) {
        try {
            double unified = Reputation.get(sp);
            int repMax = Math.max(1, UpgradeServerConfig.snapshot().repMax);
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new RepSnapshotS2C(unified, repMax));
        } catch (Throwable t) {
            LOG.error("[ModNet] Failed to send RepSnapshotS2C to {}", sp, t);
        }
    }

    public static void sendInfuseStartedTo(ServerPlayer sp, int containerId, long endGameTime, int durationTicks, RitualType ritual) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new InfuseStartedS2C(containerId, endGameTime, durationTicks, ritual));
        } catch (Throwable t) {
            LOG.error("[ModNet] Failed to send InfuseStartedS2C to {}", sp, t);
        }
    }

    public static void sendInfuseStartedTo(ServerPlayer sp, int containerId, long endGameTime, int durationTicks) {
        RitualType ritual = RitualType.ANGEL;
        try {
            if (sp.containerMenu instanceof AngelDemonMenu menu && menu.containerId == containerId) {
                ritual = menu.ritual();
            }
        } catch (Throwable ignore) {
        }
        sendInfuseStartedTo(sp, containerId, endGameTime, durationTicks, ritual);
    }

    public static void sendInfuseResultTo(ServerPlayer sp, int containerId, boolean success) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new InfuseResultS2C(containerId, success));
        } catch (Throwable t) {
            LOG.error("[ModNet] Failed to send InfuseResultS2C to {}", sp, t);
        }
    }

    public static void requestPendingState() {
        try {
            CHANNEL.sendToServer(PendingPollC2S.INSTANCE);
        } catch (Throwable t) {
            LOG.error("[ModNet] Failed to send PendingPollC2S", t);
        }
    }

    public static void sendEarlyOutcomeTo(ServerPlayer sp, boolean willSucceed) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new EarlyOutcomeS2C(willSucceed));
        } catch (Throwable t) {
            LOG.error("[ModNet] Failed to send EarlyOutcomeS2C to {}", sp, t);
        }
    }
}
