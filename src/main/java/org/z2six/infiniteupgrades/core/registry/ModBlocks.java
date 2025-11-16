// MainFile: src/main/java/org/z2six/infiniteupgrades/core/registry/ModBlocks.java
package org.z2six.infiniteupgrades.core.registry;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.z2six.infiniteupgrades.feature.statue.block.StatueBlock;

/**
 * Registers Angel/Demon statue blocks + their BlockItems.
 * Uses the specialized DeferredRegister.Blocks so register(...) returns DeferredBlock<T>.
 */
public final class ModBlocks {
    private static final Logger LOG = LogUtils.getLogger();

    /** Use the specialized Blocks register (important for DeferredBlock<T> typing). */
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Infiniteupgrades.MODID);

    /** Angel statue block (horizontal facing, non-occluding). */
    public static final DeferredBlock<StatueBlock> ANGEL_STATUE =
            BLOCKS.register("angel_statue", StatueBlock::new); // StatueBlock has a no-args ctor

    /** Demon statue block (horizontal facing, non-occluding). */
    public static final DeferredBlock<StatueBlock> DEMON_STATUE =
            BLOCKS.register("demon_statue", StatueBlock::new); // no-args ctor

    /** BlockItems go on the main mod ITEMS register to avoid duplicate registries. */
    public static final DeferredItem<net.minecraft.world.item.BlockItem> ANGEL_STATUE_ITEM =
            Infiniteupgrades.ITEMS.registerSimpleBlockItem("angel_statue", ANGEL_STATUE);

    public static final DeferredItem<net.minecraft.world.item.BlockItem> DEMON_STATUE_ITEM =
            Infiniteupgrades.ITEMS.registerSimpleBlockItem("demon_statue", DEMON_STATUE);

    /**
     * Convenience hook to match your call site:
     * Infiniteupgrades -> ModBlocks.register(modEventBus, ITEMS);
     * We only need to register our BLOCKS here; ITEMS is already handled by the main mod class.
     */
    public static void register(IEventBus modEventBus, DeferredRegister.Items ignoredItemsRegister) {
        try {
            BLOCKS.register(modEventBus);
            LOG.debug("[ModBlocks] Registered BLOCKS on MOD event bus");
        } catch (Throwable t) {
            LOG.error("[ModBlocks] Failed to register BLOCKS: {}", t.toString());
        }
    }

    private ModBlocks() {}

    static {
        LOG.debug("[ModBlocks] Static init complete");
    }
}
