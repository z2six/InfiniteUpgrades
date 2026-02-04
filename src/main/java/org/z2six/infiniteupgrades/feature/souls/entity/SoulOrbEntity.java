// MainFile: src/main/java/org/z2six/infiniteupgrades/feature/souls/entity/SoulOrbEntity.java
// SoulOrbEntity.java — souls that hover, home to cages, clump-friendly via storedUnits; supports permanent lifetime
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
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.core.registry.ModEntityTypes;
import org.z2six.infiniteupgrades.feature.souls.item.SoulCageItem;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * File: src/main/java/org/z2six/infiniteupgrades/feature/souls/entity/SoulOrbEntity.java
 *
 * Soul orbs that hover, can home into players carrying a Soul Cage, and credit the cage on pickup.
 *
 * HARD REQUIREMENTS implemented here:
 *  - #1A: Orbs must never persist across restarts -> shouldBeSaved() = false.
 *  - #1B: If a legacy orb somehow loads from disk anyway -> discard instantly.
 *  - #3: If NO player is within 30 blocks -> discard instantly (every server tick).
 *
 * Clumping is implemented in SoulDrops (merge target refreshes lifetime via refreshLifetimeFromNow()).
 */
public class SoulOrbEntity extends Entity {
    private static final Logger LOG = LogUtils.getLogger();

    // log switches
    private static final boolean LOG_SPAWN  = true;
    private static final boolean LOG_TICKS  = false;  // silenced spam
    private static final int     LOG_EVERY  = 20;     // ticks

    // HARD RULE: nuking when players out of range
    public static final double PLAYER_KEEP_RADIUS = 30.0;
    private static final double PLAYER_KEEP_RADIUS_SQR = PLAYER_KEEP_RADIUS * PLAYER_KEEP_RADIUS;

    public enum Tier { SMALL, MEDIUM, LARGE, EXTRA_LARGE }

    private static final EntityDataAccessor<Integer> DATA_TIER =
            SynchedEntityData.defineId(SoulOrbEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Integer> DATA_UNITS =
            SynchedEntityData.defineId(SoulOrbEntity.class, EntityDataSerializers.INT);

    /** 0 = permanent, >0 = despawn after N ticks (compared to tickCount). */
    private int lifetime = 20 * 30; // default 30 seconds unless overridden on construction

    // Legacy-load detection (Option B). If this entity was read from disk, we nuke instantly.
    private boolean loadedFromDisk = false;

    // Hover state
    private double hoverBaseY;            // y around which we bob
    private float hoverPhaseOffset;       // per-orb phase
    private float hoverSpeed = 0.08f;     // radians/tick
    private float hoverAmplitude = 0.08f; // blocks

    // Light cleanup bookkeeping (currently not placing lights; left for compatibility)
    private BlockPos lightAnchor;
    private boolean lightCleared = false;

    // Homing
    private boolean homing = false;
    private int     homingTicks = 0;
    private UUID    homingTargetId = null;
    private double  speedBase = 0.05;    // starts slow
    private double  speedAccel = 0.005;  // accelerates every tick while homing
    private double  speedMax = 1.10;     // clamp

    // Idle hover steering
    private static final double HOVER_MAX_VEL   = 0.10;  // clamp per-axis for idle hover
    private static final double HOVER_STIFFNESS = 0.25;  // how fast we approach targetY per tick

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
        this.lifetime = Math.max(0, lifetimeTicks); // 0 = permanent
        this.setDeltaMovement(Vec3.ZERO);
        this.hoverBaseY = pos.y;
        this.hoverPhaseOffset = level.random.nextFloat() * (float)(Math.PI * 2.0);
        this.refreshDimensions();

        // default units to tier's unit value (caller may override to a combined/clumped total)
        var snap = UpgradeServerConfig.snapshot();
        int defaultUnits = switch (tier) {
            case SMALL       -> snap.souls.tierUnitsOrDefault("SMALL", 1);
            case MEDIUM      -> snap.souls.tierUnitsOrDefault("MEDIUM", 4);
            case LARGE       -> snap.souls.tierUnitsOrDefault("LARGE", 8);
            case EXTRA_LARGE -> snap.souls.tierUnitsOrDefault("EXTRA_LARGE", 16);
        };
        setStoredUnits(defaultUnits);

        if (LOG_SPAWN && !level.isClientSide) {
            var bb = this.getBoundingBox();
            LOG.debug("[SoulOrbEntity] ctor: id={}, tier={}, units={}, permanent={}, dim={}, pos=({},{},{}), life={}t, AABB=({}, {}, {}, {}), size=({},{})",
                    this.getId(), tier, defaultUnits, (this.lifetime == 0), level.dimension().location(),
                    fmt(pos.x), fmt(pos.y), fmt(pos.z), lifetimeTicks,
                    fmt(bb.minX), fmt(bb.minY), fmt(bb.maxX), fmt(bb.maxY),
                    fmt(bb.getXsize()), fmt(bb.getYsize()));
        }
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.3f", v); }

    /**
     * HARD REQUIREMENT #1A:
     * Never persist souls across restart. This prevents them from being written into chunk NBT.
     */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_TIER, 0);
        builder.define(DATA_UNITS, 0);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        // If we are being read from disk, mark and nuke on first server tick (Option B fallback).
        // NOTE: This method can be invoked during actual disk entity load.
        this.loadedFromDisk = true;

        if (tag.contains("Tier")) setTierOrdinal(Mth.clamp(tag.getInt("Tier"), 0, Tier.values().length - 1));
        if (tag.contains("Life")) this.lifetime = Math.max(0, tag.getInt("Life")); // 0 = permanent
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
        if (tag.contains("Units")) {
            setStoredUnits(Math.max(0, tag.getInt("Units")));
        }
        this.refreshDimensions();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        // Even though shouldBeSaved() is false, keep this robust for any weird edge cases.
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
        tag.putInt("Units", getStoredUnits());
    }

    /**
     * Called by clumping logic to ensure a merged orb doesn't "inherit" a nearly-expired lifetime.
     * If lifetimeTicks == 0, the orb becomes permanent.
     *
     * Safe behavior:
     * - Resets tickCount so lifetime countdown restarts from "now".
     * - Also clears any fade window (fade uses tickCount).
     */
    public void refreshLifetimeFromNow(int lifetimeTicks) {
        try {
            this.lifetime = Math.max(0, lifetimeTicks);
            this.tickCount = 0; // restart lifetime countdown AND fade math
            if (LOG_SPAWN && !this.level().isClientSide) {
                LOG.debug("[SoulOrbEntity] refreshLifetimeFromNow: id={}, newLifeTicks={}, permanent={}",
                        this.getId(), this.lifetime, (this.lifetime == 0));
            }
        } catch (Throwable t) {
            LOG.error("[SoulOrbEntity] refreshLifetimeFromNow failed: {}", t.toString());
        }
    }

    @Override
    public void tick() {
        super.tick();

        if (!level().isClientSide) {
            final ServerLevel sl = (ServerLevel) this.level();

            // HARD REQUIREMENT #1B (fallback): if a legacy orb loaded from disk, nuke it immediately.
            if (this.loadedFromDisk) {
                if (LOG_SPAWN) {
                    LOG.warn("[SoulOrbEntity] LEGACY-LOAD NUKE: id={}, tier={}, units={}, dim={}, pos=({},{},{}). Removing immediately.",
                            this.getId(), getTier(), getStoredUnits(),
                            sl.dimension().location(),
                            fmt(this.getX()), fmt(this.getY()), fmt(this.getZ()));
                }
                this.discard();
                return;
            }

            // HARD REQUIREMENT #3: if no player is within 30 blocks, instantly nuke.
            if (!isAnyPlayerWithinKeepRadius(sl)) {
                if (LOG_SPAWN) {
                    LOG.debug("[SoulOrbEntity] OUT-OF-RANGE NUKE: id={}, tier={}, units={}, dim={}, pos=({},{},{}). Removing immediately.",
                            this.getId(), getTier(), getStoredUnits(),
                            sl.dimension().location(),
                            fmt(this.getX()), fmt(this.getY()), fmt(this.getZ()));
                }
                this.discard();
                return;
            }

            if (LOG_TICKS && ((this.tickCount + (int)this.getId()) % LOG_EVERY == 0)) {
                LOG.debug("[SoulOrbEntity] tick: id={}, tier={}, units={}, pos=({},{},{}), dim={}, lifeLeft={}t, permanent={}, homing={}",
                        this.getId(), getTier(), getStoredUnits(),
                        fmt(this.getX()), fmt(this.getY()), fmt(this.getZ()),
                        this.level().dimension().location(),
                        (this.lifetime == 0 ? -1 : Math.max(0, this.lifetime - this.tickCount)),
                        (this.lifetime == 0), homing);
            }

            // Despawn if lifetime reached (unless permanent)
            if (this.lifetime > 0 && this.tickCount >= this.lifetime) {
                if (LOG_SPAWN) {
                    LOG.debug("[SoulOrbEntity] LIFETIME DESPAWN: id={}, tier={}, units={}, dim={}, pos=({},{},{}), lifeTicks={}",
                            this.getId(), getTier(), getStoredUnits(),
                            sl.dimension().location(),
                            fmt(this.getX()), fmt(this.getY()), fmt(this.getZ()),
                            this.lifetime);
                }
                this.discard();
                return;
            }

            serverMotionAndCollection(sl);
        }

        // CLIENT: vanilla interpolates to server positions/motion
    }

    private boolean isAnyPlayerWithinKeepRadius(ServerLevel sl) {
        try {
            final double r = PLAYER_KEEP_RADIUS;
            AABB box = new AABB(
                    this.getX() - r, this.getY() - r, this.getZ() - r,
                    this.getX() + r, this.getY() + r, this.getZ() + r
            );
            List<Player> players = sl.getEntitiesOfClass(Player.class, box, EntitySelector.NO_SPECTATORS);
            for (Player p : players) {
                if (p == null) continue;
                if (!p.isAlive()) continue;
                if (p.distanceToSqr(this) <= PLAYER_KEEP_RADIUS_SQR) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // If anything goes wrong, be safe: nuke (prevents leaks).
            LOG.error("[SoulOrbEntity] isAnyPlayerWithinKeepRadius failed; nuking orb. err={}", t.toString());
            return false;
        }
        return false;
    }

    private void serverMotionAndCollection(ServerLevel sl) {
        // If already homing, check pickup first (<= 1 block radius).
        if (homing) {
            Player target = getHomingTarget(sl);
            if (target != null) {
                double distSq = target.distanceToSqr(this);
                if (distSq <= 1.0) {
                    onCollectedBy(target);
                    return;
                }
            }
        }

        // Not homing yet? Search for nearest player with a Soul Cage in range.
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
            }
        }

        // Compute desired velocity for this tick
        Vec3 newVel;

        if (homing) {
            homingTicks++;
            double speed = Math.min(speedMax, speedBase + speedAccel * homingTicks);

            Player target = getHomingTarget(sl);
            if (target != null) {
                Vec3 to = target.position().add(0, target.getBbHeight() * 0.4, 0).subtract(this.position());
                Vec3 dir = to.lengthSqr() > 1.0e-8 ? to.normalize() : Vec3.ZERO;
                newVel = dir.scale(speed);
            } else {
                // Target gone: keep drifting with current velocity (doesn't "stop")
                Vec3 vel = this.getDeltaMovement();
                if (vel.lengthSqr() < 1.0e-6) vel = new Vec3(0, 0.01, 0);
                newVel = vel;
            }
        } else {
            // Idle: hover smoothly around targetY = hoverBaseY + bobbing
            final float t = this.tickCount + this.hoverPhaseOffset;
            final double targetY = this.hoverBaseY + (Math.sin(t * this.hoverSpeed) * this.hoverAmplitude);

            double dy = targetY - this.getY();
            double vy = Mth.clamp(dy * HOVER_STIFFNESS, -HOVER_MAX_VEL, HOVER_MAX_VEL);

            // keep x/z essentially stationary while idle
            newVel = new Vec3(0.0, vy, 0.0);
        }

        // Apply velocity + move (smooth on client)
        this.setDeltaMovement(newVel);
        this.move(MoverType.SELF, newVel);
    }

    private Player getHomingTarget(ServerLevel sl) {
        if (homingTargetId == null) return null;
        Entity e = sl.getEntity(homingTargetId);
        return (e instanceof Player p && p.isAlive()) ? p : null;
    }

    private void onCollectedBy(Player player) {
        // Add units to the player's first Soul Cage stack
        var stack = SoulCageItem.findAnyCage(player);
        if (!stack.isEmpty()) {
            int after = SoulCageItem.addUnits(stack, Math.max(0, getStoredUnits()));
            if (LOG_SPAWN) {
                LOG.debug("[SoulOrbEntity] collected: player={}, units={}, tier={}, newTotal={}",
                        player.getScoreboardName(), getStoredUnits(), getTier(), after);
            }
        } else {
            if (LOG_SPAWN) {
                LOG.debug("[SoulOrbEntity] collected but player no longer has a Soul Cage; discarding orb.");
            }
        }

        this.discard();
    }

    /** Called by spawner to set the exact hover center (existing behavior). */
    public void setHoverBaseY(double y) {
        this.hoverBaseY = y;
        // no teleport; let the idle controller steer there smoothly
        if (LOG_SPAWN && !level().isClientSide) {
            LOG.debug("[SoulOrbEntity] setHoverBaseY: id={}, newBaseY={}", this.getId(), fmt(y));
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
     * If lifetime is 0 (permanent), always returns 1.
     */
    public float fadeFactor(float partialTick, int fadeWindowTicks) {
        if (this.lifetime == 0) return 1.0f; // permanent → never fade
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

    // Units sync (for clumping and accurate crediting)
    public int getStoredUnits() { return this.entityData.get(DATA_UNITS); }
    public void setStoredUnits(int units) { this.entityData.set(DATA_UNITS, Math.max(0, units)); }

    // ---- Light lifecycle glue (reserved) ----------------------------------------------------

    /** Spawner should call this once it knows the final BlockPos used for the light. */
    public void setLightAnchor(BlockPos pos) {
        this.lightAnchor = pos == null ? null : pos.immutable();
        this.lightCleared = false;
    }

    /** Called when entity is fully removed from the world (e.g., chunk unload, world quit). */
    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
    }
}
