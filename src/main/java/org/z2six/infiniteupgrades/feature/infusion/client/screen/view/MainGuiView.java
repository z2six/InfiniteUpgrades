// File: src/main/java/org/z2six/infiniteupgrades/client/screen/view/MainGuiView.java
package org.z2six.infiniteupgrades.feature.infusion.client.screen.view;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.feature.infusion.client.screen.AngelDemonScreen;
import org.z2six.infiniteupgrades.feature.infusion.client.widget.TriStateImageButton;
import org.z2six.infiniteupgrades.feature.infusion.menu.AngelDemonMenu;

/**
 * Main GUI view (left panel) responsible for:
 *  - Drawing main.png (no scaling; exact blit).
 *  - Creating the tri-state Infuse button at the specified pixel anchor.
 *
 * All positions are relative to the main panel image (176x222) with a +1,+1 draw offset
 * to align item slots visually.
 */
public final class MainGuiView {
    private static final Logger LOG = LogUtils.getLogger();

    // Main panel size (exact texture size)
    public static final int MAIN_W = 176;
    public static final int MAIN_H = 222;

    // 1px gap between main and details panels (the group centers: main + 1 + details)
    // Kept here for reference; AngelDemonScreen uses DetailsPanelView.GAP_TO_MAIN for group math.
    public static final int GAP_TO_DETAILS = 1;

    // Draw offsets used to align the slot chrome visually (+1,+1 based on your earlier guidance)
    private static final int DRAW_DX = 1;
    private static final int DRAW_DY = 1;

    // Button geometry (all three states share the same size/position)
    private static final int BTN_X = 44;  // relative to top-left of the main panel image
    private static final int BTN_Y = 107; // relative to top-left of the main panel image
    private static final int BTN_W = 90;
    private static final int BTN_H = 18;

    private final AngelDemonScreen screen;
    private TriStateImageButton infuseBtn;

    // Tracks the last server-reported infusion end tick we've applied on the client.
    // Prevents re-applying the same lock every tick.
    private long observedLockEndGameTime = -1L;

    public MainGuiView(AngelDemonScreen screen) {
        this.screen = screen;
    }

    private ResourceLocation mainTex() {
        // textures/gui/container/{angel|demon}/main.png
        return ResourceLocation.fromNamespaceAndPath(
                "infiniteupgrades",
                "textures/gui/container/" + screen.folder() + "/main.png"
        );
    }

    private ResourceLocation btnDefaultTex() {
        return ResourceLocation.fromNamespaceAndPath(
                "infiniteupgrades",
                "textures/gui/container/" + screen.folder() + "/btn_infuse_default.png"
        );
    }

    private ResourceLocation btnHoverTex() {
        return ResourceLocation.fromNamespaceAndPath(
                "infiniteupgrades",
                "textures/gui/container/" + screen.folder() + "/btn_infuse_hover.png"
        );
    }

    private ResourceLocation btnLockedTex() {
        return ResourceLocation.fromNamespaceAndPath(
                "infiniteupgrades",
                "textures/gui/container/" + screen.folder() + "/btn_infuse_locked.png"
        );
    }

    /** Exposed so other views (e.g., rep bar) can align vertically with the main panel draw. */
    public static int mainDrawDx() { return DRAW_DX; }
    public static int mainDrawDy() { return DRAW_DY; }

    /** Call from AngelDemonScreen.init() after screen has computed left/top. */
    public void onInit() {
        // Absolute screen-space anchor for the button
        final int absX = screen.getLeftPos() + DRAW_DX + BTN_X;
        final int absY = screen.getTopPos()  + DRAW_DY + BTN_Y;

        infuseBtn = new TriStateImageButton(
                absX, absY, BTN_W, BTN_H,
                btnDefaultTex(), btnHoverTex(), btnLockedTex(),
                () -> {
                    try {
                        if (screen.getMinecraft() != null && screen.getMinecraft().gameMode != null) {
                            // Send server-side menu button click (server will respond with S2C start/result)
                            screen.getMinecraft().gameMode.handleInventoryButtonClick(
                                    screen.getMenu().containerId,
                                    AngelDemonMenu.BUTTON_INFUSE
                            );
                            // NOTE: no local placeholder lock here — we now wait for InfuseStartedS2C.
                        }
                    } catch (Throwable t) {
                        LOG.error("[MainGuiView] Infuse onPress failed", t);
                    }
                }
        );

        // Add widget
        screen.addToScreen(infuseBtn);

        // If a lock was already in progress before this screen opened, honor it now based on menu values.
        applyServerLockIfAny(true);
    }

    /** Called each container tick from the parent screen. */
    public void tick() {
        // First, honor any new server-authoritative locks that arrived since the last tick.
        applyServerLockIfAny(false);

        // Then, tick down the local lock timer for the button (it auto-unlocks at 0).
        if (infuseBtn != null) {
            infuseBtn.clientTickLock();
        }
    }

    /** Draw the main panel background at (+1,+1) relative to the group's left/top. */
    public void renderBg(GuiGraphics gg) {
        gg.blit(mainTex(),
                screen.getLeftPos() + DRAW_DX,
                screen.getTopPos()  + DRAW_DY,
                0, 0,
                MAIN_W, MAIN_H,
                MAIN_W, MAIN_H); // srcW/srcH == dstW/dstH; exact pixel copy (no scaling)
    }

    /**
     * Applies any pending server lock to the button.
     * - On init: tries to compute remaining ticks using client world gameTime, so we don't "reset" a mid-run lock.
     * - On ticks: only applies when server reports a new endGameTime we haven't seen yet.
     */
    private void applyServerLockIfAny(boolean onInit) {
        if (infuseBtn == null) return;

        AngelDemonMenu menu = screen.getMenu();
        if (menu == null) return;

        long end = menu.getClientLockEndGameTime();
        int totalTicks = menu.getClientLockDurationTicks();

        if (end <= 0L || totalTicks <= 0) return;

        if (onInit) {
            // Screen just opened; we may be mid-lock. Estimate remaining time using client gameTime.
            int remaining = totalTicks;
            try {
                Minecraft mc = screen.getMinecraft();
                ClientLevel level = (mc != null) ? mc.level : null;
                long clientNow = (level != null) ? level.getGameTime() : 0L;
                long diff = end - clientNow; // may be negative if nearly done
                if (diff < 0L) diff = 0L;
                if (diff > Integer.MAX_VALUE) diff = Integer.MAX_VALUE;
                remaining = Math.min(totalTicks, (int) diff);
            } catch (Throwable ignore) {}

            if (remaining > 0) {
                infuseBtn.lockForTicks(remaining);
                observedLockEndGameTime = end;
            }
            return;
        }

        // Regular ticks: if server reports a new end tick, start/restart the lock for the full duration.
        if (end != observedLockEndGameTime) {
            infuseBtn.lockForTicks(totalTicks);
            observedLockEndGameTime = end;
        }
    }
}
