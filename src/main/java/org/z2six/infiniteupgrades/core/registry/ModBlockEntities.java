// File: src/main/java/org/z2six/infiniteupgrades/core/registry/ModBlockEntities.java
package org.z2six.infiniteupgrades.core.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.z2six.infiniteupgrades.feature.sigil.blockentity.SigilBlockEntity;

public final class ModBlockEntities {
    private static final Logger LOG = LogUtils.getLogger();

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Infiniteupgrades.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SigilBlockEntity>> SIGIL_BE =
            BLOCK_ENTITIES.register("sigil",
                    // UPDATED: make BE valid for BOTH sigil blocks (celestial + unholy)
                    () -> BlockEntityType.Builder.of(
                                    SigilBlockEntity::new,
                                    Infiniteupgrades.CELESTIAL_SIGIL.get(),
                                    Infiniteupgrades.UNHOLY_SIGIL.get()
                            )
                            .build(null));

    private ModBlockEntities() {}

    static {
        LOG.debug("[ModBlockEntities] Static init complete");
    }
}
