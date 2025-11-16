// MainFile: src/main/java/org/z2six/infiniteupgrades/feature/infusion/logic/ToolSpeedUtil.java
package org.z2six.infiniteupgrades.feature.infusion.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.slf4j.Logger;

/**
 * Utility for the custom "Block Speed +X%" stat.
 *
 * NBT schema (fractions, not percents):
 *   iu_upgrade {
 *     tool_speed_bonus: double   // fraction (e.g., 0.15 == +15%)
 *   }
 *
 * This does NOT change mining speed yet; it only stores/reads the stat and
 * helps UI/tooltip code display it consistently.
 */
public final class ToolSpeedUtil {
    private static final Logger LOG = LogUtils.getLogger();

    public static final String ROOT = "iu_upgrade";
    public static final String KEY_TOOL_BONUS = "tool_speed_bonus"; // fraction

    private ToolSpeedUtil() {}

    /** Detect vanilla & most modded tools: pickaxe/shovel/axe/hoe tags; fallback DiggerItem. */
    public static boolean isMiningTool(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        try {
            if (stack.is(ItemTags.PICKAXES)) return true;
            if (stack.is(ItemTags.SHOVELS))  return true;
            if (stack.is(ItemTags.AXES))     return true;
            if (stack.is(ItemTags.HOES))     return true;
            return stack.getItem() instanceof DiggerItem;
        } catch (Throwable t) {
            LOG.error("[ToolSpeedUtil] isMiningTool failed: {}", t.toString());
            return false;
        }
    }

    /** Read fraction (0.15 == +15%). Returns 0.0 if missing or invalid. */
    public static double getBonus(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0.0;
        try {
            CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
            if (cd == null) return 0.0;
            CompoundTag up = cd.copyTag().getCompound(ROOT);
            if (!up.contains(KEY_TOOL_BONUS)) return 0.0;
            return up.getDouble(KEY_TOOL_BONUS);
        } catch (Throwable t) {
            LOG.error("[ToolSpeedUtil] getBonus failed: {}", t.toString());
            return 0.0;
        }
    }

    /** Write fraction (0.15 == +15%). Non-destructive to other iu_upgrade keys. */
    public static void setBonus(ItemStack stack, double fraction) {
        if (stack == null || stack.isEmpty()) return;
        try {
            double v = Double.isFinite(fraction) ? fraction : 0.0;
            CustomData cd = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CustomData updated = cd.update(tag -> {
                CompoundTag up = tag.getCompound(ROOT);
                up.putDouble(KEY_TOOL_BONUS, v);
                tag.put(ROOT, up);
            });
            stack.set(DataComponents.CUSTOM_DATA, updated);
        } catch (Throwable t) {
            LOG.error("[ToolSpeedUtil] setBonus failed: {}", t.toString());
        }
    }

    /** Format UI percent string WITHOUT sign; the caller adds '+' if desired. */
    public static String formatPercentNoSign(double fraction) {
        // 0.15 -> "15%"
        double pct = fraction * 100.0;
        String s = String.format(java.util.Locale.ROOT, "%.1f", pct);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + "%";
    }
}
