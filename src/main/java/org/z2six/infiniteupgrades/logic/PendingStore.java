// File: src/main/java/org/z2six/infiniteupgrades/logic/PendingStore.java
package org.z2six.infiniteupgrades.logic;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.capability.ModAttachments;

import java.util.Optional;

/**
 * Server-only persistence for a single pending infusion attempt.
 * Stored in the player's persistent NBT under key "iu_pending".
 * When the timer elapses, the finalized result is written to the player's ritual-slots attachment (slot 2).
 *
 * IMPORTANT CHANGE:
 * - ItemStack round-trip now uses ItemStack.CODEC with RegistryOps (components-safe on 1.21.x),
 *   instead of ItemStack.save(...) + ItemStack.parse(...). This fixes empty result on finalize.
 */
public final class PendingStore {
    private static final Logger LOG = LogUtils.getLogger();

    private static final String ROOT_KEY = "iu_pending";

    // NBT keys
    private static final String K_ACTIVE = "active";
    private static final String K_END    = "end";
    private static final String K_DUR    = "dur";
    private static final String K_SUCC   = "succ";
    private static final String K_RITUAL = "rit";  // store as String name()
    private static final String K_POS    = "pos";
    private static final String K_ORIG   = "orig";
    private static final String K_UPG    = "upg";

    public record Snapshot(
            boolean active,
            long end,
            int duration,
            boolean success,
            RitualType ritual,
            BlockPos anchorPos,
            ItemStack original,
            ItemStack upgradedIfSuccess
    ) {}

    private PendingStore() {}

    /** Registry/provider for codecs. */
    private static HolderLookup.Provider provider(ServerPlayer sp) {
        return sp.registryAccess();
    }

    /** Create RegistryOps over NbtOps with the given provider (components-aware). */
    private static DynamicOps<Tag> ops(HolderLookup.Provider provider) {
        return RegistryOps.create(NbtOps.INSTANCE, provider);
    }

    /** Encode an ItemStack to NBT using the 1.21.x CODEC path (components-safe). */
    private static CompoundTag encodeStack(HolderLookup.Provider provider, ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return new CompoundTag();
            DataResult<Tag> r = ItemStack.CODEC.encodeStart(ops(provider), stack);
            Tag t = r.result().orElseGet(CompoundTag::new);
            if (t instanceof CompoundTag ct) return ct;
            // Unusual but safe: wrap non-compound into a compound holder
            CompoundTag holder = new CompoundTag();
            holder.put("_v", t);
            holder.putBoolean("_wrap", true);
            return holder;
        } catch (Throwable e) {
            LOG.error("[PendingStore] encodeStack failed", e);
            return new CompoundTag();
        }
    }

    /** Decode an ItemStack from NBT using the 1.21.x CODEC path (components-safe). */
    private static ItemStack decodeStack(HolderLookup.Provider provider, CompoundTag tag) {
        try {
            if (tag == null || tag.isEmpty()) return ItemStack.EMPTY;
            Tag payload = tag;
            if (tag.contains("_wrap")) {
                payload = tag.get("_v");
                if (payload == null) return ItemStack.EMPTY;
            }
            DataResult<ItemStack> r = ItemStack.CODEC.parse(ops(provider), payload);
            return r.result().orElse(ItemStack.EMPTY);
        } catch (Throwable e) {
            LOG.error("[PendingStore] decodeStack failed", e);
            return ItemStack.EMPTY;
        }
    }

    // --------- API ---------

    /** Read current pending state from player's persistent NBT. */
    public static Snapshot read(ServerPlayer sp) {
        CompoundTag root = sp.getPersistentData().getCompound(ROOT_KEY);
        if (root.isEmpty() || !root.getBoolean(K_ACTIVE)) {
            return new Snapshot(false, 0L, 0, false, RitualType.ANGEL, BlockPos.ZERO, ItemStack.EMPTY, ItemStack.EMPTY);
        }

        boolean succ   = root.getBoolean(K_SUCC);
        long    end    = root.getLong(K_END);
        int     dur    = root.getInt(K_DUR);

        // Ritual stored as String to avoid custom id plumbing
        String ritName = root.getString(K_RITUAL);
        RitualType rit = RitualType.ANGEL;
        if (!ritName.isEmpty()) {
            try { rit = RitualType.valueOf(ritName); } catch (IllegalArgumentException ignored) {}
        }

        CompoundTag posTag = root.getCompound(K_POS);
        BlockPos pos = new BlockPos(posTag.getInt("x"), posTag.getInt("y"), posTag.getInt("z"));

        HolderLookup.Provider prov = provider(sp);

        ItemStack orig = ItemStack.EMPTY;
        if (root.contains(K_ORIG, Tag.TAG_COMPOUND)) {
            orig = decodeStack(prov, root.getCompound(K_ORIG));
        }

        ItemStack upg = ItemStack.EMPTY;
        if (root.contains(K_UPG, Tag.TAG_COMPOUND)) {
            upg = decodeStack(prov, root.getCompound(K_UPG));
        }

        return new Snapshot(true, end, dur, succ, rit, pos, orig, upg);
    }

    /** Arm a new pending attempt; overwrites any existing entry. */
    public static void arm(ServerPlayer sp,
                           long end,
                           int durationTicks,
                           boolean success,
                           RitualType ritual,
                           BlockPos anchorPos,
                           ItemStack originalCopy,
                           ItemStack upgradedIfSuccess) {
        try {
            HolderLookup.Provider prov = provider(sp);

            CompoundTag root = new CompoundTag();
            root.putBoolean(K_ACTIVE, true);
            root.putLong(K_END, end);
            root.putInt(K_DUR, durationTicks);
            root.putBoolean(K_SUCC, success);
            root.putString(K_RITUAL, ritual.name());

            CompoundTag pos = new CompoundTag();
            pos.putInt("x", anchorPos.getX());
            pos.putInt("y", anchorPos.getY());
            pos.putInt("z", anchorPos.getZ());
            root.put(K_POS, pos);

            // Serialize stacks via CODEC (components-safe)
            if (originalCopy != null && !originalCopy.isEmpty()) {
                root.put(K_ORIG, encodeStack(prov, originalCopy));
            }
            if (upgradedIfSuccess != null && !upgradedIfSuccess.isEmpty()) {
                root.put(K_UPG, encodeStack(prov, upgradedIfSuccess));
            }

            sp.getPersistentData().put(ROOT_KEY, root);
            LOG.debug("[PendingStore] armed: end={} dur={} succ={} ritual={} pos={}", end, durationTicks, success, ritual, anchorPos);
        } catch (Throwable t) {
            LOG.error("[PendingStore] arm failed", t);
        }
    }

    /** Clear the pending entry. */
    private static void clear(ServerPlayer sp) {
        sp.getPersistentData().remove(ROOT_KEY);
    }

    /**
     * If ready (now >= end), finalize:
     *  - success: result = upgradedIfSuccess (already computed)
     *  - fail:    result = downgrade(pending original)
     * Writes the result to the ritual-slots attachment slot 2 (never as preview).
     * Returns:
     *   null   -> not ready / nothing finalized
     *   true   -> finalized success
     *   false  -> finalized fail (or failed with error; entry cleared)
     */
    public static Boolean finalizeIfReady(ServerPlayer sp, long now) {
        Snapshot snap = read(sp);
        if (!snap.active()) return null;
        if (now < snap.end()) return null;

        try {
            ItemStack result;
            if (snap.success()) {
                result = snap.upgradedIfSuccess().copy();
                if (result.isEmpty()) {
                    LOG.warn("[PendingStore] finalize: computed empty result (decoded), falling back to original if available");
                    result = snap.original().isEmpty() ? ItemStack.EMPTY : snap.original().copy();
                }
            } else {
                result = UpgradeService.downgradeLastLevel(snap.original());
            }

            if (result.isEmpty()) {
                LOG.warn("[PendingStore] finalize: result is empty; leaving slot2 empty");
            }

            // Write to ritual-slots attachment (slot2), keep s0/s1 intact
            var type = (snap.ritual() == RitualType.ANGEL)
                    ? ModAttachments.ANGEL_RITUAL_SLOTS.get()
                    : ModAttachments.DEMON_RITUAL_SLOTS.get();

            ModAttachments.RitualSlots cur = sp.getData(type);
            ItemStack s0 = cur != null ? cur.s0().copy() : ItemStack.EMPTY;
            ItemStack s1 = cur != null ? cur.s1().copy() : ItemStack.EMPTY;

            ModAttachments.RitualSlots updated = new ModAttachments.RitualSlots(s0, s1, result);
            sp.setData(type, updated);

            // Clear pending
            clear(sp);

            LOG.debug("[PendingStore] finalize: wrote result to attachment slot2 (ritual={}) success={}", snap.ritual(), snap.success());
            return snap.success();
        } catch (Throwable t) {
            LOG.error("[PendingStore] finalizeIfReady failed", t);
            clear(sp);
            return Boolean.FALSE;
        }
    }
}
