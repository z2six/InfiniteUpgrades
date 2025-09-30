package org.z2six.infiniteupgrades.client.screen.view;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.client.screen.AngelDemonScreen;

/**
 * Progress fill overlay (spritesheet) drawn over main.png during infusion.
 *
 * Spritesheet:
 *  - textures/gui/container/{angel|demon}/progress_fill_sheet.png
 *  - size: 160 x 2756
 *  - frames: 26, each 160x106, stacked bottom→top
 *    frame 0 (0%) is bottom; frame 25 (100%) is top.
 *
 * Placement over main.png:
 *  - top-left at (mainDraw + 8, mainDraw + 18)
 *
 * Timing (all inside server timer):
 *  - Fill phase
 *  - Breathe phase (pulse the final frame)
 *  - Reverse phase (back to empty), with duration = 0.5 * fill duration
 */
public final class ProgressFillView {
    private static final Logger LOG = LogUtils.getLogger();

    // Spritesheet dims
    private static final int SHEET_W = 160;
    private static final int SHEET_H = 2756;

    // Frame dims + count
    private static final int FRAME_W = 160;
    private static final int FRAME_H = 106;
    private static final int FRAMES  = 26;

    // Draw anchor relative to main.png (+1,+1) offsets (see MainGuiView)
    private static final int REL_X = 8;
    private static final int REL_Y = 18;

    // Phase tuning
    private static final double BREATH_FRACTION = 0.10;     // 10% of total time for breathing pulse
    private static final double REVERSE_TO_FILL_RATIO = 0.25;// reverse is 50% as long as fill
    private static final int    BREATH_PULSES = 2;          // number of pulse cycles during breathe

    private final AngelDemonScreen screen;
    private final int baseX; // absolute X
    private final int baseY; // absolute Y

    private final ResourceLocation sheetTex;
    private boolean checked = false;
    private boolean exists = true;

    public ProgressFillView(AngelDemonScreen screen) {
        this.screen = screen;
        this.baseX = screen.getLeftPos() + MainGuiView.mainDrawDx() + REL_X;
        this.baseY = screen.getTopPos()  + MainGuiView.mainDrawDy() + REL_Y;

        this.sheetTex = ResourceLocation.fromNamespaceAndPath(
                "infiniteupgrades",
                "textures/gui/container/" + screen.folder() + "/progress_fill_sheet.png"
        );
    }

    /** Draws the current progress frame/state if an infusion is active. */
    public void renderBg(GuiGraphics gg) {
        // Verify texture once
        if (!checked) {
            checked = true;
            try {
                Minecraft mc = screen.getMinecraft();
                if (mc != null && mc.getResourceManager() != null) {
                    java.util.Optional<Resource> res = mc.getResourceManager().getResource(sheetTex);
                    exists = res.isPresent();
                    if (!exists) LOG.error("[ProgressFill] Missing texture: {}", sheetTex);
                }
            } catch (Throwable t) {
                exists = false;
                LOG.warn("[ProgressFill] Texture lookup failed: {}", t.toString());
            }
        }
        if (!exists) return;

        final long end = screen.getMenu().getClientLockEndGameTime();
        final int duration = screen.getMenu().getClientLockDurationTicks();
        if (end <= 0L || duration <= 0) return;

        final long now;
        try {
            now = screen.getMinecraft() != null && screen.getMinecraft().level != null
                    ? screen.getMinecraft().level.getGameTime()
                    : 0L;
        } catch (Throwable t) {
            return;
        }

        final long start = end - duration;

        if (now < start || now >= end) {
            // Before it starts or after it fully ends -> nothing to draw
            return;
        }

        // Phase fractions: fill + breathe + reverse = 1
        // reverse = REVERSE_TO_FILL_RATIO * fill
        // => fill = (1 - BREATH_FRACTION) / (1 + REVERSE_TO_FILL_RATIO)
        final double fillFrac = (1.0 - BREATH_FRACTION) / (1.0 + REVERSE_TO_FILL_RATIO);
        final double reverseFrac = REVERSE_TO_FILL_RATIO * fillFrac;
        final double breatheFrac = BREATH_FRACTION;

        // Convert to ticks
        final double total = (double) duration;
        final double fillTicks    = total * fillFrac;
        final double breatheTicks = total * breatheFrac;
        final double reverseTicks = total * reverseFrac;

        final double t = (double)(now - start); // elapsed since start

        if (t < fillTicks) {
            // --------- FILL PHASE ----------
            double p = clamp01(t / fillTicks);          // [0..1]
            int frame = frameIndexForward(p);
            drawFrame(gg, frame, 1.0f);                 // full alpha
            return;
        }

        if (t < fillTicks + breatheTicks) {
            // --------- BREATHE PHASE ----------
            // Show final frame with a pulsing alpha between ~0.55..1.0
            double tb = t - fillTicks;
            double phase = clamp01(tb / Math.max(1.0, breatheTicks)); // [0..1]
            double cycles = BREATH_PULSES;
            double osc = 0.5 * (1.0 + Math.sin(phase * Math.PI * 2.0 * cycles)); // [0..1]
            float alpha = (float) (0.55 + 0.45 * osc); // 0.55..1.0
            drawFrame(gg, FRAMES - 1, alpha);
            return;
        }

        // --------- REVERSE PHASE ----------
        double tr = t - fillTicks - breatheTicks;
        double pr = clamp01(tr / Math.max(1.0, reverseTicks)); // [0..1]
        int frame = frameIndexReverse(pr);
        drawFrame(gg, frame, 1.0f);
    }

    // --- helpers ---

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** Forward mapping: 0 -> frame 0 ... 1 -> frame 25 (final) */
    private static int frameIndexForward(double p) {
        // Keep p < 1 so floor stays in-range, then clamp
        double pp = Math.min(p, Math.nextDown(1.0));
        int f = (int)Math.floor(pp * FRAMES);
        if (f < 0) f = 0;
        if (f >= FRAMES) f = FRAMES - 1;
        return f;
    }

    /** Reverse mapping: 0 -> frame 25 ... 1 -> frame 0 */
    private static int frameIndexReverse(double p) {
        int fwd = frameIndexForward(p);          // 0..25
        int rev = (FRAMES - 1) - fwd;            // 25..0
        return Math.max(0, Math.min(FRAMES - 1, rev));
    }

    private void drawFrame(GuiGraphics gg, int frame, float alpha) {
        final int srcU = 0;
        final int srcV = SHEET_H - FRAME_H * (frame + 1);

        // Pulse / transparency
        boolean resetColor = false;
        if (alpha < 0.999f) {
            try {
                RenderSystem.enableBlend();
                RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
                resetColor = true;
            } catch (Throwable ignored) {}
        }

        gg.blit(
                sheetTex,
                baseX, baseY,
                srcU, srcV,
                FRAME_W, FRAME_H,
                SHEET_W, SHEET_H
        );

        if (resetColor) {
            try {
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            } catch (Throwable ignored) {}
        }
    }
}
