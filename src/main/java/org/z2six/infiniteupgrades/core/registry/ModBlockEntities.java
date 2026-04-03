package org.z2six.infiniteupgrades.core.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;

public final class ModBlockEntities {
    private static final Logger LOG = LogUtils.getLogger();

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Infiniteupgrades.MODID);

    private ModBlockEntities() {}

    static {
        LOG.debug("[ModBlockEntities] Static init (no entries after sigil removal).");
    }
}
