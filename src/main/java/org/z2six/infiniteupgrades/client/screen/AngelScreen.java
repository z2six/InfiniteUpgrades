// MainFile: src/main/java/org/z2six/infiniteupgrades/client/screen/AngelScreen.java
package org.z2six.infiniteupgrades.client.screen;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.world.menu.AngelMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * // MainFile: AngelScreen.java
 * Renders the custom Angel container GUI and tooltips.
 * PNG is 512x512, but we only draw the top-left 274x166 region.
 *
 * Behavior:
 *  - No progress bar.
 *  - For the OUTPUT slot (index 2):
 *      * If it holds a GHOST preview (iu_preview=true), obfuscate only the numeric parts.
 *      * If it holds a REAL result (no iu_preview), let vanilla tooltip render normally.
 *  - "Infuse" button calls menu.onInfuseButtonPressed() (server-side).
 */
public class AngelScreen extends AbstractContainerScreen<AngelMenu> {
    private static final Logger LOG = LogUtils.getLogger();

    private static final ResourceLocation BG =
            ResourceLocation.fromNamespaceAndPath("infiniteupgrades", "textures/gui/container/angel_menu.png");

    // Actual GUI portion size (top-left of 512x512)
    private static final int IMAGE_W = 274;
    private static final int IMAGE_H = 166;

    // Regex: leading numeric token (int/float, optional sign), then space, then rest
    private static final Pattern LEADING_NUM = Pattern.compile("^\\s*([+\\-]?\\d+(?:\\.\\d+)?)\\s+(.*)$");
    // Regex: bracketed breakdowns like "[1 + 7]" (non-greedy)
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*\\]");

    private Button infuseBtn;

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

        // Add a simple "Infuse" button (server action)
        int bx = leftPos + 176 + 8; // in the right panel area; tweak as you like
        int by = topPos + 18;
        infuseBtn = Button.builder(Component.literal("Infuse"), b -> {
            try {
                if (this.minecraft != null && this.minecraft.gameMode != null) {
                    LOG.info("[AngelScreen] INFUSE click -> sending button {} for container {}", AngelMenu.BUTTON_INFUSE, this.menu.containerId);
                    this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, AngelMenu.BUTTON_INFUSE);
                } else {
                    LOG.warn("[AngelScreen] INFUSE click but gameMode is null (client not ready?)");
                }
            } catch (Throwable t) {
                LOG.error("[AngelScreen] Infuse click failed", t);
            }
        }).bounds(bx, by, 80, 20).build();
        this.addRenderableWidget(infuseBtn);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // Background first
        this.renderBackground(gg, mouseX, mouseY, partialTick);

        // Intercept vanilla tooltip ONLY for OUTPUT slot when it is a GHOST preview.
        Slot prevHovered = this.hoveredSlot;
        boolean intercept = false;
        if (prevHovered != null && prevHovered.index == 2 && prevHovered.hasItem()) {
            ItemStack s = prevHovered.getItem();
            intercept = isPreview(s); // only intercept when iu_preview=true
        }

        if (intercept) {
            // Prevent super.render from drawing vanilla tooltip for slot 2
            this.hoveredSlot = null;
            LOG.debug("[AngelScreen] Intercepting tooltip for preview in slot 2");
        }

        // Draw everything else (slots, item stacks, widgets)
        super.render(gg, mouseX, mouseY, partialTick);

        // VERY IMPORTANT: restore hoveredSlot so other tooltips keep working
        if (intercept) {
            this.hoveredSlot = prevHovered;
        }

        if (intercept) {
            // Build modified tooltip for PREVIEW stack
            ItemStack stack = this.menu.getSlot(2).getItem();

            // Vanilla tooltip (static on Screen in 1.21)
            List<Component> vanilla = Screen.getTooltipFromItem(this.minecraft, stack);

            // Obfuscate numeric parts on combat attribute lines
            List<Component> modified = obfuscateNumericPartsInCombatLines(stack, vanilla);

            // Convert to visual-order text for GuiGraphics API
            List<net.minecraft.util.FormattedCharSequence> ordered = modified.stream()
                    .map(Component::getVisualOrderText)
                    .toList();

            // Render adjusted tooltip
            gg.renderTooltip(this.font, ordered, mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics gg, float partialTick, int mouseX, int mouseY) {
        // Only blit the top-left 274x166 of the 512x512 texture
        gg.blit(BG, leftPos, topPos,
                0, 0,                    // source U,V
                imageWidth, imageHeight, // width/height to draw
                512, 512);               // full texture size
        // (No progress bar)
    }

    @Override
    protected void renderLabels(GuiGraphics gg, int mouseX, int mouseY) {
        // no labels for now
    }

    // ---- Tooltip post-processing ---------------------------------------------------------------

    /** Only used for the PREVIEW ghost. */
    private static List<Component> obfuscateNumericPartsInCombatLines(ItemStack preview, List<Component> vanilla) {
        List<Component> out = new ArrayList<>(vanilla.size());
        for (Component c : vanilla) {
            String raw = c.getString();
            String lower = raw.toLowerCase();

            // Skip equipment-slot headers like "When in Main Hand"
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

            if (!isCombatLine) {
                out.add(c);
                continue;
            }

            // Try to split "number + rest"
            Matcher m = LEADING_NUM.matcher(raw);
            if (!m.find()) {
                // No leading number detected; as a fallback, obfuscate any bracketed breakdowns only.
                out.add(obfuscateBracketsOnly(raw));
                continue;
            }

            String num = m.group(1);   // leading numeric token
            String rest = m.group(2);  // remainder (attribute name etc., may include [..])

            MutableComponent rebuilt = Component.literal("")
                    // obfuscated number
                    .append(Component.literal(num).withStyle(ChatFormatting.OBFUSCATED))
                    .append(Component.literal(" "))
                    // rest with bracketed parts obfuscated (but attribute name readable)
                    .append(obfuscateBracketSegments(rest));

            out.add(rebuilt);
        }
        return out;
    }

    /** Obfuscate every [...] segment in the input, preserving other text. */
    private static MutableComponent obfuscateBracketSegments(String text) {
        MutableComponent result = Component.literal("");
        int idx = 0;
        Matcher bm = BRACKETS.matcher(text);
        while (bm.find()) {
            // Plain text before the bracket
            if (bm.start() > idx) {
                result = result.append(Component.literal(text.substring(idx, bm.start())));
            }
            // The full bracketed segment, obfuscated (including the brackets)
            String seg = text.substring(bm.start(), bm.end());
            result = result.append(Component.literal(seg).withStyle(ChatFormatting.OBFUSCATED));
            idx = bm.end();
        }
        // Trailing text after the last bracket
        if (idx < text.length()) {
            result = result.append(Component.literal(text.substring(idx)));
        }
        return result;
    }

    /** Fallback: no leading number; obfuscate only the bracketed parts if present. */
    private static MutableComponent obfuscateBracketsOnly(String text) {
        return obfuscateBracketSegments(text);
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private static boolean isPreview(ItemStack stack) {
        try {
            if (stack.isEmpty()) return false;
            CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
            if (cd == null) return false;
            return cd.copyTag().getBoolean("iu_preview");
        } catch (Throwable ignored) {
            return false;
        }
    }
}
