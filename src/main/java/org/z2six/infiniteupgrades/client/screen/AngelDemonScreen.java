package org.z2six.infiniteupgrades.client.screen;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
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
import org.z2six.infiniteupgrades.client.widget.TriStateImageButton;
import org.z2six.infiniteupgrades.logic.RitualType;
import org.z2six.infiniteupgrades.world.menu.AngelDemonMenu;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Angel/Demon GUI screen (one PNG per element; no cropping).
 *
 * main.png:    176x222 (drawn at leftPos, topPos)
 * details.png: 128x222 drawn at (leftPos + 176 + 1, topPos), i.e., 1px gap
 * buttons:     90x18; all three states drawn at the *same* spot, per ritual
 *
 * Slot & button anchors are MAIN-space (relative to main.png origin),
 * so we place widgets at leftPos + anchorX, topPos + anchorY.
 */
public class AngelDemonScreen extends AbstractContainerScreen<AngelDemonMenu> {
    private static final Logger LOG = LogUtils.getLogger();

    // Main & details sizes
    private static final int MAIN_W = 176;
    private static final int MAIN_H = 222;
    private static final int DETAILS_W = 128;
    private static final int DETAILS_H = 222;
    private static final int GAP = 1;

    // Composite canvas used for centering the screen
    private static final int CANVAS_W = MAIN_W + GAP + DETAILS_W; // 176 + 1 + 128 = 305
    private static final int CANVAS_H = 222;

    // Button size & MAIN-space anchor
    private static final int BTN_W = 90;
    private static final int BTN_H = 18;
    private static final int BTN_X = 44;  // X44/Y107
    private static final int BTN_Y = 107;

    // Tooltip helpers
    private static final Pattern LEADING_NUM = Pattern.compile("^\\s*([+\\-]?\\d+(?:\\.\\d+)?)\\s+(.*)$");
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*\\]");
    private static final Map<String, String> DISPLAY_TO_ATTR_ID = Map.of(
            "attack damage", "minecraft:generic.attack_damage",
            "attack speed", "minecraft:generic.attack_speed",
            "armor toughness", "minecraft:generic.armor_toughness",
            "knockback resistance", "minecraft:generic.knockback_resistance",
            "armor", "minecraft:generic.armor"
    );

    private TriStateImageButton infuseBtn;

    public AngelDemonScreen(AngelDemonMenu menu, Inventory inv, Component title) {
        super(menu, inv, Component.empty());
        // Center a COMPOSITE canvas (main + gap + details)
        this.imageWidth = CANVAS_W;
        this.imageHeight = CANVAS_H;

        // We hide vanilla labels for now
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = this.imageHeight - 96 + 2;
    }

    private ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath("infiniteupgrades", path);
    }

    private String folder() {
        return this.menu.ritual() == RitualType.DEMON ? "demon" : "angel";
    }

    private ResourceLocation mainTex() {
        return rl("textures/gui/container/" + folder() + "/main.png");
    }

    private ResourceLocation detailsTex() {
        return rl("textures/gui/container/" + folder() + "/details.png");
    }

    private ResourceLocation btnDefaultTex() {
        return rl("textures/gui/container/" + folder() + "/btn_infuse_default.png");
    }

    private ResourceLocation btnHoverTex() {
        return rl("textures/gui/container/" + folder() + "/btn_infuse_hover.png");
    }

    private ResourceLocation btnLockedTex() {
        return rl("textures/gui/container/" + folder() + "/btn_infuse_locked.png");
    }

    @Override
    protected void init() {
        super.init();
        LOG.debug("[AngelDemonScreen] init at {},{}", leftPos, topPos);

        // Button is placed on MAIN panel at (44,107)
        int bx = leftPos + BTN_X;
        int by = topPos + BTN_Y;

        infuseBtn = new TriStateImageButton(
                bx, by, BTN_W, BTN_H,
                btnDefaultTex(),
                btnHoverTex(),
                btnLockedTex(),
                () -> {
                    try {
                        if (this.minecraft != null && this.minecraft.gameMode != null) {
                            if (infuseBtn.isLocked()) return;
                            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, AngelDemonMenu.BUTTON_INFUSE);
                            // Placeholder: hardcoded 3s lock (server-authoritative hookup later)
                            infuseBtn.lockForSeconds(3);
                        }
                    } catch (Throwable t) {
                        LOG.error("[AngelDemonScreen] Infuse onPress failed", t);
                    }
                }
        );
        this.addRenderableWidget(infuseBtn);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (infuseBtn != null) infuseBtn.clientTickLock();
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // Background first
        this.renderBackground(gg, mouseX, mouseY, partialTick);

        // Tooltip handling
        Slot hoveredBefore = this.hoveredSlot;
        boolean interceptPreview = false;
        boolean augmentReal = false;

        if (hoveredBefore != null && hoveredBefore.hasItem()) {
            ItemStack s = hoveredBefore.getItem();
            if (hoveredBefore.index == 2 && isPreview(s)) {
                interceptPreview = true;
            } else {
                augmentReal = true;
            }
        }

        if (interceptPreview) {
            this.hoveredSlot = null; // suppress vanilla tooltip this frame
        }

        // Draw everything (slots/items/widgets)
        super.render(gg, mouseX, mouseY, partialTick);

        if (interceptPreview) {
            ItemStack stack = hoveredBefore.getItem();
            List<Component> vanilla = Screen.getTooltipFromItem(this.minecraft, stack);
            List<Component> modified = obfuscateNumericPartsInCombatLines(stack, vanilla);
            List<net.minecraft.util.FormattedCharSequence> ordered = modified.stream()
                    .map(Component::getVisualOrderText)
                    .toList();
            gg.renderTooltip(this.font, ordered, mouseX, mouseY);
        } else if (augmentReal && hoveredBefore != null && hoveredBefore.hasItem()) {
            ItemStack stack = hoveredBefore.getItem();
            List<Component> vanilla = Screen.getTooltipFromItem(this.minecraft, stack);
            List<Component> modified = appendPercentFromAudit(stack, vanilla);
            List<net.minecraft.util.FormattedCharSequence> ordered = modified.stream()
                    .map(Component::getVisualOrderText)
                    .toList();
            gg.renderTooltip(this.font, ordered, mouseX, mouseY);
        } else {
            this.renderTooltip(gg, mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics gg, float partialTick, int mouseX, int mouseY) {
        // 1) Main background (exact-sized)
        gg.blit(mainTex(), leftPos, topPos, 0, 0, MAIN_W, MAIN_H, MAIN_W, MAIN_H);

        // 2) Details panel to the right with 1px gap
        int panelX = leftPos + MAIN_W + GAP;
        int panelY = topPos;
        gg.blit(detailsTex(), panelX, panelY, 0, 0, DETAILS_W, DETAILS_H, DETAILS_W, DETAILS_H);
    }

    @Override
    protected void renderLabels(GuiGraphics gg, int mouseX, int mouseY) {
        // no labels for now
    }

    // ---------------- Tooltip helpers ----------------

    private static List<Component> obfuscateNumericPartsInCombatLines(ItemStack preview, List<Component> vanilla) {
        List<Component> out = new ArrayList<>(vanilla.size());
        for (Component c : vanilla) {
            String raw = c.getString();
            String lower = raw.toLowerCase();

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

            Matcher m = LEADING_NUM.matcher(raw);
            if (!m.find()) {
                out.add(obfuscateBracketsOnly(raw));
                continue;
            }

            String num = m.group(1);
            String rest = m.group(2);

            MutableComponent rebuilt = Component.literal("")
                    .append(Component.literal(num).withStyle(ChatFormatting.OBFUSCATED))
                    .append(Component.literal(" "))
                    .append(obfuscateBracketSegments(rest));

            out.add(rebuilt);
        }
        return out;
    }

    private static List<Component> appendPercentFromAudit(ItemStack stack, List<Component> vanilla) {
        Map<String, Double> totals = readAuditPercents(stack);
        if (totals.isEmpty()) return vanilla;

        List<Component> out = new ArrayList<>(vanilla.size());
        for (Component c : vanilla) {
            String raw = c.getString();
            String lower = raw.toLowerCase();

            if (lower.startsWith("when ")) {
                out.add(c);
                continue;
            }

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

            String pctText = formatPercent(sumPct);
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

    private static MutableComponent obfuscateBracketsOnly(String text) {
        return obfuscateBracketSegments(text);
    }

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
