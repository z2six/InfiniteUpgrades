package org.z2six.infiniteupgrades.core.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.z2six.infiniteupgrades.feature.statue.block.StatueBlock;
import org.z2six.infiniteupgrades.feature.statue.block.statue.StatueKind;

public final class ModBlocks {
    private static final Logger LOG = LogUtils.getLogger();

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, Infiniteupgrades.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Infiniteupgrades.MODID);

    public static final RegistryObject<StatueBlock> ANGEL_STATUE = BLOCKS.register(
            "angel_statue",
            () -> {
                try {
                    return new StatueBlock(StatueKind.ANGEL);
                } catch (Throwable t) {
                    LOG.error("[ModBlocks] Failed to construct ANGEL_STATUE: {}", t.toString());
                    return new StatueBlock(StatueKind.DEMON);
                }
            });

    public static final RegistryObject<StatueBlock> DEMON_STATUE = BLOCKS.register(
            "demon_statue",
            () -> {
                try {
                    return new StatueBlock(StatueKind.DEMON);
                } catch (Throwable t) {
                    LOG.error("[ModBlocks] Failed to construct DEMON_STATUE: {}", t.toString());
                    return new StatueBlock(StatueKind.ANGEL);
                }
            });

    public static final RegistryObject<BlockItem> ANGEL_STATUE_ITEM = ITEMS.register(
            "angel_statue",
            () -> {
                try {
                    return new BlockItem(ANGEL_STATUE.get(), new Item.Properties());
                } catch (Throwable t) {
                    LOG.error("[ModBlocks] Failed to construct ANGEL_STATUE_ITEM: {}", t.toString());
                    return new BlockItem(DEMON_STATUE.get(), new Item.Properties());
                }
            });

    public static final RegistryObject<BlockItem> DEMON_STATUE_ITEM = ITEMS.register(
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
