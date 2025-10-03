// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/attachment/AngelDemonStorage.java
package org.z2six.infiniteupgrades.feature.infusion.attachment;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.z2six.infiniteupgrades.feature.infusion.logic.RitualType;

/**
 * Per-player persistent storage for the Angel/Demon workstation.
 * - Two ItemStackHandler(2): ANGEL and DEMON
 * - Slot layout (per side): [0]=subject/input, [1]=catalyst
 * - Preview/ghost slots are NOT stored here (keep them transient in the menu/screen).
 */
public final class AngelDemonStorage {

    public static final int SLOT_COUNT = 2;

    private final ItemStackHandler angel = new ItemStackHandler(SLOT_COUNT);
    private final ItemStackHandler demon = new ItemStackHandler(SLOT_COUNT);

    public ItemStackHandler get(RitualType side) {
        return side == RitualType.ANGEL ? angel : demon;
    }

    /** Copy contents from another storage (used by attachments if you want manual copy). */
    public void copyFrom(AngelDemonStorage other, HolderLookup.Provider provider) {
        if (other == null) return;
        this.deserializeNBT(other.serializeNBT(provider), provider);
    }

    // --- NBT (with registry Provider) ---

    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag out = new CompoundTag();
        out.put("angel", angel.serializeNBT(provider));
        out.put("demon", demon.serializeNBT(provider));
        return out;
    }

    public void deserializeNBT(CompoundTag tag, HolderLookup.Provider provider) {
        if (tag == null) return;
        if (tag.contains("angel")) angel.deserializeNBT(provider, tag.getCompound("angel"));
        if (tag.contains("demon")) demon.deserializeNBT(provider, tag.getCompound("demon"));
    }
}
