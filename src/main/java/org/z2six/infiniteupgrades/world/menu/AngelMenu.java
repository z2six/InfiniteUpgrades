// File: src/main/java/org/z2six/infiniteupgrades/world/menu/AngelMenu.java
package org.z2six.infiniteupgrades.world.menu;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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
import org.z2six.infiniteupgrades.world.blockentity.SigilBlockEntity;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AngelMenu extends AbstractContainerMenu {
    private static final Logger LOG = LogUtils.getLogger();

    private static final Set<Holder<Attribute>> COMBAT_ATTRS = Set.of(
            Attributes.ATTACK_DAMAGE,
            Attributes.ATTACK_SPEED,
            Attributes.ARMOR,
            Attributes.ARMOR_TOUGHNESS,
            Attributes.KNOCKBACK_RESISTANCE
    );

    private static final Pattern PLUS_SUFFIX = Pattern.compile("\\s+\\+(\\d+)$");

    private final Inventory playerInv;
    private final SigilBlockEntity sigil;            // may be null only in fallback ctor
    private final Container beInv;                   // be inventory: 0=item, 1=resource
    private final SimpleContainer ghostOut = new SimpleContainer(1); // slot 2 preview only
    private boolean suppressPreviewUpdate = false;
    private int previewChancePermille = 0;

    // Normal path: from BE
    public AngelMenu(int id, Inventory inv, SigilBlockEntity sigil) {
        super(ModMenus.ANGEL_MENU.get(), id);
        this.playerInv = inv;
        this.sigil = sigil;
        this.beInv = sigil.getInventory();
        setupSlots();
        updatePreview();
    }

    // Fallback (shouldn’t be used in practice; keeps old behavior if buffer missing)
    public AngelMenu(int id, Inventory inv) {
        super(ModMenus.ANGEL_MENU.get(), id);
        this.playerInv = inv;
        this.sigil = null;
        this.beInv = new SimpleContainer(2); // ephemeral
        setupSlots();
        updatePreview();
    }

    // Buffer ctor retained for compatibility; not used since we read BE in ModMenus
    public AngelMenu(int id, Inventory inv, FriendlyByteBuf buf) {
        this(id, inv);
    }

    private void setupSlots() {
        try {
            // Inputs (BE-backed)
            this.addSlot(new Slot(beInv, 0, 44, 35) {
                @Override public boolean mayPlace(ItemStack stack) { return isCombatItem(stack); }
            });
            this.addSlot(new Slot(beInv, 1, 62, 35) {
                @Override public boolean mayPlace(ItemStack stack) { return stack.is(Items.IRON_INGOT); }
            });

            // Output (ghost)
            this.addSlot(new Slot(ghostOut, 0, 120, 35) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean mayPickup(Player player) { return false; }
            });

            // Player inventory
            int startY = 84;
            for (int row = 0; row < 3; ++row) {
                for (int col = 0; col < 9; ++col) {
                    this.addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, startY + row * 18));
                }
            }
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(playerInv, col, 8 + col * 18, startY + 58));
            }
        } catch (Throwable t) {
            LOG.error("[AngelMenu] setupSlots failed: {}", t.toString());
        }
    }

    // Distance/validity: 8 blocks radius around the sigil (if present), else always valid
    @Override
    public boolean stillValid(Player player) {
        if (sigil == null) return true;
        BlockPos pos = sigil.getBlockPos();
        return player.distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5
        ) <= 64.0;
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        updatePreview();
        // Ensure BE persistence on server if the inputs changed
        if (sigil != null && !playerInv.player.level().isClientSide) {
            sigil.setChanged();
        }
    }

    public int getPreviewChancePermille() { return previewChancePermille; }

    // --- Shift-click rules (only between player inv and beInv) ---
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        try {
            ItemStack empty = ItemStack.EMPTY;
            Slot slot = this.slots.get(index);
            if (slot == null || !slot.hasItem()) return empty;

            ItemStack stack = slot.getItem();
            ItemStack copy = stack.copy();

            // Indexes: 0=item (be), 1=ingot (be), 2=ghost, 3.. = player
            if (index == 2) return ItemStack.EMPTY; // ghost

            if (index >= 3) {
                // From player -> BE inputs
                if (isCombatItem(stack)) {
                    if (!this.moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
                } else if (stack.is(Items.IRON_INGOT)) {
                    if (!this.moveItemStackTo(stack, 1, 2, false)) return ItemStack.EMPTY;
                } else {
                    return ItemStack.EMPTY;
                }
            } else {
                // From BE inputs -> player
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

    // ---- Preview (client-only ghost) ----

    private void updatePreview() {
        try {
            ItemStack in = beInv.getItem(0);
            ItemStack res = beInv.getItem(1);

            if (in.isEmpty() || !isCombatItem(in) || res.isEmpty() || !res.is(Items.IRON_INGOT)) {
                withPreviewSuppressed(() -> {
                    ghostOut.setItem(0, ItemStack.EMPTY);
                    previewChancePermille = 0;
                });
                return;
            }

            Pair<Component, Integer> baseAndLevel = parseBaseNameAndLevel(in.getHoverName());
            int currentLevel = baseAndLevel.getSecond();
            int nextLevel = currentLevel + 1;

            if (nextLevel > Config.TUNING.maxLevel) {
                withPreviewSuppressed(() -> {
                    ghostOut.setItem(0, ItemStack.EMPTY);
                    previewChancePermille = 0;
                });
                return;
            }

            double chance = Config.TUNING.chanceForNextLevel(currentLevel);
            previewChancePermille = (int)Math.round(chance * 1000.0);

            double step = Config.TUNING.percentBonusForLevelUp(currentLevel);
            double factor = 1.0 + step;

            ItemStack preview = in.copy();

            // Name: Base +N
            Component pretty = Component.literal(stripPlusSuffix(in.getHoverName().getString()))
                    .append(Component.literal(" +" + nextLevel).withStyle(ChatFormatting.AQUA));
            preview.set(DataComponents.CUSTOM_NAME, pretty);

            // Scale attribute component
            ItemAttributeModifiers cur = preview.getAttributeModifiers();
            ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();

            for (Entry e : cur.modifiers()) {
                Holder<Attribute> attr = e.attribute();
                AttributeModifier mod = e.modifier();
                if (!isCombatAttr(attr) || mod == null) {
                    builder.add(attr, mod, e.slot());
                    continue;
                }
                double scaled = mod.amount() * factor;
                AttributeModifier scaledMod = new AttributeModifier(mod.id(), scaled, mod.operation());
                builder.add(attr, scaledMod, e.slot());
            }

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

            // Mark ghost
            CustomData cd = preview.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CustomData updated = cd.update(tag -> {
                tag.putBoolean("iu_preview", true);
                tag.putDouble("iu_step", step);
            });
            preview.set(DataComponents.CUSTOM_DATA, updated);

            withPreviewSuppressed(() -> ghostOut.setItem(0, preview));
        } catch (Throwable t) {
            LOG.error("[AngelMenu] updatePreview failed: {}", t.toString());
            withPreviewSuppressed(() -> {
                ghostOut.setItem(0, ItemStack.EMPTY);
                previewChancePermille = 0;
            });
        }
    }

    private void withPreviewSuppressed(Runnable r) {
        boolean prev = suppressPreviewUpdate;
        suppressPreviewUpdate = true;
        try { r.run(); } finally { suppressPreviewUpdate = prev; }
    }

    // ---- Filtering helpers ----

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

    // ---- Name parsing ----

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

    private static String stripPlusSuffix(String s) {
        Matcher m = PLUS_SUFFIX.matcher(s);
        if (m.find()) return s.substring(0, m.start());
        return s;
    }
}
