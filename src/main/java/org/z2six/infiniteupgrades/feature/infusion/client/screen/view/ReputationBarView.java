// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/client/screen/view/ReputationBarView.java
package org.z2six.infiniteupgrades.feature.infusion.client.screen.view;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.feature.infusion.client.screen.AngelDemonScreen;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/client/screen/view/ReputationBarView.java
 *
 * Renders the reputation bar (96x11) and pointer (8x11), and shows a tooltip on hover:
 *  - Angel: <signed angel rep> rep (<signed %> chance)
 *  - Demon: <signed demon rep> rep (<signed %> chance)
 *  - Infusions till max: <N> Demon, <M> Angel
 *
 * Notes:
 * - Chance lines are computed exactly like DetailsPanelView (repBonusPerPoint + clamp).
 * - "Infusions till max" assumes successive *successful* infusions (uses repDeltaSuccess magnitude).
 *   If repDeltaSuccess is 0, it falls back to repDeltaFail; if both are 0, we display "∞".
 */
public final class ReputationBarView {
    private static final Logger LOG = LogUtils.getLogger();

    // Texture sizes (exact)
    public static final int BAR_W = 96;
    public static final int BAR_H = 11;
    public static final int PTR_W = 8;
    public static final int PTR_H = 11;

    // Formatting
    private static final DecimalFormat PCT1 = new DecimalFormat("0.0");

    // Resources
    private final ResourceLocation barTex;
    private final ResourceLocation ptrTex;

    // Parent + anchor
    private final AngelDemonScreen screen;
    private final int baseX; // absolute screen X where the bar's top-left starts
    private final int baseY; // absolute screen Y where the bar's top-left starts

    // Unified reputation value from server (domain [-repMax..+repMax]) + repMax
    private double unified = 0.0;
    private int repMaxForView = 100; // default; updated from server snapshot

    // For one-time init logging / resource verification
    private boolean loggedInit = false;
    private boolean barExists = true;
    private boolean ptrExists = true;

    public ReputationBarView(AngelDemonScreen screen, int x, int y,
                             ResourceLocation barTex, ResourceLocation pointerTex) {
        this.screen = screen;
        this.baseX = x;
        this.baseY = y;
        this.barTex = barTex;
        this.ptrTex = pointerTex;

        // Proactively check that resources exist; log if missing.
        try {
            Minecraft mc = screen.getMinecraft();
            if (mc != null && mc.getResourceManager() != null) {
                barExists = resourceExists(mc, barTex);
                ptrExists = resourceExists(mc, pointerTex);
                if (!barExists) {
                    LOG.error("[RepBar] Missing texture: {}", barTex);
                }
                if (!ptrExists) {
                    LOG.error("[RepBar] Missing texture: {}", pointerTex);
                }
            }
        } catch (Throwable t) {
            LOG.warn("[RepBar] Resource existence check failed: {}", t.toString());
        }
    }

    private static boolean resourceExists(Minecraft mc, ResourceLocation rl) {
        try {
            java.util.Optional<Resource> res = mc.getResourceManager().getResource(rl);
            return res.isPresent();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Server pushes unified reputation + repMax here. */
    public void acceptServerValues(double unified, int repMax) {
        this.unified = unified;
        this.repMaxForView = Math.max(1, repMax);
        LOG.debug("[RepBar] acceptServerValues unified={} repMax={}", unified, this.repMaxForView);
    }

    /** Called each tick; nothing to animate yet. */
    public void tick() {
        // No-op
    }

    /**
     * Render the bar and pointer.
     * Pointer is centered horizontally at baseX + BAR_W/2 for 0 rep.
     * -repMax -> pointer center at baseX (left edge)
     * +repMax -> pointer center at baseX + BAR_W (right edge)
     * Note: pointer is 8px wide; its left draw X is (center - PTR_W/2).
     */
    public void render(GuiGraphics gg) {
        // One-time init log
        if (!loggedInit) {
            loggedInit = true;
            LOG.debug("[RepBar] init: unified={} repMax={} (client snapshot)", unified, repMaxForView);
        }

        // Draw BAR
        if (barExists) {
            gg.blit(barTex,
                    baseX, baseY,
                    0, 0,
                    BAR_W, BAR_H,
                    BAR_W, BAR_H); // exact pixels, no scaling
        }

        // Normalize to [-1..+1] using the real repMax from server
        double normalized = clamp(unified / (double)repMaxForView, -1.0, 1.0);

        // Compute center X of the pointer along the BAR
        double centerX = baseX + (BAR_W * (normalized + 1.0) / 2.0);

        // Left X for drawing (pointer is 8px wide; center anchor)
        int ptrLeftX = (int)Math.round(centerX - PTR_W / 2.0);
        int ptrTopY  = baseY; // pointer shares same Y top as bar

        // Draw POINTER
        if (ptrExists) {
            gg.blit(ptrTex,
                    ptrLeftX, ptrTopY,
                    0, 0,
                    PTR_W, PTR_H,
                    PTR_W, PTR_H); // exact pixels, no scaling
        }
    }

    /** Draw a tooltip when hovering the reputation bar. */
    public void renderOverlay(GuiGraphics gg, int mouseX, int mouseY) {
        if (!isHovering(mouseX, mouseY)) return;

        // Pull server config snapshot (client-side accessible)
        var cfg = UpgradeServerConfig.snapshot();

        // Signed rep for each side (positive means aligned with that side)
        int angelRepSigned = (int)Math.round(unified);
        int demonRepSigned = (int)Math.round(-unified);

        // Chance effects (mirror DetailsPanelView: no oppositePenaltyFactor here to avoid UI mismatch)
        double bonusPerPoint = cfg.repBonusPerPoint;
        double clamp = Math.max(0.0, cfg.repBonusClamp);

        double angelBonus = clampSym(angelRepSigned * bonusPerPoint, clamp); // fraction
        double demonBonus = clampSym(demonRepSigned * bonusPerPoint, clamp); // fraction

        // Infusions till max, assuming successes toward that side
        double step = Math.abs(cfg.repDeltaSuccess) > 1.0e-9
                ? Math.abs(cfg.repDeltaSuccess)
                : Math.abs(cfg.repDeltaFail);

        String stepsLine;
        if (step <= 1.0e-12) {
            stepsLine = "Infusions till max: ∞ Demon, ∞ Angel";
        } else {
            double distDemon = Math.max(0.0, (unified + repMaxForView));          // to -repMax
            double distAngel = Math.max(0.0, (repMaxForView - unified));          // to +repMax
            long stepsDemon = (long)Math.ceil(distDemon / step);
            long stepsAngel = (long)Math.ceil(distAngel / step);
            stepsLine = "Infusions till max: " + stepsDemon + " Demon, " + stepsAngel + " Angel";
        }

        List<Component> lines = new ArrayList<>(3);
        lines.add(Component.literal("Angel: " + signedInt(angelRepSigned) + " rep (" + signedPct(angelBonus) + " chance)"));
        lines.add(Component.literal("Demon: " + signedInt(demonRepSigned) + " rep (" + signedPct(demonBonus) + " chance)"));
        lines.add(Component.literal(stepsLine));

        // Render tooltip
        var font = screen.getMinecraft().font;
        List<net.minecraft.util.FormattedCharSequence> ordered = lines.stream()
                .map(Component::getVisualOrderText)
                .toList();
        gg.renderTooltip(font, ordered, mouseX, mouseY);
    }

    // ---------------- helpers ----------------

    private boolean isHovering(int mouseX, int mouseY) {
        return mouseX >= baseX && mouseX < (baseX + BAR_W)
                && mouseY >= baseY && mouseY < (baseY + BAR_H);
    }

    private static String signedInt(int v) {
        return (v >= 0 ? "+" : "") + v;
    }

    private static String signedPct(double frac) {
        double pct = frac * 100.0;
        String s = PCT1.format(Math.abs(pct));
        return (pct >= 0 ? "+" : "−") + s + "%";
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clampSym(double v, double limitAbs) {
        if (v >  limitAbs) return  limitAbs;
        if (v < -limitAbs) return -limitAbs;
        return v;
    }
}
