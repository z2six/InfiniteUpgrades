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
import org.z2six.infiniteupgrades.Infiniteupgrades;
import org.z2six.infiniteupgrades.registry.ModEntityTypes;
import org.z2six.infiniteupgrades.world.blockentity.SigilBlockEntity;

import java.util.List;

public final class SigilBlock extends Block implements EntityBlock {
    private static final Logger LOG = LogUtils.getLogger();
    private static final VoxelShape OUTLINE = Block.box(0, 0, 0, 16, 1, 16);

    public SigilBlock(BlockBehaviour.Properties props) {
        super(props);
        LOG.debug("[SigilBlock] Constructed");
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
        return new SigilBlockEntity(pos, state);
    }

    // Spawn the correct ritual NPC when placed
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;
        ServerLevel srv = (ServerLevel) level;
        try {
            boolean isUnholy = state.getBlock() == Infiniteupgrades.UNHOLY_SIGIL.get();
            AABB box = AABB.unitCubeFromLowerCorner(pos.getCenter()).inflate(1.0);

            if (isUnholy) {
                // Avoid duplicates: Demon anchored here already?
                List<DemonEntity> existing = srv.getEntities(ModEntityTypes.DEMON.get(), box, e -> e.getAnchor().equals(pos));
                if (!existing.isEmpty()) {
                    LOG.debug("[SigilBlock] Demon already exists at {}; skipping spawn", pos);
                    return;
                }
                DemonEntity demon = ModEntityTypes.DEMON.get().create(srv);
                if (demon == null) {
                    LOG.error("[SigilBlock] Failed to create DemonEntity at {}", pos);
                    return;
                }
                demon.setAnchor(pos);
                srv.addFreshEntityWithPassengers(demon);
                LOG.info("[SigilBlock] Spawned DemonEntity id={} at {}", demon.getId(), pos);
            } else {
                // Celestial (Angel)
                List<AngelEntity> existing = srv.getEntities(ModEntityTypes.ANGEL.get(), box, e -> e.getAnchor().equals(pos));
                if (!existing.isEmpty()) {
                    LOG.debug("[SigilBlock] Angel already exists at {}; skipping spawn", pos);
                    return;
                }
                AngelEntity angel = ModEntityTypes.ANGEL.get().create(srv);
                if (angel == null) {
                    LOG.error("[SigilBlock] Failed to create AngelEntity at {}", pos);
                    return;
                }
                angel.setAnchor(pos);
                srv.addFreshEntityWithPassengers(angel);
                LOG.info("[SigilBlock] Spawned AngelEntity id={} at {}", angel.getId(), pos);
            }
        } catch (Throwable t) {
            LOG.error("[SigilBlock] setPlacedBy spawn failed at {}: {}", pos, t.toString());
        }
    }

    // Remove anchored ritual NPC and drop BE contents when the sigil is broken/replaced
    @Override
    public void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        super.onRemove(oldState, level, pos, newState, isMoving);
        if (level.isClientSide) return;
        if (oldState.getBlock() == newState.getBlock()) return;

        try {
            // Drop the two input slots if present
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof SigilBlockEntity sbe) {
                Containers.dropContents(level, pos, sbe.getInventory());
            }

            ServerLevel srv = (ServerLevel) level;
            boolean wasUnholy = oldState.getBlock() == Infiniteupgrades.UNHOLY_SIGIL.get();
            AABB box = new AABB(pos).inflate(1.5);

            if (wasUnholy) {
                List<DemonEntity> toRemove = srv.getEntities(ModEntityTypes.DEMON.get(), box, e -> e.getAnchor().equals(pos));
                for (DemonEntity e : toRemove) {
                    e.discard();
                    LOG.info("[SigilBlock] Removed DemonEntity id={} anchored at {}", e.getId(), pos);
                }
            } else {
                List<AngelEntity> toRemove = srv.getEntities(ModEntityTypes.ANGEL.get(), box, e -> e.getAnchor().equals(pos));
                for (AngelEntity e : toRemove) {
                    e.discard();
                    LOG.info("[SigilBlock] Removed AngelEntity id={} anchored at {}", e.getId(), pos);
                }
            }
        } catch (Throwable t) {
            LOG.error("[SigilBlock] onRemove cleanup failed at {}: {}", pos, t.toString());
        }
    }
}
