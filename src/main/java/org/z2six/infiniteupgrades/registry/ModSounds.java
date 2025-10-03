// File: src/main/java/org/z2six/infiniteupgrades/registry/ModSounds.java
package org.z2six.infiniteupgrades.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.z2six.infiniteupgrades.Infiniteupgrades;

/**
 * Registers mod SoundEvents so they exist in the registry on the client.
 */
public final class ModSounds {
    // File path: src/main/java/org/z2six/infiniteupgrades/registry/ModSounds.java

    private ModSounds() {}

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, Infiniteupgrades.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> INFUSE_SUCCESS =
            SOUND_EVENTS.register("infuse_success",
                    () -> SoundEvent.createVariableRangeEvent(
                            ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "infuse_success")));

    public static final DeferredHolder<SoundEvent, SoundEvent> INFUSE_FAIL =
            SOUND_EVENTS.register("infuse_fail",
                    () -> SoundEvent.createVariableRangeEvent(
                            ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "infuse_fail")));
}
