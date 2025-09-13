// MainFile: src/main/java/org/z2six/infiniteupgrades/Infiniteupgrades.java
package org.z2six.infiniteupgrades;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.registry.ModEntityTypes;
import org.z2six.infiniteupgrades.world.AngelEntity;
import org.z2six.infiniteupgrades.world.SigilBlock;

@Mod(Infiniteupgrades.MODID)
public class Infiniteupgrades {
    public static final String MODID = "infiniteupgrades";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items  ITEMS  = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // Register entity types
    static {
        // static init just to ensure the class is loaded
    }

    // Sigil block + item
    public static final DeferredBlock<Block> CELESTIAL_SIGIL = BLOCKS.register(
            "celestial_sigil",
            () -> new SigilBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.GOLD)
                    .noCollission()
                    .noOcclusion()
                    .strength(0.1f))
    );
    public static final DeferredItem<BlockItem> CELESTIAL_SIGIL_ITEM =
            ITEMS.registerSimpleBlockItem("celestial_sigil", CELESTIAL_SIGIL);

    public Infiniteupgrades(IEventBus modBus, ModContainer modContainer) {
        // Core registers
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        TABS.register(modBus);
        ModEntityTypes.ENTITY_TYPES.register(modBus);

        // Events
        modBus.addListener(this::commonSetup);
        modBus.addListener(this::attributes);
        modBus.addListener(this::addCreative);

        NeoForge.EVENT_BUS.register(this);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        LOGGER.debug("[InfiniteUpgrades] Boot OK; registers wired");
    }

    private void commonSetup(final FMLCommonSetupEvent e) {
        LOGGER.info("[InfiniteUpgrades] Common setup");
    }

    private void attributes(final EntityAttributeCreationEvent e) {
        try {
            e.put(ModEntityTypes.ANGEL.get(), AngelEntity.createAttributes().build());
            LOGGER.debug("[InfiniteUpgrades] Registered AngelEntity attributes");
        } catch (Throwable t) {
            LOGGER.error("[InfiniteUpgrades] Attribute registration failed: {}", t.toString());
        }
    }

    private void addCreative(BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            e.accept(CELESTIAL_SIGIL_ITEM);
            LOGGER.debug("[InfiniteUpgrades] Added Celestial Sigil to BUILDING_BLOCKS");
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent e) {
        LOGGER.info("[InfiniteUpgrades] Server starting");
    }

    @EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class Client {
        @SubscribeEvent
        public static void pingClientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent e) {
            // just a friendly log so we know client side is alive
            LogUtils.getLogger().info("[InfiniteUpgrades] Client setup; user={}", Minecraft.getInstance().getUser().getName());
        }
    }
}
