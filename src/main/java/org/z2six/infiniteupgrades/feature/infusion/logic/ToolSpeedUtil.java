// MainFile: src/main/java/org/z2six/infiniteupgrades/feature/infusion/logic/ToolSpeedUtil.java
package org.z2six.infiniteupgrades.feature.infusion.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import net.neoforged.fml.ModList;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Utilities for the mining speed upgrade stat:
 *
 * - If Apothic Attributes is loaded, stored as apothic_attributes:mining_speed on the item.
 * - Otherwise stored as a FRACTIONAL bonus (e.g., 0.15 == +15%) in item CustomData under key {@value #KEY_TOOL_SPEED_BONUS}.
 * - Read/write helpers are null/empty-safe and never throw (they log and return safe defaults).
 * - Mining tool detection uses vanilla item tags (PICKAXES/SHOVELS/AXES/HOES) with a DiggerItem fallback.
 *
 * NOTE:
 *   The server applies per-upgrade steps (and writes canonical history events) in UpgradeService.
 *   The value here is the authoritative effective bonus for Infinite Upgrades.
 */
public final class ToolSpeedUtil {
    private static final Logger LOG = LogUtils.getLogger();

    /** Top-level key inside the item's CustomData for our fallback block speed fractional bonus. */
    private static final String KEY_TOOL_SPEED_BONUS = "iu_tool_speed_bonus";
    private static final ResourceLocation CUSTOM_BLOCK_SPEED_ID =
            ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "block_speed");
    private static final ResourceLocation APOTHIC_MINING_SPEED_ID =
            ResourceLocation.fromNamespaceAndPath("apothic_attributes", "mining_speed");
    private static final ResourceLocation IU_MINING_SPEED_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "mining_speed");

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
     * Read the current Infinite Upgrades mining speed fraction (e.g., 0.15 == +15%).
     */
    public static double getBonus(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0.0;
        try {
            if (usesApothicMiningSpeed()) {
                return getApothicBonus(stack);
            }

            CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
            if (cd == null) return 0.0;
            CompoundTag root = cd.copyTag();
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
     * Write the Infinite Upgrades mining speed fraction (e.g., 0.15 == +15%) into the item.
     * Negative values are clamped to 0.0. Very large values are clamped to a sane upper bound.
     *
     * NOTE: This mutates the provided ItemStack (copy before calling if you need a non-destructive preview).
     */
    public static void setBonus(ItemStack stack, double fraction) {
        if (stack == null || stack.isEmpty()) return;
        try {
            double v = sanitizeFraction(fraction);

            if (usesApothicMiningSpeed()) {
                setApothicBonus(stack, v);
                clearCustomBonus(stack);
                LOG.debug("[ToolSpeedUtil] set Apothic mining speed {} -> {}", safeItemName(stack), fmtPct(v));
                return;
            }

            CustomData cd = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CustomData updated = cd.update(tag -> {
                // Keep the value top-level (consistent with our other preview keys), not inside iu_upgrade.
                tag.putDouble(KEY_TOOL_SPEED_BONUS, v);
            });
            stack.set(DataComponents.CUSTOM_DATA, updated);

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

    public static ResourceLocation miningSpeedStatId() {
        return usesApothicMiningSpeed() ? APOTHIC_MINING_SPEED_ID : CUSTOM_BLOCK_SPEED_ID;
    }

    public static boolean usesApothicMiningSpeed() {
        try {
            return ModList.get().isLoaded("apothic_attributes") && apothicMiningSpeedHolder() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void clearUpgradeBonus(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        clearCustomBonus(stack);
        clearApothicBonus(stack);
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

    private static double getApothicBonus(ItemStack stack) {
        Holder<Attribute> attr = apothicMiningSpeedHolder();
        if (attr == null) return 0.0;

        double out = 0.0;
        try {
            for (ItemAttributeModifiers.Entry e : stack.getAttributeModifiers().modifiers()) {
                if (e == null || e.modifier() == null) continue;
                if (!attr.equals(e.attribute())) continue;
                if (!IU_MINING_SPEED_MODIFIER_ID.equals(e.modifier().id())) continue;
                out += e.modifier().amount();
            }
        } catch (Throwable t) {
            LOG.error("[ToolSpeedUtil] getApothicBonus failed for {}: {}", safeItemName(stack), t.toString());
        }
        return out;
    }

    private static void setApothicBonus(ItemStack stack, double fraction) {
        Holder<Attribute> attr = apothicMiningSpeedHolder();
        if (attr == null) return;

        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
        try {
            for (ItemAttributeModifiers.Entry e : stack.getAttributeModifiers().modifiers()) {
                if (e == null || e.modifier() == null) continue;
                if (IU_MINING_SPEED_MODIFIER_ID.equals(e.modifier().id())) continue;
                builder.add(e.attribute(), e.modifier(), e.slot());
            }
        } catch (Throwable t) {
            LOG.error("[ToolSpeedUtil] setApothicBonus preserve failed for {}: {}", safeItemName(stack), t.toString());
        }

        if (fraction > 1.0e-12) {
            builder.add(
                    attr,
                    new AttributeModifier(IU_MINING_SPEED_MODIFIER_ID, fraction, AttributeModifier.Operation.ADD_VALUE),
                    EquipmentSlotGroup.MAINHAND
            );
        }
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
    }

    private static void clearApothicBonus(ItemStack stack) {
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
        boolean changed = false;
        try {
            for (ItemAttributeModifiers.Entry e : stack.getAttributeModifiers().modifiers()) {
                if (e == null || e.modifier() == null) continue;
                if (IU_MINING_SPEED_MODIFIER_ID.equals(e.modifier().id())) {
                    changed = true;
                    continue;
                }
                builder.add(e.attribute(), e.modifier(), e.slot());
            }
            if (changed) {
                stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
            }
        } catch (Throwable t) {
            LOG.error("[ToolSpeedUtil] clearApothicBonus failed for {}: {}", safeItemName(stack), t.toString());
        }
    }

    private static Holder<Attribute> apothicMiningSpeedHolder() {
        try {
            return BuiltInRegistries.ATTRIBUTE.getOptional(APOTHIC_MINING_SPEED_ID)
                    .map(BuiltInRegistries.ATTRIBUTE::wrapAsHolder)
                    .orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void clearCustomBonus(ItemStack stack) {
        try {
            CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
            if (cd == null) return;
            CustomData cleaned = cd.update(tag -> tag.remove(KEY_TOOL_SPEED_BONUS));
            stack.set(DataComponents.CUSTOM_DATA, cleaned);
        } catch (Throwable t) {
            LOG.error("[ToolSpeedUtil] clearCustomBonus failed for {}: {}", safeItemName(stack), t.toString());
        }
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
