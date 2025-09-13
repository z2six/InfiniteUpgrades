// MainFile: src/main/java/org/z2six/infiniteupgrades/client/ClientSetup.java
package org.z2six.infiniteupgrades.client;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.Infiniteupgrades;
import org.z2six.infiniteupgrades.registry.ModEntityTypes;

@EventBusSubscriber(modid = Infiniteupgrades.MODID, value = Dist.CLIENT)
public final class ClientSetup {
    private static final Logger LOG = LogUtils.getLogger();

    private ClientSetup() {}

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers e) {
        try {
            e.registerEntityRenderer(ModEntityTypes.ANGEL.get(), AngelRenderer::new);
            LOG.debug("[ClientSetup] Registered AngelRenderer");
        } catch (Throwable t) {
            LOG.error("[ClientSetup] Failed to register AngelRenderer: {}", t.toString());
        }
    }
}
