// MainFile: src/main/java/org/z2six/infiniteupgrades/client/screen/AngelScreen.java
package org.z2six.infiniteupgrades.client.screen;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.world.menu.AngelMenu;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the custom Angel container GUI and tooltips.
 * PNG is 512x512, but we only draw the top-left 274x166 region.
 */
public class AngelScreen extends AbstractContainerScreen<AngelMenu> {
    private static final Logger LOG = LogUtils.getLogger();

    private static final ResourceLocation BG =
            ResourceLocation.fromNamespaceAndPath("infiniteupgrades", "textures/gui/container/angel_menu.png");

    // Actual GUI portion size (top-left of 512x512)
    private static final int IMAGE_W = 274;
    private static final int IMAGE_H = 166;

    public AngelScreen(AngelMenu menu, Inventory inv, Component title) {
        super(menu, inv, Component.empty());
        this.imageWidth = IMAGE_W;
        this.imageHeight = IMAGE_H;
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

        Slot prevHovered = this.hoveredSlot;
        boolean interceptPreviewTooltip = prevHovered != null && prevHovered.index == 2 && prevHovered.hasItem();
        if (interceptPreviewTooltip) {
            this.hoveredSlot = null;
        }

        super.render(gg, mouseX, mouseY, partialTick);

        if (interceptPreviewTooltip) {
            ItemStack stack = this.menu.getSlot(2).getItem();
            List<Component> vanilla = Screen.getTooltipFromItem(this.minecraft, stack);
            List<Component> modified = addPercentSuffixToCombatLines(stack, vanilla);
            List<net.minecraft.util.FormattedCharSequence> ordered =
                    modified.stream().map(Component::getVisualOrderText).toList();
            gg.renderTooltip(this.font, ordered, mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics gg, float partialTick, int mouseX, int mouseY) {
        // Only blit the top-left 274x166 of the 512x512 texture
        gg.blit(BG, leftPos, topPos,
                0, 0,                    // source U,V
                imageWidth, imageHeight, // width/height to draw
                512, 512);               // full texture size of the PNG

        // Example: blue progress bar
        int barLeft = leftPos + 72;
        int barTop = topPos + 38;
        int w = 54;
        gg.fill(barLeft, barTop, barLeft + w, barTop + 6, 0xFF3A7BD5);
    }

    @Override
    protected void renderLabels(GuiGraphics gg, int mouseX, int mouseY) {
        // no labels
    }

    private static List<Component> addPercentSuffixToCombatLines(ItemStack preview, List<Component> vanilla) {
        double step = 0.0;
        try {
            CustomData cd = preview.get(DataComponents.CUSTOM_DATA);
            if (cd != null) {
                CompoundTag tag = cd.copyTag();
                if (tag.contains("iu_step")) {
                    step = tag.getDouble("iu_step");
                }
            }
        } catch (Throwable ignored) {}

        int pct = (int)Math.round(step * 100.0);
        if (pct <= 0) return vanilla;

        List<Component> out = new ArrayList<>(vanilla.size());
        for (Component c : vanilla) {
            String s = c.getString();
            String lower = s.toLowerCase();
            if (lower.startsWith("when ")) {
                out.add(c);
                continue;
            }
            boolean isCombatLine =
                    lower.contains("attack damage") ||
                            lower.contains("attack speed")  ||
                            lower.contains("armor toughness") ||
                            (lower.contains(" armor") && !lower.contains("armor trim")) ||
                            lower.contains("knockback resistance");

            if (isCombatLine && !s.contains("(+")) {
                out.add(c.copy().append(Component.literal(" (+" + pct + "%)").withStyle(ChatFormatting.BLUE)));
            } else {
                out.add(c);
            }
        }
        return out;
    }
}
