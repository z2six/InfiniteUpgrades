// MainFile: src/main/java/org/z2six/infiniteupgrades/world/SigilBlock.java
package org.z2six.infiniteupgrades.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.registry.ModEntityTypes;

import java.util.List;

public final class SigilBlock extends Block {
    private static final Logger LOG = LogUtils.getLogger();
    private static final VoxelShape OUTLINE = Block.box(0, 0, 0, 16, 1, 16);

    public SigilBlock(BlockBehaviour.Properties props) {
        super(props);
        LOG.debug("[SigilBlock] Constructed");
    }

    @Override
    public VoxelShape getCollisionShape(BlockState s, net.minecraft.world.level.BlockGetter g, BlockPos p, CollisionContext c) { return Shapes.empty(); }
    @Override
    public VoxelShape getShape(BlockState s, net.minecraft.world.level.BlockGetter g, BlockPos p, CollisionContext c) { return OUTLINE; }
    @Override
    public boolean useShapeForLightOcclusion(BlockState s) { return true; }

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
                LOG.debug("[SigilBlock] Angel already exists at {}; skipping spawn", pos);
                return;
            }

            AngelEntity angel = ModEntityTypes.ANGEL.get().create(srv);
            if (angel == null) {
                LOG.error("[SigilBlock] Failed to create AngelEntity at {}", pos);
                return;
            }
            angel.setAnchor(pos);
            // Spawn with CUSTOM spawn type to avoid any AI init
            srv.addFreshEntityWithPassengers(angel);
            LOG.info("[SigilBlock] Spawned AngelEntity id={} at {}", angel.getId(), pos);
        } catch (Throwable t) {
            LOG.error("[SigilBlock] setPlacedBy spawn failed at {}: {}", pos, t.toString());
        }
    }

    // Remove the angel when the sigil is broken/replaced
    @Override
    public void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        super.onRemove(oldState, level, pos, newState, isMoving);
        if (level.isClientSide) return;
        if (oldState.getBlock() == newState.getBlock()) return;

        try {
            ServerLevel srv = (ServerLevel) level;
            AABB box = new AABB(pos).inflate(1.5);
            List<AngelEntity> toRemove = srv.getEntities(ModEntityTypes.ANGEL.get(), box, e -> e.getAnchor().equals(pos));
            for (AngelEntity e : toRemove) {
                e.discard();
                LOG.info("[SigilBlock] Removed AngelEntity id={} anchored at {}", e.getId(), pos);
            }
        } catch (Throwable t) {
            LOG.error("[SigilBlock] onRemove cleanup failed at {}: {}", pos, t.toString());
        }
    }
}
