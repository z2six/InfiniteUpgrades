// MainFile: src/main/java/org/z2six/infiniteupgrades/client/screen/AngelScreen.java
package org.z2six.infiniteupgrades.client.screen;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.Infiniteupgrades;
import org.z2six.infiniteupgrades.world.menu.AngelMenu;

public class AngelScreen extends AbstractContainerScreen<AngelMenu> {
    private static final Logger LOG = LogUtils.getLogger();
    // Use a vanilla, known-good texture
    private static final ResourceLocation BG =
        ResourceLocation.withDefaultNamespace("textures/gui/container/anvil.png");

    public AngelScreen(AngelMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = this.imageHeight - 96 + 2;
    }

    @Override
    protected void init() {
        super.init();
        LOG.debug("[AngelScreen] init at {},{}", leftPos, topPos);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gg, mouseX, mouseY, partialTick);
        super.render(gg, mouseX, mouseY, partialTick);
        this.renderTooltip(gg, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics gg, float partialTick, int mouseX, int mouseY) {
        gg.blit(BG, leftPos, topPos, 0, 0, imageWidth, imageHeight);
        // progress bar placeholder (0..54 px wide)
        int barLeft = leftPos + 72;
        int barTop = topPos + 38;
        int w = 54; // for now, no progress logic yet
        gg.fill(barLeft, barTop, barLeft + w, barTop + 6, 0xFF3A7BD5); // blue-ish
    }

    @Override
    protected void renderLabels(GuiGraphics gg, int mouseX, int mouseY) {
        gg.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 0x404040, false);
        gg.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 0x404040, false);
    }
}
