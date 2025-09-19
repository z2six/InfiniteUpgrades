// MainFile: src/main/java/org/z2six/infiniteupgrades/world/DemonSigil.java
package org.z2six.infiniteupgrades.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;

/**
 * Unholy/Demon sigil block.
 *
 * Responsibilities:
 *  - On place (server), ensure a DemonEntity exists above the sigil (spawn if missing).
 *  - On use (server), will open the Demon menu (wired in next step via a MenuProvider).
 *
 * NOTE: Requires registry for the block + item and for the menu type (next step).
 */
public class DemonSigil extends Block {
    private static final Logger LOG = LogUtils.getLogger();

    public DemonSigil(Properties props) {
        super(props);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
        super.onPlace(state, level, pos, oldState, moved);
        try {
            if (!level.isClientSide && level instanceof ServerLevel sLevel) {
                // Spawn (or ensure) demon above the sigil
                DemonEntity e = DemonEntity.spawn(sLevel, pos);
                if (e == null) {
                    LOG.error("[DemonSigil] Failed to spawn DemonEntity at {}", pos);
                } else {
                    LOG.debug("[DemonSigil] Spawned DemonEntity for sigil at {}", pos);
                }
            }
        } catch (Throwable t) {
            LOG.error("[DemonSigil] onPlace failed: {}", t.toString());
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        try {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            // Menu open will be wired in next step via BlockEntity (provider) or direct menu call
            LOG.debug("[DemonSigil] Right-click by {} at {}", player.getName().getString(), pos);
            // Return SUCCESS to avoid client-side “hand swing without action” feel
            return InteractionResult.SUCCESS;
        } catch (Throwable t) {
            LOG.error("[DemonSigil] use failed: {}", t.toString());
            return InteractionResult.FAIL;
        }
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // Standard model JSON
        return RenderShape.MODEL;
    }
}
