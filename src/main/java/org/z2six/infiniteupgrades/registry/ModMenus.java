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

/** Registers our menu types (uses IMenuTypeExtension to avoid ctor churn across versions). */
public final class ModMenus {
    private static final Logger LOG = LogUtils.getLogger();

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Infiniteupgrades.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<AngelMenu>> ANGEL_MENU =
            MENUS.register("angel_menu",
                    () -> IMenuTypeExtension.create((id, inv, buf) -> new AngelMenu(id, inv)));

    private ModMenus() {}

    static {
        LOG.debug("[ModMenus] Static init complete");
    }
}
