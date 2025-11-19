// MainFile: src/main/java/org/z2six/infiniteupgrades/core/Config.java
package org.z2six.infiniteupgrades.core;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Central COMMON config (client-safe).
 *
 * IMPORTANT:
 *  - No server-authoritative tuning lives here anymore.
 *  - Gameplay tuning is in UpgradeServerConfig (SERVER).
 *
 * Kept: your original "example.*" entries.
 */
@EventBusSubscriber(modid = Infiniteupgrades.MODID)
public class Config {

    private static final Logger LOG = LogUtils.getLogger();

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("example.logDirtBlock", true);

    private static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("example.magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("example.magicNumberIntroduction", "The magic number is... ");

    private static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("example.items", List.of("minecraft:iron_ingot"), Config::validateItemName);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean      logDirtBlock;
    public static int          magicNumber;
    public static String       magicNumberIntroduction;
    public static Set<Item>    items;

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName
                && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }

    /**
     * Internal helper that actually reads the COMMON config values into static fields.
     * Called only when the config is known to be loaded (Loading/Reloading events for SPEC).
     */
    private static void bakeCommon() {
        try {
            logDirtBlock = LOG_DIRT_BLOCK.get();
            magicNumber = MAGIC_NUMBER.get();
            magicNumberIntroduction = MAGIC_NUMBER_INTRODUCTION.get();
            items = ITEM_STRINGS.get().stream()
                    .map(s -> BuiltInRegistries.ITEM.get(ResourceLocation.parse(s)))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            LOG.debug(
                    "[InfiniteUpgrades/Config] COMMON config baked: logDirtBlock={}, magicNumber={}, intro='{}', items={}",
                    logDirtBlock,
                    magicNumber,
                    magicNumberIntroduction,
                    items.size()
            );
        } catch (Throwable t) {
            // Defensive: never crash the game from here, just log and keep defaults.
            LOG.error("[InfiniteUpgrades/Config] Failed to bake COMMON config safely", t);
        }
    }

    /**
     * Fired when ANY config for this mod is first loaded.
     * We filter by SPEC to ensure we only touch the COMMON config here.
     */
    @SubscribeEvent
    static void onLoad(final ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() != SPEC) {
            // Not our COMMON config; ignore.
            return;
        }
        LOG.debug("[InfiniteUpgrades/Config] ModConfigEvent.Loading received for COMMON config: {}", event.getConfig().getFileName());
        bakeCommon();
    }

    /**
     * Fired when ANY config for this mod is reloaded (e.g. via /reload or world reload).
     * Again, only act for our COMMON config spec.
     */
    @SubscribeEvent
    static void onReload(final ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != SPEC) {
            // Not our COMMON config; ignore.
            return;
        }
        LOG.debug("[InfiniteUpgrades/Config] ModConfigEvent.Reloading received for COMMON config: {}", event.getConfig().getFileName());
        bakeCommon();
    }
}
