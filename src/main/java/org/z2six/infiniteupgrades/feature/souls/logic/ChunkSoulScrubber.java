// MainFile: src/main/java/org/z2six/infiniteupgrades/feature/souls/logic/ChunkSoulScrubber.java
package org.z2six.infiniteupgrades.feature.souls.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ChunkSoulScrubber (Option B support for existing worlds):
 *
 * Removes legacy "infiniteupgrades:soul_orb" entries from chunk NBT DURING chunk load.
 *
 * No EventBusSubscriber. Register from main mod constructor:
 *   NeoForge.EVENT_BUS.addListener(ChunkSoulScrubber::onChunkDataLoad);
 */
public final class ChunkSoulScrubber {
    private static final Logger LOG = LogUtils.getLogger();

    private static final String SOUL_ID = Infiniteupgrades.MODID + ":soul_orb";

    private static final AtomicLong TOTAL_STRIPPED = new AtomicLong(0);
    private static final AtomicLong TOTAL_CHUNKS_TOUCHED = new AtomicLong(0);

    private ChunkSoulScrubber() {}

    public static void onChunkDataLoad(ChunkDataEvent.Load evt) {
        try {
            CompoundTag data = evt.getData();
            if (data == null) return;

            int stripped = 0;
            stripped += stripFromList(data, "Entities");
            stripped += stripFromList(data, "entities");

            if (stripped > 0) {
                long total = TOTAL_STRIPPED.addAndGet(stripped);
                long chunks = TOTAL_CHUNKS_TOUCHED.incrementAndGet();
                LOG.info("[ChunkSoulScrubber] stripped={} legacy souls from chunkNbt (runningTotalStripped={}, chunksTouched={})",
                        stripped, total, chunks);
            }
        } catch (Throwable t) {
            LOG.error("[ChunkSoulScrubber] onChunkDataLoad failed: {}", t.toString());
        }
    }

    private static int stripFromList(CompoundTag root, String key) {
        try {
            if (!root.contains(key, Tag.TAG_LIST)) return 0;

            ListTag list = root.getList(key, Tag.TAG_COMPOUND);
            if (list == null || list.isEmpty()) return 0;

            int removed = 0;

            for (int i = list.size() - 1; i >= 0; i--) {
                CompoundTag ent = list.getCompound(i);
                if (ent == null) continue;

                String id = ent.getString("id");
                if (id == null || id.isEmpty()) continue;

                if (SOUL_ID.equals(id)) {
                    list.remove(i);
                    removed++;
                }
            }

            if (removed > 0) {
                root.put(key, list);
            }

            return removed;
        } catch (Throwable t) {
            LOG.error("[ChunkSoulScrubber] stripFromList failed key={} err={}", key, t.toString());
            return 0;
        }
    }
}
