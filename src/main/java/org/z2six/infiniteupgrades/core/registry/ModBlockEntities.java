// MainFile: src/main/java/org/z2six/infiniteupgrades/core/registry/ModBlockEntities.java
package org.z2six.infiniteupgrades.core.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;

/**
 * Central BlockEntity registry.
 *
 * Angel/Demon sigil BlockEntities were removed. We intentionally keep this empty
 * DeferredRegister so existing bootstrap code that calls
 * ModBlockEntities.BLOCK_ENTITIES.register(bus) remains valid.
 */
public final class ModBlockEntities {
    private static final Logger LOG = LogUtils.getLogger();

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Infiniteupgrades.MODID);

    private ModBlockEntities() {}

    static {
        LOG.debug("[ModBlockEntities] Static init (no entries after sigil removal).");
    }
}
