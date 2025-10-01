// File: src/main/java/org/z2six/infiniteupgrades/capability/ModAttachments.java
package org.z2six.infiniteupgrades.capability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.z2six.infiniteupgrades.Infiniteupgrades;

import java.util.function.Supplier;

/**
 * Registers player attachments used by the Angel/Demon workstation.
 *
 * We keep two tiny, serializable attachments (one for ANGEL, one for DEMON),
 * each holding EXACTLY three ItemStacks that mirror the menu's 3 slots:
 *   0 = input item, 1 = resource item(s), 2 = output/result
 *
 * Why two attachments instead of a single struct with both?
 * - Keeps S/R very simple and avoids guessing method names on a separate storage class.
 * - Lets the menu pick "which one" based solely on RitualType.
 */
public final class ModAttachments {

    private ModAttachments() {}

    // Deferred register for AttachmentType<?>
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Infiniteupgrades.MODID);

    /**
     * A tiny immutable record that holds three stacks.
     * We provide a CODEC so the attachment can be persisted by NeoForge automatically.
     */
    public static final class RitualSlots {
        private final ItemStack s0;
        private final ItemStack s1;
        private final ItemStack s2;

        public RitualSlots(ItemStack s0, ItemStack s1, ItemStack s2) {
            this.s0 = (s0 == null ? ItemStack.EMPTY : s0);
            this.s1 = (s1 == null ? ItemStack.EMPTY : s1);
            this.s2 = (s2 == null ? ItemStack.EMPTY : s2);
        }

        public ItemStack s0() { return s0; }
        public ItemStack s1() { return s1; }
        public ItemStack s2() { return s2; }

        public static final Codec<RitualSlots> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ItemStack.CODEC.optionalFieldOf("s0", ItemStack.EMPTY).forGetter(RitualSlots::s0),
                ItemStack.CODEC.optionalFieldOf("s1", ItemStack.EMPTY).forGetter(RitualSlots::s1),
                ItemStack.CODEC.optionalFieldOf("s2", ItemStack.EMPTY).forGetter(RitualSlots::s2)
        ).apply(instance, RitualSlots::new));
    }

    /**
     * Default factory: empty three-slot value.
     */
    private static RitualSlots emptySlots() {
        return new RitualSlots(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
    }

    // Angel-side ritual slots
    public static final Supplier<AttachmentType<RitualSlots>> ANGEL_RITUAL_SLOTS =
            ATTACHMENTS.register("angel_ritual_slots",
                    () -> AttachmentType.builder(ModAttachments::emptySlots)
                            .serialize(RitualSlots.CODEC)
                            .build());

    // Demon-side ritual slots
    public static final Supplier<AttachmentType<RitualSlots>> DEMON_RITUAL_SLOTS =
            ATTACHMENTS.register("demon_ritual_slots",
                    () -> AttachmentType.builder(ModAttachments::emptySlots)
                            .serialize(RitualSlots.CODEC)
                            .build());

    /** Call from your mod init to wire up the register. */
    public static void register(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
    }

    // --- Convenience helpers (optional) ---

    /** Clears both attachments for the given player (server-side). */
    public static void clearAll(Player player) {
        if (player == null || player.level().isClientSide) return;
        player.setData(ANGEL_RITUAL_SLOTS.get(), emptySlots());
        player.setData(DEMON_RITUAL_SLOTS.get(), emptySlots());
    }
}
