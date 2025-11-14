// File: src/main/java/org/z2six/infiniteupgrades/feature/souls/item/SoulCageItem.java
package org.z2six.infiniteupgrades.feature.souls.item;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;
import java.util.function.Consumer;

public class SoulCageItem extends Item implements GeoItem {
    private static final Logger LOG = LogUtils.getLogger();
    public static final String NBT_TOTAL = "SoulTotal";

    private final AnimatableInstanceCache geckoCache = GeckoLibUtil.createInstanceCache(this);

    public SoulCageItem(Properties props) {
        super(props);
    }

    // ----- GeckoLib hook for custom renderer -----
    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private final SoulCageItemRenderer renderer = new SoulCageItemRenderer();

            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return renderer;
            }
        });
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geckoCache;
    }

    // Required by GeoAnimatable; no bone anims needed for now.
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // no-op
    }

    // ----- Storage helpers -----
    public static int getTotal(@NotNull ItemStack stack) {
        if (stack.isEmpty()) return 0;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return 0;
        CompoundTag tag = data.copyTag();
        return Math.max(0, tag.getInt(NBT_TOTAL));
    }

    public static int addUnits(@NotNull ItemStack stack, int delta) {
        if (stack.isEmpty() || delta <= 0) return getTotal(stack);

        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = (data != null) ? data.copyTag() : new CompoundTag();

        int now = Math.max(0, tag.getInt(NBT_TOTAL));
        int next = Math.max(0, now + delta);
        tag.putInt(NBT_TOTAL, next);

        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return next;
    }

    /**
     * Returns true if this stack is a Soul Cage and it contains at least the requested
     * number of soul units. Never throws; on any error returns false.
     */
    public static boolean hasAtLeast(@NotNull ItemStack stack, int units) {
        if (units <= 0) return true; // trivial case
        if (!isCage(stack)) return false;

        try {
            int total = getTotal(stack);
            return total >= units;
        } catch (Throwable t) {
            LOG.error("[SoulCageItem] hasAtLeast failed: {}", t.toString());
            return false;
        }
    }

    /**
     * Server-side helper to consume soul units from this cage.
     * Returns true if the requested amount was fully consumed, false if there were not
     * enough souls or an error occurred. On false, the stack is left unchanged.
     */
    public static boolean consumeUnits(@NotNull ItemStack stack, int units) {
        if (units <= 0) return true; // nothing to consume

        if (!isCage(stack)) {
            LOG.warn("[SoulCageItem] consumeUnits called with non-cage stack: {}", stack);
            return false;
        }

        try {
            CustomData data = stack.get(DataComponents.CUSTOM_DATA);
            CompoundTag tag = (data != null) ? data.copyTag() : new CompoundTag();

            int current = Math.max(0, tag.getInt(NBT_TOTAL));
            if (current < units) {
                LOG.debug("[SoulCageItem] Not enough souls to consume: have={}, need={}", current, units);
                return false;
            }

            int remaining = current - units;
            tag.putInt(NBT_TOTAL, remaining);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

            LOG.debug("[SoulCageItem] Consumed {} souls from cage; now remaining={}", units, remaining);
            return true;
        } catch (Throwable t) {
            LOG.error("[SoulCageItem] consumeUnits failed: {}", t.toString());
            return false;
        }
    }

    public static @NotNull ItemStack findAnyCage(@NotNull Player player) {
        ItemStack off = player.getOffhandItem();
        if (isCage(off)) return off;

        for (int i = 0; i < 9; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (isCage(st)) return st;
        }
        for (int i = 9; i < player.getInventory().items.size(); i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (isCage(st)) return st;
        }
        return ItemStack.EMPTY;
    }

    public static boolean isCage(@NotNull ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof SoulCageItem;
    }

    @Override
    public void appendHoverText(ItemStack stack,
                                @Nullable Item.TooltipContext ctx,
                                List<Component> tooltip,
                                TooltipFlag flags) {
        int total = getTotal(stack);
        tooltip.add(Component.translatable("item.infiniteupgrades.soul_cage.total",
                        Component.literal(String.valueOf(total)).withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, ctx, tooltip, flags);
    }
}
