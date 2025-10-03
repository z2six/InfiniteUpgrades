// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/logic/AutoClaimService.java
package org.z2six.infiniteupgrades.feature.infusion.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

/**
 * File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/logic/AutoClaimService.java
 *
 * Inventory-first delivery; if anything remains, drop it by the player.
 * Safe to call with EMPTY stacks. Server-side only.
 */
public final class AutoClaimService {
    private static final Logger LOG = LogUtils.getLogger();

    private AutoClaimService() {}

    /**
     * Tries to insert the given stack into the player's inventory. If insertion does not consume the
     * entire stack, drops the leftover near the player.
     *
     * NOTE: This method mutates the passed-in ItemStack (reducing its count if inventory absorbs some).
     */
    public static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()) return;
        try {
            // Inventory.add(stack) will reduce stack's count by what it inserted.
            boolean fullyInserted = player.getInventory().add(stack);
            if (!fullyInserted && !stack.isEmpty()) {
                // Whatever remains in "stack" now is the remainder -> drop that remainder.
                player.drop(stack.copy(), false);
                stack.setCount(0);
            }
        } catch (Throwable t) {
            LOG.error("[AutoClaimService] giveOrDrop failed", t);
            try {
                if (stack != null && !stack.isEmpty()) {
                    player.drop(stack.copy(), false);
                    stack.setCount(0);
                }
            } catch (Throwable ignored) {}
        }
    }
}
