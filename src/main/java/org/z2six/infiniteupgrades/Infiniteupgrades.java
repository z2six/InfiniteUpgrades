package org.z2six.infiniteupgrades;

// NOTE: GeckoLib.initialize() is not required with 4.7.6 on NeoForge.

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
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
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.client.AngelRenderer;
import org.z2six.infiniteupgrades.client.DemonRenderer;
import org.z2six.infiniteupgrades.client.screen.AngelScreen;
import org.z2six.infiniteupgrades.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.registry.ModBlockEntities;
import org.z2six.infiniteupgrades.registry.ModEntityTypes;
import org.z2six.infiniteupgrades.registry.ModMenus;
import org.z2six.infiniteupgrades.world.AngelEntity;
import org.z2six.infiniteupgrades.world.DemonEntity;
import org.z2six.infiniteupgrades.world.SigilBlock;

@Mod(Infiniteupgrades.MODID)
public class Infiniteupgrades {

    public static final String MODID = "infiniteupgrades";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredBlock<Block> EXAMPLE_BLOCK =
            BLOCKS.registerSimpleBlock("example_block", BlockBehaviour.Properties.of().mapColor(MapColor.STONE));
    public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem("example_block", EXAMPLE_BLOCK);
    public static final DeferredItem<Item> EXAMPLE_ITEM =
            ITEMS.registerSimpleItem("example_item",
                    new Item.Properties().food(new FoodProperties.Builder().alwaysEdible().nutrition(1).saturationModifier(2f).build()));
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> EXAMPLE_TAB =
            CREATIVE_MODE_TABS.register("example_tab",
                    () -> CreativeModeTab.builder()
                            .title(Component.translatable("itemGroup.infiniteupgrades"))
                            .withTabsBefore(CreativeModeTabs.COMBAT)
                            .icon(() -> EXAMPLE_ITEM.get().getDefaultInstance())
                            .displayItems((parameters, output) -> output.accept(EXAMPLE_ITEM.get()))
                            .build());

    // Our Sigils
    public static final DeferredBlock<Block> CELESTIAL_SIGIL = BLOCKS.register(
            "celestial_sigil",
            () -> new SigilBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.GOLD)
                    .noCollission()
                    .noOcclusion()
                    .strength(0.1f)));

    public static final DeferredItem<BlockItem> CELESTIAL_SIGIL_ITEM =
            ITEMS.registerSimpleBlockItem("celestial_sigil", CELESTIAL_SIGIL);

    // Unholy Sigil
    public static final DeferredBlock<Block> UNHOLY_SIGIL = BLOCKS.register(
            "unholy_sigil",
            () -> new SigilBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.NETHER)
                    .noCollission()
                    .noOcclusion()
                    .strength(0.1f)));

    public static final DeferredItem<BlockItem> UNHOLY_SIGIL_ITEM =
            ITEMS.registerSimpleBlockItem("unholy_sigil", UNHOLY_SIGIL);

    // --- Mod construction ---
    public Infiniteupgrades(IEventBus modEventBus, ModContainer modContainer) {
        // Registry/event wiring
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(this::registerEntityAttributes);

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        ModEntityTypes.ENTITY_TYPES.register(modEventBus);

        // Listen to common-bus gameplay events (ServerStartingEvent below)
        NeoForge.EVENT_BUS.register(this);

        // Register GAME-bus listeners programmatically (Bus.GAME is deprecated)
        try {
            NeoForge.EVENT_BUS.addListener(org.z2six.infiniteupgrades.logic.RepEvents::onPlayerClone);
            LOGGER.debug("[Infiniteupgrades] Registered RepEvents.onPlayerClone on NeoForge.EVENT_BUS");
        } catch (Throwable t) {
            LOGGER.error("[Infiniteupgrades] Failed to register RepEvents listener", t);
        }

        // Existing COMMON config (your original)
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // SERVER-authoritative upgrade config
        modContainer.registerConfig(ModConfig.Type.SERVER, UpgradeServerConfig.SPEC);

        // ✅ Correct: Mod config events belong to the MOD event bus, not the common bus
        modEventBus.addListener(UpgradeServerConfig::onServerConfigReload);

        LOGGER.debug("[Infiniteupgrades] Registered SERVER config for upgrades");
        LOGGER.debug("[Infiniteupgrades] Mod constructed");
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        try {
            if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
                event.accept(EXAMPLE_BLOCK_ITEM);
                event.accept(CELESTIAL_SIGIL_ITEM);
                event.accept(UNHOLY_SIGIL_ITEM);
                LOGGER.debug("[Infiniteupgrades] Added items to BUILDING_BLOCKS tab");
            }
        } catch (Throwable t) {
            LOGGER.error("[Infiniteupgrades] Failed to add items to creative tab", t);
        }
    }

    private void registerEntityAttributes(final EntityAttributeCreationEvent evt) {
        evt.put(ModEntityTypes.ANGEL.get(), AngelEntity.createAttributes().build());
        evt.put(ModEntityTypes.DEMON.get(), DemonEntity.createAttributes().build()); // NEW
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[Infiniteupgrades] Server starting");
    }

    // ---- CLIENT ----
    @EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class Client {
        @SubscribeEvent
        public static void clientSetup(FMLClientSetupEvent evt) {
            LOGGER.info("[InfiniteUpgrades] Client setup; user={}", Minecraft.getInstance().getUser().getName());
        }

        @SubscribeEvent
        public static void registerScreens(RegisterMenuScreensEvent evt) {
            try {
                evt.register(ModMenus.ANGEL_MENU.get(), org.z2six.infiniteupgrades.client.screen.AngelScreen::new);
                LOGGER.debug("[InfiniteUpgrades] Registered AngelScreen");
            } catch (Throwable t) {
                LOGGER.error("[InfiniteUpgrades] Failed to register AngelScreen", t);
            }
        }

        @SubscribeEvent
        public static void registerRenderers(final EntityRenderersEvent.RegisterRenderers evt) {
            try {
                evt.registerEntityRenderer(ModEntityTypes.ANGEL.get(), AngelRenderer::new);
                evt.registerEntityRenderer(ModEntityTypes.DEMON.get(), DemonRenderer::new); // NEW
                LOGGER.debug("[InfiniteUpgrades] Registered AngelRenderer & DemonRenderer (GeckoLib)");
            } catch (Throwable t) {
                LOGGER.error("[InfiniteUpgrades] Failed to register entity renderers", t);
            }
        }
    }
}
