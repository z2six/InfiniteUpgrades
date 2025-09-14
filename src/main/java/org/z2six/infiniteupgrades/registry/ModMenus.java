// File: src/main/java/org/z2six/infiniteupgrades/registry/ModMenus.java
package org.z2six.infiniteupgrades.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.Infiniteupgrades;
import org.z2six.infiniteupgrades.world.blockentity.SigilBlockEntity;
import org.z2six.infiniteupgrades.world.menu.AngelMenu;

public final class ModMenus {
    private static final Logger LOG = LogUtils.getLogger();

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Infiniteupgrades.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<AngelMenu>> ANGEL_MENU =
            MENUS.register("angel_menu",
                    () -> IMenuTypeExtension.create((id, inv, buf) -> {
                        try {
                            BlockPos pos = buf.readBlockPos();
                            BlockEntity be = inv.player.level().getBlockEntity(pos);
                            if (be instanceof SigilBlockEntity sigil) {
                                return new AngelMenu(id, inv, sigil);
                            }
                        } catch (Throwable t) {
                            LOG.error("[ModMenus] Failed to read AngelMenu buffer: {}", t.toString());
                        }
                        // Fallback: dummy menu (stateless) – should not happen
                        return new AngelMenu(id, inv);
                    }));

    private ModMenus() {}

    static {
        LOG.debug("[ModMenus] Static init complete");
    }
}
