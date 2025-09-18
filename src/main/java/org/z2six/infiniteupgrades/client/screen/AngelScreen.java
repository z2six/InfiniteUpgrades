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

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the custom Angel container GUI and tooltips.
 * PNG is 512x512, but we only draw the top-left 274x166 region.
 *
 * Tooltips:
 *  - PREVIEW (slot 2 with iu_preview=true):
 *      Obfuscates numeric parts (same as before).
 *  - REAL items (any slot, including output after infusion):
 *      We augment each combat-attribute line by appending " (+X%)"
 *      where X is the accumulated percent from the item audit
 *      (iu_upgrade -> totals[<attrId>] -> sumPercent).
 *
 * Button:
 *  - "Infuse" sends a menu button click (server receives in AngelMenu.clickMenuButton).
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

    // Mapping displayed English names to our audit attribute ids
    // (Safe best-effort; if the user's language differs, we still show vanilla text.)
    private static final Map<String, String> DISPLAY_TO_ATTR_ID = Map.of(
            "attack damage", "minecraft:generic.attack_damage",
            "attack speed", "minecraft:generic.attack_speed",
            "armor toughness", "minecraft:generic.armor_toughness",
            "knockback resistance", "minecraft:generic.knockback_resistance",
            "armor", "minecraft:generic.armor"
    );

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
        int bx = leftPos + 176 + 8; // tweak position as needed
        int by = topPos + 18;
        infuseBtn = Button.builder(Component.literal("Infuse"), b -> {
            try {
                if (this.minecraft != null && this.minecraft.gameMode != null) {
                    LOG.info("[AngelScreen] INFUSE click -> sending button {} for container {}", AngelMenu.BUTTON_INFUSE, this.menu.containerId);
                    this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, AngelMenu.BUTTON_INFUSE);
                } else {
                    LOG.warn("[AngelScreen] INFUSE click but gameMode is null");
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

        // Decide if we intercept for preview or augment for real items
        Slot hoveredBefore = this.hoveredSlot;
        boolean interceptPreview = false;
        boolean augmentReal = false;

        if (hoveredBefore != null && hoveredBefore.hasItem()) {
            ItemStack s = hoveredBefore.getItem();
            if (hoveredBefore.index == 2 && isPreview(s)) {
                interceptPreview = true; // custom obfuscated tooltip
            } else {
                augmentReal = true;      // vanilla + "(+X%)" tail on combat lines
            }
        }

        if (interceptPreview) {
            // Prevent super.render from drawing vanilla tooltip for PREVIEW
            this.hoveredSlot = null;
            LOG.debug("[AngelScreen] Intercepting tooltip for preview in slot 2");
        }

        // Draw everything (slots/items/widgets)
        super.render(gg, mouseX, mouseY, partialTick);

        // Restore hovered slot BEFORE we draw any tooltip
        if (interceptPreview) this.hoveredSlot = hoveredBefore;

        if (interceptPreview) {
            // Build modified tooltip for PREVIEW stack
            ItemStack stack = this.menu.getSlot(2).getItem();
            List<Component> vanilla = Screen.getTooltipFromItem(this.minecraft, stack);
            List<Component> modified = obfuscateNumericPartsInCombatLines(stack, vanilla);

            List<net.minecraft.util.FormattedCharSequence> ordered = modified.stream()
                    .map(Component::getVisualOrderText)
                    .toList();
            gg.renderTooltip(this.font, ordered, mouseX, mouseY);
        } else if (augmentReal) {
            // Augment any real item’s tooltip inside our GUI (including output after infusion)
            ItemStack stack = hoveredBefore.getItem();
            List<Component> vanilla = Screen.getTooltipFromItem(this.minecraft, stack);
            List<Component> modified = appendPercentFromAudit(stack, vanilla);

            List<net.minecraft.util.FormattedCharSequence> ordered = modified.stream()
                    .map(Component::getVisualOrderText)
                    .toList();
            gg.renderTooltip(this.font, ordered, mouseX, mouseY);
        } else {
            // Not intercepting: let vanilla draw tooltips
            this.renderTooltip(gg, mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics gg, float partialTick, int mouseX, int mouseY) {
        // Only blit the top-left 274x166 of the 512x512 texture
        gg.blit(BG, leftPos, topPos,
                0, 0,
                imageWidth, imageHeight,
                512, 512);
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

    /** For REAL items: append " (+X%)" to combat lines based on our audit totals. */
    private static List<Component> appendPercentFromAudit(ItemStack stack, List<Component> vanilla) {
        // Read totals map once
        Map<String, Double> totals = readAuditPercents(stack);
        if (totals.isEmpty()) return vanilla; // nothing to add

        List<Component> out = new ArrayList<>(vanilla.size());
        for (Component c : vanilla) {
            String raw = c.getString();
            String lower = raw.toLowerCase();

            // Equipment-slot headers etc.
            if (lower.startsWith("when ")) {
                out.add(c);
                continue;
            }

            // Identify which attribute line this is (english match best-effort)
            String matchedAttrId = null;
            for (Map.Entry<String, String> e : DISPLAY_TO_ATTR_ID.entrySet()) {
                if (lower.contains(e.getKey())) {
                    matchedAttrId = e.getValue();
                    break;
                }
            }
            if (matchedAttrId == null) {
                out.add(c);
                continue;
            }

            Double sumPct = totals.get(matchedAttrId);
            if (sumPct == null || Math.abs(sumPct) < 1.0e-9) {
                out.add(c);
                continue;
            }

            // Build " (+X%)" with color by sign
            String pctText = formatPercent(sumPct); // e.g., "+15%" or "-5%"
            ChatFormatting col = sumPct >= 0.0 ? ChatFormatting.DARK_GREEN : ChatFormatting.RED;

            MutableComponent withTail = c.copy().append(Component.literal(" (" + pctText + ")").withStyle(col));
            out.add(withTail);
        }
        return out;
    }

    private static Map<String, Double> readAuditPercents(ItemStack stack) {
        try {
            CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
            if (cd == null) return Map.of();
            var root = cd.copyTag().getCompound("iu_upgrade");
            if (root == null) return Map.of();
            var totals = root.getCompound("totals");
            if (totals == null || totals.isEmpty()) return Map.of();

            Map<String, Double> map = new HashMap<>();
            for (String key : totals.getAllKeys()) {
                var a = totals.getCompound(key);
                double sumPercent = a.getDouble("sumPercent");
                map.put(key, sumPercent);
            }
            return map;
        } catch (Throwable ignored) {
            return Map.of();
        }
    }

    private static String formatPercent(double frac) {
        // Convert fraction to percent string; keep one decimal if needed.
        double val = frac * 100.0;
        double abs = Math.abs(val);
        String sign = val >= 0 ? "+" : "-";
        double rounded = (abs % 1.0 < 0.05) ? Math.rint(abs) : Math.round(abs * 10.0) / 10.0;
        if (Math.abs(rounded - Math.rint(abs)) < 1e-9) {
            return sign + ((int)Math.rint(abs)) + "%";
        } else {
            return sign + rounded + "%";
        }
    }

    /** Obfuscate every [...] segment in the input, preserving other text. */
    private static MutableComponent obfuscateBracketSegments(String text) {
        MutableComponent result = Component.literal("");
        int idx = 0;
        Matcher bm = BRACKETS.matcher(text);
        while (bm.find()) {
            if (bm.start() > idx) {
                result = result.append(Component.literal(text.substring(idx, bm.start())));
            }
            String seg = text.substring(bm.start(), bm.end());
            result = result.append(Component.literal(seg).withStyle(ChatFormatting.OBFUSCATED));
            idx = bm.end();
        }
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
