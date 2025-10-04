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
 *
 * This version adds two locking modes:
 *  - Timed lock   (client cooldown): {@link #lockForTicks(int)} with auto-unlock.
 *  - Hard lock    (await server):    {@link #lockIndefinite()} / {@link #unlockNow()}.
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

    /** Generic locked flag driving visuals + input gate. */
    private boolean locked = false;
    /** Hard lock that never ticks down locally (used while waiting for server authority). */
    private boolean hardLocked = false;
    /** Remaining ticks for timed lock (ignored while {@code hardLocked} is true). */
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

    /** Lock the button for N ticks (client-side timed lock). */
    public void lockForTicks(int ticks) {
        this.hardLocked = false;                 // switch to timed mode
        this.locked = true;
        this.lockTicksRemaining = Math.max(0, ticks);
        this.active = false;                     // built-in input gate
    }

    /** Convenience: seconds -> ticks (20 TPS). */
    public void lockForSeconds(int seconds) { lockForTicks(seconds * 20); }

    /** Engage a hard lock that does NOT tick down locally (await server instruction to unlock). */
    public void lockIndefinite() {
        this.hardLocked = true;
        this.locked = true;
        this.lockTicksRemaining = 0;
        this.active = false;
    }

    /** Immediately clear any lock and make the button clickable again. */
    public void unlockNow() {
        this.hardLocked = false;
        this.locked = false;
        this.lockTicksRemaining = 0;
        this.active = true;
    }

    /** Decrements the lock timer. Call from screen.containerTick. Ignored while hard-locked. */
    public void clientTickLock() {
        if (!locked || hardLocked) return;
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
        final ResourceLocation tex = locked ? texLocked : (isHovered() ? texHover : texDefault);
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
