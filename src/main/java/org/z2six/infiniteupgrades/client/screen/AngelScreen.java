// File: src/main/java/org/z2six/infiniteupgrades/client/screen/AngelScreen.java
package org.z2six.infiniteupgrades.client.screen;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
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

public class AngelScreen extends AbstractContainerScreen<AngelMenu> {
    private static final Logger LOG = LogUtils.getLogger();

    // Use vanilla anvil GUI so we always have a texture
    private static final ResourceLocation BG = ResourceLocation.withDefaultNamespace("textures/gui/container/anvil.png");

    public AngelScreen(AngelMenu menu, Inventory inv, Component title) {
        // Title not shown; pass empty to avoid surprises
        super(menu, inv, Component.empty());
        this.imageWidth = 176;
        this.imageHeight = 166;
        // We won't draw labels, but keep positions sensible if you ever turn them back on
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
        // Background first
        this.renderBackground(gg, mouseX, mouseY, partialTick);

        // Intercept vanilla tooltip ONLY for preview slot (index 2)
        Slot prevHovered = this.hoveredSlot;
        boolean interceptPreviewTooltip = prevHovered != null && prevHovered.index == 2 && prevHovered.hasItem();

        if (interceptPreviewTooltip) {
            // Prevent super.render from drawing vanilla tooltip for slot 2
            this.hoveredSlot = null;
        }

        // Draw everything else (slots, item stacks, etc.)
        super.render(gg, mouseX, mouseY, partialTick);

        if (interceptPreviewTooltip) {
            // Build modified tooltip for preview stack
            ItemStack stack = this.menu.getSlot(2).getItem();

            // Vanilla tooltip (static on Screen in 1.21)
            List<Component> vanilla = Screen.getTooltipFromItem(this.minecraft, stack);

            // Post-process: append (+X%) to combat attribute lines
            List<Component> modified = addPercentSuffixToCombatLines(stack, vanilla);

            // Convert to visual-order text for GuiGraphics API
            List<net.minecraft.util.FormattedCharSequence> ordered = modified.stream()
                    .map(Component::getVisualOrderText)
                    .toList();

            // Render our adjusted tooltip
            gg.renderTooltip(this.font, ordered, mouseX, mouseY);
        }

        // (No additional tooltips here; super.render already drew for other slots)
    }

    @Override
    protected void renderBg(GuiGraphics gg, float partialTick, int mouseX, int mouseY) {
        // Vanilla anvil BG
        gg.blit(BG, leftPos, topPos, 0, 0, imageWidth, imageHeight);

        // Simple blue progress bar placeholder (0..54 px wide)
        int barLeft = leftPos + 72;
        int barTop = topPos + 38;
        int w = 54; // no real progress yet; purely visual
        gg.fill(barLeft, barTop, barLeft + w, barTop + 6, 0xFF3A7BD5);
    }

    // Intentionally draw NO labels (no title, no "chance" text)
    @Override
    protected void renderLabels(GuiGraphics gg, int mouseX, int mouseY) {
        // no-op
    }

    // ---- Tooltip post-processing ---------------------------------------------------------------

    /** Append " (+X%)" to combat attribute lines in the tooltip, using iu_step on the preview stack. */
    private static List<Component> addPercentSuffixToCombatLines(ItemStack preview, List<Component> vanilla) {
        double step = 0.0;
        try {
            CustomData cd = preview.get(DataComponents.CUSTOM_DATA);
            if (cd != null) {
                CompoundTag tag = cd.copyTag();
                if (tag.contains("iu_step")) {
                    step = tag.getDouble("iu_step"); // e.g., 0.05 for +5%
                }
            }
        } catch (Throwable ignored) {}

        int pct = (int)Math.round(step * 100.0);
        if (pct <= 0) return vanilla; // nothing to add

        List<Component> out = new ArrayList<>(vanilla.size());
        for (Component c : vanilla) {
            String s = c.getString();
            String lower = s.toLowerCase();

            // Skip the equipment-slot header lines like "When in Main Hand"
            if (lower.startsWith("when ")) {
                out.add(c);
                continue;
            }

            // Only target the common combat attribute lines; keep all other lines unchanged.
            boolean isCombatLine =
                    lower.contains("attack damage") ||
                            lower.contains("attack speed")  ||
                            lower.contains("armor toughness") ||
                            // guard so plain "armor" in other strings isn't caught accidentally:
                            (lower.contains(" armor") && !lower.contains("armor trim")) ||
                            lower.contains("knockback resistance");

            if (isCombatLine && !s.contains("(+")) {
                // Keep original style; just append the colored suffix
                out.add(c.copy().append(Component.literal(" (+" + pct + "%)").withStyle(ChatFormatting.BLUE)));
            } else {
                out.add(c);
            }
        }
        return out;
    }
}
