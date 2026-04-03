package org.z2six.infiniteupgrades.core.registry;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;

public final class ModSounds {
    private ModSounds() {}

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, Infiniteupgrades.MODID);

    public static final RegistryObject<SoundEvent> INFUSE_SUCCESS =
            SOUND_EVENTS.register("infuse_success",
                    () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(Infiniteupgrades.MODID, "infuse_success")));

    public static final RegistryObject<SoundEvent> INFUSE_FAIL =
            SOUND_EVENTS.register("infuse_fail",
                    () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(Infiniteupgrades.MODID, "infuse_fail")));
}
