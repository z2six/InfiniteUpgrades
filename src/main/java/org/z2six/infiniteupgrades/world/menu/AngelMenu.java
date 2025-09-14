// MainFile: src/main/java/org/z2six/infiniteupgrades/world/menu/AngelMenu.java
package org.z2six.infiniteupgrades.world.menu;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemAttributeModifiers.Entry;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.Config;
import org.z2six.infiniteupgrades.registry.ModMenus;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Angel menu with client-only preview in output slot (index 2).
 * - Slot 0: weapon/armor (filtered by combat attributes/classes)
 * - Slot 1: resource (iron ingot for now)
 * - Slot 2: PREVIEW ONLY (cannot be taken)
 */
public class AngelMenu extends AbstractContainerMenu {
    private static final Logger LOG = LogUtils.getLogger();

    // Consider these as "combat attributes" for filtering & scaling
    private static final Set<Holder<Attribute>> COMBAT_ATTRS = Set.of(
            Attributes.ATTACK_DAMAGE,
            Attributes.ATTACK_SPEED,
            Attributes.ARMOR,
            Attributes.ARMOR_TOUGHNESS,
            Attributes.KNOCKBACK_RESISTANCE
    );

    // Detect trailing " +N" in a name (space-plus-number at end)
    private static final Pattern PLUS_SUFFIX = Pattern.compile("\\s+\\+(\\d+)$");

    // Reentrancy guard: prevent preview writes from recalling slotsChanged -> updatePreview -> ...
    private boolean suppressPreviewUpdate = false;

    // Backing inventory (3 slots) – invokes slotsChanged on edits
    private final Container baseInv = new SimpleContainer(3) {
        @Override public void setChanged() {
            super.setChanged();
            // Skip callback while we are programmatically writing the ghost preview
            if (suppressPreviewUpdate) return;
            try { AngelMenu.this.slotsChanged(this); }
            catch (Throwable t) { LOG.error("[AngelMenu] slotsChanged dispatch failed: {}", t.toString()); }
        }
    };

    // Cached preview chance (permille). Client-only display for now.
    private int previewChancePermille = 0;

    public AngelMenu(int id, Inventory inv) {
        super(ModMenus.ANGEL_MENU.get(), id);
        try {
            // Inputs
            this.addSlot(new Slot(baseInv, 0, 44, 35) {
                @Override public boolean mayPlace(ItemStack stack) { return isCombatItem(stack); }
            });
            this.addSlot(new Slot(baseInv, 1, 62, 35) {
                @Override public boolean mayPlace(ItemStack stack) { return stack.is(Items.IRON_INGOT); }
            });

            // Output (preview) – cannot pick up
            this.addSlot(new Slot(baseInv, 2, 120, 35) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean mayPickup(Player player) { return false; }
            });

            // Player inventory
            int startY = 84;
            for (int row = 0; row < 3; ++row) {
                for (int col = 0; col < 9; ++col) {
                    this.addSlot(new Slot(inv, col + row * 9 + 9, 8 + col * 18, startY + row * 18));
                }
            }
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(inv, col, 8 + col * 18, startY + 58));
            }

            // Initial preview
            updatePreview();
        } catch (Throwable t) {
            LOG.error("[AngelMenu] ctor failed: {}", t.toString());
        }
    }

    public AngelMenu(int id, Inventory inv, FriendlyByteBuf buf) {
        this(id, inv);
    }

    @Override
    public boolean stillValid(Player player) {
        return true; // stateless for now
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        updatePreview();
    }

    /** Public accessor for the screen HUD text. */
    public int getPreviewChancePermille() { return previewChancePermille; }

    // ---- Shift-click rules ---------------------------------------------------------------------

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        try {
            ItemStack empty = ItemStack.EMPTY;
            Slot slot = this.slots.get(index);
            if (slot == null || !slot.hasItem()) return empty;

            ItemStack stack = slot.getItem();
            ItemStack copy = stack.copy();

            // Output is ghost; never move
            if (index == 2) return ItemStack.EMPTY;

            // From player inventory -> inputs
            if (index >= 3) {
                if (isCombatItem(stack)) {
                    if (!this.moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
                } else if (stack.is(Items.IRON_INGOT)) {
                    if (!this.moveItemStackTo(stack, 1, 2, false)) return ItemStack.EMPTY;
                } else {
                    return ItemStack.EMPTY;
                }
            } else {
                // From inputs -> back to player inventory
                if (!this.moveItemStackTo(stack, 3, 39, false)) return ItemStack.EMPTY;
            }

            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();

            if (stack.getCount() == copy.getCount()) return ItemStack.EMPTY;
            slot.onTake(player, stack);
            return copy;
        } catch (Throwable t) {
            LOG.error("[AngelMenu] quickMoveStack failed: {}", t.toString());
            return ItemStack.EMPTY;
        }
    }

    // ---- Preview logic (client-safe, no server mutation) ---------------------------------------

    /** Update preview item (slot 2) and cached chance text. */
    private void updatePreview() {
        try {
            ItemStack in = baseInv.getItem(0);
            ItemStack res = baseInv.getItem(1);

            // Requirements: valid combat item + at least 1 iron ingot
            if (in.isEmpty() || !isCombatItem(in) || res.isEmpty() || !res.is(Items.IRON_INGOT)) {
                withPreviewSuppressed(() -> {
                    baseInv.setItem(2, ItemStack.EMPTY);
                    previewChancePermille = 0;
                });
                return;
            }

            // Determine current level from name (… +N). If none, treat as 0.
            Pair<Component, Integer> baseAndLevel = parseBaseNameAndLevel(in.getHoverName());
            String baseName = baseAndLevel.getFirst().getString();
            int currentLevel = baseAndLevel.getSecond();
            int nextLevel = currentLevel + 1;

            // Respect max level from config
            if (nextLevel > Config.TUNING.maxLevel) {
                withPreviewSuppressed(() -> {
                    baseInv.setItem(2, ItemStack.EMPTY);
                    previewChancePermille = 0;
                });
                return;
            }

            // Chance for current -> next
            double chance = Config.TUNING.chanceForNextLevel(currentLevel);
            previewChancePermille = (int)Math.round(chance * 1000.0);

            // Scale factor for THIS increment (not total)
            double step = Config.TUNING.percentBonusForLevelUp(currentLevel);
            double factor = 1.0 + step;

            // Build preview: clone input, rewrite name to "Base +next", scale combat modifiers
            ItemStack preview = in.copy();

            // Name: Base +N (strip any existing suffix first)
            Component pretty = Component.literal(stripPlusSuffix(in.getHoverName().getString()))
                    .append(Component.literal(" +" + nextLevel).withStyle(ChatFormatting.AQUA));
            preview.set(DataComponents.CUSTOM_NAME, pretty);

            // Attributes: read current modifiers then multiply combat amounts by factor
            ItemAttributeModifiers cur = preview.getAttributeModifiers();
            ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();

            for (Entry e : cur.modifiers()) {
                Holder<Attribute> attr = e.attribute();
                AttributeModifier mod = e.modifier();
                // Keep non-combat entries unchanged
                if (!isCombatAttr(attr) || mod == null) {
                    builder.add(attr, mod, e.slot());
                    continue;
                }
                // Multiply magnitude; preserve id/op/slot
                double scaled = mod.amount() * factor;
                AttributeModifier scaledMod = new AttributeModifier(mod.id(), scaled, mod.operation());
                builder.add(attr, scaledMod, e.slot());
            }

            // If no attribute component existed, try item defaults for this stack
            if (cur.modifiers().isEmpty()) {
                ItemAttributeModifiers def = preview.getItem().getDefaultAttributeModifiers(preview);
                for (Entry e : def.modifiers()) {
                    Holder<Attribute> attr = e.attribute();
                    AttributeModifier mod = e.modifier();
                    if (!isCombatAttr(attr) || mod == null) {
                        builder.add(attr, mod, e.slot());
                    } else {
                        double scaled = mod.amount() * factor;
                        builder.add(attr, new AttributeModifier(mod.id(), scaled, mod.operation()), e.slot());
                    }
                }
            }

            preview.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());

            // Mark as a preview + store step% for the client tooltip
            CustomData cd = preview.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CustomData updated = cd.update(tag -> {
                tag.putBoolean("iu_preview", true);
                tag.putDouble("iu_step", step); // e.g., 0.05 for +5%
            });
            preview.set(DataComponents.CUSTOM_DATA, updated);

            // Put into the ghost output slot (without re-triggering updatePreview)
            withPreviewSuppressed(() -> baseInv.setItem(2, preview));

        } catch (Throwable t) {
            LOG.error("[AngelMenu] updatePreview failed: {}", t.toString());
            withPreviewSuppressed(() -> {
                baseInv.setItem(2, ItemStack.EMPTY);
                previewChancePermille = 0;
            });
        }
    }

    /** Run an action while suppressing setChanged->slotsChanged feedback from our ghost writes. */
    private void withPreviewSuppressed(Runnable r) {
        boolean prev = suppressPreviewUpdate;
        suppressPreviewUpdate = true;
        try { r.run(); } finally { suppressPreviewUpdate = prev; }
    }

    // ---- Filtering ---------------------------------------------------------------------------

    private static boolean isCombatAttr(Holder<Attribute> attr) {
        return attr != null && COMBAT_ATTRS.contains(attr);
    }

    private static boolean hasCombatAttributes(ItemAttributeModifiers mods) {
        if (mods == null) return false;
        for (Entry e : mods.modifiers()) {
            Holder<Attribute> a = e.attribute();
            AttributeModifier m = e.modifier();
            if (isCombatAttr(a) && m != null && Math.abs(m.amount()) > 1.0E-4) return true;
        }
        return false;
    }

    private boolean isCombatItem(ItemStack stack) {
        if (stack.isEmpty()) return false;

        try {
            if (hasCombatAttributes(stack.getAttributeModifiers())) return true;
            ItemAttributeModifiers defs = stack.getItem().getDefaultAttributeModifiers(stack);
            if (hasCombatAttributes(defs)) return true;

            // Class fallback to catch simple tools/armor that might not carry components
            Item it = stack.getItem();
            if (it instanceof ArmorItem) return true;
            if (it instanceof SwordItem) return true;
            if (it instanceof DiggerItem) return true;
            if (it instanceof TridentItem) return true;
            if (it instanceof BowItem) return true;
            if (it instanceof CrossbowItem) return true;
            if (it instanceof ShieldItem) return true;
        } catch (Throwable t) {
            LOG.error("[AngelMenu] isCombatItem failed: {}", t.toString());
        }

        return false;
    }

    // ---- Name parsing helpers ----------------------------------------------------------------

    /** Returns (baseNameComponent, level). If no "+N", level = 0. */
    private static Pair<Component, Integer> parseBaseNameAndLevel(Component name) {
        try {
            String s = name.getString();
            Matcher m = PLUS_SUFFIX.matcher(s);
            if (m.find()) {
                int lvl = Mth.clamp(Integer.parseInt(m.group(1)), 0, 10000);
                String base = s.substring(0, m.start());
                return Pair.of(Component.literal(base), lvl);
            }
        } catch (Throwable ignored) {}
        return Pair.of(name.copy(), 0);
    }

    /** Strip trailing " +N" (if any). */
    private static String stripPlusSuffix(String s) {
        Matcher m = PLUS_SUFFIX.matcher(s);
        if (m.find()) return s.substring(0, m.start());
        return s;
    }
}
