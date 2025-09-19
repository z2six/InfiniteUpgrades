// MainFile: src/main/java/org/z2six/infiniteupgrades/client/screen/DemonScreen.java
package org.z2six.infiniteupgrades.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.Infiniteupgrades;
import org.z2six.infiniteupgrades.world.menu.DemonMenu;

/**
 * Simple container screen for DemonMenu.
 * Uses your provided GUI texture: textures/gui/container/demon_menu.png
 *
 * NOTE: ClientSetup will register the screen factory (next step).
 */
public class DemonScreen extends AbstractContainerScreen<DemonMenu> {
    private static final Logger LOG = LogUtils.getLogger();
    private static final ResourceLocation TEX = ResourceLocation.fromNamespaceAndPath(
            Infiniteupgrades.MODID, "textures/gui/container/demon_menu.png");

    public DemonScreen(DemonMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        try {
            RenderSystem.enableBlend();
            g.blit(TEX, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);
            RenderSystem.disableBlend();
        } catch (Throwable t) {
            LOG.error("[DemonScreen] renderBg failed: {}", t.toString());
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        try {
            g.drawString(this.font, this.title, 8, 6, 0x404040, false);
            // Optionally draw chance preview if you want parity with Angel screen
        } catch (Throwable t) {
            LOG.error("[DemonScreen] renderLabels failed: {}", t.toString());
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        try {
            this.renderBackground(g);
            super.render(g, mouseX, mouseY, partialTick);
            this.renderTooltip(g, mouseX, mouseY);
        } catch (Throwable t) {
            LOG.error("[DemonScreen] render failed: {}", t.toString());
        }
    }
}
