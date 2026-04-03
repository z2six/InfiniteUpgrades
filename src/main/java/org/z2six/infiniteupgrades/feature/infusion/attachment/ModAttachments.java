package org.z2six.infiniteupgrades.feature.infusion.attachment;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.z2six.infiniteupgrades.feature.infusion.logic.RitualType;

public final class ModAttachments {
    private static final String ROOT_KEY = Infiniteupgrades.MODID;
    private static final String ANGEL_KEY = "angel_ritual_slots";
    private static final String DEMON_KEY = "demon_ritual_slots";

    private ModAttachments() {}

    public static final class RitualSlots {
        private final ItemStack s0;
        private final ItemStack s1;
        private final ItemStack s2;

        public RitualSlots(ItemStack s0, ItemStack s1, ItemStack s2) {
            this.s0 = s0 == null ? ItemStack.EMPTY : s0;
            this.s1 = s1 == null ? ItemStack.EMPTY : s1;
            this.s2 = s2 == null ? ItemStack.EMPTY : s2;
        }

        public ItemStack s0() { return s0; }
        public ItemStack s1() { return s1; }
        public ItemStack s2() { return s2; }
    }

    public static void register(IEventBus modBus) {
        // No-op on Forge 1.20.1. Ritual slot persistence is backed by player persistent NBT.
    }

    public static RitualSlots get(Player player, RitualType ritual) {
        if (player == null) {
            return emptySlots();
        }

        CompoundTag root = player.getPersistentData().getCompound(ROOT_KEY);
        CompoundTag data = root.getCompound(key(ritual));
        return new RitualSlots(
                data.contains("s0", net.minecraft.nbt.Tag.TAG_COMPOUND) ? ItemStack.of(data.getCompound("s0")) : ItemStack.EMPTY,
                data.contains("s1", net.minecraft.nbt.Tag.TAG_COMPOUND) ? ItemStack.of(data.getCompound("s1")) : ItemStack.EMPTY,
                data.contains("s2", net.minecraft.nbt.Tag.TAG_COMPOUND) ? ItemStack.of(data.getCompound("s2")) : ItemStack.EMPTY
        );
    }

    public static void set(Player player, RitualType ritual, RitualSlots slots) {
        if (player == null || player.level().isClientSide) {
            return;
        }

        CompoundTag root = player.getPersistentData().getCompound(ROOT_KEY);
        CompoundTag data = new CompoundTag();
        writeStack(data, "s0", slots == null ? ItemStack.EMPTY : slots.s0());
        writeStack(data, "s1", slots == null ? ItemStack.EMPTY : slots.s1());
        writeStack(data, "s2", slots == null ? ItemStack.EMPTY : slots.s2());
        root.put(key(ritual), data);
        player.getPersistentData().put(ROOT_KEY, root);
    }

    public static void copy(Player oldPlayer, Player newPlayer) {
        if (oldPlayer == null || newPlayer == null) {
            return;
        }

        CompoundTag root = oldPlayer.getPersistentData().getCompound(ROOT_KEY);
        if (!root.isEmpty()) {
            newPlayer.getPersistentData().put(ROOT_KEY, root.copy());
        }
    }

    public static void clearAll(Player player) {
        if (player == null || player.level().isClientSide) {
            return;
        }
        CompoundTag root = player.getPersistentData().getCompound(ROOT_KEY);
        root.put(ANGEL_KEY, new CompoundTag());
        root.put(DEMON_KEY, new CompoundTag());
        player.getPersistentData().put(ROOT_KEY, root);
    }

    private static String key(RitualType ritual) {
        return ritual == RitualType.DEMON ? DEMON_KEY : ANGEL_KEY;
    }

    private static RitualSlots emptySlots() {
        return new RitualSlots(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
    }

    private static void writeStack(CompoundTag parent, String key, ItemStack stack) {
        if (stack != null && !stack.isEmpty()) {
            parent.put(key, stack.save(new CompoundTag()));
        }
    }
}
