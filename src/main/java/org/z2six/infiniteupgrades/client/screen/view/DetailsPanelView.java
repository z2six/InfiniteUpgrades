// File: src/main/java/org/z2six/infiniteuprades/client/screen/view/DetailsPanelView.java

package org.z2six.infiniteupgrades.client.screen.view;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.z2six.infiniteupgrades.client.screen.AngelDemonScreen;

/**
 * Static details panel drawn to the right of the main panel:
 *  - Size: 128x222 (exact texture).
 *  - 1px gap from the main panel.
 *  - Shares the same +1,+1 vertical alignment as main.
 */
public final class DetailsPanelView {
    public static final int DETAILS_W = 128;
    public static final int DETAILS_H = 222;

    // 1px gap between main and details
    public static final int GAP_TO_MAIN = 1;

    private final AngelDemonScreen screen;

    public DetailsPanelView(AngelDemonScreen screen) {
        this.screen = screen;
    }

    private ResourceLocation detailsTex() {
        // textures/gui/container/{angel|demon}/details.png
        return ResourceLocation.fromNamespaceAndPath(
                "infiniteupgrades",
                "textures/gui/container/" + screen.folder() + "/details.png"
        );
    }

    public void renderBg(GuiGraphics gg) {
        // Draw to the right of MAIN: main-left + main-draw-dx + MAIN_W + GAP
        int x = screen.getLeftPos() + MainGuiView.mainDrawDx() + MainGuiView.MAIN_W + GAP_TO_MAIN;
        int y = screen.getTopPos()  + MainGuiView.mainDrawDy();

        gg.blit(detailsTex(),
                x, y,
                0, 0,
                DETAILS_W, DETAILS_H,
                DETAILS_W, DETAILS_H); // exact blit (no scaling)
    }
}
