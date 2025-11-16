// MainFile: src/main/java/org/z2six/infiniteupgrades/core/Infiniteupgrades.java
package org.z2six.infiniteupgrades.core;

// NOTE: GeckoLib.initialize() is not required with 4.7.6 on NeoForge.

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
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.config.UpgradeClientConfig;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.core.registry.ModBlockEntities;
import org.z2six.infiniteupgrades.core.registry.ModEntityTypes;
import org.z2six.infiniteupgrades.core.registry.ModMenus;
// NEW: attachments registration
import org.z2six.infiniteupgrades.feature.infusion.attachment.ModAttachments;
// NEW: sounds registration
import org.z2six.infiniteupgrades.core.registry.ModSounds;
import org.z2six.infiniteupgrades.feature.infusion.client.InfuseClientTicker;
import org.z2six.infiniteupgrades.feature.infusion.client.screen.AngelDemonScreen; // keeping if still used by menus
import org.z2six.infiniteupgrades.feature.infusion.logic.InfuseTimers;
import org.z2six.infiniteupgrades.feature.reputation.commands.RepCommands;
import org.z2six.infiniteupgrades.feature.reputation.logic.RepEvents;
import org.z2six.infiniteupgrades.feature.souls.client.render.SoulOrbRenderer;
import org.z2six.infiniteupgrades.feature.souls.item.SoulCageItem;

@Mod(Infiniteupgrades.MODID)
public class Infiniteupgrades {

    public static final String MODID = "infiniteupgrades";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // === Soul Cage item (remains) ===
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final net.neoforged.neoforge.registries.DeferredItem<Item> SOUL_CAGE =
            ITEMS.register("soul_cage", () -> new SoulCageItem(new Item.Properties().stacksTo(1)));

    // --- Mod construction ---
    public Infiniteupgrades(IEventBus modEventBus, ModContainer modContainer) {
        // Registry/event wiring
        modEventBus.addListener(this::addCreative);

        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        // Safe to keep (even empty after sigil removal)
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        ModEntityTypes.ENTITY_TYPES.register(modEventBus);

        // ✅ Register attachment types on the MOD event bus
        ModAttachments.register(modEventBus);

        // ✅ Register mod sounds
        ModSounds.SOUND_EVENTS.register(modEventBus);

        // Listen to common-bus gameplay events (ServerStartingEvent below)
        NeoForge.EVENT_BUS.register(this);

        // Register GAME-bus listeners programmatically
        try {
            NeoForge.EVENT_BUS.addListener(RepEvents::onPlayerClone);
            // Infusion timer tick finalizer
            NeoForge.EVENT_BUS.addListener(InfuseTimers::onLevelTick);
            // Admin commands (requires OP >= 3)
            NeoForge.EVENT_BUS.addListener(RepCommands::register);
            LOGGER.debug("[Infiniteupgrades] Registered RepEvents, InfuseTimers & RepCommands on NeoForge.EVENT_BUS");
        } catch (Throwable t) {
            LOGGER.error("[Infiniteupgrades] Failed to register listeners", t);
        }

        // === Configs ===
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modContainer.registerConfig(ModConfig.Type.SERVER, UpgradeServerConfig.SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, UpgradeClientConfig.SPEC);

        modEventBus.addListener(UpgradeServerConfig::onServerConfigReload);
        modEventBus.addListener(UpgradeClientConfig::onClientConfigReload);

        LOGGER.debug("[Infiniteupgrades] Registered SERVER and CLIENT configs for upgrades");
        LOGGER.debug("[Infiniteupgrades] Mod constructed");
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        try {
            if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
                event.accept(SOUL_CAGE);
                LOGGER.debug("[Infiniteupgrades] Added Soul Cage to TOOLS_AND_UTILITIES tab");
            }
        } catch (Throwable t) {
            LOGGER.error("[Infiniteupgrades] Failed to add items to creative tab", t);
        }
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

            // Client tick listener for infusion client-side effects
            NeoForge.EVENT_BUS.addListener(InfuseClientTicker::onClientTick);
        }

        @SubscribeEvent
        public static void registerScreens(RegisterMenuScreensEvent evt) {
            try {
                // Keep your unified infusion screen if the menu still exists
                evt.register(ModMenus.ANGEL_MENU.get(), AngelDemonScreen::new);
                LOGGER.debug("[InfiniteUpgrades] Registered AngelDemonScreen");
            } catch (Throwable t) {
                LOGGER.error("[InfiniteUpgrades] Failed to register AngelDemonScreen", t);
            }
        }

        @SubscribeEvent
        public static void registerRenderers(final EntityRenderersEvent.RegisterRenderers evt) {
            try {
                // Only Soul Orb remains after removing Angel/Demon
                evt.registerEntityRenderer(ModEntityTypes.SOUL_ORB.get(), SoulOrbRenderer::new);
                LOGGER.debug("[InfiniteUpgrades] Registered SoulOrbRenderer");
            } catch (Throwable t) {
                LOGGER.error("[InfiniteUpgrades] Failed to register entity renderers", t);
            }
        }
    }
}
