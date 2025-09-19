// MainFile: src/main/java/org/z2six/infiniteupgrades/registry/ModBlockEntities.java
package org.z2six.infiniteupgrades.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.Infiniteupgrades;
import org.z2six.infiniteupgrades.world.blockentity.AngelSigilBlockEntity;
import org.z2six.infiniteupgrades.world.blockentity.DemonSigilBlockEntity;

public final class ModBlockEntities {
    private static final Logger LOG = LogUtils.getLogger();

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Infiniteupgrades.MODID);

    // Existing: Angel sigil BE
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AngelSigilBlockEntity>> SIGIL_BE =
            BLOCK_ENTITIES.register("sigil",
                    () -> BlockEntityType.Builder.of(AngelSigilBlockEntity::new, Infiniteupgrades.CELESTIAL_SIGIL.get())
                            .build(null));

    // NEW: Demon (Unholy) sigil BE
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DemonSigilBlockEntity>> DEMON_SIGIL_BE =
            BLOCK_ENTITIES.register("demon_sigil",
                    () -> BlockEntityType.Builder.of(DemonSigilBlockEntity::new, Infiniteupgrades.UNHOLY_SIGIL.get())
                            .build(null));

    private ModBlockEntities() {}

    static {
        LOG.debug("[ModBlockEntities] Static init complete");
    }
}
