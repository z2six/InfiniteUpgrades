// file: src/main/java/org/z2six/infiniteupgrades/world/SoulOrbEntity.java
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

import java.util.Locale;

public class SoulOrbEntity extends Entity {
    private static final Logger LOG = LogUtils.getLogger();

    // log switches
    private static final boolean LOG_SPAWN  = true;
    private static final boolean LOG_TICKS  = false;
    private static final int     LOG_EVERY  = 20;

    public enum Tier { SMALL, MEDIUM, LARGE, EXTRA_LARGE }

    private static final EntityDataAccessor<Integer> DATA_TIER =
            SynchedEntityData.defineId(SoulOrbEntity.class, EntityDataSerializers.INT);

    private int lifetime = 20 * 30; // default 30 seconds, configurable via spawner

    // Hover state
    private double hoverBaseY;
    private float hoverPhaseOffset;
    private float hoverSpeed = 0.08f;
    private float hoverAmplitude = 0.08f;

    // Where we placed the static light (queued via LightScheduler)
    private BlockPos lightPos = null;

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
            LOG.info("[SoulOrbEntity] ctor: id={}, tier={}, dim={}, pos=({},{},{}), life={}t, size=({},{})",
                    this.getId(), tier, level.dimension().location(),
                    fmt(pos.x), fmt(pos.y), fmt(pos.z), lifetimeTicks,
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

        if (tag.contains("LightX")) {
            this.lightPos = new BlockPos(tag.getInt("LightX"), tag.getInt("LightY"), tag.getInt("LightZ"));
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

        if (this.lightPos != null) {
            tag.putInt("LightX", this.lightPos.getX());
            tag.putInt("LightY", this.lightPos.getY());
            tag.putInt("LightZ", this.lightPos.getZ());
        }
    }

    @Override
    public void tick() {
        super.tick();

        if (this.tickCount > this.lifetime) {
            if (!level().isClientSide && LOG_SPAWN) {
                LOG.info("[SoulOrbEntity] discard: id={}, age={}t/life={}t, lastPos=({},{},{})",
                        this.getId(), this.tickCount, this.lifetime,
                        fmt(this.getX()), fmt(this.getY()), fmt(this.getZ()));
            }
            this.discard();
            return;
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

        if (LOG_TICKS && (this.tickCount % LOG_EVERY) == 0 && !level().isClientSide) {
            LOG.info("[SoulOrbEntity] tick: id={}, pos=({},{},{}), lifeLeft={}t",
                    this.getId(), fmt(this.getX()), fmt(this.getY()), fmt(this.getZ()), (this.lifetime - this.tickCount));
        }
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        if (this.lightPos != null && this.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            LightScheduler.queueRemove(sl, this.lightPos);
            this.lightPos = null;
        }
    }

    /** Let spawner fix our exact hover center. */
    public void setHoverBaseY(double y) {
        this.hoverBaseY = y;
        this.setPos(this.getX(), y, this.getZ());
        if (LOG_SPAWN && !level().isClientSide) {
            LOG.info("[SoulOrbEntity] setHoverBaseY: id={}, newBaseY={}, posNow=({},{},{})",
                    this.getId(), fmt(y), fmt(this.getX()), fmt(this.getY()), fmt(this.getZ()));
        }
    }

    public void setLightPos(BlockPos pos) { this.lightPos = pos; }

    // ---- Fade-out support ----------------------------------------------------

    /** Total lifetime in ticks. */
    public int getLifetimeTicks() { return this.lifetime; }

    /**
     * Returns a [0..1] factor to fade visuals as the orb approaches despawn.
     * @param partialTick       render partial tick
     * @param fadeWindowTicks   how long (in ticks) the fade should last; commonly 200 (=10s)
     */
    public float fadeFactor(float partialTick, int fadeWindowTicks) {
        final float age = this.tickCount + Mth.clamp(partialTick, 0f, 1f);
        final int life = Math.max(1, this.lifetime);
        final int window = Mth.clamp(fadeWindowTicks, 1, life);
        final float remaining = life - age;
        if (remaining <= 0f) return 0f;
        if (remaining >= window) return 1f;
        return Mth.clamp(remaining / (float) window, 0f, 1f);
    }

    // Slight inflation for safety (helpers in 1.21.1; no @Override)
    public AABB getBoundingBoxForCulling() { return super.getBoundingBox().inflate(0.15); }
    public AABB getRenderBoundingBox()     { return this.getBoundingBox().inflate(0.15); }

    @Override
    public boolean shouldRenderAtSqrDistance(double dist) { return dist < 16384.0D; } // ~128 blocks

    public float baseQuadSize() {
        return switch (getTier()) {
            case SMALL       -> 0.28f;
            case MEDIUM      -> 0.32f;
            case LARGE       -> 0.36f;
            case EXTRA_LARGE -> 0.40f;
        };
    }

    public Tier getTier() {
        int ord = getTierOrdinal();
        return Tier.values()[Mth.clamp(ord, 0, Tier.values().length - 1)];
    }
    public void setTier(Tier tier) { setTierOrdinal(tier.ordinal()); }
    private int getTierOrdinal() { return this.entityData.get(DATA_TIER); }
    private void setTierOrdinal(int ord) { this.entityData.set(DATA_TIER, ord); }
}
