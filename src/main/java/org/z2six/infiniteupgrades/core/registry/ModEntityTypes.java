package org.z2six.infiniteupgrades.core.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.z2six.infiniteupgrades.feature.souls.entity.SoulOrbEntity;

public final class ModEntityTypes {
    private static final Logger LOG = LogUtils.getLogger();

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Infiniteupgrades.MODID);

    public static final RegistryObject<EntityType<SoulOrbEntity>> SOUL_ORB =
            ENTITY_TYPES.register("soul_orb", () -> {
                LOG.debug("[ModEntityTypes] Building EntityType<SoulOrbEntity>");
                ResourceLocation id = new ResourceLocation(Infiniteupgrades.MODID, "soul_orb");
                return EntityType.Builder.<SoulOrbEntity>of(SoulOrbEntity::new, MobCategory.MISC)
                        .sized(0.3f, 0.3f)
                        .clientTrackingRange(32)
                        .updateInterval(1)
                        .build(id.toString());
            });

    private ModEntityTypes() {}
}
