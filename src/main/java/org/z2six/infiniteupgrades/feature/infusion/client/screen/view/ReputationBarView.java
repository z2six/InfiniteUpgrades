// MainFile: src/main/java/org/z2six/infiniteupgrades/feature/infusion/client/screen/view/ReputationBarView.java
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
 * Background mapping (normalized rep n = unified/repMax):
 *  - [-1.00, -0.75]      -> background_demonic.png
 *  - (-0.75, -0.25]      -> background_75p_demonic.png
 *  - (-0.25, +0.25)      -> background_half.png
 *  - [ +0.25, +0.75 )    -> background_75p_celestial.png
 *  - [ +0.75, +1.00]     -> background_celestial.png
 */
public final class ReputationBarView {
    private static final Logger LOG = LogUtils.getLogger();

    // Bar & pointer sizes (pixels)
    public static final int BAR_W = 108;
    public static final int BAR_H = 13;
    public static final int PTR_W = 8;
    public static final int PTR_H = 11;

    private static final DecimalFormat PCT1 = new DecimalFormat("0.0");

    // Backgrounds
    private final ResourceLocation bgHalf;
    private final ResourceLocation bgCelestial;
    private final ResourceLocation bgDemonic;
    private final ResourceLocation bg75Celestial;
    private final ResourceLocation bg75Demonic;

    private final ResourceLocation ptrTex;

    // Parent + anchor
    private final AngelDemonScreen screen;
    private final int baseX;
    private final int baseY;

    // Rep snapshot (server-provided)
    private double unified = 0.0;
    private int repMaxForView = 100;

    // One-time init logging / resource verification
    private boolean loggedInit = false;
    private boolean ptrExists = true;
    private boolean halfExists = true;
    private boolean angelExists = true;
    private boolean demonExists = true;
    private boolean c75Exists = true;
    private boolean d75Exists = true;

    public ReputationBarView(
            AngelDemonScreen screen, int x, int y,
            ResourceLocation backgroundHalf,
            ResourceLocation background75Celestial,
            ResourceLocation background75Demonic,
            ResourceLocation backgroundCelestial,
            ResourceLocation backgroundDemonic,
            ResourceLocation pointerTex
    ) {
        this.screen = screen;
        this.baseX = x;
        this.baseY = y;

        this.bgHalf        = backgroundHalf;
        this.bg75Celestial = background75Celestial;
        this.bg75Demonic   = background75Demonic;
        this.bgCelestial   = backgroundCelestial;
        this.bgDemonic     = backgroundDemonic;

        this.ptrTex = pointerTex;

        // Proactively check that resources exist; log if missing (defensive, non-fatal).
        try {
            Minecraft mc = screen.getMinecraft();
            if (mc != null && mc.getResourceManager() != null) {
                halfExists = resourceExists(mc, bgHalf);
                c75Exists  = resourceExists(mc, bg75Celestial);
                d75Exists  = resourceExists(mc, bg75Demonic);
                angelExists = resourceExists(mc, bgCelestial);
                demonExists = resourceExists(mc, bgDemonic);
                ptrExists   = resourceExists(mc, pointerTex);

                if (!halfExists)  LOG.error("[RepBar] Missing texture: {}", bgHalf);
                if (!c75Exists)   LOG.error("[RepBar] Missing texture: {}", bg75Celestial);
                if (!d75Exists)   LOG.error("[RepBar] Missing texture: {}", bg75Demonic);
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
        // reserved for future easing
    }

    /** Draw background + pointer. */
    public void render(GuiGraphics gg) {
        if (!loggedInit) {
            loggedInit = true;
            LOG.debug("[RepBar] init: unified={} repMax={} (client snapshot)", unified, repMaxForView);
        }

        // Background
        ResourceLocation bg = chooseBackground();
        gg.blit(bg, baseX, baseY, 0, 0, BAR_W, BAR_H, BAR_W, BAR_H);

        // Pointer mapping
        double normalized = clamp(unified / (double)repMaxForView, -1.0, 1.0);

        final int SIDE_MARGIN = 11; // inward clamp so pointer doesn’t clip edges
        double usableWidth = BAR_W - (SIDE_MARGIN * 2);
        double offset = (usableWidth * (normalized + 1.0) / 2.0);
        double centerX = baseX + SIDE_MARGIN + offset;
        int ptrLeftX = (int)Math.round(centerX - PTR_W / 2.0);
        int ptrTopY = baseY; // looks good slightly inset against 13px bar

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

    // ---------- background selection ----------

    private ResourceLocation chooseBackground() {
        // Guard against div-by-zero (repMaxForView clamped >=1 elsewhere)
        double n = (repMaxForView == 0) ? 0.0 : (unified / (double)repMaxForView);

        // Use exact inclusivity to match your spec:
        // demonic full:     [-1.00, -0.75]
        // demonic 75%:      (-0.75, -0.25]
        // half:             (-0.25, +0.25)
        // celestial 75%:    [ +0.25, +0.75 )
        // celestial full:   [ +0.75, +1.00]
        if (n >= 0.75) {
            return (angelExists ? bgCelestial : bgHalf);
        } else if (n >= 0.25) {
            return (c75Exists ? bg75Celestial : bgHalf);
        } else if (n > -0.25 && n < 0.25) {
            return bgHalf;
        } else if (n > -0.75) { // n <= -0.25 handled here
            return (d75Exists ? bg75Demonic : bgHalf);
        } else {
            return (demonExists ? bgDemonic : bgHalf);
        }
    }

    // ---------- helpers ----------

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
