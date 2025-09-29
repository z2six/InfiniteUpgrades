// File: src/main/java/org/z2six/infiniteuprades/client/screen/view/ReputationBarView.java

package org.z2six.infiniteupgrades.client.screen.view;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.client.screen.AngelDemonScreen;
import org.z2six.infiniteupgrades.logic.RitualType;

/**
 * Renders the reputation bar (96x11) and its pointer (8x11).
 * - Positioned by absolute screen coords provided in ctor.
 * - Pointer center represents the user's reputation in [-100..+100].
 * - If reputation unknown/unavailable, pointer sits at the exact middle.
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

    // Snapshotted reputation values (from server)
    private double repAngel = 0.0; // in points, e.g., 0.6, 12.0, etc. (domain [-max..+max])
    private double repDemon = 0.0;

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

    /** Server pushes reputations here (angel & demon). */
    public void acceptServerValues(double angel, double demon) {
        this.repAngel = angel;
        this.repDemon = demon;
        LOG.debug("[RepBar] acceptServerValues angel={} demon={}", angel, demon);
    }

    /** Called each tick; nothing to animate yet, but kept for symmetry. */
    public void tick() {
        // No-op for now (no smoothing/animation requested yet).
    }

    /**
     * Render the bar and pointer.
     * Pointer is centered horizontally at baseX + BAR_W/2 for 0 rep.
     * -100 -> pointer center at baseX (left edge)
     * +100 -> pointer center at baseX + BAR_W (right edge)
     * Note: pointer is 8px wide; its left draw X is (center - PTR_W/2).
     */
    public void render(GuiGraphics gg) {
        // One-time init log (ritual + current rep we’ll use)
        if (!loggedInit) {
            loggedInit = true;
            LOG.debug("[RepBar] init: ritual={} player={} repAngel={} repDemon={} (client snapshot)",
                    screen.getMenu().ritual(),
                    (screen.getMinecraft() != null && screen.getMinecraft().player != null
                            ? screen.getMinecraft().player.getGameProfile().getName() : "?"),
                    repAngel, repDemon);
        }

        // Draw BAR
        if (barExists) {
            gg.blit(barTex,
                    baseX, baseY,
                    0, 0,
                    BAR_W, BAR_H,
                    BAR_W, BAR_H); // exact pixels, no scaling
        }

        // Determine which rep to use based on ritual
        RitualType ritual = screen.getMenu().ritual();
        double rep = (ritual == RitualType.ANGEL) ? repAngel : repDemon;

        // Normalize to [-1..+1] if your max is 100; if server uses another max, clamp anyway.
        // Current server config clamps at UpgradeServerConfig.repMax but we draw against 100 here per your spec.
        double normalized = clamp(rep / 100.0, -1.0, 1.0);

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

        LOG.debug("[RepBar] draw ritual={} rep={} (client) centerX={} ptrLeftX={} barBaseX={}",
                ritual, rep, (int)Math.round(centerX), ptrLeftX, baseX);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
