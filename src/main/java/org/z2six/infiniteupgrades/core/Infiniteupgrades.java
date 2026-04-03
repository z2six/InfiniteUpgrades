package org.z2six.infiniteupgrades.core;

import com.mojang.logging.LogUtils;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.config.UpgradeClientConfig;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.core.net.ModNet;
import org.z2six.infiniteupgrades.core.registry.ModBlockEntities;
import org.z2six.infiniteupgrades.core.registry.ModBlocks;
import org.z2six.infiniteupgrades.core.registry.ModEntityTypes;
import org.z2six.infiniteupgrades.core.registry.ModMenus;
import org.z2six.infiniteupgrades.core.registry.ModSounds;
import org.z2six.infiniteupgrades.feature.infusion.attachment.ModAttachments;
import org.z2six.infiniteupgrades.feature.infusion.logic.BreakSpeedHooks;
import org.z2six.infiniteupgrades.feature.infusion.logic.InfuseTimers;
import org.z2six.infiniteupgrades.feature.reputation.commands.RepCommands;
import org.z2six.infiniteupgrades.feature.reputation.logic.RepEvents;
import org.z2six.infiniteupgrades.feature.souls.item.SoulCageItem;
import org.z2six.infiniteupgrades.feature.souls.logic.ChunkSoulScrubber;
import org.z2six.infiniteupgrades.feature.souls.logic.SoulDrops;
import org.z2six.infiniteupgrades.feature.souls.logic.StartupCleanup;

@Mod(Infiniteupgrades.MODID)
public class Infiniteupgrades {
    public static final String MODID = "infiniteupgrades";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    public static final RegistryObject<Item> SOUL_CAGE = ITEMS.register("soul_cage",
            () -> new SoulCageItem(new Item.Properties().stacksTo(1)));

    public Infiniteupgrades() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ITEMS.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        ModEntityTypes.ENTITY_TYPES.register(modEventBus);
        ModSounds.SOUND_EVENTS.register(modEventBus);
        ModAttachments.register(modEventBus);

        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(UpgradeServerConfig::onServerConfigReload);
        modEventBus.addListener(UpgradeClientConfig::onClientConfigReload);

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.addListener(RepEvents::onPlayerClone);
        MinecraftForge.EVENT_BUS.addListener(InfuseTimers::onLevelTick);
        MinecraftForge.EVENT_BUS.addListener(RepCommands::register);
        MinecraftForge.EVENT_BUS.addListener(BreakSpeedHooks::onBreakSpeed);
        MinecraftForge.EVENT_BUS.addListener(ChunkSoulScrubber::onChunkDataLoad);
        MinecraftForge.EVENT_BUS.addListener(StartupCleanup::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(StartupCleanup::onLevelLoad);
        MinecraftForge.EVENT_BUS.addListener(SoulDrops::onLivingDrops);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, UpgradeServerConfig.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, UpgradeClientConfig.SPEC);

        ModNet.init();
        LOGGER.debug("[Infiniteupgrades] Mod constructed");
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        try {
            if (CreativeModeTabs.BUILDING_BLOCKS.equals(event.getTabKey())) {
                event.accept(ModBlocks.ANGEL_STATUE_ITEM.get());
                event.accept(ModBlocks.DEMON_STATUE_ITEM.get());
                LOGGER.debug("[Infiniteupgrades] Added statue items to BUILDING_BLOCKS");
            }
            if (CreativeModeTabs.TOOLS_AND_UTILITIES.equals(event.getTabKey())) {
                event.accept(SOUL_CAGE.get());
                LOGGER.debug("[Infiniteupgrades] Added soul cage to TOOLS_AND_UTILITIES");
            }
        } catch (Throwable t) {
            LOGGER.error("[Infiniteupgrades] Creative tab add failed", t);
        }
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        try {
            UpgradeServerConfig.snapshot();
            LOGGER.info("[Infiniteupgrades] ServerAboutToStart: ensured server config is loaded/written");
        } catch (Throwable t) {
            LOGGER.error("[Infiniteupgrades] Failed to pre-load server config", t);
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[Infiniteupgrades] Server starting");
        try {
            UpgradeServerConfig.snapshot();
        } catch (Throwable t) {
            LOGGER.error("[Infiniteupgrades] Failed to load server config on start", t);
        }
    }
}
