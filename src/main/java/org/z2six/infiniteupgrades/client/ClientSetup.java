// MainFile: src/main/java/org/z2six/infiniteupgrades/client/ClientSetup.java
package org.z2six.infiniteupgrades.client;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.Infiniteupgrades;
import org.z2six.infiniteupgrades.client.render.AngelRenderer;
import org.z2six.infiniteupgrades.client.render.DemonRenderer;
import org.z2six.infiniteupgrades.client.screen.AngelScreen;
import org.z2six.infiniteupgrades.client.screen.DemonScreen;
import org.z2six.infiniteupgrades.registry.ModEntityTypes;
import org.z2six.infiniteupgrades.registry.ModMenus;

/**
 * Client-only registrations for renderers and screens.
 * Your existing Angel registration is preserved verbatim; Demon is appended.
 */
@EventBusSubscriber(modid = Infiniteupgrades.MODID, value = Dist.CLIENT)
public final class ClientSetup {
    private static final Logger LOG = LogUtils.getLogger();

    private ClientSetup() {}

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers e) {
        try {
            // --- Existing: Angel ---
            e.registerEntityRenderer(ModEntityTypes.ANGEL.get(), AngelRenderer::new);
            LOG.debug("[ClientSetup] Registered AngelRenderer");
        } catch (Throwable t) {
            LOG.error("[ClientSetup] Failed to register AngelRenderer: {}", t.toString());
        }

        try {
            // --- New: Demon ---
            e.registerEntityRenderer(ModEntityTypes.DEMON.get(), DemonRenderer::new);
            LOG.debug("[ClientSetup] Registered DemonRenderer");
        } catch (Throwable t) {
            LOG.error("[ClientSetup] Failed to register DemonRenderer: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onRegisterScreens(RegisterMenuScreensEvent e) {
        try {
            // Keep your Angel screen factory intact (assumes ANGEL_MENU exists in ModMenus)
            e.register(ModMenus.ANGEL_MENU.get(), AngelScreen::new);
            LOG.debug("[ClientSetup] Registered AngelScreen");
        } catch (Throwable t) {
            LOG.error("[ClientSetup] Failed to register AngelScreen (skipping if already elsewhere): {}", t.toString());
        }

        try {
            // New Demon screen
            e.register(ModMenus.DEMON_MENU.get(), DemonScreen::new);
            LOG.debug("[ClientSetup] Registered DemonScreen");
        } catch (Throwable t) {
            LOG.error("[ClientSetup] Failed to register DemonScreen: {}", t.toString());
        }
    }
}
