// File: src/main/java/org/z2six/infiniteupgrades/world/SoulOrbEntity.java
package org.z2six.infiniteupgrades.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.registry.ModEntityTypes;
import org.z2six.infiniteupgrades.util.LightScheduler;
import org.z2six.infiniteupgrades.util.SoulLightService;

import java.util.Locale;

/**
 * NOTE: Logic preserved from your version. Additions:
 * - Tracks the BlockPos used for the static light (if any) via setLightAnchor().
 * - On removal paths (onRemovedFromLevel and lifetime expiry), schedules a light remove and
 *   updates SoulLightService (recordRemoved) to keep persistence consistent.
 * - fadeFactor(...) helper added to support renderer fade-out.
 */
public class SoulOrbEntity extends Entity {
    private static final Logger LOG = LogUtils.getLogger();

    // log switches
    private static final boolean LOG_SPAWN  = true;
    private static final boolean LOG_TICKS  = false;  // silenced spam
    private static final int     LOG_EVERY  = 20;     // ticks

    public enum Tier { SMALL, MEDIUM, LARGE, EXTRA_LARGE }

    private static final EntityDataAccessor<Integer> DATA_TIER =
            SynchedEntityData.defineId(SoulOrbEntity.class, EntityDataSerializers.INT);

    private int lifetime = 20 * 30; // 30 seconds

    // Hover state
    private double hoverBaseY;           // y around which we bob
    private float hoverPhaseOffset;      // per-orb phase
    private float hoverSpeed = 0.08f;    // radians/tick
    private float hoverAmplitude = 0.08f;// blocks

    // Light cleanup bookkeeping
    private @org.jetbrains.annotations.Nullable BlockPos lightAnchor;
    private boolean lightCleared = false;

    public SoulOrbEntity(EntityType<? extends SoulOrbEntity> type, net.minecraft.world.level.Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.noPhysics = true;
        this.setDeltaMovement(Vec3.ZERO);
        this.hoverBaseY = this.getY();
        this.hoverPhaseOffset = level != null ? level.random.nextFloat() * (float)(Math.PI * 2.0) : 0.0f;
        this.refreshDimensions();
    }

    /** Convenience spawn ctor. */
    public SoulOrbEntity(net.minecraft.world.level.Level level, Vec3 pos, Tier tier, int lifetimeTicks) {
        this(ModEntityTypes.SOUL_ORB.get(), level);
        this.moveTo(pos.x, pos.y, pos.z, 0.0f, 0.0f);
        this.setTier(tier);
        this.lifetime = Math.max(1, lifetimeTicks);
        this.setDeltaMovement(Vec3.ZERO);
        this.hoverBaseY = pos.y;
        this.hoverPhaseOffset = level.random.nextFloat() * (float)(Math.PI * 2.0);
        this.refreshDimensions();

        if (LOG_SPAWN && !level.isClientSide) {
            var bb = this.getBoundingBox();
            LOG.info("[SoulOrbEntity] ctor: id={}, tier={}, dim={}, pos=({},{},{}), life={}t, AABB=({}, {}, {}, {}), size=({},{})",
                    this.getId(), tier, level.dimension().location(),
                    fmt(pos.x), fmt(pos.y), fmt(pos.z), lifetimeTicks,
                    fmt(bb.minX), fmt(bb.minY), fmt(bb.maxX), fmt(bb.maxY),
                    fmt(bb.getXsize()), fmt(bb.getYsize()));
        }
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.3f", v); }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_TIER, 0);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Tier")) setTierOrdinal(Mth.clamp(tag.getInt("Tier"), 0, Tier.values().length - 1));
        if (tag.contains("Life")) this.lifetime = Math.max(1, tag.getInt("Life"));
        if (tag.contains("HoverBaseY")) this.hoverBaseY = tag.getDouble("HoverBaseY");
        if (tag.contains("HoverSpeed")) this.hoverSpeed = tag.getFloat("HoverSpeed");
        if (tag.contains("HoverAmp"))   this.hoverAmplitude = tag.getFloat("HoverAmp");
        if (tag.contains("HoverPhase")) this.hoverPhaseOffset = tag.getFloat("HoverPhase");
        if (tag.contains("LightPos")) {
            long lp = tag.getLong("LightPos");
            if (lp != 0L) this.lightAnchor = BlockPos.of(lp);
        }
        this.refreshDimensions();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Tier", getTierOrdinal());
        tag.putInt("Life", this.lifetime);
        tag.putDouble("HoverBaseY", this.hoverBaseY);
        tag.putFloat("HoverSpeed", this.hoverSpeed);
        tag.putFloat("HoverAmp", this.hoverAmplitude);
        tag.putFloat("HoverPhase", this.hoverPhaseOffset);
        if (this.lightAnchor != null) tag.putLong("LightPos", this.lightAnchor.asLong());
    }

    @Override
    public void tick() {
        super.tick();

        if (this.tickCount > this.lifetime) {
            // lifetime over: clean up light (server) and discard
            if (!level().isClientSide) cleanupLight();
            this.discard();
            return;
        }

        if (LOG_TICKS && ((this.tickCount + (int)this.getId()) % LOG_EVERY == 0)) {
            LOG.info("[SoulOrbEntity] tick: id={}, pos=({},{},{}), dim={}, lifeLeft={}t",
                    this.getId(), fmt(this.getX()), fmt(this.getY()), fmt(this.getZ()),
                    this.level().dimension().location(), (this.lifetime - this.tickCount));
        }

        // ---- SERVER: authoritative hover motion ----
        if (!level().isClientSide) {
            final double x = this.getX();
            final double z = this.getZ();
            final float t = this.tickCount + this.hoverPhaseOffset;
            final double y = this.hoverBaseY + (Math.sin(t * this.hoverSpeed) * this.hoverAmplitude);

            this.setDeltaMovement(Vec3.ZERO);
            this.setPos(x, y, z);
        }

        // CLIENT: no position fiddling here; vanilla handles interpolation
    }

    /** Called by spawner to set the exact hover center (existing behavior). */
    public void setHoverBaseY(double y) {
        this.hoverBaseY = y;
        this.setPos(this.getX(), y, this.getZ());
        if (LOG_SPAWN && !level().isClientSide) {
            LOG.info("[SoulOrbEntity] setHoverBaseY: id={}, newBaseY={}, posNow=({},{},{})",
                    this.getId(), fmt(y), fmt(this.getX()), fmt(this.getY()), fmt(this.getZ()));
        }
    }

    // Slight inflation for safety (helpers in 1.21.1; no @Override)
    public AABB getBoundingBoxForCulling() { return super.getBoundingBox().inflate(0.15); }
    public AABB getRenderBoundingBox()     { return this.getBoundingBox().inflate(0.15); }

    @Override
    public boolean shouldRenderAtSqrDistance(double dist) {
        return dist < 16384.0D; // ~128 blocks
    }

    // Render helpers
    public float baseQuadSize() {
        return switch (getTier()) {
            case SMALL       -> 0.28f;
            case MEDIUM      -> 0.32f;
            case LARGE       -> 0.36f;
            case EXTRA_LARGE -> 0.40f;
        };
    }

    /**
     * Used by the renderer to fade the soul out during the last N ticks of its life.
     * Returns a factor in [0..1], where 1 = fully visible, 0 = fully faded.
     */
    public float fadeFactor(float partialTick, int fadeWindowTicks) {
        if (fadeWindowTicks <= 0) return 1.0f;
        float remaining = (this.lifetime - this.tickCount) - partialTick;
        if (remaining <= 0f) return 0f;
        if (remaining >= fadeWindowTicks) return 1f;
        return Mth.clamp(remaining / (float) fadeWindowTicks, 0f, 1f);
    }

    // Tier sync
    public Tier getTier() {
        int ord = getTierOrdinal();
        return Tier.values()[Mth.clamp(ord, 0, Tier.values().length - 1)];
    }
    public void setTier(Tier tier) { setTierOrdinal(tier.ordinal()); }
    private int getTierOrdinal() { return this.entityData.get(DATA_TIER); }
    private void setTierOrdinal(int ord) { this.entityData.set(DATA_TIER, ord); }

    // ---- Light lifecycle glue ----------------------------------------------------

    /** Spawner should call this once it knows the final BlockPos used for the light. */
    public void setLightAnchor(BlockPos pos) {
        this.lightAnchor = pos == null ? null : pos.immutable();
        this.lightCleared = false;
    }

    /** Attempt to clear the static light if we registered one. Server side only. */
    private void cleanupLight() {
        if (this.lightCleared) return;
        if (this.lightAnchor == null) { this.lightCleared = true; return; }
        if (!(this.level() instanceof net.minecraft.server.level.ServerLevel sl)) { return; }

        // Schedule actual removal and update persisted registry (matches current SoulLightService API)
        LightScheduler.queueRemove(sl, this.lightAnchor);
        SoulLightService.get(sl).recordRemoved(this.lightAnchor);

        this.lightCleared = true;

        if (LOG_SPAWN) {
            LOG.info("[SoulOrbEntity] cleanupLight: id={}, removed light @ {}", this.getId(), this.lightAnchor);
        }
    }

    /** Called when entity is fully removed from the world (e.g., chunk unload, world quit). */
    @Override
    public void onRemovedFromLevel() {
        if (!this.level().isClientSide) cleanupLight();
        super.onRemovedFromLevel();
    }
}
