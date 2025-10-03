// File: src/main/java/org/z2six/infiniteupgrades/client/screen/view/ReputationBarView.java
package org.z2six.infiniteupgrades.feature.infusion.client.screen.view;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.feature.infusion.client.screen.AngelDemonScreen;

/**
 * Renders the reputation bar (96x11) and its pointer (8x11) using a unified value:
 *  - unified < 0 : demon alignment (LEFT)
 *  - unified > 0 : angel alignment (RIGHT)
 *
 * The bar normalizes against the server-provided repMax, so it works for 100, 1000, 10000, etc.
 */
public final class ReputationBarView {
    private static final Logger LOG = LogUtils.getLogger();

    // Texture sizes (exact)
    public static final int BAR_W = 96;
    public static final int BAR_H = 11;
    public static final int PTR_W = 8;
    public static final int PTR_H = 11;

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

        // LOG.debug("[RepBar] draw unified={} repMax={} normalized={} centerX={} ptrLeftX={} barBaseX={}",
                // unified, repMaxForView, normalized, (int)Math.round(centerX), ptrLeftX, baseX);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
