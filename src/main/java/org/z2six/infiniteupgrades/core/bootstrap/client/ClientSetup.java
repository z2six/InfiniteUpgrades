// MainFile: src/main/java/org/z2six/infiniteupgrades/core/bootstrap/client/ClientSetup.java
package org.z2six.infiniteupgrades.core.bootstrap.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.z2six.infiniteupgrades.core.registry.ModBlocks;
import org.z2six.infiniteupgrades.feature.infusion.client.InfuseClientEffects;
import org.z2six.infiniteupgrades.feature.tooltips.TooltipHooks;

/**
 * ClientSetup – client-only initialisation for InfiniteUpgrades.
 *
 * - Registers runtime client listeners (GAME bus), including tooltip augmentation.
 * - Registers infusion overlay render + tick listeners (non-deprecated NeoForge APIs).
 * - Sets render layers for statue blocks so PNG alpha works (cutout by default).
 * - Renderer registration for Angel/Demon entities remains removed.
 */
@EventBusSubscriber(modid = Infiniteupgrades.MODID, value = Dist.CLIENT)
public final class ClientSetup {
    private static final Logger LOG = LogUtils.getLogger();

    private ClientSetup() {}

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers e) {
        // No-op after Angel/Demon removal
        LOG.debug("[ClientSetup] RegisterRenderers no-op (Angel/Demon removed).");
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent e) {
        try {
            // Tooltips
            NeoForge.EVENT_BUS.addListener(TooltipHooks::onTooltip);

            // Infusion effects: per-tick scheduler
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post evt) ->
                    InfuseClientEffects.clientTick()
            );

            // Overlay when NO screen is open (HUD render path)
            NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post evt) ->
                    InfuseClientEffects.onRenderGuiPost(evt.getGuiGraphics())
            );

            // Overlay when a screen IS open (draw after screen -> on top of GUI)
            NeoForge.EVENT_BUS.addListener((ScreenEvent.Render.Post evt) ->
                    InfuseClientEffects.onRenderGuiPost(evt.getGuiGraphics())
            );

            // ---- Render layers for alpha PNGs on statues ----
            e.enqueueWork(() -> {
                try {
                    // Use CUTOUT for hard alpha wings (recommended)
                    ItemBlockRenderTypes.setRenderLayer(ModBlocks.ANGEL_STATUE.get(), RenderType.cutout());
                    ItemBlockRenderTypes.setRenderLayer(ModBlocks.DEMON_STATUE.get(), RenderType.cutout());

                    // If you need soft transparency/gradients, swap to translucent:
                    // ItemBlockRenderTypes.setRenderLayer(ModBlocks.ANGEL_STATUE.get(), RenderType.translucent());
                    // ItemBlockRenderTypes.setRenderLayer(ModBlocks.DEMON_STATUE.get(), RenderType.translucent());

                    LOG.debug("[ClientSetup] Render layers set for statues (cutout).");
                } catch (Throwable t) {
                    LOG.error("[ClientSetup] Failed to set statue render layers: {}", t.toString());
                }
            });

            LOG.debug("[ClientSetup] Registered TooltipHooks/InfuseClientEffects & render layers.");
        } catch (Throwable t) {
            LOG.error("[ClientSetup] Failed to register client listeners: {}", t.toString());
        }
    }
}
