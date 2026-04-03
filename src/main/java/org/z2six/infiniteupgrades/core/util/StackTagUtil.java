package org.z2six.infiniteupgrades.core.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;

public final class StackTagUtil {
    private StackTagUtil() {}

    public static boolean hasTag(ItemStack stack, String key) {
        return stack != null && !stack.isEmpty() && stack.hasTag() && stack.getTag().contains(key);
    }

    public static CompoundTag getTagCopy(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTag()) {
            return new CompoundTag();
        }
        return stack.getTag().copy();
    }

    public static CompoundTag getSubTagCopy(ItemStack stack, String key) {
        CompoundTag root = getTagCopy(stack);
        return root.contains(key, net.minecraft.nbt.Tag.TAG_COMPOUND) ? root.getCompound(key).copy() : new CompoundTag();
    }

    public static void updateTag(ItemStack stack, Consumer<CompoundTag> mutator) {
        if (stack == null || stack.isEmpty() || mutator == null) {
            return;
        }

        CompoundTag root = getTagCopy(stack);
        mutator.accept(root);

        if (root.isEmpty()) {
            stack.setTag(null);
        } else {
            stack.setTag(root);
        }
    }
}
