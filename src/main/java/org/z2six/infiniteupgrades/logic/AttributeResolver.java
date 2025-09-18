// MainFile: src/main/java/org/z2six/infiniteupgrades/logic/AttributeResolver.java
package org.z2six.infiniteupgrades.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.slf4j.Logger;

import java.util.*;

/**
 * Collects attributes from an ItemStack and exposes base/current values we can modify.
 *
 * For v1: we aggregate ALL entries (regardless of slot), but the UpgradeService will
 * write back modifiers preserving slot assignments and modifier IDs/op types. We scale
 * amounts only for rules we recognize.
 *
 * Note: We treat the "current" as the sum of modifier amounts for each attribute id.
 */
public final class AttributeResolver {
    private static final Logger LOG = LogUtils.getLogger();

    public record AttrSnapshot(ResourceLocation id, double sum, List<ItemAttributeModifiers.Entry> entries) {}

    public static Map<ResourceLocation, AttrSnapshot> read(ItemStack stack) {
        Map<ResourceLocation, List<ItemAttributeModifiers.Entry>> byAttr = new LinkedHashMap<>();
        try {
            ItemAttributeModifiers comp = stack.getAttributeModifiers();
            if (comp == null || comp.modifiers().isEmpty()) {
                comp = stack.getItem().getDefaultAttributeModifiers(stack);
            }
            for (ItemAttributeModifiers.Entry e : comp.modifiers()) {
                Holder<Attribute> attr = e.attribute();
                if (attr == null || attr.isBound() == false) continue;
                ResourceLocation id = attr.unwrapKey().orElse(null) != null
                        ? attr.unwrapKey().get().location()
                        : null;
                if (id == null) continue;
                byAttr.computeIfAbsent(id, k -> new ArrayList<>()).add(e);
            }
        } catch (Throwable t) {
            LOG.error("[AttributeResolver] read failed: {}", t.toString());
        }

        Map<ResourceLocation, AttrSnapshot> out = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, List<ItemAttributeModifiers.Entry>> en : byAttr.entrySet()) {
            double sum = 0.0;
            for (ItemAttributeModifiers.Entry e : en.getValue()) {
                AttributeModifier mod = e.modifier();
                if (mod != null) sum += mod.amount(); // we ignore operation types for v1; works for ADDITION
            }
            out.put(en.getKey(), new AttrSnapshot(en.getKey(), sum, List.copyOf(en.getValue())));
        }
        return out;
    }

    /** Build a new ItemAttributeModifiers by replacing only amounts for a given attribute id. */
    public static ItemAttributeModifiers rebuildWith(ItemAttributeModifiers current,
                                                     ResourceLocation attrId,
                                                     double scaleFactorOrNewAmount,
                                                     boolean isPercentDelta,
                                                     Map<ResourceLocation, Double> absoluteTargets,
                                                     Map<ResourceLocation, Double> deltaAdds) {
        ItemAttributeModifiers.Builder b = ItemAttributeModifiers.builder();
        try {
            for (ItemAttributeModifiers.Entry e : current.modifiers()) {
                Holder<Attribute> h = e.attribute();
                ResourceLocation id = h != null && h.unwrapKey().isPresent() ? h.unwrapKey().get().location() : null;
                AttributeModifier old = e.modifier();
                if (id == null || old == null) {
                    b.add(h, old, e.slot());
                    continue;
                }

                if (!id.equals(attrId)) {
                    b.add(h, old, e.slot());
                    continue;
                }

                // For v1 we scale every entry proportionally if percent; for additive we add to the first ADDITION-like entry.
                AttributeModifier newMod = old;
                if (isPercentDelta) {
                    double newAmt = old.amount() * scaleFactorOrNewAmount;
                    newMod = new AttributeModifier(old.id(), newAmt, old.operation());
                } else {
                    // additive: distribute evenly across entries with same attr (rare), or add to the first
                    double add = deltaAdds.getOrDefault(attrId, 0.0);
                    double toUse = add;
                    if (Math.abs(add) > 1e-9) {
                        newMod = new AttributeModifier(old.id(), old.amount() + toUse, old.operation());
                        // zero-out so we don't add multiple times if more than one entry
                        deltaAdds.put(attrId, 0.0);
                    }
                }
                b.add(h, newMod, e.slot());
            }
        } catch (Throwable t) {
            LOG.error("[AttributeResolver] rebuildWith failed: {}", t.toString());
            // Fallback to current
            return current;
        }
        return b.build();
    }
}
