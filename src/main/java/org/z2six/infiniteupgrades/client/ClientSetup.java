// MainFile: src/main/java/org/z2six/infiniteupgrades/client/ClientSetup.java
package org.z2six.infiniteupgrades.client;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.Infiniteupgrades;
import org.z2six.infiniteupgrades.registry.ModEntityTypes;

/**
 * ClientSetup – client-only initialisation for InfiniteUpgrades.
 *
 * - Registers the Angel entity renderer (MOD bus event).
 * - Registers runtime client listeners (GAME bus), including the tooltip augmentation.
 *
 * Uses only non-deprecated APIs for NeoForge 1.21.1.
 */
@EventBusSubscriber(modid = Infiniteupgrades.MODID, value = Dist.CLIENT)
public final class ClientSetup {
    private static final Logger LOG = LogUtils.getLogger();

    private ClientSetup() {}

    /**
     * Register entity renderers (MOD bus event).
     */
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers e) {
        try {
            e.registerEntityRenderer(ModEntityTypes.ANGEL.get(), AngelRenderer::new);
            LOG.debug("[ClientSetup] Registered AngelRenderer");
        } catch (Throwable t) {
            LOG.error("[ClientSetup] Failed to register AngelRenderer: {}", t.toString());
        }
    }

    /**
     * Register GAME bus listeners at client setup time (non-deprecated).
     * This is the right place to hook ItemTooltipEvent etc. for NeoForge 1.21.1.
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent e) {
        try {
            NeoForge.EVENT_BUS.addListener(TooltipHooks::onTooltip);
            LOG.debug("[ClientSetup] Registered TooltipHooks listener on NeoForge.EVENT_BUS");
        } catch (Throwable t) {
            LOG.error("[ClientSetup] Failed to register TooltipHooks listener: {}", t.toString());
        }
    }
}
