// MainFile: src/main/java/org/z2six/infiniteupgrades/core/bootstrap/client/ClientSetup.java
package org.z2six.infiniteupgrades.core.bootstrap.client;

import com.mojang.logging.LogUtils;
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
import org.z2six.infiniteupgrades.feature.infusion.client.InfuseClientEffects;
import org.z2six.infiniteupgrades.feature.tooltips.TooltipHooks;

/**
 * File: src/main/java/org/z2six/infiniteupgrades/core/bootstrap/client/ClientSetup.java
 *
 * ClientSetup – client-only initialisation for InfiniteUpgrades.
 *
 * - Registers runtime client listeners (GAME bus), including tooltip augmentation.
 * - Registers infusion overlay render + tick listeners (non-deprecated NeoForge APIs).
 * - Renderer registration for Angel/Demon removed (entities deleted).
 */
@EventBusSubscriber(modid = Infiniteupgrades.MODID, value = Dist.CLIENT)
public final class ClientSetup {
    private static final Logger LOG = LogUtils.getLogger();

    private ClientSetup() {}

    /**
     * Renderer registrations for Angel/Demon were removed.
     * If you want to keep renderer registration consolidated here in the future,
     * you can register Soul Orb here and remove it from Infiniteupgrades.Client.
     */
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers e) {
        // No-op after Angel/Demon removal
        LOG.debug("[ClientSetup] RegisterRenderers no-op (Angel/Demon removed).");
    }

    /**
     * Register GAME bus listeners at client setup time (non-deprecated).
     * Hooks:
     *  - Item tooltips
     *  - Infusion effects tick (ClientTickEvent.Post)
     *  - Overlay when NO screen is open (RenderGuiEvent.Post)
     *  - Overlay when a screen IS open (ScreenEvent.Render.Post)
     */
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

            LOG.debug("[ClientSetup] Registered TooltipHooks and InfuseClientEffects listeners on NeoForge.EVENT_BUS");
        } catch (Throwable t) {
            LOG.error("[ClientSetup] Failed to register client listeners: {}", t.toString());
        }
    }
}
