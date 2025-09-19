// MainFile: src/main/java/org/z2six/infiniteupgrades/registry/ModEntityTypes.java
package org.z2six.infiniteupgrades.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.Infiniteupgrades;
import org.z2six.infiniteupgrades.world.AngelEntity;
import org.z2six.infiniteupgrades.world.DemonEntity;

/**
 * Registers all custom entity types for Infinite Upgrades.
 * Angel kept verbatim; Demon appended.
 */
public final class ModEntityTypes {
    // Debug logger
    private static final Logger LOG = LogUtils.getLogger();

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Infiniteupgrades.MODID);

    // -------------------- ANGEL (existing) --------------------
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

    // -------------------- DEMON (new) --------------------
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

    private ModEntityTypes() {}
}
