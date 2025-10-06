// REPLACE the whole file contents with this updated version

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

/**
 * Renders the reputation bar (108x13) with a state-based background and an 8x11 pointer.
 *
 * Backgrounds:
 *  - background_half.png      : default (and temporary placeholder for “halfway” states)
 *  - background_celestial.png : shown when at +max (angel cap)
 *  - background_demonic.png   : shown when at -max (demon cap)
 *
 * Future: halfway-to-demon / halfway-to-angel art. Code already branches; currently both fall back to background_half.png.
 *
 * Pointer logic unchanged; it spans the full bar width and is centered at 0 rep.
 */
public final class ReputationBarView {
    private static final Logger LOG = LogUtils.getLogger();

    // New bar size (exact pixels)
    public static final int BAR_W = 108;
    public static final int BAR_H = 13;

    // Pointer size stays the same
    public static final int PTR_W = 8;
    public static final int PTR_H = 11;

    // Formatting
    private static final DecimalFormat PCT1 = new DecimalFormat("0.0");

    // Resources (all resolved up-front)
    private final ResourceLocation bgHalf;        // default
    private final ResourceLocation bgCelestial;   // angel max
    private final ResourceLocation bgDemonic;     // demon max

    // When you add “halfway to side” textures later, just change these to your new paths.
    // For now, they intentionally point to bgHalf so visuals don’t change until art arrives.
    private final ResourceLocation bgHalfToAngel; // TODO swap to e.g. background_half_angel.png later
    private final ResourceLocation bgHalfToDemon; // TODO swap to e.g. background_half_demon.png later

    private final ResourceLocation ptrTex;

    // Parent + anchor
    private final AngelDemonScreen screen;
    private final int baseX; // absolute screen X where the bar's top-left starts
    private final int baseY; // absolute screen Y where the bar's top-left starts

    // Unified reputation value from server (domain [-repMax..+repMax]) + repMax
    private double unified = 0.0;
    private int repMaxForView = 100; // default; updated from server snapshot

    // One-time init logging / resource verification
    private boolean loggedInit = false;
    private boolean ptrExists = true;
    private boolean halfExists = true;
    private boolean angelExists = true;
    private boolean demonExists = true;

    public ReputationBarView(AngelDemonScreen screen, int x, int y,
                             ResourceLocation backgroundHalf,
                             ResourceLocation backgroundCelestial,
                             ResourceLocation backgroundDemonic,
                             ResourceLocation pointerTex) {
        this.screen = screen;
        this.baseX = x;
        this.baseY = y;

        this.bgHalf = backgroundHalf;
        this.bgCelestial = backgroundCelestial;
        this.bgDemonic = backgroundDemonic;

        // Pre-wire future “halfway” art — currently routed to the neutral half background.
        this.bgHalfToAngel = backgroundHalf;
        this.bgHalfToDemon = backgroundHalf;

        this.ptrTex = pointerTex;

        // Proactively check that resources exist; log if missing.
        try {
            Minecraft mc = screen.getMinecraft();
            if (mc != null && mc.getResourceManager() != null) {
                halfExists  = resourceExists(mc, bgHalf);
                angelExists = resourceExists(mc, bgCelestial);
                demonExists = resourceExists(mc, bgDemonic);
                ptrExists   = resourceExists(mc, pointerTex);

                if (!halfExists)  LOG.error("[RepBar] Missing texture: {}", bgHalf);
                if (!angelExists) LOG.error("[RepBar] Missing texture: {}", bgCelestial);
                if (!demonExists) LOG.error("[RepBar] Missing texture: {}", bgDemonic);
                if (!ptrExists)   LOG.error("[RepBar] Missing texture: {}", pointerTex);
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

    public void tick() {
        // No-op (reserved for future pointer easing/animation)
    }

    /**
     * Render:
     * - Choose background by state (max demon / max angel / halfway-to-side / default half).
     * - Draw background at (baseX, baseY).
     * - Draw pointer centered along the BAR width based on unified rep.
     */
    public void render(GuiGraphics gg) {
        if (!loggedInit) {
            loggedInit = true;
            LOG.debug("[RepBar] init: unified={} repMax={} (client snapshot)", unified, repMaxForView);
        }

        // Pick proper background
        ResourceLocation bg = chooseBackground();

        // Draw BAR background (no scaling)
        gg.blit(bg, baseX, baseY, 0, 0, BAR_W, BAR_H, BAR_W, BAR_H);

        // Normalize to [-1..+1]
        double normalized = clamp(unified / (double)repMaxForView, -1.0, 1.0);

        // Pointer travel range shrinks by side margins
                final int SIDE_MARGIN = 11; // px inward from each side where pointer stops

        // Effective width that the pointer can move within
                double usableWidth = BAR_W - (SIDE_MARGIN * 2);

        // Map normalized [-1..+1] → [0..usableWidth]
                double offset = (usableWidth * (normalized + 1.0) / 2.0);

        // Compute center position inside the image, offset by left margin
                double centerX = baseX + SIDE_MARGIN + offset;

        // Convert to left draw X (center-anchor the 8-px pointer)
                int ptrLeftX = (int)Math.round(centerX - PTR_W / 2.0);

        // Align pointer to the bar’s top; (it’s 11px tall vs bar 13px — looks good slightly inset)
        int ptrTopY = baseY;

        if (ptrExists) {
            gg.blit(ptrTex, ptrLeftX, ptrTopY, 0, 0, PTR_W, PTR_H, PTR_W, PTR_H);
        }
    }

    /** Tooltip on hover. */
    public void renderOverlay(GuiGraphics gg, int mouseX, int mouseY) {
        if (!isHovering(mouseX, mouseY)) return;

        var cfg = UpgradeServerConfig.snapshot();

        int angelRepSigned = (int)Math.round(unified);
        int demonRepSigned = (int)Math.round(-unified);

        double bonusPerPoint = cfg.repBonusPerPoint;
        double clamp = Math.max(0.0, cfg.repBonusClamp);

        double angelBonus = clampSym(angelRepSigned * bonusPerPoint, clamp);
        double demonBonus = clampSym(demonRepSigned * bonusPerPoint, clamp);

        double step = Math.abs(cfg.repDeltaSuccess) > 1.0e-9
                ? Math.abs(cfg.repDeltaSuccess)
                : Math.abs(cfg.repDeltaFail);

        String stepsLine;
        if (step <= 1.0e-12) {
            stepsLine = "Infusions till max: ∞ Demon, ∞ Angel";
        } else {
            double distDemon = Math.max(0.0, (unified + repMaxForView)); // to -repMax
            double distAngel = Math.max(0.0, (repMaxForView - unified)); // to +repMax
            long stepsDemon = (long)Math.ceil(distDemon / step);
            long stepsAngel = (long)Math.ceil(distAngel / step);
            stepsLine = "Infusions till max: " + stepsDemon + " Demon, " + stepsAngel + " Angel";
        }

        List<Component> lines = new ArrayList<>(3);
        lines.add(Component.literal("Angel: " + signedInt(angelRepSigned) + " rep (" + signedPct(angelBonus) + " chance)"));
        lines.add(Component.literal("Demon: " + signedInt(demonRepSigned) + " rep (" + signedPct(demonBonus) + " chance)"));
        lines.add(Component.literal(stepsLine));

        var font = screen.getMinecraft().font;
        List<net.minecraft.util.FormattedCharSequence> ordered = lines.stream()
                .map(Component::getVisualOrderText)
                .toList();
        gg.renderTooltip(font, ordered, mouseX, mouseY);
    }

    // -------- state/background selection --------

    private ResourceLocation chooseBackground() {
        double n = (repMaxForView == 0) ? 0.0 : unified / (double)repMaxForView;

        // Max caps (exact ends)
        if (n >= 1.0 - 1e-12 && angelExists) return bgCelestial;
        if (n <= -1.0 + 1e-12 && demonExists) return bgDemonic;

        // Future half-way states (currently point at bgHalf until you add textures)
        if (n >= 0.5) return bgHalfToAngel; // TODO: swap to your dedicated halfway-to-angel art
        if (n <= -0.5) return bgHalfToDemon; // TODO: swap to your dedicated halfway-to-demon art

        // Default
        return bgHalf;
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
