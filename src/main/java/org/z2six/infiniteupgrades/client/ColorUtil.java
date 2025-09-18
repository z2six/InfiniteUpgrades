package org.z2six.infiniteupgrades.client;

import net.minecraft.ChatFormatting;

/**
 * Returns a color for the "+N" enhancement suffix based on level ranges.
 *
 * Server-configurable in the future; for now we use sane defaults:
 *   1..4  -> BLUE
 *   5..9  -> LIGHT_PURPLE
 *   10..14 -> GOLD
 *   15+   -> RED
 */
public final class ColorUtil {
    private ColorUtil() {}

    public static ChatFormatting colorForLevel(int lvl) {
        if (lvl <= 0) return ChatFormatting.GRAY;
        if (lvl <= 4)  return ChatFormatting.BLUE;
        if (lvl <= 9)  return ChatFormatting.LIGHT_PURPLE;
        if (lvl <= 14) return ChatFormatting.GOLD;
        return ChatFormatting.RED;
    }
}
