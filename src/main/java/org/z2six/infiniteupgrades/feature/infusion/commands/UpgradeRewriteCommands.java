package org.z2six.infiniteupgrades.feature.infusion.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.feature.infusion.logic.UpgradeService;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * OP-only maintenance commands for retroactively rewriting already-upgraded items against the current config.
 *
 * Current scope:
 * - Online player inventories
 * - Online player ender chests
 * - Block-entity containers in loaded chunks only
 */
public final class UpgradeRewriteCommands {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int REQUIRED_LEVEL = 3;
    private static final Method CHUNK_MAP_GET_CHUNKS = resolveChunkMapGetChunks();

    private UpgradeRewriteCommands() {}

    public static void register(RegisterCommandsEvent evt) {
        var root = LiteralArgumentBuilder.<CommandSourceStack>literal("iu")
                .requires(src -> src.hasPermission(REQUIRED_LEVEL))
                .then(Commands.literal("rewriteupgrades")
                        .executes(ctx -> rewriteLoadedItems(ctx.getSource())))
                .then(Commands.literal("resetitem")
                        .executes(ctx -> resetHeldItem(ctx.getSource())));

        evt.getDispatcher().register(root);
        LOG.debug("[UpgradeRewriteCommands] Registered /iu rewriteupgrades and /iu resetitem (requires OP level >= {})", REQUIRED_LEVEL);
    }

    private static int resetHeldItem(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Throwable t) {
            source.sendFailure(Component.literal("/iu resetitem must be run by a player holding an item."));
            return 0;
        }

        var original = player.getMainHandItem();
        if (original == null || original.isEmpty()) {
            source.sendFailure(Component.literal("Hold the upgraded item in your main hand, then run /iu resetitem."));
            return 0;
        }

        var reset = UpgradeService.resetUpgradeChanges(original);
        if (!reset.removedUpgradeData() || !reset.changed()) {
            source.sendFailure(Component.literal("Held item has no Infinite Upgrades data to reset."));
            return 0;
        }

        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, reset.reset());
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();

        String summary = String.format(
                "Reset Infinite Upgrades data on held item. Restored %d attribute modifier(s); removed upgrade history/totals and IU mining speed data.",
                reset.restoredAttributes()
        );
        source.sendSuccess(() -> Component.literal(summary), true);
        LOG.info("[UpgradeRewriteCommands] {} Player={} Item={}", summary, player.getGameProfile().getName(), reset.reset().getItem());
        return 1;
    }

    private static int rewriteLoadedItems(CommandSourceStack source) {
        Counters counters = new Counters();

        for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
            counters.onlinePlayers++;

            if (rewriteInventory(player.getInventory(), counters)) {
                player.getInventory().setChanged();
                player.inventoryMenu.broadcastChanges();
                player.containerMenu.broadcastChanges();
            }

            PlayerEnderChestContainer enderChest = player.getEnderChestInventory();
            if (enderChest != null && rewriteInventory(enderChest, counters)) {
                enderChest.setChanged();
                player.inventoryMenu.broadcastChanges();
                player.containerMenu.broadcastChanges();
            }
        }

        for (ServerLevel level : source.getServer().getAllLevels()) {
            counters.levels++;
            for (ChunkHolder holder : getLoadedChunkHolders(level)) {
                LevelChunk chunk = resolveChunk(holder);
                if (chunk == null) continue;

                counters.loadedChunks++;
                for (BlockEntity blockEntity : new ArrayList<>(chunk.getBlockEntities().values())) {
                    counters.blockEntities++;
                    if (!(blockEntity instanceof Container container)) continue;

                    counters.worldContainers++;
                    if (rewriteInventory(container, counters)) {
                        blockEntity.setChanged();
                        level.sendBlockUpdated(blockEntity.getBlockPos(), blockEntity.getBlockState(), blockEntity.getBlockState(), 3);
                    }
                }
            }
        }

        final String summary = String.format(
                "Rewrote %d upgraded stacks (%d history entries) across %d online players, %d loaded containers, %d loaded chunks. Scope: online inventories + loaded chunk containers.",
                counters.rewrittenStacks,
                counters.rewrittenHistoryEntries,
                counters.onlinePlayers,
                counters.worldContainers,
                counters.loadedChunks
        );

        source.sendSuccess(() -> Component.literal(summary), true);
        LOG.info("[UpgradeRewriteCommands] {}", summary);
        return counters.rewrittenStacks;
    }

    private static boolean rewriteInventory(Container container, Counters counters) {
        if (container == null) return false;

        boolean changed = false;
        counters.containersScanned++;

        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            var stack = container.getItem(slot);
            if (stack == null || stack.isEmpty()) continue;

            counters.stacksSeen++;
            var rewrite = UpgradeService.rewriteToCurrentConfig(stack);
            if (!rewrite.changed()) continue;

            container.setItem(slot, rewrite.rewritten());
            changed = true;
            counters.rewrittenStacks++;
            counters.rewrittenBatches += rewrite.rewrittenBatches();
            counters.rewrittenHistoryEntries += rewrite.rewrittenEvents();
        }

        if (changed) {
            container.setChanged();
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private static List<ChunkHolder> getLoadedChunkHolders(ServerLevel level) {
        if (CHUNK_MAP_GET_CHUNKS == null) return List.of();
        try {
            Object raw = CHUNK_MAP_GET_CHUNKS.invoke(level.getChunkSource().chunkMap);
            if (raw instanceof Iterable<?> iterable) {
                List<ChunkHolder> holders = new ArrayList<>();
                for (Object value : iterable) {
                    if (value instanceof ChunkHolder holder) holders.add(holder);
                }
                return holders;
            }
        } catch (Throwable t) {
            LOG.error("[UpgradeRewriteCommands] Failed to enumerate loaded chunks for {}: {}", level.dimension().location(), t.toString());
        }
        return List.of();
    }

    private static LevelChunk resolveChunk(ChunkHolder holder) {
        if (holder == null) return null;
        LevelChunk chunk = holder.getTickingChunk();
        if (chunk != null) return chunk;
        return holder.getChunkToSend();
    }

    private static Method resolveChunkMapGetChunks() {
        try {
            Method method = net.minecraft.server.level.ChunkMap.class.getDeclaredMethod("getChunks");
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            LOG.error("[UpgradeRewriteCommands] Failed to resolve ChunkMap#getChunks reflection hook: {}", t.toString());
            return null;
        }
    }

    private static final class Counters {
        int levels;
        int onlinePlayers;
        int loadedChunks;
        int blockEntities;
        int worldContainers;
        int containersScanned;
        int stacksSeen;
        int rewrittenStacks;
        int rewrittenBatches;
        int rewrittenHistoryEntries;
    }
}
