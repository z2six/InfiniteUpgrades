// File: src/main/java/org/z2six/infiniteupgrades/world/AngelSigil.java
package org.z2six.infiniteupgrades.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.registry.ModEntityTypes;
import org.z2six.infiniteupgrades.world.blockentity.AngelSigilBlockEntity;

import java.util.List;

public final class AngelSigil extends Block implements EntityBlock {
    private static final Logger LOG = LogUtils.getLogger();
    private static final VoxelShape OUTLINE = Block.box(0, 0, 0, 16, 1, 16);

    public AngelSigil(BlockBehaviour.Properties props) {
        super(props);
        LOG.debug("[AngelSigil] Constructed");
    }

    @Override
    public VoxelShape getCollisionShape(BlockState s, BlockGetter g, BlockPos p, CollisionContext c) { return Shapes.empty(); }
    @Override
    public VoxelShape getShape(BlockState s, BlockGetter g, BlockPos p, CollisionContext c) { return OUTLINE; }
    @Override
    public boolean useShapeForLightOcclusion(BlockState s) { return true; }

    // ----- Block Entity -----

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AngelSigilBlockEntity(pos, state);
    }

    // Spawn the angel when placed
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;
        ServerLevel srv = (ServerLevel) level;
        try {
            // Avoid duplicates: if an anchored angel exists here, skip
            AABB box = AABB.unitCubeFromLowerCorner(pos.getCenter()).inflate(1.0);
            List<AngelEntity> existing = srv.getEntities(ModEntityTypes.ANGEL.get(), box, e -> e.getAnchor().equals(pos));
            if (!existing.isEmpty()) {
                LOG.debug("[AngelSigil] Angel already exists at {}; skipping spawn", pos);
                return;
            }

            AngelEntity angel = ModEntityTypes.ANGEL.get().create(srv);
            if (angel == null) {
                LOG.error("[AngelSigil] Failed to create AngelEntity at {}", pos);
                return;
            }
            angel.setAnchor(pos);
            srv.addFreshEntityWithPassengers(angel);
            LOG.info("[AngelSigil] Spawned AngelEntity id={} at {}", angel.getId(), pos);
        } catch (Throwable t) {
            LOG.error("[AngelSigil] setPlacedBy spawn failed at {}: {}", pos, t.toString());
        }
    }

    // Remove the angel and drop BE contents when the sigil is broken/replaced
    @Override
    public void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        super.onRemove(oldState, level, pos, newState, isMoving);
        if (level.isClientSide) return;
        if (oldState.getBlock() == newState.getBlock()) return;

        try {
            // Drop the two input slots if present
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof AngelSigilBlockEntity sbe) {
                Containers.dropContents(level, pos, sbe.getInventory());
            }

            ServerLevel srv = (ServerLevel) level;
            AABB box = new AABB(pos).inflate(1.5);
            List<AngelEntity> toRemove = srv.getEntities(ModEntityTypes.ANGEL.get(), box, e -> e.getAnchor().equals(pos));
            for (AngelEntity e : toRemove) {
                e.discard();
                LOG.info("[AngelSigil] Removed AngelEntity id={} anchored at {}", e.getId(), pos);
            }
        } catch (Throwable t) {
            LOG.error("[AngelSigil] onRemove cleanup failed at {}: {}", pos, t.toString());
        }
    }
}
