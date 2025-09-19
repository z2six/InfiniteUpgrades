// MainFile: src/main/java/org/z2six/infiniteupgrades/world/menu/DemonMenu.java
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
import org.z2six.infiniteupgrades.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.logic.ReputationService;
import org.z2six.infiniteupgrades.logic.UpgradeService;
import org.z2six.infiniteupgrades.logic.UpgradeService.Patron;
import org.z2six.infiniteupgrades.registry.ModMenus;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Demon menu — mirrors AngelMenu but:
 *  - Uses Patron.DEMON for rep-aware chance and rep bumps.
 *  - (In a later step) UpgradeService will apply demonRandomMult for actual step magnitudes.
 *
 * NOTE: Requires ModMenus.DEMON_MENU (next step).
 */
public class DemonMenu extends AbstractContainerMenu {
    private static final Logger LOG = LogUtils.getLogger();

    public static final int INPUT1_X = 27;
    public static final int INPUT1_Y = 47;

    public static final int INPUT2_X = 76;
    public static final int INPUT2_Y = 47;

    public static final int OUTPUT_X = 134;
    public static final int OUTPUT_Y = 47;

    private static final int PLAYER_INV_X = 8;
    private static final int PLAYER_INV_Y = 84;
    private static final int HOTBAR_Y     = 142;

    public static final int BUTTON_INFUSE = 0;

    private static final Set<Holder<Attribute>> COMBAT_ATTRS = Set.of(
            Attributes.ATTACK_DAMAGE,
            Attributes.ATTACK_SPEED,
            Attributes.ARMOR,
            Attributes.ARMOR_TOUGHNESS,
            Attributes.KNOCKBACK_RESISTANCE
    );

    private static final Pattern PLUS_SUFFIX = Pattern.compile("\\s+\\+(\\d+)$");

    private boolean suppressPreviewUpdate = false;

    private final Container baseInv = new SimpleContainer(3) {
        @Override public void setChanged() {
            super.setChanged();
            if (suppressPreviewUpdate) return;
            try {
                DemonMenu.this.slotsChanged(this);
            } catch (Throwable t) {
                LOG.error("[DemonMenu] slotsChanged dispatch failed: {}", t.toString());
            }
        }
    };

    private int previewChancePermille = 0;

    public DemonMenu(int id, Inventory inv) {
        super(ModMenus.DEMON_MENU.get(), id);
        try {
            this.addSlot(new Slot(baseInv, 0, INPUT1_X, INPUT1_Y) {
                @Override public boolean mayPlace(ItemStack stack) { return isCombatItem(stack); }
            });
            this.addSlot(new Slot(baseInv, 1, INPUT2_X, INPUT2_Y) {
                @Override public boolean mayPlace(ItemStack stack) { return stack.is(Items.IRON_INGOT); }
            });

            this.addSlot(new Slot(baseInv, 2, OUTPUT_X, OUTPUT_Y) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean mayPickup(Player player) {
                    boolean real = hasRealResult();
                    LOG.debug("[DemonMenu] mayPickup slot2? realResult={}", real);
                    return real;
                }
                @Override public void onTake(Player player, ItemStack stack) {
                    super.onTake(player, stack);
                    LOG.info("[DemonMenu] Player took infused result from output slot");
                    withPreviewSuppressed(() -> baseInv.setItem(2, ItemStack.EMPTY));
                    syncToClient("onTake result");
                    updatePreview();
                }
            });

            for (int row = 0; row < 3; ++row) {
                for (int col = 0; col < 9; ++col) {
                    this.addSlot(new Slot(inv, col + row * 9 + 9, PLAYER_INV_X + col * 18, PLAYER_INV_Y + row * 18));
                }
            }
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(inv, col, PLAYER_INV_X + col * 18, HOTBAR_Y));
            }

            updatePreview();
        } catch (Throwable t) {
            LOG.error("[DemonMenu] ctor failed: {}", t.toString());
        }
    }

    public DemonMenu(int id, Inventory inv, FriendlyByteBuf buf) {
        this(id, inv);
    }

    @Override
    public boolean stillValid(Player player) { return true; }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        LOG.debug("[DemonMenu] slotsChanged: in={}, res={}, out={}, realOut={}",
                baseInv.getItem(0), baseInv.getItem(1), baseInv.getItem(2), hasRealResult());
        updatePreview();
    }

    public int getPreviewChancePermille() { return previewChancePermille; }

    public void clientRecomputePreview() { updatePreview(); }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        LOG.info("[DemonMenu] clickMenuButton id={} (serverSide={})", id, player != null && !player.level().isClientSide);
        if (id == BUTTON_INFUSE) {
            onInfuseButtonPressed(player);
            return true;
        }
        return super.clickMenuButton(player, id);
    }

    public void onInfuseButtonPressed(Player player) {
        try {
            if (player == null || player.level().isClientSide) {
                LOG.warn("[DemonMenu] onInfuseButtonPressed ignored (player null or client-side)");
                return;
            }

            ItemStack in  = baseInv.getItem(0);
            ItemStack res = baseInv.getItem(1);

            if (in.isEmpty() || !isCombatItem(in)) {
                LOG.debug("[DemonMenu] Infuse pressed with invalid/missing combat item");
                return;
            }
            if (res.isEmpty() || !res.is(Items.IRON_INGOT)) {
                LOG.debug("[DemonMenu] Infuse pressed without required resource");
                return;
            }

            int cur = parseBaseNameAndLevel(in.getHoverName()).getSecond();

            // Reputation-aware chance (DEMON patron)
            double rep = ReputationService.get(player, Patron.DEMON);
            double chance = UpgradeService.getSuccessChanceWithReputation(cur, Patron.DEMON, rep);
            boolean success = player.getRandom().nextDouble() < chance;

            if (LOG.isDebugEnabled()) {
                LOG.debug("[DemonMenu] Attempt: level={} patron=DEMON rep={} chance={} outcome={}",
                        cur, round3(rep), round3(chance), success ? "SUCCESS" : "FAIL");
            }

            res.shrink(1);
            baseInv.setItem(1, res);

            // Bump reputation (+demon, -angel)
            try {
                double gain = UpgradeServerConfig.snapshot().repGainPerUpgrade;
                ReputationService.bump(player, Patron.DEMON, gain);
            } catch (Throwable t) {
                LOG.error("[DemonMenu] reputation bump failed: {}", t.toString());
            }

            if (success) {
                UpgradeService.Result r = UpgradeService.tryUpgrade(in, player.getRandom());
                ItemStack upgraded = r.upgraded();
                clearPreviewTags(upgraded);

                LOG.info("[DemonMenu] Infuse SUCCESS at L{} -> placing result into output", cur);
                withPreviewSuppressed(() -> {
                    baseInv.setItem(0, ItemStack.EMPTY);
                    baseInv.setItem(2, upgraded);
                });
                syncToClient("infuse success");
            } else {
                LOG.info("[DemonMenu] Infuse FAILED at L{}", cur);
                syncToClient("infuse fail");
                updatePreview();
            }
        } catch (Throwable t) {
            LOG.error("[DemonMenu] onInfuseButtonPressed failed: {}", t.toString());
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        try {
            ItemStack empty = ItemStack.EMPTY;
            Slot slot = this.slots.get(index);
            if (slot == null || !slot.hasItem()) return empty;

            if (index == 2) {
                if (!hasRealResult()) return ItemStack.EMPTY;
                ItemStack stack = slot.getItem();
                ItemStack copy = stack.copy();
                if (!this.moveItemStackTo(stack, 3, 39, false)) return ItemStack.EMPTY;
                slot.onTake(player, stack);
                return copy;
            }

            ItemStack stack = slot.getItem();
            ItemStack copy = stack.copy();

            if (index >= 3) {
                if (isCombatItem(stack)) {
                    if (!this.moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
                } else if (stack.is(Items.IRON_INGOT)) {
                    if (!this.moveItemStackTo(stack, 1, 2, false)) return ItemStack.EMPTY;
                } else {
                    return ItemStack.EMPTY;
                }
            } else {
                if (!this.moveItemStackTo(stack, 3, 39, false)) return ItemStack.EMPTY;
            }

            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();

            if (stack.getCount() == copy.getCount()) return ItemStack.EMPTY;
            slot.onTake(player, stack);
            return copy;
        } catch (Throwable t) {
            LOG.error("[DemonMenu] quickMoveStack failed: {}", t.toString());
            return ItemStack.EMPTY;
        }
    }

    @Override
    public void removed(Player player) {
        try {
            super.removed(player);
        } catch (Throwable t) {
            LOG.error("[DemonMenu] super.removed threw: {}", t.toString());
        }

        try {
            ItemStack out = baseInv.getItem(2);
            if (isPreview(out)) {
                withPreviewSuppressed(() -> baseInv.setItem(2, ItemStack.EMPTY));
            }
            this.clearContainer(player, baseInv);
            LOG.debug("[DemonMenu] Menu closed; returned items to {}", player != null ? player.getName().getString() : "null-player");
        } catch (Throwable t) {
            LOG.error("[DemonMenu] removed() failed to clear/return items: {}", t.toString());
        }
    }

    private void updatePreview() {
        try {
            if (hasRealResult()) {
                LOG.debug("[DemonMenu] updatePreview skipped (real result present)");
                return;
            }

            ItemStack in = baseInv.getItem(0);
            ItemStack res = baseInv.getItem(1);

            if (in.isEmpty() || !isCombatItem(in) || res.isEmpty() || !res.is(Items.IRON_INGOT)) {
                withPreviewSuppressed(() -> {
                    baseInv.setItem(2, ItemStack.EMPTY);
                    previewChancePermille = 0;
                });
                return;
            }

            Pair<Component, Integer> baseAndLevel = parseBaseNameAndLevel(in.getHoverName());
            int currentLevel = baseAndLevel.getSecond();
            int nextLevel = currentLevel + 1;

            int maxLevel = UpgradeServerConfig.snapshot().maxLevel;
            if (nextLevel > maxLevel) {
                withPreviewSuppressed(() -> {
                    baseInv.setItem(2, ItemStack.EMPTY);
                    previewChancePermille = 0;
                });
                return;
            }

            // Base chance only (no rep) for preview:
            double chance = UpgradeService.getSuccessChance(currentLevel);
            previewChancePermille = (int)Math.round(chance * 1000.0);

            // Reuse preview math identical to Angel (obfuscated): +percent to visible combat attrs
            double step = Config.TUNING.percentBonusForLevelUp(currentLevel);
            double factor = 1.0 + step;

            ItemStack preview = in.copy();
            ChatFormatting lvlColor = UpgradeServerConfig.nameColorForLevel(nextLevel);
            Component pretty = Component.literal(stripPlusSuffix(in.getHoverName().getString()))
                    .append(Component.literal(" +" + nextLevel).withStyle(lvlColor));
            preview.set(DataComponents.CUSTOM_NAME, pretty);

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
                builder.add(attr, new AttributeModifier(mod.id(), scaled, mod.operation()), e.slot());
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

            CustomData cd = preview.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CustomData updated = cd.update(tag -> {
                tag.putBoolean("iu_preview", true);
                tag.putDouble("iu_step", step);
            });
            preview.set(DataComponents.CUSTOM_DATA, updated);

            withPreviewSuppressed(() -> baseInv.setItem(2, preview));
        } catch (Throwable t) {
            LOG.error("[DemonMenu] updatePreview failed: {}", t.toString());
            withPreviewSuppressed(() -> {
                baseInv.setItem(2, ItemStack.EMPTY);
                previewChancePermille = 0;
            });
        }
    }

    private void withPreviewSuppressed(Runnable r) {
        boolean prev = suppressPreviewUpdate;
        suppressPreviewUpdate = true;
        try { r.run(); } finally { suppressPreviewUpdate = prev; }
    }

    private void syncToClient(String reason) {
        try {
            LOG.debug("[DemonMenu] syncToClient: {}", reason);
            this.broadcastChanges();
            try { this.sendAllDataToRemote(); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            LOG.error("[DemonMenu] syncToClient failed: {}", t.toString());
        }
    }

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
            LOG.error("[DemonMenu] clearPreviewTags failed: {}", t.toString());
        }
    }

    private static boolean isCombatAttr(Holder<Attribute> attr) { return attr != null && COMBAT_ATTRS.contains(attr); }

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
            LOG.error("[DemonMenu] isCombatItem failed: {}", t.toString());
        }
        return false;
    }

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

    private static double round3(double d) { return Math.round(d * 1000.0) / 1000.0; }
}
