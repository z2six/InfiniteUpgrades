// File: src/main/java/org/z2six/infiniteupgrades/feature/souls/entity/SoulOrbEntity.java
package org.z2six.infiniteupgrades.feature.souls.entity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.core.registry.ModEntityTypes;
import org.z2six.infiniteupgrades.feature.souls.item.SoulCageItem;
import org.z2six.infiniteupgrades.feature.souls.light.LightScheduler;
import org.z2six.infiniteupgrades.feature.souls.light.SoulLightService;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * NOTE: Logic preserved from your version. Additions:
 * - Homing to nearby players who have a Soul Cage (server authoritative).
 * - On proximity (<=1 block), adds the tier value to the player's Soul Cage and removes the orb.
 * - Tracks/saves homing target lightly; if target despawns, orb continues with its current velocity.
 * - Light cleanup on collect.
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
    private BlockPos lightAnchor;
    private boolean lightCleared = false;

    // Homing
    private boolean homing = false;
    private int     homingTicks = 0;
    private UUID    homingTargetId = null;
    private double  speedBase = 0.05;    // starts slow
    private double  speedAccel = 0.005;  // accelerates every tick while homing
    private double  speedMax = 1.10;     // clamp

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
        if (tag.contains("Homing")) this.homing = tag.getBoolean("Homing");
        if (tag.contains("HomingTicks")) this.homingTicks = tag.getInt("HomingTicks");
        if (tag.contains("HomingTarget")) {
            try { this.homingTargetId = tag.getUUID("HomingTarget"); } catch (Throwable ignored) {}
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
        if (this.homing) tag.putBoolean("Homing", true);
        if (this.homingTicks > 0) tag.putInt("HomingTicks", this.homingTicks);
        if (this.homingTargetId != null) tag.putUUID("HomingTarget", this.homingTargetId);
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
            LOG.info("[SoulOrbEntity] tick: id={}, pos=({},{},{}), dim={}, lifeLeft={}t, homing={}",
                    this.getId(), fmt(this.getX()), fmt(this.getY()), fmt(this.getZ()),
                    this.level().dimension().location(), (this.lifetime - this.tickCount), homing);
        }

        if (!level().isClientSide) {
            serverMotionAndCollection();
        }
        // CLIENT: vanilla handles interpolation
    }

    private void serverMotionAndCollection() {
        final ServerLevel sl = (ServerLevel) this.level();

        // Check proximity pickup first if already homing (fast path)
        if (homing) {
            Player target = getHomingTarget(sl);
            if (target != null) {
                // pickup distance is 1 block radius
                double distSq = target.distanceToSqr(this);
                if (distSq <= 1.0) {
                    onCollectedBy(target);
                    return;
                }
            }
        }

        // Not homing yet? Try to find a player with a Soul Cage in range
        if (!homing) {
            int range = Math.max(1, UpgradeServerConfig.snapshot().souls.collectRangeBlocks);
            AABB box = new AABB(
                    this.getX() - range, this.getY() - range, this.getZ() - range,
                    this.getX() + range, this.getY() + range, this.getZ() + range
            );
            List<Player> players = sl.getEntitiesOfClass(Player.class, box, EntitySelector.NO_SPECTATORS);
            Player best = null;
            double bestDist2 = Double.MAX_VALUE;
            for (Player p : players) {
                if (!p.isAlive()) continue;
                if (SoulCageItem.findAnyCage(p).isEmpty()) continue;
                double d2 = p.distanceToSqr(this);
                if (d2 < bestDist2) { bestDist2 = d2; best = p; }
            }
            if (best != null) {
                homing = true;
                homingTicks = 0;
                homingTargetId = best.getUUID();
                // start slow by not changing velocity here; next block accelerates
            }
        }

        // If homing, accelerate toward target
        if (homing) {
            homingTicks++;
            double speed = Math.min(speedMax, speedBase + speedAccel * homingTicks);

            Player target = getHomingTarget(sl);
            if (target != null) {
                Vec3 to = target.position().add(0, target.getBbHeight() * 0.4, 0).subtract(this.position());
                Vec3 dir = to.normalize();
                Vec3 vel = dir.scale(speed);
                this.setDeltaMovement(vel);
                this.setPos(this.getX() + vel.x, this.getY() + vel.y, this.getZ() + vel.z);
            } else {
                // Target gone: keep drifting with current velocity (doesn't "stop")
                Vec3 vel = this.getDeltaMovement();
                if (vel.lengthSqr() < 1.0e-6) {
                    // give it a tiny nudge forward if it somehow stopped
                    vel = new Vec3(0, 0.01, 0);
                }
                this.setPos(this.getX() + vel.x, this.getY() + vel.y, this.getZ() + vel.z);
            }
        } else {
            // Idle hover (original behavior)
            final double x = this.getX();
            final double z = this.getZ();
            final float t = this.tickCount + this.hoverPhaseOffset;
            final double y = this.hoverBaseY + (Math.sin(t * this.hoverSpeed) * this.hoverAmplitude);
            this.setDeltaMovement(Vec3.ZERO);
            this.setPos(x, y, z);
        }
    }

    private Player getHomingTarget(ServerLevel sl) {
        if (homingTargetId == null) return null;
        Entity e = sl.getEntity(homingTargetId);
        return (e instanceof Player p && p.isAlive()) ? p : null;
    }

    private void onCollectedBy(Player player) {
        // Add units to the player's first Soul Cage stack
        var snap = UpgradeServerConfig.snapshot();
        int units = switch (getTier()) {
            case SMALL       -> snap.souls.tierUnitsOrDefault("SMALL", 1);
            case MEDIUM      -> snap.souls.tierUnitsOrDefault("MEDIUM", 4);
            case LARGE       -> snap.souls.tierUnitsOrDefault("LARGE", 8);
            case EXTRA_LARGE -> snap.souls.tierUnitsOrDefault("EXTRA_LARGE", 16);
        };

        // If player lost the cage between homing & collect, just discard (no storage target).
        var stack = SoulCageItem.findAnyCage(player);
        if (!stack.isEmpty()) {
            int after = SoulCageItem.addUnits(stack, units);
            if (LOG_SPAWN) {
                LOG.info("[SoulOrbEntity] collected: player={}, units={}, tier={}, newTotal={}",
                        player.getScoreboardName(), units, getTier(), after);
            }
        } else {
            if (LOG_SPAWN) {
                LOG.info("[SoulOrbEntity] collected but player no longer has a Soul Cage; discarding orb.");
            }
        }

        // Clean light and remove
        cleanupLight();
        this.discard();
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
        if (!(this.level() instanceof ServerLevel sl)) { return; }

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
