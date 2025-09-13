// MainFile: src/main/java/org/z2six/infiniteupgrades/registry/ModEntityTypes.java
package org.z2six.infiniteupgrades.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.Infiniteupgrades;
import org.z2six.infiniteupgrades.world.AngelEntity;

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
                        .sized(0.6f, 3.6f) // ~zombie width, tall like your future angel (≈ 4 blocks visual, hitbox smaller)
                        .clientTrackingRange(64)
                        .updateInterval(Integer.MAX_VALUE) // it doesn't change; no frequent packets
                        .build(id.toString());
            });

    private ModEntityTypes() {}
}
