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
import org.z2six.infiniteupgrades.client.screen.view.ProgressFillView;

import java.util.ArrayList;
import java.util.List;
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

    // Tooltip helpers
    private static final Pattern LEADING_NUM = Pattern.compile("^\\s*([+\\-]?\\d+(?:\\.\\d+)?)\\s+(.*)$");
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*\\]");

    private MainGuiView mainView;
    private DetailsPanelView detailsView;
    private ReputationBarView repView;
    private ProgressFillView progressView;

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
        progressView = new ProgressFillView(this); // <— NEW

        // Rep bar anchor...
        int repX = this.leftPos + (MainGuiView.MAIN_W - REP_W) / 2;
        int repY = (this.topPos + DRAW_FUDGE_Y) - REP_H - 1;
        repView = new ReputationBarView(this, repX, repY, repTex(), repPointerTex());

        // Add & init subview widgets
        mainView.onInit();

        // Ask the server for the current reputation snapshot
        ModNet.requestRepSnapshot();
    }

    /** Called from ModNet client handler on S2C snapshot arrival. */
    public void acceptRepSnapshot(double unified, int repMax) {
        if (repView != null) {
            repView.acceptServerValues(unified, repMax);
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

        if (hoveredBefore != null && hoveredBefore.hasItem()) {
            ItemStack s = hoveredBefore.getItem();
            if (hoveredBefore.index == 2 && isPreview(s)) {
                interceptPreview = true; // we'll draw a special "mystery" tooltip
            }
        }

        if (interceptPreview) {
            this.hoveredSlot = null; // suppress vanilla tooltip this frame
        }

        super.render(gg, mouseX, mouseY, partialTick);

        // Draw reputation overlay after the base UI so it's on top
        if (repView != null) repView.render(gg);

        if (interceptPreview && hoveredBefore != null && hoveredBefore.hasItem()) {
            // Custom tooltip for PREVIEW items: obfuscate numeric parts to avoid spoilers.
            ItemStack stack = hoveredBefore.getItem();
            List<Component> vanilla = Screen.getTooltipFromItem(this.minecraft, stack);
            List<Component> modified = obfuscateNumericPartsInCombatLines(stack, vanilla);
            List<net.minecraft.util.FormattedCharSequence> ordered = modified.stream()
                    .map(Component::getVisualOrderText)
                    .toList();
            gg.renderTooltip(this.font, ordered, mouseX, mouseY);
        } else {
            // For REAL items, let the normal tooltip flow run (TooltipHooks will augment globally).
            this.renderTooltip(gg, mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics gg, float partialTick, int mouseX, int mouseY) {
        if (mainView != null) mainView.renderBg(gg);
        if (progressView != null) progressView.renderBg(gg); // <— draw animation overlay here
        if (detailsView != null) detailsView.renderBg(gg);
    }

    @Override
    protected void renderLabels(GuiGraphics gg, int mouseX, int mouseY) {
        // no vanilla labels
    }

    // ---------------- Tooltip helpers (preview obfuscation) ----------------

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
