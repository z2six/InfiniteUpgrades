// file: src/main/java/org/z2six/infiniteupgrades/util/LightScheduler.java
package org.z2six.infiniteupgrades.feature.souls.light;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Defers light placement/removal to a safe point (end of level tick),
 * avoiding re-entrancy into the chunk DistanceManager during mass spawns/removals.
 */
@EventBusSubscriber(modid = Infiniteupgrades.MODID)
public final class LightScheduler {
    private static final Logger LOG = LogUtils.getLogger();

    private static final ConcurrentLinkedQueue<Job> QUEUE = new ConcurrentLinkedQueue<>();

    // Tune to your liking; avoids huge spikes when thousands of jobs queue up.
    private static final int MAX_JOBS_PER_TICK_PER_LEVEL = 1024;

    private record Job(Kind kind, ResourceKey<Level> dim, BlockPos pos, int level) {
        enum Kind { PLACE, REMOVE }
    }

    private LightScheduler() {}

    public static void queuePlace(ServerLevel level, BlockPos pos, int lightLevel) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        int ll = Math.max(0, Math.min(15, lightLevel));
        if (ll == 0) return;
        QUEUE.add(new Job(Job.Kind.PLACE, level.dimension(), pos.immutable(), ll));
    }

    public static void queueRemove(ServerLevel level, BlockPos pos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        QUEUE.add(new Job(Job.Kind.REMOVE, level.dimension(), pos.immutable(), 0));
    }

    @SubscribeEvent
    public static void onLevelTickPost(LevelTickEvent.Post evt) {
        Level lvl = evt.getLevel();
        if (!(lvl instanceof ServerLevel sl)) return;

        int processed = 0;
        int scanned = 0;
        int toScan = Math.min(QUEUE.size(), MAX_JOBS_PER_TICK_PER_LEVEL * 6); // soft bound

        while (processed < MAX_JOBS_PER_TICK_PER_LEVEL && scanned < toScan) {
            Job job = QUEUE.poll();
            if (job == null) break;
            scanned++;

            if (job.dim.equals(sl.dimension())) {
                try {
                    switch (job.kind) {
                        case PLACE -> LightUtils.placeStaticLight(sl, job.pos, job.level, /*preferAux=*/true);
                        case REMOVE -> LightUtils.removeStaticLight(sl, job.pos);
                    }
                } catch (Throwable t) {
                    LOG.warn("[LightScheduler] job {} @ {} failed: {}", job.kind, job.pos, t.toString());
                }
                processed++;
            } else {
                // Not for this level: put it back for the matching dimension’s tick.
                QUEUE.add(job);
            }
        }
    }
}
