// MainFile: src/main/java/org/z2six/infiniteupgrades/world/menu/AngelMenu.java
package org.z2six.infiniteupgrades.world.menu;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
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
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.registry.ModMenus;

import java.util.Set;

/**
 * Vanilla-style angel menu:
 *  - Slot 0: weapon/armor input (filtered)
 *  - Slot 1: resource input (iron ingot for now)
 *  - Slot 2: output (no insert)
 *
 * No processing/progress yet (next step). Includes defensive logging.
 */
public class AngelMenu extends AbstractContainerMenu {
    private static final Logger LOG = LogUtils.getLogger();

    // Treat these attributes as "combat" for filtering (weapons + armor)
    private static final Set<Holder<Attribute>> COMBAT_ATTRS = Set.of(
            Attributes.ATTACK_DAMAGE,
            Attributes.ATTACK_SPEED,
            Attributes.ATTACK_KNOCKBACK,     // new: melee knockback on some gear/mods
            Attributes.ARMOR,
            Attributes.ARMOR_TOUGHNESS,
            Attributes.KNOCKBACK_RESISTANCE  // armor (e.g., netherite) uses this
    );

    // Backing inventory: 3 slots
    private final Container baseInv = new SimpleContainer(3) {
        @Override public void setChanged() {
            super.setChanged();
            try { AngelMenu.this.slotsChanged(this); }
            catch (Throwable t) { LOG.error("[AngelMenu] slotsChanged dispatch failed: {}", t.toString()); }
        }
    };

    // Standard constructor (server + client)
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

            // Output
            this.addSlot(new Slot(baseInv, 2, 120, 35) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean mayPickup(Player player) { return !getItem().isEmpty(); }
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
        } catch (Throwable t) {
            LOG.error("[AngelMenu] ctor failed: {}", t.toString());
        }
    }

    // Network constructor (buffer currently unused, but needed for client sync)
    public AngelMenu(int id, Inventory inv, FriendlyByteBuf buf) {
        this(id, inv);
    }

    // Shift-click behavior
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        try {
            ItemStack empty = ItemStack.EMPTY;
            Slot slot = this.slots.get(index);
            if (slot == null || !slot.hasItem()) return empty;

            ItemStack stack = slot.getItem();
            ItemStack copy = stack.copy();

            // From output -> player inventory
            if (index == 2) {
                if (!this.moveItemStackTo(stack, 3, 39, true)) return ItemStack.EMPTY;
                slot.onQuickCraft(stack, copy);
                return copy;
            }

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
                // From input -> back to player inventory
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

    @Override
    public boolean stillValid(Player player) {
        return true; // stateless for now
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        // No server processing yet
    }

    // ---- Filtering helpers ---------------------------------------------------------------------

    private static boolean isCombatAttr(Holder<Attribute> attr) {
        return attr != null && COMBAT_ATTRS.contains(attr);
    }

    private static boolean hasCombatAttributes(ItemAttributeModifiers mods) {
        if (mods == null) return false;
        try {
            for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
                Holder<Attribute> attr = e.attribute();
                AttributeModifier mod = e.modifier();
                if (isCombatAttr(attr) && mod != null && Math.abs(mod.amount()) > 1.0E-4) {
                    return true;
                }
            }
        } catch (Throwable t) {
            LOG.error("[AngelMenu] hasCombatAttributes error: {}", t.toString());
        }
        return false;
    }

    private boolean isCombatItem(ItemStack stack) {
        if (stack.isEmpty()) return false;

        // 1) Check attribute component on the stack
        try {
            if (hasCombatAttributes(stack.getAttributeModifiers())) return true;
        } catch (Throwable t) {
            LOG.error("[AngelMenu] component attribute check failed: {}", t.toString());
        }

        // 2) Fallback: use the item's **default modifiers for this stack** (1.21+ API)
        try {
            ItemAttributeModifiers defMods = stack.getItem().getDefaultAttributeModifiers(stack);
            if (hasCombatAttributes(defMods)) return true;
        } catch (Throwable t) {
            LOG.error("[AngelMenu] default attribute check failed: {}", t.toString());
        }

        // 3) Broad class fallback (tools/armor/etc.) to catch older/simple items
        try {
            Item it = stack.getItem();
            if (it instanceof ArmorItem) return true;
            if (it instanceof SwordItem) return true;
            if (it instanceof DiggerItem) return true;
            if (it instanceof TridentItem) return true;
            if (it instanceof BowItem) return true;
            if (it instanceof CrossbowItem) return true;
            if (it instanceof ShieldItem) return true;
        } catch (Throwable t) {
            LOG.error("[AngelMenu] class fallback check failed: {}", t.toString());
        }

        return false;
    }
}
