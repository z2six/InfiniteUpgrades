// MainFile: src/main/java/org/z2six/infiniteupgrades/feature/infusion/logic/BreakSpeedHooks.java
package org.z2six.infiniteupgrades.feature.infusion.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

/**
 * Game-bus hook: multiplies block mining speed by our custom per-item "block_speed" bonus.
 *
 * Reads the fraction from ToolSpeedUtil.getBonus(stack), e.g. +0.80 == +80% faster.
 * Applies only when the player is holding a mining tool as determined by ToolSpeedUtil.isMiningTool(stack).
 *
 * Safe on both logical sides. Client-side affects the breaking progress UI; server-side is authoritative.
 */
public final class BreakSpeedHooks {
    private static final Logger LOG = LogUtils.getLogger();

    private BreakSpeedHooks() {}

    /** Game-bus listener (wired from Infiniteupgrades ctor via NeoForge.EVENT_BUS.addListener). */
    public static void onBreakSpeed(PlayerEvent.BreakSpeed e) {
        try {
            if (e == null || e.getEntity() == null) return;

            final ItemStack held = e.getEntity().getMainHandItem();
            if (held.isEmpty()) return;
            if (!ToolSpeedUtil.isMiningTool(held)) return;

            // Original computed speed (already includes Efficiency, Haste, etc.)
            final float original = e.getNewSpeed();
            if (original <= 0.0f) return;

            final double bonus = Math.max(0.0, ToolSpeedUtil.getBonus(held)); // fraction, e.g. 0.80 == +80%
            if (bonus <= 1.0e-9) return;

            // Optionally, only boost when this tool is appropriate for the block:
            // If you want unconditional speed (even when "wrong tool"), remove this 'correct' check.
            final BlockState state = e.getState();
            final boolean correct = held.isCorrectToolForDrops(state);
            if (!correct) {
                // Keep this as a debug so you can evaluate. Comment out to always boost.
                LOG.debug("[BreakSpeedHooks] Tool not correct for block; skipping boost (tool={}, block={})",
                        held.getItem(), state.getBlock().builtInRegistryHolder().key().location());
                return;
            }

            // Multiply mining speed: new = original * (1 + bonus)
            final float scaled = (float)(original * (1.0 + bonus));
            e.setNewSpeed(scaled);

            LOG.debug("[BreakSpeedHooks] Applied block_speed bonus: orig={} boosted={} (bonus={} = +{}%) tool={}",
                    String.format(java.util.Locale.ROOT, "%.3f", original),
                    String.format(java.util.Locale.ROOT, "%.3f", scaled),
                    String.format(java.util.Locale.ROOT, "%.5f", bonus),
                    String.format(java.util.Locale.ROOT, "%.1f", bonus * 100.0),
                    held.getItem());
        } catch (Throwable t) {
            LOG.error("[BreakSpeedHooks] onBreakSpeed failed: {}", t.toString());
        }
    }
}
