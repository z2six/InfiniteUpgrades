// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/client/screen/view/DetailsPanelView.java
package org.z2six.infiniteupgrades.feature.infusion.client.screen.view;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemAttributeModifiers.Entry;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.feature.infusion.client.screen.AngelDemonScreen;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.feature.infusion.logic.RitualType;
import org.z2six.infiniteupgrades.feature.infusion.logic.UpgradeService;
import org.z2six.infiniteupgrades.feature.infusion.menu.AngelDemonMenu;

import java.text.DecimalFormat;
import java.util.*;

/**
 * Details panel drawn to the right of the main panel.
 */
public final class DetailsPanelView {
    private static final Logger LOG = LogUtils.getLogger();

    public static final int DETAILS_W = 128;
    public static final int DETAILS_H = 222;

    // 1px gap between main and details
    public static final int GAP_TO_MAIN = 1;

    // Inner text box (relative to details origin)
    private static final int TEXT_INSET_LEFT = 11;
    private static final int TEXT_INSET_TOP  = 20;
    private static final int TEXT_INSET_RIGHT = 113;
    private static final int TEXT_INSET_BOTTOM = 212;

    // Scroll holder + scroller geometry (relative to details origin)
    private static final int HOLDER_X = 116;
    private static final int HOLDER_Y = 20;
    private static final int HOLDER_W = 3;
    private static final int HOLDER_H = 193;

    private static final int SCROLLER_W = 5;
    private static final int SCROLLER_H = 24;
    private static final int SCROLLER_X = 115; // fixed—do NOT vary with drag (prevents drifting)
    private static final int SCROLLER_TOP_Y = 20;
    private static final int SCROLLER_BOTTOM_Y = 189; // top-left at lowest point

    // Scroll constants
    private static final double WHEEL_LINES = 5.0;  // logical lines per wheel step
    private static final int LINE_SPACING = 2;

    private final AngelDemonScreen screen;

    // Cached layout
    private int detailsOriginX;
    private int detailsOriginY;

    // Scroll state
    private double contentHeight = 0.0;
    private double scrollOffset = 0.0;   // pixels
    private boolean dirty = true;

    // Drag state
    private boolean draggingScroller = false;
    private int dragYOffset = 0; // mouseY - scrollerTopY at click time

    // Built text buffer
    private final List<Row> rows = new ArrayList<>();

    // Bullet glyph (compact, readable)
    private static final String BULLET = "▸ ";

    private static final DecimalFormat PCT1 = new DecimalFormat("0.0");
    private static final DecimalFormat PCT0 = new DecimalFormat("0");

    public DetailsPanelView(AngelDemonScreen screen) {
        this.screen = screen;
    }

    // -------------- Resources ----------------

    private ResourceLocation detailsTex() {
        // textures/gui/container/{angel|demon}/details.png
        return ResourceLocation.fromNamespaceAndPath(
                "infiniteupgrades",
                "textures/gui/container/" + screen.folder() + "/details.png"
        );
    }

    private ResourceLocation holderTex() {
        // textures/gui/container/{angel|demon}/scroller_holder.png
        return ResourceLocation.fromNamespaceAndPath(
                "infiniteupgrades",
                "textures/gui/container/" + screen.folder() + "/scroller_holder.png"
        );
    }

    private ResourceLocation scrollerTex() {
        // textures/gui/container/{angel|demon}/scroller.png
        return ResourceLocation.fromNamespaceAndPath(
                "infiniteupgrades",
                "textures/gui/container/" + screen.folder() + "/scroller.png"
        );
    }

    // -------------- Public hooks ----------------

    /** Mark contents dirty so they’ll rebuild next frame. */
    public void markDirty() { this.dirty = true; }

    // -------------- Rendering ----------------

    public void renderBg(GuiGraphics gg) {
        // Compute details origin (to the right of MAIN)
        detailsOriginX = screen.getLeftPos() + MainGuiView.mainDrawDx() + MainGuiView.MAIN_W + GAP_TO_MAIN;
        detailsOriginY = screen.getTopPos()  + MainGuiView.mainDrawDy();

        // Panel background
        gg.blit(detailsTex(), detailsOriginX, detailsOriginY, 0, 0, DETAILS_W, DETAILS_H, DETAILS_W, DETAILS_H);

        // Holder (fixed)
        gg.blit(holderTex(),
                detailsOriginX + HOLDER_X,
                detailsOriginY + HOLDER_Y,
                0, 0,
                HOLDER_W, HOLDER_H,
                HOLDER_W, HOLDER_H);

        // Build rows if needed
        if (dirty) {
            rebuildRows();
            dirty = false;
        }

        // Clip text to the text area
        int clipX = detailsOriginX + TEXT_INSET_LEFT;
        int clipY = detailsOriginY + TEXT_INSET_TOP;
        int clipW = (TEXT_INSET_RIGHT - TEXT_INSET_LEFT);
        int clipH = (TEXT_INSET_BOTTOM - TEXT_INSET_TOP);

        gg.enableScissor(clipX, clipY, clipX + clipW, clipY + clipH);
        drawRows(gg, clipX, clipY, clipW, clipH);
        gg.disableScissor();

        // Draw scroller knob AFTER text (on top)
        int scrollerDrawX = detailsOriginX + SCROLLER_X;
        int scrollerDrawY = detailsOriginY + currentScrollerTopY();
        gg.blit(scrollerTex(), scrollerDrawX, scrollerDrawY, 0, 0, SCROLLER_W, SCROLLER_H, SCROLLER_W, SCROLLER_H);
    }

    /** Overlay (optional). */
    public void renderOverlay(GuiGraphics gg, int mouseX, int mouseY) {
        // no overlay for now
    }

    // -------------- Input ----------------

    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!isInsideTextOrBar(mouseX, mouseY)) return false;
        double lineHeight = lineHeight();
        double deltaPx = -delta * WHEEL_LINES * lineHeight; // wheel up -> negative scroll (move view up)
        setScrollOffset(scrollOffset + deltaPx);
        return true;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false; // left click only for dragging
        // Check if click on scroller knob
        int sx = detailsOriginX + SCROLLER_X;
        int sy = detailsOriginY + currentScrollerTopY();
        if (hit(mouseX, mouseY, sx, sy, SCROLLER_W, SCROLLER_H)) {
            draggingScroller = true;
            dragYOffset = (int)mouseY - sy;
            return true;
        }
        // Click on holder: jump scroller toward click and start drag
        int hx = detailsOriginX + HOLDER_X;
        int hy = detailsOriginY + HOLDER_Y;
        if (hit(mouseX, mouseY, hx, hy, HOLDER_W, HOLDER_H)) {
            int targetTop = (int)mouseY - detailsOriginY - (SCROLLER_H / 2);
            targetTop = Mth.clamp(targetTop, SCROLLER_TOP_Y, SCROLLER_BOTTOM_Y);
            setScrollerTopY(targetTop);
            draggingScroller = true;
            dragYOffset = (int)mouseY - (detailsOriginY + currentScrollerTopY());
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!draggingScroller) return false;
        int newTopLocal = (int)mouseY - detailsOriginY - dragYOffset;
        newTopLocal = Mth.clamp(newTopLocal, SCROLLER_TOP_Y, SCROLLER_BOTTOM_Y);
        setScrollerTopY(newTopLocal);
        return true;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingScroller && button == 0) {
            draggingScroller = false;
            return true;
        }
        return false;
    }

    // -------------- Drawing helpers ----------------

    private void drawRows(GuiGraphics gg, int x, int y, int w, int h) {
        Font font = screen.getMinecraft().font;
        int yCursor = y - (int)scrollOffset;

        for (Row r : rows) {
            for (FormattedCharSequence line : r.lines) {
                int lineH = (int)lineHeight();
                // Vertical clip test
                if (yCursor + lineH >= y && yCursor <= y + h) {
                    int color = r.color;
                    gg.drawString(font, line, x, yCursor, color, false);
                }
                yCursor += (int)lineHeight() + LINE_SPACING;
            }
        }
    }

    private double lineHeight() {
        return screen.getMinecraft().font.lineHeight;
    }

    // -------------- Layout/build ----------------

    private void rebuildRows() {
        rows.clear();

        // Suppress ALL details during an active infusion lock.
        if (isInfusionLocked()) {
            contentHeight = 0.0;
            clampScrollToContent();
            return;
        }

        // Subject item:
        // - Prefer slot 0 (input) if present.
        // - Else, use slot 2 **only if it's a REAL (non-preview) result** after infusion.
        ItemStack subj = ItemStack.EMPTY;
        try {
            var menu = screen.getMenu();
            ItemStack s0 = menu != null ? menu.getSlot(0).getItem() : ItemStack.EMPTY;
            ItemStack s2 = menu != null ? menu.getSlot(2).getItem() : ItemStack.EMPTY;

            if (!s0.isEmpty()) {
                subj = s0;
            } else if (!s2.isEmpty() && !isPreview(s2)) {
                subj = s2;
            }
        } catch (Throwable ignored) {}

        final RitualType ritual = screen.getMenu().ritual();
        final int width = TEXT_INSET_RIGHT - TEXT_INSET_LEFT;

        // Item line (so user sees we detected the subject) — now white
        String itemName = subj.isEmpty() ? "—" : subj.getHoverName().getString();
        addWrapped(itemName, ChatFormatting.WHITE, width);
        addBlank();

        // Section: Next attempt preview
        addHeader("Next attempt");
        Double ruleStep = uniqueRuleStepForThisItem(subj, ritual);
        if (ruleStep != null) {
            addWrapped(BULLET + "Per-upgrade boost: " + pct1(ruleStep), ChatFormatting.WHITE, width);
        } else {
            addWrapped(BULLET + "Per-upgrade boost: varies by attribute (see below)", ChatFormatting.WHITE, width);
        }

        // Chance line with reputation breakdown (fix double and missing sign)
        ChanceParts chance = computeChanceWithRep(subj, ritual);
        String repBonusStr = (chance.repBonus >= 0 ? "+" : "")
                + PCT1.format(chance.repBonus * 100.0) + "%"; // <-- removed Math.abs()
        String repUnifiedStr = (chance.repUnified >= 0 ? "+" : "")
                + PCT0.format(chance.repUnified);

        addWrapped(BULLET + "Chance: " + PCT1.format(chance.total * 100.0) + "%  ("
                + PCT1.format(chance.base * 100.0) + "% " + repBonusStr
                + " from " + repUnifiedStr + " rep)", ChatFormatting.WHITE, width);

        // Divider
        addBlank();

        // Section: Totals (accumulated) — white
        addHeader("Totals");
        buildTotals(subj, width);

        addBlank();

        // Section: Recent history — white
        addHeader("Recent history");
        buildRecentHistory(subj, width);

        addBlank();

        // Section: Possible upgrades for this item (filtered by present attributes) — white
        addHeader("Possible upgrades");
        buildPossibleUpgradesForItem(subj, ritual, width);

        // Compute content height
        int total = 0;
        for (Row r : rows) {
            total += r.lines.size() * ((int)lineHeight() + LINE_SPACING);
        }
        contentHeight = total;
        clampScrollToContent();
    }

    // ----- Content builders -----

    private void buildTotals(ItemStack s, int width) {
        Map<String, TotalsInfo> tot = readTotalsWithCounts(s);
        if (tot.isEmpty()) {
            addWrapped(BULLET + "—", ChatFormatting.WHITE, width);
            return;
        }
        for (var e : tot.entrySet()) {
            String display = resolveAttrDisplayName(e.getKey());
            double pct = e.getValue().sumPercent; // fraction
            int count   = e.getValue().count;
            String line = BULLET + display + " " +
                    (pct >= 0 ? "+" : "") + PCT1.format(pct * 100.0) + "%" +
                    " (+" + count + ")";
            addWrapped(line, ChatFormatting.WHITE, width);
        }
    }

    @org.jetbrains.annotations.Nullable
    private Double uniqueRuleStepForThisItem(ItemStack s, RitualType ritual) {
        if (s == null || s.isEmpty()) return null;

        // Which attributes are actually present on the item?
        Set<ResourceLocation> present = new java.util.LinkedHashSet<>();
        try {
            ItemAttributeModifiers cur = s.getAttributeModifiers();
            for (Entry e : cur.modifiers()) {
                var id = idOf(e.attribute());
                if (id != null) present.add(id);
            }
            ItemAttributeModifiers def = s.getItem().getDefaultAttributeModifiers(s);
            for (Entry e : def.modifiers()) {
                var id = idOf(e.attribute());
                if (id != null) present.add(id);
            }
        } catch (Throwable ignored) {}

        var snap = UpgradeServerConfig.snapshot();
        int level = parsePlusLevel(s);
        double mult = (ritual == RitualType.ANGEL) ? snap.angelStepMult : snap.demonStepMult;

        Double unique = null;
        for (var rule : snap.attributes) {
            if (!rule.enabled) continue;
            if (!present.contains(rule.id)) continue;

            double base = rule.perLevelOverrides.getOrDefault(level, rule.defaultStep);
            double step = Math.max(0.0, base) * Math.max(0.0, mult);
            if (step <= 0.0) continue;

            if (unique == null) {
                unique = step;
            } else if (Math.abs(step - unique) > 1.0e-9) {
                // Different per-attr steps -> no single "unique" value
                return null;
            }
        }
        return unique; // could be null if nothing matched
    }

    private void buildRecentHistory(ItemStack s, int width) {
        List<HistEvent> events = readHistory(s);
        if (events.isEmpty()) {
            addWrapped(BULLET + "—", ChatFormatting.WHITE, width);
            return;
        }

        // Newest first
        Collections.reverse(events);

        for (HistEvent ev : events) {
            boolean up = ev.levelAfter > ev.levelBefore;
            ChatFormatting jumpColor = up ? ChatFormatting.GREEN : ChatFormatting.RED;

            String display = resolveAttrDisplayName(ev.attrId);
            String amount = (ev.appliedPercent >= 0 ? "+" : "") + PCT1.format(ev.appliedPercent * 100.0) + "%";

            // Default row color = white; styled segment overrides for the jump
            Component line = Component.literal(BULLET)
                    .append(Component.literal("L" + ev.levelBefore + "→L" + ev.levelAfter).withStyle(jumpColor))
                    .append(Component.literal("  " + display + "  " + amount));

            addWrapped(line, mapColor(ChatFormatting.WHITE), width);
        }
    }

    private void buildPossibleUpgradesForItem(ItemStack s, RitualType ritual, int width) {
        // Determine present attributes on the item (current + defaults)
        Set<ResourceLocation> present = new LinkedHashSet<>();
        try {
            ItemAttributeModifiers cur = s.getAttributeModifiers();
            for (Entry e : cur.modifiers()) {
                var id = idOf(e.attribute());
                if (id != null) present.add(id);
            }
            ItemAttributeModifiers def = s.getItem().getDefaultAttributeModifiers(s);
            for (Entry e : def.modifiers()) {
                var id = idOf(e.attribute());
                if (id != null) present.add(id);
            }
        } catch (Throwable ignored) {}

        var snap = UpgradeServerConfig.snapshot();
        int level = parsePlusLevel(s);
        double ritualMult = ritual == RitualType.ANGEL ? snap.angelStepMult : snap.demonStepMult;

        int shown = 0;
        for (var rule : snap.attributes) {
            if (!rule.enabled) continue;
            if (!present.contains(rule.id)) continue; // filter to attributes actually on the item
            double base = rule.perLevelOverrides.getOrDefault(level, rule.defaultStep);
            double step = Math.max(0.0, base) * Math.max(0.0, ritualMult);
            if (step <= 0.0) continue;

            String display = resolveAttrDisplayName(rule.id.toString());
            String dir = rule.direction == UpgradeServerConfig.Direction.INCREASE ? "+" : "−";
            String line = BULLET + display + "  (" + dir + PCT1.format(step * 100.0) + "% per upgrade)";
            addWrapped(line, ChatFormatting.WHITE, width);
            shown++;
        }
        if (shown == 0) {
            addWrapped(BULLET + "—", ChatFormatting.WHITE, width);
        }
    }

    // ----- Chance with reputation -----

    private static final class ChanceParts {
        final double base;      // base chance fraction
        final double repBonus;  // reputation bonus fraction (can be negative)
        final double total;     // clamped total
        final double repUnified;
        ChanceParts(double base, double repBonus, double total, double repUnified) {
            this.base = base; this.repBonus = repBonus; this.total = total; this.repUnified = repUnified;
        }
    }

    private ChanceParts computeChanceWithRep(ItemStack s, RitualType ritual) {
        int currentLevel = parsePlusLevel(s);
        double base = UpgradeService.getSuccessChance(currentLevel); // server model (base)

        var snap = UpgradeServerConfig.snapshot();
        double unified = screen.getRepUnified();
        double towardSide = (ritual == RitualType.ANGEL) ? unified : -unified;
        double repBonus = towardSide * snap.repBonusPerPoint;
        double clamp = Math.max(0.0, snap.repBonusClamp);
        repBonus = Mth.clamp(repBonus, -clamp, clamp);

        double total = Mth.clamp(base + repBonus, 0.0, 1.0);
        return new ChanceParts(base, repBonus, total, unified);
    }

    // -------------- Small helpers ----------------

    private boolean isInsideTextOrBar(double mouseX, double mouseY) {
        int x = (int)mouseX - detailsOriginX;
        int y = (int)mouseY - detailsOriginY;
        if (x >= TEXT_INSET_LEFT && x <= TEXT_INSET_RIGHT && y >= TEXT_INSET_TOP && y <= TEXT_INSET_BOTTOM) return true;
        if (x >= HOLDER_X && x <= HOLDER_X + HOLDER_W && y >= HOLDER_Y && y <= HOLDER_Y + HOLDER_H) return true;
        int scTop = currentScrollerTopY();
        return (x >= SCROLLER_X && x <= SCROLLER_X + SCROLLER_W && y >= scTop && y <= scTop + SCROLLER_H);
    }

    private boolean hit(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < (x + w) && mouseY >= y && mouseY < (y + h);
    }

    private int currentScrollerTopY() {
        double maxScroll = Math.max(0.0, contentHeight - (TEXT_INSET_BOTTOM - TEXT_INSET_TOP));
        if (maxScroll <= 0.0) return SCROLLER_TOP_Y;

        double t = Mth.clamp(scrollOffset / maxScroll, 0.0, 1.0);
        int track = SCROLLER_BOTTOM_Y - SCROLLER_TOP_Y;
        return SCROLLER_TOP_Y + (int)Math.round(t * track);
    }

    private void setScrollerTopY(int scrollerTopLocalY) {
        int track = SCROLLER_BOTTOM_Y - SCROLLER_TOP_Y;
        double t = (track <= 0) ? 0.0 : (double)(scrollerTopLocalY - SCROLLER_TOP_Y) / (double)track;
        t = Mth.clamp(t, 0.0, 1.0);

        double maxScroll = Math.max(0.0, contentHeight - (TEXT_INSET_BOTTOM - TEXT_INSET_TOP));
        setScrollOffset(t * maxScroll);
    }

    private void setScrollOffset(double newOffsetPx) {
        scrollOffset = newOffsetPx;
        clampScrollToContent();
    }

    private void clampScrollToContent() {
        double maxScroll = Math.max(0.0, contentHeight - (TEXT_INSET_BOTTOM - TEXT_INSET_TOP));
        if (scrollOffset < 0.0) scrollOffset = 0.0;
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
    }

    private void addHeader(String title) {
        int col = mapColor(ChatFormatting.YELLOW);
        rows.add(Row.single(Component.literal(title).withStyle(ChatFormatting.YELLOW), col));
    }

    private void addBlank() {
        rows.add(Row.single(Component.literal(" "), 0xFFFFFF));
    }

    private void addWrapped(Component text, int defaultColor, int width) {
        Font font = screen.getMinecraft().font;
        List<FormattedCharSequence> seq = font.split(text, width); // preserves per-segment styles
        rows.add(new Row(seq, defaultColor));
    }

    private void addWrapped(String text, ChatFormatting color, int width) {
        // Use the row's default color for the whole line (no per-segment styles here).
        addWrapped(Component.literal(text), mapColor(color), width);
    }

    private int mapColor(ChatFormatting c) {
        Integer v = c.getColor();
        return v != null ? v : 0xFFFFFF;
    }

    private int parsePlusLevel(ItemStack stack) {
        try {
            String s = stack.getHoverName().getString();
            int i = s.lastIndexOf('+');
            if (i >= 0) {
                String tail = s.substring(i + 1).trim();
                int space = tail.indexOf(' ');
                if (space > 0) tail = tail.substring(0, space);
                return Integer.parseInt(tail);
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private String resolveAttrDisplayName(String attrId) {
        try {
            var mc = screen.getMinecraft();
            if (mc != null && mc.level != null) {
                RegistryAccess access = mc.level.registryAccess();
                Registry<Attribute> reg = access.registryOrThrow(Registries.ATTRIBUTE);
                ResourceLocation rl = ResourceLocation.tryParse(attrId);
                if (rl != null && reg.containsKey(rl)) {
                    Attribute attr = reg.get(rl);
                    if (attr != null) {
                        return net.minecraft.network.chat.Component.translatable(attr.getDescriptionId()).getString();
                    }
                }
            }
        } catch (Throwable ignored) { }
        String s = attrId;
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        return s.replace('_', ' ').replace('.', ' ');
    }

    // --- Totals + counts ---

    private static final class TotalsInfo {
        final double sumPercent; // fraction
        final int count;         // number of upgrades recorded (net)
        TotalsInfo(double sumPercent, int count) {
            this.sumPercent = sumPercent;
            this.count = count;
        }
    }

    private Map<String, TotalsInfo> readTotalsWithCounts(ItemStack s) {
        Map<String, TotalsInfo> out = new LinkedHashMap<>();
        try {
            CustomData cd = s.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            if (cd == null) return out;
            var root = cd.copyTag().getCompound("iu_upgrade");
            var totals = root.getCompound("totals");
            for (String key : totals.getAllKeys()) {
                var a = totals.getCompound(key);
                double sumPct = a.getDouble("sumPercent"); // fraction
                int count = a.getInt("count");
                out.put(key, new TotalsInfo(sumPct, Math.max(0, count)));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static final class HistEvent {
        String attrId;
        int levelBefore;
        int levelAfter;
        double appliedPercent; // fraction (+/-)
    }

    private List<HistEvent> readHistory(ItemStack s) {
        List<HistEvent> out = new ArrayList<>();
        try {
            CustomData cd = s.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            if (cd == null) return out;
            var root = cd.copyTag().getCompound("iu_upgrade");
            var hist = root.getList("history", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < hist.size(); i++) {
                var ev = hist.getCompound(i);
                HistEvent h = new HistEvent();
                h.attrId = ev.getString("attribute");
                h.levelBefore = ev.getInt("levelBefore");
                h.levelAfter  = ev.getInt("levelAfter");
                h.appliedPercent = ev.getDouble("stepPercent");
                out.add(h);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static ResourceLocation idOf(Holder<Attribute> holder) {
        try {
            return holder != null ? holder.unwrapKey().map(ResourceKey::location).orElse(null) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String pct1(double frac) { return PCT1.format(frac * 100.0) + "%"; }

    // --- New local helpers ---

    private boolean isInfusionLocked() {
        AngelDemonMenu menu = screen.getMenu();
        if (menu == null) return false;
        return menu.getClientLockEndGameTime() > 0L;
    }

    private static boolean isPreview(ItemStack stack) {
        try {
            if (stack.isEmpty()) return false;
            CustomData cd = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            if (cd == null) return false;
            return cd.copyTag().getBoolean("iu_preview");
        } catch (Throwable ignored) {
            return false;
        }
    }

    // -------------- Row struct ----------------
    private static final class Row {
        final List<FormattedCharSequence> lines;
        final int color;

        Row(List<FormattedCharSequence> lines, int color) {
            this.lines = lines;
            this.color = color;
        }

        static Row single(Component c, int color) {
            return new Row(List.of(c.getVisualOrderText()), color);
        }
    }
}
