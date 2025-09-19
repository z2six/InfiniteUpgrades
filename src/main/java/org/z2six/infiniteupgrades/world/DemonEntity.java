// MainFile: src/main/java/org/z2six/infiniteupgrades/world/DemonEntity.java
package org.z2six.infiniteupgrades.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Simple ambient, non-colliding demon entity that:
 *  - Faces the nearest player.
 *  - Fades in/out based on distance to nearest player (alpha hint via data).
 *  - Is non-interactive physics-wise (no pushing / gravity).
 *
 * Renderer will consume DATA_ALPHA (0..1) for visual fade.
 *
 * NOTE: Requires ModEntityTypes.DEMON registration (next step).
 */
public class DemonEntity extends Entity {
    private static final Logger LOG = LogUtils.getLogger();

    // client hint (0..1). Renderer reads it to fade.
    private static final EntityDataAccessor<Float> DATA_ALPHA =
            SynchedEntityData.defineId(DemonEntity.class, EntityDataSerializers.FLOAT);

    // Tuning
    private static final double FADE_IN_RADIUS = 6.0;   // fully visible within this
    private static final double FADE_OUT_RADIUS = 16.0; // fully invisible beyond this

    public DemonEntity(EntityType<? extends DemonEntity> type, Level level) {
        super(type, level);
        try {
            this.noPhysics = true;      // do not collide
            this.noCulling = true;      // always render (renderer will fade)
        } catch (Throwable t) {
            LOG.error("[DemonEntity] ctor: {}", t.toString());
        }
    }

    public static DemonEntity spawn(ServerLevel level, BlockPos pos) {
        try {
            DemonEntity e = new DemonEntity((EntityType<? extends DemonEntity>) thisEntityType(), level);
            e.moveTo(pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 0f, 0f);
            level.addFreshEntity(e);
            LOG.debug("[DemonEntity] spawned at {}", pos);
            return e;
        } catch (Throwable t) {
            LOG.error("[DemonEntity] spawn failed: {}", t.toString());
            return null;
        }
    }

    private static EntityType<?> thisEntityType() {
        // Placeholder indirection to avoid hard import cycles in comments; real code just uses ModEntityTypes.DEMON.get()
        return org.z2six.infiniteupgrades.registry.ModEntityTypes.DEMON.get();
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_ALPHA, 0.0f);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        try {
            float a = tag.getFloat("alpha");
            this.entityData.set(DATA_ALPHA, Mth.clamp(a, 0f, 1f));
        } catch (Throwable t) {
            LOG.error("[DemonEntity] read NBT failed: {}", t.toString());
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        try {
            tag.putFloat("alpha", this.entityData.get(DATA_ALPHA));
        } catch (Throwable t) {
            LOG.error("[DemonEntity] write NBT failed: {}", t.toString());
        }
    }

    @Override
    public void tick() {
        try {
            super.tick();

            // Face nearest player (server + client OK)
            Player nearest = this.level().getNearestPlayer(this, 32.0);
            if (nearest != null) {
                Vec3 me = this.position();
                Vec3 pl = nearest.position();
                double dx = pl.x - me.x;
                double dz = pl.z - me.z;
                float yaw = (float)(Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
                this.setYRot(yaw);
                this.setYHeadRot(yaw);
                this.setYBodyRot(yaw);
            }

            // Fade by distance
            float alpha = 0f;
            if (nearest != null) {
                double d = nearest.distanceTo(this);
                if (d <= FADE_IN_RADIUS) alpha = 1f;
                else if (d >= FADE_OUT_RADIUS) alpha = 0f;
                else {
                    double t = (d - FADE_IN_RADIUS) / (FADE_OUT_RADIUS - FADE_IN_RADIUS);
                    alpha = (float)Mth.clamp(1.0 - t, 0.0, 1.0);
                }
            }
            this.entityData.set(DATA_ALPHA, alpha);
        } catch (Throwable t) {
            LOG.error("[DemonEntity] tick failed: {}", t.toString());
        }
    }

    public float getAlpha() {
        return Mth.clamp(this.entityData.get(DATA_ALPHA), 0f, 1f);
    }

    @Override
    public boolean isPickable() { return false; }

    @Override
    protected MovementEmission getMovementEmission() { return MovementEmission.NONE; }

    @Override
    public boolean isNoGravity() { return true; }

    @Override
    public boolean canBeCollidedWith() { return false; }
}
