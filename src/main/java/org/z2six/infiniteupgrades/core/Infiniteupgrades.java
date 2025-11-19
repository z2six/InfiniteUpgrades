// MainFile: src/main/java/org/z2six/infiniteupgrades/core/Infiniteupgrades.java
package org.z2six.infiniteupgrades.core;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import org.z2six.infiniteupgrades.core.config.UpgradeClientConfig;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.core.registry.ModBlockEntities;
import org.z2six.infiniteupgrades.core.registry.ModBlocks;
import org.z2six.infiniteupgrades.core.registry.ModEntityTypes;
import org.z2six.infiniteupgrades.core.registry.ModMenus;
import org.z2six.infiniteupgrades.core.registry.ModSounds;
import org.z2six.infiniteupgrades.feature.infusion.attachment.ModAttachments;
import org.z2six.infiniteupgrades.feature.infusion.client.InfuseClientTicker;
import org.z2six.infiniteupgrades.feature.infusion.client.screen.AngelDemonScreen;
import org.z2six.infiniteupgrades.feature.reputation.commands.RepCommands;
import org.z2six.infiniteupgrades.feature.reputation.logic.RepEvents;
import org.z2six.infiniteupgrades.feature.souls.client.render.SoulOrbRenderer;
import org.z2six.infiniteupgrades.feature.souls.item.SoulCageItem;

@Mod(Infiniteupgrades.MODID)
public class Infiniteupgrades {

    public static final String MODID = "infiniteupgrades";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final net.neoforged.neoforge.registries.DeferredRegister.Blocks BLOCKS =
            net.neoforged.neoforge.registries.DeferredRegister.createBlocks(MODID);
    public static final net.neoforged.neoforge.registries.DeferredRegister.Items ITEMS =
            net.neoforged.neoforge.registries.DeferredRegister.createItems(MODID);
    public static final net.neoforged.neoforge.registries.DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            net.neoforged.neoforge.registries.DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // --- Soul Cage item ---
    public static final net.neoforged.neoforge.registries.DeferredItem<Item> SOUL_CAGE = ITEMS.register("soul_cage",
            () -> new SoulCageItem(new Item.Properties().stacksTo(1)));

    public Infiniteupgrades(IEventBus modEventBus, ModContainer modContainer) {
        // Core mod registries
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        // Statues (separate registry holder)
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.ITEMS.register(modEventBus); // <-- IMPORTANT: register the BlockItems too

        // Other module registries
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        ModEntityTypes.ENTITY_TYPES.register(modEventBus);
        ModAttachments.register(modEventBus);
        ModSounds.SOUND_EVENTS.register(modEventBus);

        // Creative tabs
        modEventBus.addListener(this::addCreative);

        // GAME bus listeners
        NeoForge.EVENT_BUS.register(this);
        try {
            NeoForge.EVENT_BUS.addListener(RepEvents::onPlayerClone);
            NeoForge.EVENT_BUS.addListener(org.z2six.infiniteupgrades.feature.infusion.logic.InfuseTimers::onLevelTick);
            NeoForge.EVENT_BUS.addListener(RepCommands::register);

            // NEW: multiply mining speed by our "block_speed" bonus
            NeoForge.EVENT_BUS.addListener(org.z2six.infiniteupgrades.feature.infusion.logic.BreakSpeedHooks::onBreakSpeed);
        } catch (Throwable t) {
            LOGGER.error("[Infiniteupgrades] Failed to register game listeners", t);
        }

        // Configs
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, Config.SPEC);
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, UpgradeServerConfig.SPEC);
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, UpgradeClientConfig.SPEC);
        modEventBus.addListener(UpgradeServerConfig::onServerConfigReload);
        modEventBus.addListener(UpgradeClientConfig::onClientConfigReload);

        LOGGER.debug("[Infiniteupgrades] Mod constructed");
    }

    private void addCreative(net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) {
        try {
            if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
                // pass ItemLike, not the holder
                event.accept(ModBlocks.ANGEL_STATUE_ITEM.get());
                event.accept(ModBlocks.DEMON_STATUE_ITEM.get());
            }
            if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
                event.accept(SOUL_CAGE.get());
            }
        } catch (Throwable t) {
            LOGGER.error("[Infiniteupgrades] Creative tab add failed", t);
        }
    }

    // ---------- Server lifecycle (force serverconfig to be created early) ----------

    @SubscribeEvent
    public void onServerAboutToStart(net.neoforged.neoforge.event.server.ServerAboutToStartEvent event) {
        try {
            // Forces the SERVER config to load and be written into <world>/serverconfig/
            org.z2six.infiniteupgrades.core.config.UpgradeServerConfig.snapshot();
            LOGGER.info("[Infiniteupgrades] ServerAboutToStart: ensured server config is loaded/written");
        } catch (Throwable t) {
            LOGGER.error("[Infiniteupgrades] Failed to pre-load server config", t);
        }
    }

    @SubscribeEvent
    public void onServerStarting(net.neoforged.neoforge.event.server.ServerStartingEvent event) {
        LOGGER.info("[Infiniteupgrades] Server starting");
        try {
            // Harmless if called twice; guarantees presence even on odd loaders
            org.z2six.infiniteupgrades.core.config.UpgradeServerConfig.snapshot();
        } catch (Throwable t) {
            LOGGER.error("[Infiniteupgrades] Failed to load server config on start", t);
        }
    }

    // ---- CLIENT ----
    @EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class Client {
        @SubscribeEvent
        public static void clientSetup(FMLClientSetupEvent evt) {
            LOGGER.info("[InfiniteUpgrades] Client setup; user={}", Minecraft.getInstance().getUser().getName());
            NeoForge.EVENT_BUS.addListener(InfuseClientTicker::onClientTick);
        }

        @SubscribeEvent
        public static void registerScreens(RegisterMenuScreensEvent evt) {
            try {
                evt.register(ModMenus.ANGEL_MENU.get(), AngelDemonScreen::new);
            } catch (Throwable t) {
                LogUtils.getLogger().error("[InfiniteUpgrades] Failed to register AngelDemonScreen", t);
            }
        }

        @SubscribeEvent
        public static void registerRenderers(final EntityRenderersEvent.RegisterRenderers evt) {
            try {
                // Only Soul Orb renderer (Angel/Demon entities intentionally removed)
                evt.registerEntityRenderer(ModEntityTypes.SOUL_ORB.get(), SoulOrbRenderer::new);
            } catch (Throwable t) {
                LogUtils.getLogger().error("[InfiniteUpgrades] Failed to register entity renderers", t);
            }
        }
    }
}
