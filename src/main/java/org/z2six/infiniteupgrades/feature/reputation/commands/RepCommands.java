// File: src/main/java/org/z2six/infiniteupgrades/feature/reputation/commands/RepCommands.java
package org.z2six.infiniteupgrades.feature.reputation.commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.feature.reputation.logic.Reputation;
import org.z2six.infiniteupgrades.core.net.ModNet;

/**
 * Admin-only commands for manipulating unified reputation.
 *
 * Usage (requires OP >= 3):
 *   /iu getrep
 *   /iu setrep <value>
 *   /iu addrep <delta>
 */
public final class RepCommands {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int REQUIRED_LEVEL = 3;

    private RepCommands() {}

    /** Called from main mod class via NeoForge.EVENT_BUS.addListener. */
    public static void register(RegisterCommandsEvent evt) {
        var root = LiteralArgumentBuilder.<CommandSourceStack>literal("iu")
                .requires(src -> src.hasPermission(REQUIRED_LEVEL))
                .then(Commands.literal("getrep")
                        .executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayerOrException();
                            double v = Reputation.get(sp);
                            int max = UpgradeServerConfig.snapshot().repMax;
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal(String.format("Unified reputation: %.3f (range: [%d..+%d])", v, -max, max)),
                                    false
                            );
                            return 1;
                        }))
                .then(Commands.literal("setrep")
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(-1_000_000, 1_000_000))
                                .executes(ctx -> {
                                    ServerPlayer sp = ctx.getSource().getPlayerOrException();
                                    double value = DoubleArgumentType.getDouble(ctx, "value");
                                    Reputation.set(sp, value);
                                    ModNet.sendRepSnapshotTo(sp);
                                    double v = Reputation.get(sp);
                                    int max = UpgradeServerConfig.snapshot().repMax;
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal(String.format("Set unified reputation to %.3f (clamped to ±%d)", v, max)),
                                            true
                                    );
                                    return 1;
                                })))
                .then(Commands.literal("addrep")
                        .then(Commands.argument("delta", DoubleArgumentType.doubleArg(-1_000_000, 1_000_000))
                                .executes(ctx -> {
                                    ServerPlayer sp = ctx.getSource().getPlayerOrException();
                                    double delta = DoubleArgumentType.getDouble(ctx, "delta");
                                    Reputation.add(sp, delta);
                                    ModNet.sendRepSnapshotTo(sp);
                                    double v = Reputation.get(sp);
                                    int max = UpgradeServerConfig.snapshot().repMax;
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal(String.format("Added %.3f -> unified=%.3f (±%d)", delta, v, max)),
                                            true
                                    );
                                    return 1;
                                })));

        evt.getDispatcher().register(root);
        LOG.debug("[RepCommands] Registered /iu getrep|setrep|addrep (requires OP level >= {})", REQUIRED_LEVEL);
    }
}
