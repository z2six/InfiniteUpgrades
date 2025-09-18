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
import org.z2six.infiniteupgrades.logic.UpgradeService;
import org.z2six.infiniteupgrades.registry.ModMenus;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Angel menu:
 *  - Slot 0: input combat item
 *  - Slot 1: resource (iron ingot)
 *  - Slot 2: OUTPUT/PREVIEW
 *      * Ghost preview while no real result is present.
 *      * Holds REAL infused result on success; user can take it.
 *
 * Important:
 *  - We do NOT track a client/server flag for "resultReady".
 *    Instead, we derive it from the actual content of slot 2:
 *      hasRealResult() = slot2 not empty && !isPreview(slot2)
 *    That way client will never overwrite a real result with a ghost.
 */
public class AngelMenu extends AbstractContainerMenu {
    private static final Logger LOG = LogUtils.getLogger();

    // ---------------- Slot coordinates (GUI-relative) ----------------
    public static final int INPUT1_X = 27;
    public static final int INPUT1_Y = 47;

    public static final int INPUT2_X = 76;
    public static final int INPUT2_Y = 47;

    public static final int OUTPUT_X = 134;
    public static final int OUTPUT_Y = 47;

    // Player inv anchors (vanilla layout)
    private static final int PLAYER_INV_X = 8;
    private static final int PLAYER_INV_Y = 84;
    private static final int HOTBAR_Y     = 142;

    // Button ids
    public static final int BUTTON_INFUSE = 0;

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

    // Prevent recursive preview recomputation while we programmatically write slot 2
    private boolean suppressPreviewUpdate = false;

    // Backing inventory (3 slots) – invokes slotsChanged on edits
    private final Container baseInv = new SimpleContainer(3) {
        @Override public void setChanged() {
            super.setChanged();
            if (suppressPreviewUpdate) return;
            try {
                AngelMenu.this.slotsChanged(this);
            } catch (Throwable t) {
                LOG.error("[AngelMenu] slotsChanged dispatch failed: {}", t.toString());
            }
        }
    };

    // Cached preview chance (permille). Client-only display for now.
    private int previewChancePermille = 0;

    public AngelMenu(int id, Inventory inv) {
        super(ModMenus.ANGEL_MENU.get(), id);
        try {
            // Inputs
            this.addSlot(new Slot(baseInv, 0, INPUT1_X, INPUT1_Y) {
                @Override public boolean mayPlace(ItemStack stack) { return isCombatItem(stack); }
            });
            this.addSlot(new Slot(baseInv, 1, INPUT2_X, INPUT2_Y) {
                @Override public boolean mayPlace(ItemStack stack) { return stack.is(Items.IRON_INGOT); }
            });

            // Output (preview/result)
            this.addSlot(new Slot(baseInv, 2, OUTPUT_X, OUTPUT_Y) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean mayPickup(Player player) {
                    boolean real = hasRealResult();
                    LOG.debug("[AngelMenu] mayPickup slot2? realResult={}", real);
                    return real;
                }
                @Override public void onTake(Player player, ItemStack stack) {
                    super.onTake(player, stack);
                    LOG.info("[AngelMenu] Player took infused result from output slot");
                    withPreviewSuppressed(() -> baseInv.setItem(2, ItemStack.EMPTY));
                    // After taking, recompute ghost preview if inputs still valid
                    syncToClient("onTake result");
                    updatePreview();
                }
            });

            // Player inventory (3 rows)
            for (int row = 0; row < 3; ++row) {
                for (int col = 0; col < 9; ++col) {
                    this.addSlot(new Slot(inv, col + row * 9 + 9, PLAYER_INV_X + col * 18, PLAYER_INV_Y + row * 18));
                }
            }
            // Hotbar
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(inv, col, PLAYER_INV_X + col * 18, HOTBAR_Y));
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
        LOG.debug("[AngelMenu] slotsChanged: in={}, res={}, out={}, realOut={}",
                baseInv.getItem(0), baseInv.getItem(1), baseInv.getItem(2), hasRealResult());
        updatePreview();
    }

    /** Expose preview chance for HUD text if needed later. */
    public int getPreviewChancePermille() { return previewChancePermille; }

    /** Client-only nudge to recompute the ghost preview. */
    public void clientRecomputePreview() {
        updatePreview();
    }

    // ---- Buttons (server) ----------------------------------------------------------------------

    @Override
    public boolean clickMenuButton(Player player, int id) {
        LOG.info("[AngelMenu] clickMenuButton id={} (serverSide={})", id, player != null && !player.level().isClientSide);
        if (id == BUTTON_INFUSE) {
            onInfuseButtonPressed(player);
            return true;
        }
        return super.clickMenuButton(player, id);
    }

    /** Called from server via clickMenuButton. */
    public void onInfuseButtonPressed(Player player) {
        try {
            if (player == null || player.level().isClientSide) {
                LOG.warn("[AngelMenu] onInfuseButtonPressed ignored (player null or client-side)");
                return;
            }

            ItemStack in  = baseInv.getItem(0);
            ItemStack res = baseInv.getItem(1);

            // Validate inputs
            if (in.isEmpty() || !isCombatItem(in)) {
                LOG.debug("[AngelMenu] Infuse pressed with invalid/missing combat item");
                return;
            }
            if (res.isEmpty() || !res.is(Items.IRON_INGOT)) {
                LOG.debug("[AngelMenu] Infuse pressed without required resource");
                return;
            }

            int cur = parseBaseNameAndLevel(in.getHoverName()).getSecond();
            double chance = UpgradeService.getSuccessChance(cur);
            boolean success = player.getRandom().nextDouble() < chance;

            // Always consume one resource on attempt
            res.shrink(1);
            baseInv.setItem(1, res);

            if (success) {
                UpgradeService.Result r = UpgradeService.tryUpgrade(in, player.getRandom());
                ItemStack upgraded = r.upgraded();
                clearPreviewTags(upgraded); // ensure not marked as preview

                LOG.info("[AngelMenu] Infuse SUCCESS at L{} -> placing result into output", cur);
                withPreviewSuppressed(() -> {
                    baseInv.setItem(0, ItemStack.EMPTY); // consume the input item on success
                    baseInv.setItem(2, upgraded);        // put real result into output
                });
                // DO NOT recompute preview; show the real result
                syncToClient("infuse success");
            } else {
                LOG.info("[AngelMenu] Infuse FAILED at L{}", cur);
                // We only consumed resource. Keep input. Recompute ghost preview if still valid.
                syncToClient("infuse fail");
                updatePreview();
            }
        } catch (Throwable t) {
            LOG.error("[AngelMenu] onInfuseButtonPressed failed: {}", t.toString());
        }
    }

    // ---- Shift-click rules ---------------------------------------------------------------------

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        try {
            ItemStack empty = ItemStack.EMPTY;
            Slot slot = this.slots.get(index);
            if (slot == null || !slot.hasItem()) return empty;

            // From OUTPUT (real result) -> player inventory
            if (index == 2) {
                if (!hasRealResult()) return ItemStack.EMPTY; // don't quick-move ghosts
                ItemStack stack = slot.getItem();
                ItemStack copy = stack.copy();
                if (!this.moveItemStackTo(stack, 3, 39, false)) return ItemStack.EMPTY;
                slot.onTake(player, stack);
                return copy;
            }

            ItemStack stack = slot.getItem();
            ItemStack copy = stack.copy();

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

    // ---- Lifecycle: return inputs when the menu closes -----------------------------------------

    @Override
    public void removed(Player player) {
        try {
            super.removed(player);
        } catch (Throwable t) {
            LOG.error("[AngelMenu] super.removed threw: {}", t.toString());
        }

        try {
            // If slot 2 holds a GHOST preview, ensure it isn't returned.
            ItemStack out = baseInv.getItem(2);
            if (isPreview(out)) {
                withPreviewSuppressed(() -> baseInv.setItem(2, ItemStack.EMPTY));
            }

            // Return everything else (including a REAL result if present)
            this.clearContainer(player, baseInv);
            LOG.debug("[AngelMenu] Menu closed; returned items to {}", player != null ? player.getName().getString() : "null-player");
        } catch (Throwable t) {
            LOG.error("[AngelMenu] removed() failed to clear/return items: {}", t.toString());
        }
    }

    // ---- Preview logic (client-safe, no server mutation) ---------------------------------------

    /** Update preview item (slot 2) and cached chance text. */
    private void updatePreview() {
        try {
            // If we’re currently showing a REAL result, do not clobber it with a ghost
            if (hasRealResult()) {
                LOG.debug("[AngelMenu] updatePreview skipped (real result present)");
                return;
            }

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
            double chance = UpgradeService.getSuccessChance(currentLevel);
            previewChancePermille = (int)Math.round(chance * 1000.0);

            // Scale factor for THIS increment (not total)
            double step = Config.TUNING.percentBonusForLevelUp(currentLevel);
            double factor = 1.0 + step;

            // Build GHOST preview: clone input, rewrite name to "Base +next", scale combat modifiers
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

            // Mark as a GHOST preview
            CustomData cd = preview.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CustomData updated = cd.update(tag -> {
                tag.putBoolean("iu_preview", true);
                tag.putDouble("iu_step", step); // informational; the screen may ignore it
            });
            preview.set(DataComponents.CUSTOM_DATA, updated);

            // Put the ghost into the output slot
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

    private void syncToClient(String reason) {
        try {
            LOG.debug("[AngelMenu] syncToClient: {}", reason);
            this.broadcastChanges();
            try {
                // In case full resync is needed on your 1.21 environment
                this.sendAllDataToRemote();
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            LOG.error("[AngelMenu] syncToClient failed: {}", t.toString());
        }
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private boolean hasRealResult() {
        ItemStack out = baseInv.getItem(2);
        return !out.isEmpty() && !isPreview(out);
    }

    private static boolean isPreview(ItemStack stack) {
        try {
            if (stack.isEmpty()) return false;
            CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
            if (cd == null) return false;
            return cd.copyTag().getBoolean("iu_preview");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void clearPreviewTags(ItemStack stack) {
        try {
            if (stack.isEmpty()) return;
            CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
            if (cd == null) return;
            CustomData cleaned = cd.update(tag -> {
                tag.remove("iu_preview");
                tag.remove("iu_step");
            });
            stack.set(DataComponents.CUSTOM_DATA, cleaned);
        } catch (Throwable t) {
            LOG.error("[AngelMenu] clearPreviewTags failed: {}", t.toString());
        }
    }

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
