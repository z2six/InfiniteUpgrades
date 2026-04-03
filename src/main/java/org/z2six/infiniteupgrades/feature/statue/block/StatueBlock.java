// MainFile: src/main/java/org/z2six/infiniteupgrades/feature/statue/block/StatueBlock.java
package org.z2six.infiniteupgrades.feature.statue.block;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.feature.infusion.logic.RitualType;
import org.z2six.infiniteupgrades.feature.infusion.menu.AngelDemonMenu;
import org.z2six.infiniteupgrades.feature.statue.block.statue.StatueKind;

/**
 * Decorative statue block (Angel/Demon).
 * - 1x1 footprint, EXACTLY 2 blocks tall collision (0..32).
 * - Right-click opens Angel/Demon menu from SERVER, sending BlockPos + ritual to the client.
 * - Defensive logs; never crashes the game.
 */
public class StatueBlock extends Block {
    private static final Logger LOG = LogUtils.getLogger();

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    // 1x1 footprint, exactly 2 blocks high
    private static final VoxelShape PEDESTAL = box(1, 0, 1, 15, 32, 15);

    private final StatueKind kind;

    public StatueBlock(StatueKind kind) {
        super(BlockBehaviour.Properties
                .of()
                .strength(2.0F, 6.0F)
                .sound(SoundType.STONE)
                .noOcclusion()
        );
        this.kind = kind;
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
        LOG.debug("[StatueBlock] Constructed statue block for kind={}", kind);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
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
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return PEDESTAL;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        try {
            LOG.debug("[StatueBlock] use kind={} pos={} hand={} sideClient={}", kind, pos, hand, level.isClientSide);
            return handleOpen(level, pos, player);
        } catch (Throwable t) {
            LOG.error("[StatueBlock] use failed: {}", t.toString());
            return InteractionResult.PASS;
        }
    }

    // -------------------- Common open logic --------------------

    private InteractionResult handleOpen(Level level, BlockPos pos, Player player) {
        if (level.isClientSide) {
            return InteractionResult.sidedSuccess(true);
        }
        if (!(player instanceof ServerPlayer sp)) {
            return InteractionResult.PASS;
        }

        try {
            // Server creates the menu with explicit ritual + anchor
            RitualType ritual = (kind == StatueKind.ANGEL) ? RitualType.ANGEL : RitualType.DEMON;

            Component title = (ritual == RitualType.ANGEL)
                    ? Component.translatable("screen.infiniteupgrades.angel")
                    : Component.translatable("screen.infiniteupgrades.demon");

            var provider = new net.minecraft.world.SimpleMenuProvider(
                    (int id, Inventory inv, Player p) -> {
                        try {
                            AbstractContainerMenu menu = new AngelDemonMenu(id, inv, ritual, pos);
                            LOG.debug("[StatueBlock] Created server menu id={} kind={} ritual={} for {}",
                                    id, kind, ritual, p.getGameProfile().getName());
                            return menu;
                        } catch (Throwable t) {
                            LOG.error("[StatueBlock] Menu ctor failed: {}", t.toString());
                            return null;
                        }
                    },
                    title
            );

            // Write SAME order the client reads: pos then ritual ordinal
            NetworkHooks.openScreen(sp, provider, buf -> {
                try {
                    buf.writeBlockPos(pos);
                    buf.writeVarInt(ritual.ordinal());
                } catch (Throwable t) {
                    LOG.error("[StatueBlock] Failed writing extra buf: {}", t.toString());
                }
            });

            LOG.debug("[StatueBlock] Opened menu kind={} ritual={} at {} for {}",
                    kind, ritual, pos, sp.getGameProfile().getName());
            return InteractionResult.CONSUME;
        } catch (Throwable t) {
            LOG.error("[StatueBlock] handleOpen failed at {}: {}", pos, t.toString());
            return InteractionResult.PASS;
        }
    }

    // -------------------- Legacy placement helper --------------------

    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext ctx) {
        try {
            return this.defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
        } catch (Throwable t) {
            LOG.error("[StatueBlock] getStateForPlacement failed: {}", t.toString());
            return this.defaultBlockState();
        }
    }
}
