package org.z2six.infiniteupgrades.core.bootstrap.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.z2six.infiniteupgrades.core.registry.ModBlocks;
import org.z2six.infiniteupgrades.core.registry.ModEntityTypes;
import org.z2six.infiniteupgrades.core.registry.ModMenus;
import org.z2six.infiniteupgrades.feature.infusion.client.InfuseClientEffects;
import org.z2six.infiniteupgrades.feature.infusion.client.InfuseClientTicker;
import org.z2six.infiniteupgrades.feature.infusion.client.screen.AngelDemonScreen;
import org.z2six.infiniteupgrades.feature.souls.client.render.SoulOrbRenderer;
import org.z2six.infiniteupgrades.feature.tooltips.TooltipHooks;

@Mod.EventBusSubscriber(modid = Infiniteupgrades.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    private static final Logger LOG = LogUtils.getLogger();

    private ClientSetup() {}

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        try {
            event.registerEntityRenderer(ModEntityTypes.SOUL_ORB.get(), SoulOrbRenderer::new);
        } catch (Throwable t) {
            LOG.error("[ClientSetup] Failed to register entity renderers", t);
        }
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        try {
            MinecraftForge.EVENT_BUS.addListener(TooltipHooks::onTooltip);
            MinecraftForge.EVENT_BUS.addListener(InfuseClientTicker::onClientTick);
            MinecraftForge.EVENT_BUS.addListener((RenderGuiEvent.Post evt) ->
                    InfuseClientEffects.onRenderGuiPost(evt.getGuiGraphics()));
            MinecraftForge.EVENT_BUS.addListener((ScreenEvent.Render.Post evt) ->
                    InfuseClientEffects.onRenderGuiPost(evt.getGuiGraphics()));

            event.enqueueWork(() -> {
                try {
                    MenuScreens.register(ModMenus.ANGEL_MENU.get(), AngelDemonScreen::new);
                    ItemBlockRenderTypes.setRenderLayer(ModBlocks.ANGEL_STATUE.get(), RenderType.cutout());
                    ItemBlockRenderTypes.setRenderLayer(ModBlocks.DEMON_STATUE.get(), RenderType.cutout());
                } catch (Throwable t) {
                    LOG.error("[ClientSetup] Client enqueueWork failed", t);
                }
            });
        } catch (Throwable t) {
            LOG.error("[ClientSetup] Failed to register client listeners", t);
        }
    }
}
