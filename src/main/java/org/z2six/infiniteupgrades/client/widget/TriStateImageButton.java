package org.z2six.infiniteupgrades.client.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * A simple image-only button with three textures:
 *  - default (idle)
 *  - hover
 *  - locked (click-disabled)
 *
 * Each texture is the exact drawable size (no extra canvas).
 */
public final class TriStateImageButton extends AbstractWidget {
    private final ResourceLocation texDefault;
    private final ResourceLocation texHover;
    private final ResourceLocation texLocked;

    private final int drawW;
    private final int drawH;

    private boolean locked = false;
    private int lockTicksRemaining = 0; // client-side cooldown ticks (20 tps)

    private final Runnable onPressRunnable;

    public TriStateImageButton(int x, int y, int w, int h,
                               ResourceLocation texDefault,
                               ResourceLocation texHover,
                               ResourceLocation texLocked,
                               Runnable onPress) {
        super(x, y, w, h, Component.empty());
        this.drawW = w;
        this.drawH = h;
        this.texDefault = texDefault;
        this.texHover = texHover;
        this.texLocked = texLocked;
        this.onPressRunnable = onPress;
    }

    public boolean isLocked() { return locked; }

    /** Lock the button for N ticks (client-side). */
    public void lockForTicks(int ticks) {
        this.locked = true;
        this.lockTicksRemaining = Math.max(this.lockTicksRemaining, Math.max(0, ticks));
        this.active = false; // built-in input gate
    }

    /** Convenience: seconds -> ticks (20 TPS). */
    public void lockForSeconds(int seconds) {
        lockForTicks(seconds * 20);
    }

    /** Decrements the lock timer. Call from screen.tick/containerTick. */
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
        // Textures are exact-sized; use drawW/drawH for both image and texture sizes
        gg.blit(tex, getX(), getY(), 0, 0, drawW, drawH, drawW, drawH);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (locked || !active || !visible) return;
        if (onPressRunnable != null) onPressRunnable.run();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        // No-op for now (pure image button with no spoken text)
    }
}
