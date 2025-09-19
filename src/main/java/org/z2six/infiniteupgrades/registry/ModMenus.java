// MainFile: src/main/java/org/z2six/infiniteupgrades/registry/ModMenus.java
package org.z2six.infiniteupgrades.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.Infiniteupgrades;
import org.z2six.infiniteupgrades.world.menu.AngelMenu;
import org.z2six.infiniteupgrades.world.menu.DemonMenu;

/**
 * // MainFile: ModMenus.java
 * Registers container/menu types.
 * Keeps AngelMenu as-is; adds DemonMenu.
 */
public final class ModMenus {
    private static final Logger LOG = LogUtils.getLogger();

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Infiniteupgrades.MODID);

    // Existing Angel menu
    public static final DeferredHolder<MenuType<?>, MenuType<AngelMenu>> ANGEL_MENU =
            MENUS.register("angel_menu",
                    () -> IMenuTypeExtension.create((id, inv, buf) -> new AngelMenu(id, inv, buf)));

    // NEW Demon menu
    public static final DeferredHolder<MenuType<?>, MenuType<DemonMenu>> DEMON_MENU =
            MENUS.register("demon_menu",
                    () -> IMenuTypeExtension.create((id, inv, buf) -> new DemonMenu(id, inv, buf)));

    private ModMenus() {}

    static {
        LOG.debug("[ModMenus] Static init complete");
    }
}
