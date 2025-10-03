// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/client/widget/TriStateImageButton.java
package org.z2six.infiniteupgrades.feature.infusion.client.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * A simple image-only button with three textures:
 *  - default (idle)
 *  - hover
 *  - locked (click-disabled)
 *
 * Textures are full images (no atlases); we pass the real texture size to blit() to avoid stretching.
 */
public final class TriStateImageButton extends AbstractWidget {
    private final ResourceLocation texDefault;
    private final ResourceLocation texHover;
    private final ResourceLocation texLocked;

    private final int drawW;
    private final int drawH;

    // The texture's actual (native) width/height for UV mapping
    private final int texW;
    private final int texH;

    private boolean locked = false;
    private int lockTicksRemaining = 0; // client-side cooldown ticks (20 tps)

    private final Runnable onPressRunnable;

    /**
     * @param w drawn width (and native texture width)
     * @param h drawn height (and native texture height)
     */
    public TriStateImageButton(int x, int y, int w, int h,
                               ResourceLocation texDefault,
                               ResourceLocation texHover,
                               ResourceLocation texLocked,
                               Runnable onPress) {
        super(x, y, w, h, Component.empty());
        this.drawW = w;
        this.drawH = h;
        this.texW = w; // IMPORTANT: the PNGs are exactly this size
        this.texH = h;
        this.texDefault = texDefault;
        this.texHover = texHover;
        this.texLocked = texLocked;
        this.onPressRunnable = onPress;
    }

    public boolean isLocked() { return locked; }

    /** Lock the button for N ticks (client-side). */
    public void lockForTicks(int ticks) {
        this.locked = true;
        // ensure we never shorten an existing lock if this is called repeatedly
        int add = Math.max(0, ticks);
        this.lockTicksRemaining = Math.max(this.lockTicksRemaining, add);
        this.active = false; // built-in input gate
    }

    /** Convenience: seconds -> ticks (20 TPS). */
    public void lockForSeconds(int seconds) {
        lockForTicks(seconds * 20);
    }

    /** Decrements the lock timer. Call from screen.containerTick. */
    public void clientTickLock() {
        if (!locked) return;
        if (lockTicksRemaining > 0) {
            lockTicksRemaining--;
            if (lockTicksRemaining <= 0) {
                locked = false;
                active = true;
            }
        }
    }

    @Override
    protected void renderWidget(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        ResourceLocation tex = locked ? texLocked : (isHovered() ? texHover : texDefault);
        // Use the real texture size for UVs to avoid stretching
        gg.blit(tex, getX(), getY(), 0, 0, drawW, drawH, texW, texH);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (locked || !active || !visible) return;
        if (onPressRunnable != null) onPressRunnable.run();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        narration.add(NarratedElementType.TITLE, Component.literal("Infuse"));
    }
}
