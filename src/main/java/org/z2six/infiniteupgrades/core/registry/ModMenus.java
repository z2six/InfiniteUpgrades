package org.z2six.infiniteupgrades.core.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.z2six.infiniteupgrades.feature.infusion.menu.AngelDemonMenu;

public final class ModMenus {
    private static final Logger LOG = LogUtils.getLogger();

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, Infiniteupgrades.MODID);

    public static final RegistryObject<MenuType<AngelDemonMenu>> ANGEL_MENU =
            MENUS.register("angel_menu",
                    () -> IForgeMenuType.create((id, inv, buf) -> new AngelDemonMenu(id, inv, buf)));

    private ModMenus() {}

    static {
        LOG.debug("[ModMenus] Static init complete");
    }
}
