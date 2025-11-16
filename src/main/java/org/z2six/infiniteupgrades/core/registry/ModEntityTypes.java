// MainFile: src/main/java/org/z2six/infiniteupgrades/core/registry/ModEntityTypes.java
package org.z2six.infiniteupgrades.core.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.z2six.infiniteupgrades.feature.souls.entity.SoulOrbEntity;

/**
 * Central EntityType registry.
 *
 * Angel/Demon entities were removed in step 1. Only Soul Orb remains here.
 */
public final class ModEntityTypes {
    private static final Logger LOG = LogUtils.getLogger();

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Infiniteupgrades.MODID);

    // Soul Orb (billboarded, tiny, client-tracked frequently)
    public static final DeferredHolder<EntityType<?>, EntityType<SoulOrbEntity>> SOUL_ORB =
            ENTITY_TYPES.register("soul_orb", () -> {
                LOG.debug("[ModEntityTypes] Building EntityType<SoulOrbEntity>");
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "soul_orb");
                return EntityType.Builder.<SoulOrbEntity>of(SoulOrbEntity::new, MobCategory.MISC)
                        .sized(0.3f, 0.3f)
                        .clientTrackingRange(32)
                        .updateInterval(1) // frequent updates for smooth interpolation
                        .build(id.toString());
            });

    private ModEntityTypes() {}
}
