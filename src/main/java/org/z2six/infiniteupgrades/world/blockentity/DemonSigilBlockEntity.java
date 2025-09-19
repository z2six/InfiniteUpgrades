// MainFile: src/main/java/org/z2six/infiniteupgrades/world/blockentity/DemonSigilBlockEntity.java
package org.z2six.infiniteupgrades.world.blockentity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.logic.UpgradeService.Patron;
import org.z2six.infiniteupgrades.registry.ModBlockEntities;
import org.z2six.infiniteupgrades.world.menu.DemonMenu;

/**
 * BlockEntity for the Unholy Sigil; provides a menu.
 *
 * NOTE: Requires ModBlockEntities.DEMON_SIGIL_BE and ModMenus.DEMON_MENU (next step).
 */
public class DemonSigilBlockEntity extends BlockEntity implements MenuProvider {
    private static final Logger LOG = LogUtils.getLogger();

    public DemonSigilBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DEMON_SIGIL_BE.get(), pos, state);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.infiniteupgrades.demon");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        try {
            LOG.debug("[DemonSigilBE] createMenu for {}", player.getName().getString());
            return new DemonMenu(id, inv);
        } catch (Throwable t) {
            LOG.error("[DemonSigilBE] createMenu failed: {}", t.toString());
            return null;
        }
    }

    // Optional tick hook for effects/fade if needed later
    public static void serverTick(Level level, BlockPos pos, BlockState state, DemonSigilBlockEntity be) {
        // no-op for now
    }
}
