// File: src/main/java/org/z2six/infiniteupgrades/Infiniteupgrades.java
package org.z2six.infiniteupgrades.core;

// NOTE: GeckoLib.initialize() is not required with 4.7.6 on NeoForge.

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
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.feature.angel.client.render.AngelRenderer;
import org.z2six.infiniteupgrades.feature.demon.client.render.DemonRenderer;
import org.z2six.infiniteupgrades.feature.infusion.client.InfuseClientTicker;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.core.registry.ModBlockEntities;
import org.z2six.infiniteupgrades.core.registry.ModEntityTypes;
import org.z2six.infiniteupgrades.core.registry.ModMenus;
import org.z2six.infiniteupgrades.feature.infusion.client.screen.AngelDemonScreen;
import org.z2six.infiniteupgrades.feature.infusion.logic.InfuseTimers;
import org.z2six.infiniteupgrades.feature.reputation.commands.RepCommands;
import org.z2six.infiniteupgrades.feature.reputation.logic.RepEvents;
import org.z2six.infiniteupgrades.feature.angel.entity.AngelEntity;
import org.z2six.infiniteupgrades.feature.demon.entity.DemonEntity;
import org.z2six.infiniteupgrades.feature.sigil.block.SigilBlock;
// NEW: attachments registration
import org.z2six.infiniteupgrades.feature.infusion.attachment.ModAttachments;
// NEW: sounds registration
import org.z2six.infiniteupgrades.core.registry.ModSounds;
import org.z2six.infiniteupgrades.feature.souls.client.render.SoulOrbRenderer;

@Mod(Infiniteupgrades.MODID)
public class Infiniteupgrades {

    public static final String MODID = "infiniteupgrades";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

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

        // ✅ Register attachment types on the MOD event bus (this was missing)
        ModAttachments.register(modEventBus);

        // ✅ Register mod sounds
        ModSounds.SOUND_EVENTS.register(modEventBus);

        // Listen to common-bus gameplay events (ServerStartingEvent below)
        NeoForge.EVENT_BUS.register(this);

        // Register GAME-bus listeners programmatically (Bus.GAME is deprecated)
        try {
            NeoForge.EVENT_BUS.addListener(RepEvents::onPlayerClone);
            // NEW: infusion timer tick finalizer
            NeoForge.EVENT_BUS.addListener(InfuseTimers::onLevelTick);
            // NEW: register admin commands (requires OP >= 3)
            NeoForge.EVENT_BUS.addListener(RepCommands::register);
            LOGGER.debug("[Infiniteupgrades] Registered RepEvents, InfuseTimers & RepCommands on NeoForge.EVENT_BUS");
        } catch (Throwable t) {
            LOGGER.error("[Infiniteupgrades] Failed to register listeners", t);
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

            // ✅ Non-deprecated: register client tick listener programmatically
            NeoForge.EVENT_BUS.addListener(InfuseClientTicker::onClientTick);
        }

        @SubscribeEvent
        public static void registerScreens(RegisterMenuScreensEvent evt) {
            try {
                // Register our unified Angel/Demon screen
                evt.register(ModMenus.ANGEL_MENU.get(), AngelDemonScreen::new);
                LOGGER.debug("[InfiniteUpgrades] Registered AngelDemonScreen");
            } catch (Throwable t) {
                LOGGER.error("[InfiniteUpgrades] Failed to register AngelDemonScreen", t);
            }
        }

        @SubscribeEvent
        public static void registerRenderers(final EntityRenderersEvent.RegisterRenderers evt) {
            try {
                evt.registerEntityRenderer(ModEntityTypes.ANGEL.get(), AngelRenderer::new);
                evt.registerEntityRenderer(ModEntityTypes.DEMON.get(), DemonRenderer::new);
                evt.registerEntityRenderer(ModEntityTypes.SOUL_ORB.get(), SoulOrbRenderer::new);
                LOGGER.debug("[InfiniteUpgrades] Registered AngelRenderer & DemonRenderer (GeckoLib)");
            } catch (Throwable t) {
                LOGGER.error("[InfiniteUpgrades] Failed to register entity renderers", t);
            }
        }
    }
}
