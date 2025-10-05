// File: src/main/java/org/z2six/infiniteupgrades/feature/souls/item/SoulCageItem.java
package org.z2six.infiniteupgrades.feature.souls.item;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;

/**
 * File: src/main/java/org/z2six/infiniteupgrades/feature/souls/item/SoulCageItem.java
 *
 * Soul Cage item – holds a running total of "soul units" collected.
 *
 * Storage:
 *   - Stored inside DataComponents.CUSTOM_DATA under NBT key "SoulTotal" (int >= 0).
 *
 * Tooltip:
 *   - Shows the total units stored (server-authoritative; but reading from stack is fine client-side).
 *
 * Helpers:
 *   - getTotal(ItemStack)
 *   - addUnits(ItemStack, int)
 *   - findAnyCage(Player)
 */
public class SoulCageItem extends Item {
    private static final Logger LOG = LogUtils.getLogger();

    public static final String NBT_TOTAL = "SoulTotal";

    public SoulCageItem(Properties props) {
        super(props);
    }

    // ----- Storage helpers (1.21+ data components) -----

    public static int getTotal(@NotNull ItemStack stack) {
        if (stack.isEmpty()) return 0;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return 0;
        CompoundTag tag = data.copyTag(); // defensive copy
        return Math.max(0, tag.getInt(NBT_TOTAL));
    }

    /** Adds delta units to the cage. @return new total. */
    public static int addUnits(@NotNull ItemStack stack, int delta) {
        if (stack.isEmpty() || delta <= 0) return getTotal(stack);

        // Read current custom data (if any), mutate a copy, write back.
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = (data != null) ? data.copyTag() : new CompoundTag();

        int now = Math.max(0, tag.getInt(NBT_TOTAL));
        int next = Math.max(0, now + delta);
        tag.putInt(NBT_TOTAL, next);

        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return next;
    }

    /** Locate a Soul Cage in the player's inventory. Returns the ItemStack or ItemStack.EMPTY. */
    public static @NotNull ItemStack findAnyCage(@NotNull Player player) {
        // Offhand first
        ItemStack off = player.getOffhandItem();
        if (isCage(off)) return off;

        // Hotbar next
        for (int i = 0; i < 9; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (isCage(st)) return st;
        }

        // Rest of inventory
        for (int i = 9; i < player.getInventory().items.size(); i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (isCage(st)) return st;
        }
        return ItemStack.EMPTY;
    }

    public static boolean isCage(@NotNull ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof SoulCageItem;
    }

    // ----- Tooltip (NeoForge/Mojang 1.21.x uses Item.TooltipContext) -----

    @Override
    public void appendHoverText(ItemStack stack,
                                @Nullable Item.TooltipContext ctx,
                                List<Component> tooltip,
                                TooltipFlag flags) {
        int total = getTotal(stack);
        tooltip.add(Component.translatable("item.infiniteupgrades.soul_cage.total",
                        Component.literal(String.valueOf(total)).withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, ctx, tooltip, flags);
    }
}
