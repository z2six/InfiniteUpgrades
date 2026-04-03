package org.z2six.infiniteupgrades.core.util;

import com.google.common.collect.Multimap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ItemAttributeHelper {
    private ItemAttributeHelper() {}

    public record Entry(Attribute attribute, ResourceLocation attributeId, AttributeModifier modifier, EquipmentSlot slot) {}

    public static List<Entry> getCurrentEntries(ItemStack stack) {
        List<Entry> out = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            addEntries(out, stack.getAttributeModifiers(slot), slot);
        }
        return dedupe(out);
    }

    public static List<Entry> getDefaultEntries(ItemStack stack) {
        List<Entry> out = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            addEntries(out, stack.getItem().getDefaultAttributeModifiers(slot), slot);
        }
        return dedupe(out);
    }

    public static List<Entry> mergeUnique(List<Entry> current, List<Entry> defaults) {
        List<Entry> out = new ArrayList<>(current);
        for (Entry entry : defaults) {
            if (!contains(out, entry)) {
                out.add(entry);
            }
        }
        return out;
    }

    public static void writeEntries(ItemStack stack, List<Entry> entries) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        stack.removeTagKey("AttributeModifiers");
        if (entries == null || entries.isEmpty()) {
            return;
        }

        for (Entry entry : entries) {
            if (entry == null || entry.attribute() == null || entry.modifier() == null || entry.slot() == null) {
                continue;
            }
            stack.addAttributeModifier(entry.attribute(), entry.modifier(), entry.slot());
        }
    }

    public static ResourceLocation idOf(Attribute attribute) {
        return attribute == null ? null : ForgeRegistries.ATTRIBUTES.getKey(attribute);
    }

    private static void addEntries(List<Entry> out, Multimap<Attribute, AttributeModifier> modifiers, EquipmentSlot slot) {
        for (var mapEntry : modifiers.entries()) {
            Attribute attribute = mapEntry.getKey();
            AttributeModifier modifier = mapEntry.getValue();
            ResourceLocation id = idOf(attribute);
            if (attribute == null || modifier == null || id == null) {
                continue;
            }
            out.add(new Entry(attribute, id, modifier, slot));
        }
    }

    private static List<Entry> dedupe(List<Entry> entries) {
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Entry entry : entries) {
            String key = key(entry);
            if (seen.add(key)) {
                out.add(entry);
            }
        }
        return out;
    }

    private static boolean contains(List<Entry> entries, Entry wanted) {
        String wantedKey = key(wanted);
        for (Entry entry : entries) {
            if (Objects.equals(wantedKey, key(entry))) {
                return true;
            }
        }
        return false;
    }

    private static String key(Entry entry) {
        if (entry == null || entry.attributeId() == null || entry.modifier() == null || entry.slot() == null) {
            return "";
        }
        return entry.attributeId() + "|" + entry.modifier().getId() + "|" + entry.modifier().getOperation() + "|" + entry.slot();
    }
}
