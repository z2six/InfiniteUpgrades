// MainFile: src/main/java/org/z2six/infiniteupgrades/feature/infusion/client/screen/AngelDemonScreen.java
package org.z2six.infiniteupgrades.feature.infusion.client.screen;

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
import org.z2six.infiniteupgrades.feature.infusion.client.InfuseClientEffects;
import org.z2six.infiniteupgrades.feature.infusion.client.screen.view.DetailsPanelView;
import org.z2six.infiniteupgrades.feature.infusion.client.screen.view.MainGuiView;
import org.z2six.infiniteupgrades.feature.infusion.client.screen.view.ProgressFillView;
import org.z2six.infiniteupgrades.feature.infusion.client.screen.view.ReputationBarView;
import org.z2six.infiniteupgrades.feature.infusion.logic.RitualType;
import org.z2six.infiniteupgrades.core.net.ModNet;
import org.z2six.infiniteupgrades.feature.infusion.menu.AngelDemonMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/client/screen/AngelDemonScreen.java
 *
 * Angel/Demon GUI screen split into sub-views.
 */
public class AngelDemonScreen extends AbstractContainerScreen<AngelDemonMenu> {
    private static final Logger LOG = LogUtils.getLogger();

    // Group dimensions: main(176) + 1 + details(128) = 305 width, height = 222
    private static final int GROUP_W = MainGuiView.MAIN_W + MainGuiView.GAP_TO_DETAILS + DetailsPanelView.DETAILS_W;
    private static final int GROUP_H = 222;

    // Rep bar dims
    private static final int REP_W = 108;
    private static final int REP_H = 13;

    // Fudge used by subviews (keep here as well for computing rep bar vertical anchor)
    private static final int DRAW_FUDGE_Y = -1;

    // Tooltip helpers
    private static final Pattern LEADING_NUM = Pattern.compile("^\\s*([+\\-]?\\d+(?:\\.\\d+)?)\\s+(.*)$");
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*\\]");
    private static final Pattern PARENS   = Pattern.compile("\\([^)]*\\)");

    private MainGuiView mainView;
    private DetailsPanelView detailsView;
    private ReputationBarView repView;
    private ProgressFillView progressView;

    // Latest reputation snapshot from server (for client-side chance preview text)
    private double repUnified = 0.0;
    private int repMax = 100;

    // Slot change detection (robust across mappings)
    private ItemStack prev0 = ItemStack.EMPTY;
    private ItemStack prev1 = ItemStack.EMPTY;
    private ItemStack prev2 = ItemStack.EMPTY;

    public AngelDemonScreen(AngelDemonMenu menu, Inventory inv, Component title) {
        super(menu, inv, Component.empty());
        this.imageWidth  = GROUP_W;
        this.imageHeight = GROUP_H;
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
    private ResourceLocation repPointerTex() {
        return ResourceLocation.fromNamespaceAndPath("infiniteupgrades", "textures/gui/container/reputation/pointer.png");
    }
    private ResourceLocation repBgHalfTex() {
        return ResourceLocation.fromNamespaceAndPath(
                "infiniteupgrades",
                "textures/gui/container/reputation/background_half.png"
        );
    }
    private ResourceLocation repBgCelestialTex() {
        return ResourceLocation.fromNamespaceAndPath(
                "infiniteupgrades",
                "textures/gui/container/reputation/background_celestial.png"
        );
    }
    private ResourceLocation repBgDemonicTex() {
        return ResourceLocation.fromNamespaceAndPath(
                "infiniteupgrades",
                "textures/gui/container/reputation/background_demonic.png"
        );
    }
    private ResourceLocation repBg75CelestialTex() {
        return ResourceLocation.fromNamespaceAndPath(
                "infiniteupgrades",
                "textures/gui/container/reputation/background_75p_celestial.png"
        );
    }
    private ResourceLocation repBg75DemonicTex() {
        return ResourceLocation.fromNamespaceAndPath(
                "infiniteupgrades",
                "textures/gui/container/reputation/background_75p_demonic.png"
        );
    }

    // Expose latest rep snapshot for DetailsPanelView (for chance preview)
    public double getRepUnified() { return repUnified; }
    public int getRepMax() { return repMax; }

    // --- Lifecycle ---

    @Override
    protected void init() {
        super.init();
        LOG.debug("[AngelDemonScreen] init at {},{} (group centered)", leftPos, topPos);

        // Create subviews
        mainView = new MainGuiView(this);
        detailsView = new DetailsPanelView(this);
        progressView = new ProgressFillView(this);

        // Rep bar anchor
        int repX = this.leftPos + (MainGuiView.MAIN_W - REP_W) / 2;
        int repY = (this.topPos + DRAW_FUDGE_Y) - REP_H - 1;

        // NOTE: pass five backgrounds (half, 75c, 75d, full c, full d) + pointer
        repView = new ReputationBarView(
                this,
                repX, repY,
                repBgHalfTex(),
                repBg75CelestialTex(),
                repBg75DemonicTex(),
                repBgCelestialTex(),
                repBgDemonicTex(),
                repPointerTex()
        );

        // Add & init subview widgets
        mainView.onInit();

        // Ask the server for the current reputation snapshot
        ModNet.requestRepSnapshot();

        // Resume any pending infusion lock/animation after reopen
        ModNet.requestPendingState();
        InfuseClientEffects.onScreenOpenedPlayBacklog();

        if (detailsView != null) detailsView.markDirty();
        snapshotSlots();
    }

    /** Called from ModNet client handler on S2C snapshot arrival. */
    public void acceptRepSnapshot(double unified, int repMax) {
        this.repUnified = unified;
        this.repMax = Math.max(1, repMax);
        if (repView != null) {
            repView.acceptServerValues(unified, this.repMax);
        }
        if (detailsView != null) detailsView.markDirty();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (mainView != null) mainView.tick();

        boolean changed = false;
        ItemStack s0 = safeStack(0);
        ItemStack s1 = safeStack(1);
        ItemStack s2 = safeStack(2);

        if (!ItemStack.matches(s0, prev0)) { changed = true; prev0 = s0.copy(); }
        if (!ItemStack.matches(s1, prev1)) { changed = true; prev1 = s1.copy(); }
        if (!ItemStack.matches(s2, prev2)) { changed = true; prev2 = s2.copy(); }

        if (changed && detailsView != null) {
            detailsView.markDirty();
        }
    }

    private void snapshotSlots() {
        prev0 = safeStack(0).copy();
        prev1 = safeStack(1).copy();
        prev2 = safeStack(2).copy();
    }

    private ItemStack safeStack(int idx) {
        try {
            return this.menu.getSlot(idx).getItem();
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gg, mouseX, mouseY, partialTick);

        // Tooltip routing for preview item
        Slot hoveredBefore = this.hoveredSlot;
        boolean interceptPreview = false;

        if (hoveredBefore != null && hoveredBefore.hasItem()) {
            ItemStack s = hoveredBefore.getItem();
            if (hoveredBefore.index == 2 && isPreview(s)) {
                interceptPreview = true;
            }
        }

        if (interceptPreview) {
            this.hoveredSlot = null; // suppress vanilla tooltip this frame
        }

        super.render(gg, mouseX, mouseY, partialTick);

        if (repView != null) {
            repView.render(gg);
            repView.renderOverlay(gg, mouseX, mouseY);
        }

        if (detailsView != null) detailsView.renderOverlay(gg, mouseX, mouseY);

        if (interceptPreview && hoveredBefore != null && hoveredBefore.hasItem()) {
            ItemStack stack = hoveredBefore.getItem();
            List<Component> vanilla = Screen.getTooltipFromItem(this.minecraft, stack);
            List<Component> modified = obfuscateNumericPartsInCombatLines(stack, vanilla);
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
        if (progressView != null) progressView.renderBg(gg);
        if (detailsView != null) detailsView.renderBg(gg);
    }

    @Override
    protected void renderLabels(GuiGraphics gg, int mouseX, int mouseY) {
        // no vanilla labels
    }

    // --- Mouse handling (1.21.x uses 4-arg mouseScrolled) ---

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (detailsView != null && detailsView.mouseScrolled(mouseX, mouseY, deltaY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (detailsView != null && detailsView.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (detailsView != null && detailsView.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (detailsView != null && detailsView.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
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
                out.add(obfuscateBracketsAndParensOnly(raw));
                continue;
            }

            String num = m.group(1);
            String rest = m.group(2);

            MutableComponent rebuilt = Component.literal("")
                    .append(Component.literal(num).withStyle(ChatFormatting.OBFUSCATED))
                    .append(Component.literal(" "))
                    .append(obfuscateBracketAndParenSegments(rest));

            out.add(rebuilt);
        }
        return out;
    }

    private static MutableComponent obfuscateBracketAndParenSegments(String text) {
        class Range { final int s, e; Range(int s, int e){ this.s=s; this.e=e; } }
        List<Range> ranges = new ArrayList<>();

        Matcher bm = BRACKETS.matcher(text);
        while (bm.find()) ranges.add(new Range(bm.start(), bm.end()));

        Matcher pm = PARENS.matcher(text);
        while (pm.find()) ranges.add(new Range(pm.start(), pm.end()));

        ranges.sort((a,b) -> Integer.compare(a.s, b.s));

        MutableComponent result = Component.literal("");
        int idx = 0;
        for (Range r : ranges) {
            if (r.s > idx) {
                result = result.append(Component.literal(text.substring(idx, r.s)));
            }
            String seg = text.substring(r.s, r.e);
            result = result.append(Component.literal(seg).withStyle(ChatFormatting.OBFUSCATED));
            idx = r.e;
        }
        if (idx < text.length()) {
            result = result.append(Component.literal(text.substring(idx)));
        }
        return result;
    }

    private static MutableComponent obfuscateBracketsAndParensOnly(String text) {
        return obfuscateBracketAndParenSegments(text);
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
