// File: src/main/java/org/z2six/infiniteupgrades/client/screen/AngelDemonScreen.java

package org.z2six.infiniteupgrades.client.screen;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
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
import org.z2six.infiniteupgrades.client.screen.view.DetailsPanelView;
import org.z2six.infiniteupgrades.client.screen.view.MainGuiView;
import org.z2six.infiniteupgrades.client.screen.view.ReputationBarView;
import org.z2six.infiniteupgrades.logic.RitualType;
import org.z2six.infiniteupgrades.network.ModNet;
import org.z2six.infiniteupgrades.world.menu.AngelDemonMenu;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Angel/Demon GUI screen split into sub-views:
 *  - MainGuiView       (main panel + infuse button)
 *  - DetailsPanelView  (static panel on the right)
 *  - ReputationBarView (above the main panel, centered on the main only)
 *
 * Group centering: (Main + 1px + Details) are centered as one unit.
 * Reputation bar sits above main and does not push the group downward.
 */
public class AngelDemonScreen extends AbstractContainerScreen<AngelDemonMenu> {
    private static final Logger LOG = LogUtils.getLogger();

    // Group dimensions: group = main(176) + 1 + details(128) = 305 width, height = 222 (max of both)
    private static final int GROUP_W = MainGuiView.MAIN_W + MainGuiView.GAP_TO_DETAILS + DetailsPanelView.DETAILS_W;
    private static final int GROUP_H = 222;

    // Rep bar dims
    private static final int REP_W = 96;
    private static final int REP_H = 11;

    // Fudge used by subviews (keep here as well for computing rep bar vertical anchor)
    private static final int DRAW_FUDGE_Y = -1;

    // Tooltip helpers (same logic you had)
    private static final Pattern LEADING_NUM = Pattern.compile("^\\s*([+\\-]?\\d+(?:\\.\\d+)?)\\s+(.*)$");
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*\\]");

    private static final Map<String, String> DISPLAY_TO_ATTR_ID = Map.of(
            "attack damage", "minecraft:generic.attack_damage",
            "attack speed", "minecraft:generic.attack_speed",
            "armor toughness", "minecraft:generic.armor_toughness",
            "knockback resistance", "minecraft:generic.knockback_resistance",
            "armor", "minecraft:generic.armor"
    );

    private MainGuiView mainView;
    private DetailsPanelView detailsView;
    private ReputationBarView repView;

    public AngelDemonScreen(AngelDemonMenu menu, Inventory inv, Component title) {
        super(menu, inv, Component.empty());
        // Center the **group**
        this.imageWidth  = GROUP_W;
        this.imageHeight = GROUP_H;
        // We don't render vanilla labels
        this.titleLabelX = 0;
        this.titleLabelY = 0;
        this.inventoryLabelX = 0;
        this.inventoryLabelY = 0;
    }

    // --- Helpers exposed for sub-views ---

    public int getLeftPos() { return this.leftPos; }
    public int getTopPos()  { return this.topPos; }

    public AngelDemonMenu getMenu() { return this.menu; }
    public Minecraft getMinecraft() { return this.minecraft; }

    /** Public helper to add widgets from child views without exposing protected addRenderableWidget. */
    public void addToScreen(AbstractWidget widget) {
        this.addRenderableWidget(widget);
    }

    public String folder() {
        return this.menu.ritual() == RitualType.DEMON ? "demon" : "angel";
    }

    /** Reputation textures (repbar + pointer). */
    private ResourceLocation repTex() {
        return ResourceLocation.fromNamespaceAndPath("infiniteupgrades", "textures/gui/container/reputation/repbar.png");
    }
    private ResourceLocation repPointerTex() {
        return ResourceLocation.fromNamespaceAndPath("infiniteupgrades", "textures/gui/container/reputation/pointer.png");
    }

    // --- Lifecycle ---

    @Override
    protected void init() {
        super.init();
        LOG.debug("[AngelDemonScreen] init at {},{} (group centered)", leftPos, topPos);

        // Create subviews
        mainView = new MainGuiView(this);
        detailsView = new DetailsPanelView(this);

        // Rep bar anchor: centered relative to the MAIN panel only
        int repX = this.leftPos + (MainGuiView.MAIN_W - REP_W) / 2;
        // 1px gap above main; include the same DRAW_FUDGE_Y used by views for visual alignment
        int repY = (this.topPos + DRAW_FUDGE_Y) - REP_H - 1;
        // UPDATED: pass texture locations explicitly (new ReputationBarView ctor)
        repView = new ReputationBarView(this, repX, repY, repTex(), repPointerTex());

        // Add & init subview widgets
        mainView.onInit();

        // Ask the server for the current reputation snapshot
        ModNet.requestRepSnapshot();
    }

    /** Called from ModNet client handler on S2C snapshot arrival. */
    public void acceptRepSnapshot(double angel, double demon) {
        if (repView != null) {
            // UPDATED: method name changed in ReputationBarView
            repView.acceptServerValues(angel, demon);
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (mainView != null) mainView.tick();
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // Background first
        this.renderBackground(gg, mouseX, mouseY, partialTick);

        // Tooltip routing
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

        super.render(gg, mouseX, mouseY, partialTick);

        // Draw reputation overlay after the base UI so it's on top
        if (repView != null) repView.render(gg);

        if (interceptPreview && hoveredBefore != null && hoveredBefore.hasItem()) {
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
        if (mainView != null) mainView.renderBg(gg);
        if (detailsView != null) detailsView.renderBg(gg);
    }

    @Override
    protected void renderLabels(GuiGraphics gg, int mouseX, int mouseY) {
        // no vanilla labels
    }

    // ---------------- Tooltip helpers (unchanged core logic) ----------------

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
