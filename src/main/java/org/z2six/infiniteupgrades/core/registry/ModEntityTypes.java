// File: src/main/java/org/z2six/infiniteupgrades/registry/ModEntityTypes.java
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
import org.z2six.infiniteupgrades.feature.angel.entity.AngelEntity;
import org.z2six.infiniteupgrades.feature.demon.entity.DemonEntity;
import org.z2six.infiniteupgrades.feature.souls.entity.SoulOrbEntity; // <-- NEW

public final class ModEntityTypes {
    // Debug logger
    private static final Logger LOG = LogUtils.getLogger();

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Infiniteupgrades.MODID);

    // Real entity, but inert: treat as MISC so it doesn't count toward mob caps
    public static final DeferredHolder<EntityType<?>, EntityType<AngelEntity>> ANGEL =
            ENTITY_TYPES.register("angel", () -> {
                LOG.debug("[ModEntityTypes] Building EntityType<AngelEntity>");
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "angel");
                return EntityType.Builder.<AngelEntity>of(AngelEntity::new, MobCategory.MISC)
                        .sized(0.6f, 3.6f)
                        .clientTrackingRange(64)
                        .updateInterval(Integer.MAX_VALUE)
                        .build(id.toString());
            });

    // NEW: Demon entity type (same footprint/behavioral flags as Angel)
    public static final DeferredHolder<EntityType<?>, EntityType<DemonEntity>> DEMON =
            ENTITY_TYPES.register("demon", () -> {
                LOG.debug("[ModEntityTypes] Building EntityType<DemonEntity>");
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "demon");
                return EntityType.Builder.<DemonEntity>of(DemonEntity::new, MobCategory.MISC)
                        .sized(0.6f, 3.6f)
                        .clientTrackingRange(64)
                        .updateInterval(Integer.MAX_VALUE)
                        .build(id.toString());
            });

    // NEW: Soul Orb (billboarded, tiny, client-tracked frequently)
    public static final DeferredHolder<EntityType<?>, EntityType<SoulOrbEntity>> SOUL_ORB =
            ENTITY_TYPES.register("soul_orb", () -> {
                LOG.debug("[ModEntityTypes] Building EntityType<SoulOrbEntity>");
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Infiniteupgrades.MODID, "soul_orb");
                return EntityType.Builder.<SoulOrbEntity>of(SoulOrbEntity::new, MobCategory.MISC)
                        .sized(0.3f, 0.3f)
                        .clientTrackingRange(32)
                        .updateInterval(2)
                        .build(id.toString());
            });

    private ModEntityTypes() {}
}
