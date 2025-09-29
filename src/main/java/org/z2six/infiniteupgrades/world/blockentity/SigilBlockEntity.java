// MainFile: src/main/java/org/z2six/infiniteupgrades/world/blockentity/SigilBlockEntity.java
package org.z2six.infiniteupgrades.world.blockentity;

import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
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
import org.z2six.infiniteupgrades.world.menu.AngelDemonMenu;

/**
 * // MainFile: SigilBlockEntity.java
 * Holds 2 slots for the angel upgrade ritual.
 * Defensive logging; never crashes.
 */
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

    // ---- Persistence (1.21: include HolderLookup.Provider) ----
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        try {
            ContainerHelper.saveAllItems(tag, inventory.getItems(), provider);
        } catch (Throwable t) {
            LOG.error("[SigilBE] saveAdditional failed: {}", t.toString());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        try {
            inventory.clearContent();
            ContainerHelper.loadAllItems(tag, inventory.getItems(), provider);
        } catch (Throwable t) {
            LOG.error("[SigilBE] loadAdditional failed: {}", t.toString());
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
            // AngelDemonMenu's registered factory signature is (id, inv, FriendlyByteBuf).
            // On the SERVER, we create a tiny buffer that carries our BlockPos for the client.
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeBlockPos(this.getBlockPos());
            return new AngelDemonMenu(id, playerInv, buf);
        } catch (Throwable t) {
            LOG.error("[SigilBE] createMenu failed: {}", t.toString());
            return null; // Returning null cancels opening gracefully.
        }
    }
}
