// file: src/main/java/org/z2six/infiniteupgrades/util/LightUtils.java
package org.z2six.infiniteupgrades.util;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.world.AuxiliaryLightManager;
import org.slf4j.Logger;

/** Aux-light first; fallback to vanilla invisible LIGHT block for guaranteed propagation. */
public final class LightUtils {
    private static final Logger LOG = LogUtils.getLogger();

    private LightUtils() {}

    public static void placeStaticLight(Level level, BlockPos pos, int value, boolean preferAux) {
        value = Math.max(0, Math.min(15, value));
        if (value <= 0) return;

        boolean usedAux = false;
        if (preferAux) usedAux = trySetAux(level, pos, value);

        if (usedAux && level instanceof ServerLevel sl) {
            // Confirm it actually registers as block light (not just aux-sampled)
            int after = sl.getBrightness(LightLayer.BLOCK, pos);
            if (after >= value) {
                if (LOG.isInfoEnabled()) LOG.info("[LightUtils] aux OK at {} ({}).", pos, after);
                return;
            } else if (LOG.isInfoEnabled()) {
                LOG.info("[LightUtils] aux not effective ({}), falling back to LIGHT at {}", after, pos);
            }
        }

        // Fallback: vanilla LIGHT block (server only)
        if (!level.isClientSide) {
            BlockState st = Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, value);
            level.setBlock(pos, st, 3 | 16); // neighbors + immediate
            if (level instanceof ServerLevel sl) {
                sl.getChunkSource().getLightEngine().checkBlock(pos);
            }
            if (LOG.isInfoEnabled()) LOG.info("[LightUtils] placed LIGHT {} at {}", value, pos);
        }
    }

    public static void removeStaticLight(Level level, BlockPos pos) {
        // Remove aux entry (if any)
        try {
            ChunkPos cp = new ChunkPos(pos);
            LevelChunk chunk = (LevelChunk) level.getChunk(cp.x, cp.z);
            AuxiliaryLightManager alm = chunk.getAuxLightManager(cp);
            if (alm != null) {
                alm.removeLightAt(pos);
                if (level instanceof ServerLevel sl) {
                    sl.getChunkSource().getLightEngine().checkBlock(pos);
                }
            }
        } catch (Throwable ignored) {}

        // Remove LIGHT block (if present)
        if (!level.isClientSide && level.getBlockState(pos).is(Blocks.LIGHT)) {
            level.removeBlock(pos, false);
            if (LOG.isInfoEnabled()) LOG.info("[LightUtils] removed LIGHT at {}", pos);
        }
    }

    private static boolean trySetAux(Level level, BlockPos pos, int val) {
        try {
            ChunkPos cp = new ChunkPos(pos);
            LevelChunk chunk = (LevelChunk) level.getChunk(cp.x, cp.z);
            AuxiliaryLightManager alm = chunk.getAuxLightManager(cp);
            if (alm == null) return false;
            alm.setLightAt(pos, val);
            if (level instanceof ServerLevel sl) {
                sl.getChunkSource().getLightEngine().checkBlock(pos);
            }
            if (LOG.isInfoEnabled()) LOG.info("[LightUtils] aux set {} at {}", val, pos);
            return true;
        } catch (Throwable t) {
            LOG.warn("[LightUtils] aux failed at {}: {}", pos, t.toString());
            return false;
        }
    }
}
