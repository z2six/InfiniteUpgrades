// MainFile: src/main/java/org/z2six/infiniteupgrades/feature/infusion/logic/ToolSpeedUtil.java
package org.z2six.infiniteupgrades.feature.infusion.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.util.StackTagUtil;

import java.util.Locale;

/**
 * Utilities for our custom "Block Speed" stat:
 *
 * - Stored as a FRACTIONAL bonus (e.g., 0.15 == +15%) in item CustomData under key {@value #KEY_TOOL_SPEED_BONUS}.
 * - Read/write helpers are null/empty-safe and never throw (they log and return safe defaults).
 * - Mining tool detection uses vanilla item tags (PICKAXES/SHOVELS/AXES/HOES) with a DiggerItem fallback.
 *
 * NOTE:
 *   We intentionally keep this stat separate from vanilla Attribute system. The server applies per-upgrade steps
 *   (and writes canonical history events) in UpgradeService. The value here is the authoritative effective bonus.
 */
public final class ToolSpeedUtil {
    private static final Logger LOG = LogUtils.getLogger();

    /** Top-level key inside the item's CustomData for our block speed fractional bonus. */
    private static final String KEY_TOOL_SPEED_BONUS = "iu_tool_speed_bonus";

    private ToolSpeedUtil() {}

    // ------------------------------------------------------------------------------------------------------------
    // Mining tool detection (no NeoForge ToolActions dependency)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Returns true if the stack should be treated as a mining tool for the purposes of our Block Speed stat.
     * Uses vanilla tags for pickaxe/shovel/axe/hoe; falls back to instanceof DiggerItem (covers modded diggers).
     */
    public static boolean isMiningTool(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        try {
            // Quick explicit checks first (fast-path)
            if (s.is(ItemTags.PICKAXES)) return true;
            if (s.is(ItemTags.SHOVELS))  return true;
            if (s.is(ItemTags.AXES))     return true;
            if (s.is(ItemTags.HOES))     return true;

            // Fallback: broad digger family (includes many modded tools)
            return s.getItem() instanceof DiggerItem;
        } catch (Throwable t) {
            // Never crash the UI / server tick due to tag/mapping oddities; just log once per odd case at info level.
            LOG.debug("[ToolSpeedUtil] isMiningTool failed for {}: {}", safeItemName(s), t.toString());
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Bonus read/write (+ helpers)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Read the current Block Speed FRACTION (e.g., 0.15 == +15%). Missing key returns 0.0.
     */
    public static double getBonus(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0.0;
        try {
            CompoundTag root = StackTagUtil.getTagCopy(stack);
            if (!root.contains(KEY_TOOL_SPEED_BONUS)) return 0.0;
            double v = root.getDouble(KEY_TOOL_SPEED_BONUS);
            if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
            return v;
        } catch (Throwable t) {
            LOG.error("[ToolSpeedUtil] getBonus failed for {}: {}", safeItemName(stack), t.toString());
            return 0.0;
        }
    }

    /**
     * Write the Block Speed FRACTION (e.g., 0.15 == +15%) into the item.
     * Negative values are clamped to 0.0. Very large values are clamped to a sane upper bound.
     *
     * NOTE: This mutates the provided ItemStack (copy before calling if you need a non-destructive preview).
     */
    public static void setBonus(ItemStack stack, double fraction) {
        if (stack == null || stack.isEmpty()) return;
        try {
            double v = sanitizeFraction(fraction);
            StackTagUtil.updateTag(stack, tag -> tag.putDouble(KEY_TOOL_SPEED_BONUS, v));

            LOG.debug("[ToolSpeedUtil] setBonus {} -> {}", safeItemName(stack), fmtPct(v));
        } catch (Throwable t) {
            LOG.error("[ToolSpeedUtil] setBonus failed for {}: {}", safeItemName(stack), t.toString());
        }
    }

    /** Format helper: 0.15 -> "15%" (no sign). */
    public static String formatPercentNoSign(double fraction) {
        try {
            double v = Math.max(0.0, fraction) * 100.0;
            // Round to one decimal if needed; avoid allocations for common integers
            if (Math.abs(v - Math.rint(v)) < 1e-9) {
                return String.format(Locale.ROOT, "%.0f%%", v);
            }
            return String.format(Locale.ROOT, "%.1f%%", v);
        } catch (Throwable ignored) {
            return "0%";
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------------------------------

    private static double sanitizeFraction(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        // Clamp to [0, 1000%] as a very generous upper bound to avoid runaway values.
        if (v < 0.0) v = 0.0;
        if (v > 10.0) v = 10.0;
        return v;
    }

    private static String fmtPct(double fraction) {
        return String.format(Locale.ROOT, "%.3f (=%s)", fraction, formatPercentNoSign(fraction));
    }

    private static String safeItemName(ItemStack s) {
        try {
            if (s.isEmpty()) return "<empty>";
            // Prefer stable registry name, else fallback to class
            var key = s.getItemHolder().unwrapKey();
            if (key.isPresent()) return key.get().location().toString();
            // Literal fallback if above fails
            if (s.getItem() == Items.AIR) return "minecraft:air";
            return s.getItem().getClass().getSimpleName();
        } catch (Throwable ignored) {
            return "<item>";
        }
    }
}
