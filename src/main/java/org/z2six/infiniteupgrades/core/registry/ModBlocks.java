// MainFile: src/main/java/org/z2six/infiniteupgrades/core/registry/ModBlocks.java
package org.z2six.infiniteupgrades.core.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.z2six.infiniteupgrades.feature.statue.block.StatueBlock;
import org.z2six.infiniteupgrades.feature.statue.block.statue.StatueKind;

/**
 * ModBlocks – central block & block-item registry (NeoForge 1.21+ style).
 * Uses DeferredHolder instead of RegistryObject.
 * Includes BlockItems (ANGEL_STATUE_ITEM / DEMON_STATUE_ITEM) as your client code expects.
 */
public final class ModBlocks {
    private static final Logger LOG = LogUtils.getLogger();

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, Infiniteupgrades.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, Infiniteupgrades.MODID);

    // --- Statues (blocks) ---
    public static final DeferredHolder<Block, StatueBlock> ANGEL_STATUE = BLOCKS.register(
            "angel_statue",
            () -> {
                try {
                    return new StatueBlock(StatueKind.ANGEL);
                } catch (Throwable t) {
                    LOG.error("[ModBlocks] Failed to construct ANGEL_STATUE: {}", t.toString());
                    return new StatueBlock(StatueKind.DEMON); // safe fallback, never crash
                }
            });

    public static final DeferredHolder<Block, StatueBlock> DEMON_STATUE = BLOCKS.register(
            "demon_statue",
            () -> {
                try {
                    return new StatueBlock(StatueKind.DEMON);
                } catch (Throwable t) {
                    LOG.error("[ModBlocks] Failed to construct DEMON_STATUE: {}", t.toString());
                    return new StatueBlock(StatueKind.ANGEL);
                }
            });

    // --- BlockItems (so they appear in creative & can be picked up) ---
    public static final DeferredHolder<Item, BlockItem> ANGEL_STATUE_ITEM = ITEMS.register(
            "angel_statue",
            () -> {
                try {
                    return new BlockItem(ANGEL_STATUE.get(), new Item.Properties());
                } catch (Throwable t) {
                    LOG.error("[ModBlocks] Failed to construct ANGEL_STATUE_ITEM: {}", t.toString());
                    // Fallback to a dummy pointing to DEMON_STATUE to avoid nulls
                    return new BlockItem(DEMON_STATUE.get(), new Item.Properties());
                }
            });

    public static final DeferredHolder<Item, BlockItem> DEMON_STATUE_ITEM = ITEMS.register(
            "demon_statue",
            () -> {
                try {
                    return new BlockItem(DEMON_STATUE.get(), new Item.Properties());
                } catch (Throwable t) {
                    LOG.error("[ModBlocks] Failed to construct DEMON_STATUE_ITEM: {}", t.toString());
                    return new BlockItem(ANGEL_STATUE.get(), new Item.Properties());
                }
            });

    private ModBlocks() {}
}
