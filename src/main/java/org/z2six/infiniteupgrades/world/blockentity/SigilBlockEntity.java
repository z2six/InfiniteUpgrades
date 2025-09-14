// File: src/main/java/org/z2six/infiniteupgrades/world/blockentity/SigilBlockEntity.java
package org.z2six.infiniteupgrades.world.blockentity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.registry.ModBlockEntities;
import org.z2six.infiniteupgrades.world.menu.AngelMenu;

public class SigilBlockEntity extends BlockEntity implements net.minecraft.world.MenuProvider {
    private static final Logger LOG = LogUtils.getLogger();

    // Two real slots: 0 = combat item, 1 = resource
    private final SimpleContainer inventory = new SimpleContainer(2) {
        @Override public void setChanged() {
            super.setChanged();
            SigilBlockEntity.this.setChanged();
        }
    };

    public SigilBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SIGIL_BE.get(), pos, state);
    }

    public SimpleContainer getInventory() {
        return inventory;
    }

    // ---- Persistence ----

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        try {
            ContainerHelper.saveAllItems(tag, inventory.getItems());
        } catch (Throwable t) {
            LOG.error("[SigilBE] saveAdditional failed: {}", t.toString());
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        try {
            inventory.clearContent();
            ContainerHelper.loadAllItems(tag, inventory.getItems());
        } catch (Throwable t) {
            LOG.error("[SigilBE] load failed: {}", t.toString());
        }
    }

    // ---- MenuProvider ----

    @Override
    public Component getDisplayName() {
        return Component.translatable("gui.infiniteupgrades.angel");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInv, Player player) {
        try {
            return new AngelMenu(id, playerInv, this);
        } catch (Throwable t) {
            LOG.error("[SigilBE] createMenu failed: {}", t.toString());
            return null;
        }
    }
}
