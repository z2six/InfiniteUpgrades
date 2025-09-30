package org.z2six.infiniteupgrades.client.screen.view;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.client.screen.AngelDemonScreen;
import org.z2six.infiniteupgrades.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.world.menu.AngelDemonMenu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.z2six.infiniteupgrades.client.screen.view.MainGuiView.MAIN_W;
import static org.z2six.infiniteupgrades.client.screen.view.MainGuiView.mainDrawDx;
import static org.z2six.infiniteupgrades.client.screen.view.MainGuiView.mainDrawDy;

/**
 * Details panel drawn to the right of the main panel.
 *
 * Texture:
 *  - Size: 128x222 (exact texture).
 *  - 1px gap from the main panel.
 *
 * Text area (relative to this panel texture):
 *  - From X=11,Y=20 to X=113,Y=212
 *
 * Scroll bar assets (independent textures):
 *  - holder: 3x193 placed with top-left at X=116,Y=20 (relative to panel)
 *  - scroller: 5x24; top-most top-left is X=115,Y=20; bottom-most top-left is X=119,Y=189
 *
 * Scrolling:
 *  - Consumes wheel/trackpad when cursor is inside the text area OR on the scrollbar area.
 *  - We render a minimal custom scrollbar now; later you can add dragging, arrows, etc.
 */
public final class DetailsPanelView {
    private static final Logger LOG = LogUtils.getLogger();

    public static final int DETAILS_W = 128;
    public static final int DETAILS_H = 222;

    // 1px gap between main and details
    public static final int GAP_TO_MAIN = 1;

    // Text area (within details.png)
    private static final int TEXT_X0 = 11;
    private static final int TEXT_Y0 = 20;
    private static final int TEXT_X1 = 113;
    private static final int TEXT_Y1 = 212;

    // Scroll holder area (within details.png)
    private static final int HOLDER_X = 116;   // width 3
    private static final int HOLDER_Y = 20;
    private static final int HOLDER_W = 3;
    private static final int HOLDER_H = 193;

    // Scroller sprite (within its own texture)
    private static final int SCROLLER_W = 5;
    private static final int SCROLLER_H = 24;

    // Scroller motion range (panel-space), top and bottom top-left anchors
    private static final int SCROLLER_TOP_X = 115;
    private static final int SCROLLER_TOP_Y = 20;
    private static final int SCROLLER_BOT_X = 119;
    private static final int SCROLLER_BOT_Y = 189;

    private final AngelDemonScreen screen;

    // Render cache
    private final List<Line> lines = new ArrayList<>();

    // Scrolling state (pixels)
    private int scrollOffsetPx = 0;
    private int contentHeightPx = 0;
    private int maxScrollPx = 0;

    // Rebuild guard
    private @Nullable ItemStack lastSeenStack = ItemStack.EMPTY;
    private int lastSeenPreviewPermille = -1;

    public DetailsPanelView(AngelDemonScreen screen) {
        this.screen = screen;
    }

    // ----------- Textures -----------

    private ResourceLocation detailsTex() {
        // textures/gui/container/{angel|demon}/details.png
        return ResourceLocation.fromNamespaceAndPath(
                "infiniteupgrades",
                "textures/gui/container/" + screen.folder() + "/details.png"
        );
    }

    // Put your scrollbar textures here (shared by both rituals)
    private ResourceLocation scrollerHolderTex() {
        // textures/gui/container/common/scroller_holder.png
        return ResourceLocation.fromNamespaceAndPath(
                "infiniteupgrades",
                "textures/gui/container/common/scroller_holder.png"
        );
    }
    private ResourceLocation scrollerTex() {
        // textures/gui/container/common/scroller.png
        return ResourceLocation.fromNamespaceAndPath(
                "infiniteupgrades",
                "textures/gui/container/common/scroller.png"
        );
    }

    // ----------- Rendering -----------

    public void renderBg(GuiGraphics gg) {
        // Panel top-left in screen space
        int px = panelLeft();
        int py = panelTop();

        // Draw the details panel texture
        gg.blit(detailsTex(), px, py, 0, 0, DETAILS_W, DETAILS_H, DETAILS_W, DETAILS_H);

        // Build/refresh content (cheap) and compute scroll ranges
        rebuildIfNeeded();

        // Clip to the text area
        int x0 = px + TEXT_X0;
        int y0 = py + TEXT_Y0;
        int textW = TEXT_X1 - TEXT_X0; // 113-11 = 102 px
        int textH = TEXT_Y1 - TEXT_Y0; // 212-20 = 192 px

        gg.enableScissor(x0, y0, x0 + textW, y0 + textH);
        drawLines(gg, x0, y0, textW, textH);
        gg.disableScissor();

        // Draw the scroll holder
        gg.blit(scrollerHolderTex(),
                px + HOLDER_X, py + HOLDER_Y,
                0, 0,
                HOLDER_W, HOLDER_H,
                HOLDER_W, HOLDER_H);

        // Draw the scroller at a position interpolated by scroll fraction
        double frac = (maxScrollPx <= 0) ? 0.0 : (scrollOffsetPx / (double) maxScrollPx);
        frac = Mth.clamp(frac, 0.0, 1.0);

        int scX = (int)Math.round(Mth.lerp(frac, SCROLLER_TOP_X, SCROLLER_BOT_X));
        int scY = (int)Math.round(Mth.lerp(frac, SCROLLER_TOP_Y, SCROLLER_BOT_Y));

        gg.blit(scrollerTex(),
                px + scX, py + scY,
                0, 0,
                SCROLLER_W, SCROLLER_H,
                SCROLLER_W, SCROLLER_H);
    }

    private void drawLines(GuiGraphics gg, int x0, int y0, int w, int h) {
        Font font = screen.getMinecraft().font;
        int lineH = font.lineHeight; // 9

        // Total content height (already measured during build)
        int yStart = y0 - scrollOffsetPx;

        int y = yStart;
        for (Line ln : lines) {
            if (y + lineH < y0) { // above clip
                y += lineH;
                continue;
            }
            if (y > y0 + h - lineH) { // below clip
                break;
            }

            int color = ln.dimmed ? 0xFF8A8A8A : 0xFFE0E0E0; // pleasant light gray/white
            gg.drawString(font, ln.text, x0, y, color, false);
            y += lineH;
        }
    }

    // ----------- Input (scroll) -----------

    /**
     * Handle wheel/trackpad scrolling; only consumes if mouse is inside the text area or scrollbar zone.
     * @param mouseX screen-space
     * @param mouseY screen-space
     * @param delta positive = scroll up, negative = scroll down
     * @return true if consumed
     */
    public boolean handleScroll(double mouseX, double mouseY, double delta) {
        int px = panelLeft();
        int py = panelTop();

        int textX0 = px + TEXT_X0;
        int textY0 = py + TEXT_Y0;
        int textX1 = px + TEXT_X1;
        int textY1 = py + TEXT_Y1;

        int sbX0 = px + HOLDER_X - 2;     // allow a tiny grab margin
        int sbY0 = py + HOLDER_Y;
        int sbX1 = px + HOLDER_X + HOLDER_W + 4; // plus some margin to the right
        int sbY1 = py + HOLDER_Y + HOLDER_H;

        boolean inText = mouseX >= textX0 && mouseX <= textX1 && mouseY >= textY0 && mouseY <= textY1;
        boolean inScroll = mouseX >= sbX0 && mouseX <= sbX1 && mouseY >= sbY0 && mouseY <= sbY1;

        if (!inText && !inScroll) return false;

        // Step per notch ~ 12px; invert sign so wheel up moves content up (offset down)
        int step = (delta < 0 ? 12 : -12);
        scrollOffsetPx = Mth.clamp(scrollOffsetPx + step, 0, Math.max(0, maxScrollPx));
        return true;
        // (Scroller position updates on next render via 'frac')
    }

    // ----------- Content building -----------

    private void rebuildIfNeeded() {
        AngelDemonMenu menu = screen.getMenu();
        ItemStack in = safeSlot(menu, 0);
        ItemStack out = safeSlot(menu, 2);

        int previewPermille = menu.getPreviewChancePermille();

        // Rebuild if input stack changed materially or preview chance changed
        boolean mustRebuild =
                !ItemStack.isSameItemSameComponents(in, lastSeenStack) ||
                        (previewPermille != lastSeenPreviewPermille);

        if (!mustRebuild) return;

        lastSeenStack = in.copy();
        lastSeenPreviewPermille = previewPermille;
        lines.clear();

        Font font = screen.getMinecraft().font;
        int lineH = font.lineHeight;
        int textW = (TEXT_X1 - TEXT_X0);

        // Title / header
        if (in.isEmpty()) {
            addHeader(Component.literal("Insert a combat item"), textW, font);
            addDim(Component.literal("Place a weapon/armor in the left slot."), textW, font);
        } else {
            addHeader(in.getHoverName(), textW, font);

            // Current level (from iu_upgrade.level)
            int curLvl = readItemLevel(in);
            lines.add(Line.of(Component.literal("Level: " + curLvl), false));

            // Preview
            if (out.isEmpty()) {
                // No preview or no resource
                lines.add(Line.spacer());
                addDim(Component.literal("Add an iron ingot to see the next-step preview."), textW, font);
            } else if (isPreview(out)) {
                lines.add(Line.spacer());
                lines.add(Line.of(Component.literal("Next attempt (preview)"), true));

                // chance text
                int perMil = Mth.clamp(previewPermille, 0, 1000);
                double pct = perMil / 10.0; // 0..100.0
                lines.add(Line.of(Component.literal("Chance: " + format1(pct) + "%"), false));

                // step percent from server tuning
                double step = UpgradeServerConfig.snapshot().percentBonusForLevelUp(curLvl) * 100.0;
                lines.add(Line.of(Component.literal("Step: +" + format1(step) + "%"), false));
            }

            lines.add(Line.spacer());

            // What happened: totals
            appendTotals(in, textW, font);

            lines.add(Line.spacer());

            // History (last ~10 lines)
            appendHistory(in, textW, font, 10);

            lines.add(Line.spacer());

            // What could happen
            appendPossibilities(curLvl, textW, font);
        }

        // Recompute content + scroll metrics
        contentHeightPx = lines.size() * lineH;
        int textH = (TEXT_Y1 - TEXT_Y0);
        maxScrollPx = Math.max(0, contentHeightPx - textH);

        // Clamp offset to new max
        scrollOffsetPx = Mth.clamp(scrollOffsetPx, 0, maxScrollPx);
    }

    private void appendTotals(ItemStack s, int textW, Font font) {
        CustomData cd = s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag root = cd.copyTag().getCompound("iu_upgrade");
        CompoundTag totals = root.getCompound("totals");
        if (totals.isEmpty()) {
            addDim(Component.literal("No upgrades applied yet."), textW, font);
            return;
        }

        lines.add(Line.of(Component.literal("Totals"), true));

        // Keep attribute display order stable by insertion
        for (String key : totals.getAllKeys()) {
            CompoundTag a = totals.getCompound(key);
            double sumPct = a.getDouble("sumPercent") * 100.0;
            double sumDelta = a.getDouble("sumDelta");

            StringBuilder sb = new StringBuilder();
            sb.append(humanizeAttr(key)).append(": ");

            boolean had = false;
            if (Math.abs(sumPct) > 1.0e-6) {
                sb.append((sumPct >= 0 ? "+" : "")).append(format0(sumPct)).append("%");
                had = true;
            }
            if (Math.abs(sumDelta) > 1.0e-6) {
                if (had) sb.append(", ");
                sb.append((sumDelta >= 0 ? "+" : "")).append(format2(sumDelta));
                had = true;
            }
            if (!had) sb.append("—");

            lines.add(Line.of(Component.literal(sb.toString()), false));
        }
    }

    private void appendHistory(ItemStack s, int textW, Font font, int lastN) {
        CustomData cd = s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag root = cd.copyTag().getCompound("iu_upgrade");
        ListTag hist = root.getList("history", Tag.TAG_COMPOUND);
        if (hist.isEmpty()) {
            addDim(Component.literal("No history yet."), textW, font);
            return;
        }

        Map<Integer, CompoundTag> events = new LinkedHashMap<>();
        for (int i = 0; i < hist.size(); i++) {
            CompoundTag ev = hist.getCompound(i);
            events.put(i, ev);
        }

        lines.add(Line.of(Component.literal("Recent history"), true));

        int start = Math.max(0, hist.size() - lastN);
        for (int i = start; i < hist.size(); i++) {
            CompoundTag ev = hist.getCompound(i);
            String attr = humanizeAttr(ev.getString("attribute"));
            int lb = ev.getInt("levelBefore");
            int la = ev.getInt("levelAfter");
            double pct = ev.getDouble("stepPercent") * 100.0;
            double d = ev.getDouble("delta");
            String rule = ev.getString("ruleId");

            String txt;
            if ("downgrade".equals(rule)) {
                txt = String.format("L%d→L%d: downgrade %s (%s%.0f%%)", lb, la, attr, (pct >= 0 ? "+" : ""), pct);
            } else {
                String more = Math.abs(d) > 1e-6 ? String.format(", %s%.2f", (d >= 0 ? "+" : ""), d) : "";
                txt = String.format("L%d→L%d: %s (%s%.0f%%%s)", lb, la, attr, (pct >= 0 ? "+" : ""), pct, more);
            }
            lines.add(Line.of(Component.literal(txt), false));
        }
    }

    private void appendPossibilities(int curLvl, int textW, Font font) {
        var snap = UpgradeServerConfig.snapshot();

        lines.add(Line.of(Component.literal("Possible upgrades"), true));

        // Attribute rules
        for (var r : snap.attributes) {
            if (!r.enabled) continue;

            String dir = r.direction == UpgradeServerConfig.Direction.INCREASE ? "+" : "−";
            String stepStr;
            if (r.stepType == UpgradeServerConfig.StepType.PERCENT) {
                double step = (r.perLevelOverrides.getOrDefault(curLvl, r.defaultStep)) * 100.0;
                stepStr = dir + format1(step) + "%";
            } else {
                double step = r.perLevelOverrides.getOrDefault(curLvl, r.defaultStep);
                stepStr = dir + format2(step);
            }

            String line = String.format("%s (%s)", humanizeAttr(r.id.toString()), stepStr);
            lines.add(Line.of(Component.literal(line), false));
        }

        // Chances block
        lines.add(Line.spacer());
        lines.add(Line.of(Component.literal("Chances"), true));

        double base = snap.startChance;
        double dec = snap.decrementPerLevel;
        double minC = snap.minChance;
        double ov = snap.chanceOverrides.getOrDefault(curLvl, Double.NaN);

        if (!Double.isNaN(ov)) {
            lines.add(Line.of(Component.literal("Next: " + format1(ov * 100.0) + "% (override)"), false));
        } else {
            double c = Math.max(minC, base - curLvl * dec);
            lines.add(Line.of(Component.literal("Next: " + format1(c * 100.0) + "%"), false));
        }

        lines.add(Line.of(Component.literal("Min: " + format1(minC * 100.0) + "%"), true));
    }

    // ----------- Small helpers -----------

    private int panelLeft() {
        return screen.getLeftPos() + mainDrawDx() + MAIN_W + GAP_TO_MAIN;
    }

    private int panelTop() {
        return screen.getTopPos() + mainDrawDy();
    }

    private static ItemStack safeSlot(AngelDemonMenu menu, int idx) {
        try {
            return menu.getSlot(idx).getItem();
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private static boolean isPreview(ItemStack s) {
        try {
            if (s.isEmpty()) return false;
            CustomData cd = s.get(DataComponents.CUSTOM_DATA);
            if (cd == null) return false;
            return cd.copyTag().getBoolean("iu_preview");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int readItemLevel(ItemStack s) {
        try {
            CustomData cd = s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            return cd.copyTag().getCompound("iu_upgrade").getInt("level");
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String humanizeAttr(String rl) {
        // e.g. "minecraft:generic.attack_speed" -> "Attack Speed"
        String s = rl;
        int c = s.indexOf(':');
        if (c >= 0) s = s.substring(c + 1);
        s = s.replace('_', ' ').replace('.', ' ').trim();
        if (s.isEmpty()) return rl;
        String[] parts = s.split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            out.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) out.append(p.substring(1));
            out.append(' ');
        }
        return out.toString().trim();
    }

    private static String format0(double v) {
        return String.format("%.0f", v);
    }
    private static String format1(double v) {
        return String.format("%.1f", v);
    }
    private static String format2(double v) {
        return String.format("%.2f", v);
    }

    // ----------- Line model -----------

    public record Line(Component text, boolean dimmed) {
        public static Line of(Component text, boolean dim) { return new Line(text, dim); }
        public static Line spacer() { return new Line(Component.literal(""), true); }
    }

    // (Not using word-wrap into multiple components; keeping to single-line components for perf.)
    private void addHeader(Component title, int textW, Font font) {
        MutableComponent c = title.copy().withStyle(ChatFormatting.GOLD);
        lines.add(Line.of(c, false));
    }
    private void addDim(Component text, int textW, Font font) {
        lines.add(Line.of(text.copy().withStyle(ChatFormatting.GRAY), true));
    }
}
