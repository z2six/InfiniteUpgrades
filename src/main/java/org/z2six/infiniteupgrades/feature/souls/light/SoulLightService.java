// File: src/main/java/org/z2six/infiniteupgrades/feature/souls/light/SoulLightService.java
package org.z2six.infiniteupgrades.feature.souls.light;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Persists the set of "static" lights we create so they can be reliably removed:
 * - on expiry (lifetime over),
 * - on level load (sweep anything that shouldn't exist anymore),
 * - when an owner entity explicitly cleans up.
 *
 * One instance per dimension (stored in that dimension's data storage).
 *
 * API surface kept to match existing call-sites:
 *   - registerAndPlace(ServerLevel, BlockPos, int, long, UUID)
 *   - removeAt(ServerLevel, BlockPos)
 *   - recordRemoved(BlockPos)   // used by SoulOrbEntity cleanup path
 *   - removeAll(ServerLevel)    // full purge (used by StartupCleanup)
 *
 * Implementation detail:
 *   Physical placement/removal is deferred to LightScheduler to avoid light-engine re-entrancy.
 */
@EventBusSubscriber(modid = Infiniteupgrades.MODID)
public final class SoulLightService extends SavedData {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String DATA_NAME = "infiniteupgrades_soul_lights";

    /** SavedData factory (NeoForge/Minecraft 1.21.1 signature). */
    private static final SavedData.Factory<SoulLightService> FACTORY =
            new SavedData.Factory<>(
                    (Supplier<SoulLightService>) SoulLightService::new,
                    (BiFunction<CompoundTag, HolderLookup.Provider, SoulLightService>) SoulLightService::load
            );

    /** Single level-local registry: posLong -> entry. */
    private final Map<Long, Entry> entries = new HashMap<>();

    /** Small grace to avoid race with save/unload windows. */
    private static final int EXPIRE_GRACE_TICKS = 5;

    public SoulLightService() {}

    // -------------------------- Public API --------------------------

    public static SoulLightService get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /**
     * Register & place a static light. If an entry already existed at the pos, it is replaced.
     * Schedules the actual placement via LightScheduler to avoid re-entrancy.
     *
     * @param level server level
     * @param pos block position to light
     * @param lightLevel [1..15]
     * @param expiresAtGameTime absolute game time in ticks when light should expire
     * @param owner optional owner UUID (e.g., the Soul entity's UUID)
     */
    public void registerAndPlace(ServerLevel level, BlockPos pos, int lightLevel, long expiresAtGameTime, @Nullable java.util.UUID owner) {
        if (lightLevel <= 0) return;

        long now = level.getGameTime();
        long exp = Math.max(now + EXPIRE_GRACE_TICKS, expiresAtGameTime);

        entries.put(pos.asLong(), new Entry(lightLevel, exp, owner));
        setDirty();

        LightScheduler.queuePlace(level, pos, lightLevel);
        if (LOG.isDebugEnabled()) {
            LOG.debug("[SoulLightService] +register {} L{} exp@{} owner={}", pos, lightLevel, exp, owner);
        }
    }

    /** Remove a specific light at pos (if present) and schedule physical removal. */
    public void removeAt(ServerLevel level, BlockPos pos) {
        if (entries.remove(pos.asLong()) != null) {
            setDirty();
            LightScheduler.queueRemove(level, pos);
            if (LOG.isDebugEnabled()) {
                LOG.debug("[SoulLightService] -remove {}", pos);
            }
        }
    }

    /**
     * Record that a light was removed externally (e.g., caller already scheduled LightScheduler.queueRemove).
     * Does NOT schedule removal itself; used by SoulOrbEntity.cleanupLight().
     */
    public void recordRemoved(BlockPos pos) {
        if (pos == null) return;
        if (entries.remove(pos.asLong()) != null) {
            setDirty();
        }
    }

    /**
     * Purge all tracked lights in this level and schedule world removals.
     * @return number of lights scheduled for removal
     */
    public int removeAll(ServerLevel level) {
        if (entries.isEmpty()) return 0;
        int count = 0;
        for (long key : new ArrayList<>(entries.keySet())) {
            BlockPos pos = BlockPos.of(key);
            LightScheduler.queueRemove(level, pos);
            count++;
        }
        entries.clear();
        setDirty();
        if (LOG.isInfoEnabled() && count > 0) {
            LOG.info("[SoulLightService] removeAll: scheduled removal of {} lights in {}", count, level.dimension().location());
        }
        return count;
    }

    /** Sweep expired entries immediately. Called on load and periodically while ticking. */
    public void sweep(ServerLevel level) {
        if (entries.isEmpty()) return;

        long now = level.getGameTime();
        List<Long> expired = new ArrayList<>();
        for (Map.Entry<Long, Entry> e : entries.entrySet()) {
            if (now >= e.getValue().expiresAt) expired.add(e.getKey());
        }
        if (expired.isEmpty()) return;

        for (long key : expired) {
            BlockPos pos = BlockPos.of(key);
            LightScheduler.queueRemove(level, pos);
            entries.remove(key);
        }
        setDirty();
        if (LOG.isDebugEnabled()) {
            LOG.debug("[SoulLightService] sweep removed {} expired lights", expired.size());
        }
    }

    // -------------------------- Event hooks --------------------------

    /** On level load, do a quick sweep in case lights were serialized after their TTL passed. */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load evt) {
        if (!(evt.getLevel() instanceof ServerLevel sl)) return;
        get(sl).sweep(sl);
    }

    /** Once per second per server level, sweep expired lights. */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post evt) {
        if (!(evt.getLevel() instanceof ServerLevel sl)) return;
        if ((sl.getGameTime() % 20L) != 0L) return;
        get(sl).sweep(sl);
    }

    // -------------------------- SavedData (NBT) --------------------------

    private static final class Entry {
        final int lightLevel;
        final long expiresAt;
        final @Nullable java.util.UUID owner;

        Entry(int level, long expiresAt, @Nullable java.util.UUID owner) {
            this.lightLevel = Math.max(0, Math.min(15, level));
            this.expiresAt = Math.max(0L, expiresAt);
            this.owner = owner;
        }

        CompoundTag save(long posLong) {
            CompoundTag t = new CompoundTag();
            t.putLong("pos", posLong);
            t.putInt("lvl", lightLevel);
            t.putLong("exp", expiresAt);
            if (owner != null) t.putUUID("owner", owner);
            return t;
        }

        static Entry load(CompoundTag t) {
            int lvl = t.getInt("lvl");
            long exp = t.getLong("exp");
            java.util.UUID own = t.contains("owner", Tag.TAG_INT_ARRAY) ? t.getUUID("owner") : null;
            return new Entry(lvl, exp, own);
        }
    }

    /** Current loader (with registry Provider). */
    private static SoulLightService load(CompoundTag tag, @Nullable HolderLookup.Provider provider) {
        SoulLightService svc = new SoulLightService();
        try {
            if (tag != null && tag.contains("lights", Tag.TAG_LIST)) {
                ListTag arr = tag.getList("lights", Tag.TAG_COMPOUND);
                for (int i = 0; i < arr.size(); i++) {
                    CompoundTag e = arr.getCompound(i);
                    long posLong = e.getLong("pos");
                    svc.entries.put(posLong, Entry.load(e));
                }
            }
        } catch (Throwable t) {
            LOG.warn("[SoulLightService] load failed: {}", t.toString());
        }
        return svc;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag arr = new ListTag();
        for (Map.Entry<Long, Entry> e : entries.entrySet()) {
            arr.add(e.getValue().save(e.getKey()));
        }
        tag.put("lights", arr);
        return tag;
    }
}
