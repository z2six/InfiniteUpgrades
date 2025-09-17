// MainFile: src/main/java/org/z2six/infiniteupgrades/client/screen/AngelScreen.java
package org.z2six.infiniteupgrades.client.screen;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the custom Angel container GUI and tooltips.
 * PNG is 512x512, but we only draw the top-left 274x166 region.
 *
 * Behavior:
 *  - No progress bar.
 *  - For the ghost output (slot 2) tooltip:
 *      * Keep attribute NAMES readable (e.g., "Attack Damage").
 *      * Obfuscate ONLY the numeric value at the start (e.g., "8" or "-2.4"),
 *        and any bracketed breakdown (e.g., "[1 + 7]") if present.
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

    public AngelScreen(AngelMenu menu, Inventory inv, Component title) {
        // Title not shown; pass empty to avoid surprises
        super(menu, inv, Component.empty());
        this.imageWidth = IMAGE_W;
        this.imageHeight = IMAGE_H;
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

            // New behavior: obfuscate only numeric parts on combat attribute lines
            List<Component> modified = obfuscateNumericPartsInCombatLines(stack, vanilla);

            // Convert to visual-order text for GuiGraphics API
            List<net.minecraft.util.FormattedCharSequence> ordered = modified.stream()
                    .map(Component::getVisualOrderText)
                    .toList();

            // Render our adjusted tooltip
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
        // (No progress bar)
    }

    // Intentionally draw NO labels (no title, no "chance" text)
    @Override
    protected void renderLabels(GuiGraphics gg, int mouseX, int mouseY) {
        // no-op
    }

    // ---- Tooltip post-processing ---------------------------------------------------------------

    /**
     * For combat attribute lines:
     *  - Keep the attribute name readable.
     *  - Obfuscate the leading numeric value and any bracketed breakdown "[...]".
     * Everything else stays unchanged.
     */
    private static List<Component> obfuscateNumericPartsInCombatLines(ItemStack preview, List<Component> vanilla) {
        // We no longer use iu_step here; keep this read harmlessly in case tags are present.
        try {
            CustomData cd = preview.get(DataComponents.CUSTOM_DATA);
            if (cd != null) {
                CompoundTag tag = cd.copyTag();
                // (unused)
            }
        } catch (Throwable ignored) {}

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
}
