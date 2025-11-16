// MainFile: src/main/java/org/z2six/infiniteupgrades/feature/statue/block/StatueBlock.java
package org.z2six.infiniteupgrades.feature.statue.block;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

/**
 * Decorative statue block (Angel/Demon).
 * Defensive: logs on errors; non-crashing behavior.
 *
 * NOTE: This version uses a FULL 1×1×1 collision/selection box so entities can't clip onto the pedestal.
 */
public class StatueBlock extends Block {
    private static final Logger LOG = LogUtils.getLogger();
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    // Full cube for both selection and collision (exactly 1 block high).
    private static final VoxelShape FULL = box(0, 0, 0, 16, 16, 16);

    public StatueBlock() {
        super(BlockBehaviour.Properties
                .of()
                .strength(2.0F, 6.0F)
                .sound(SoundType.STONE)
                .noOcclusion() // model can have transparency; does not affect collision
        );
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        try {
            return this.defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
        } catch (Throwable t) {
            LOG.error("[StatueBlock] getStateForPlacement failed: {}", t.toString());
            return this.defaultBlockState();
        }
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        try {
            BlockPos below = pos.below();
            return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
        } catch (Throwable t) {
            LOG.error("[StatueBlock] canSurvive failed at {}: {}", pos, t.toString());
            return true;
        }
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    // Selection/outline shape (what you see with F3+B and when aiming the block).
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return FULL;
    }

    // Actual collision (what entities collide with).
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return FULL;
    }
}
