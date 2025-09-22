// File: src/main/java/org/z2six/infiniteupgrades/world/SoulOrbEntity.java
package org.z2six.infiniteupgrades.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.z2six.infiniteupgrades.registry.ModEntityTypes;

public class SoulOrbEntity extends Entity {

    public enum Tier { SMALL, MEDIUM, LARGE, EXTRA_LARGE }

    private static final EntityDataAccessor<Integer> DATA_TIER =
            SynchedEntityData.defineId(SoulOrbEntity.class, EntityDataSerializers.INT);

    private int lifetime = 20 * 30; // 30 seconds

    public SoulOrbEntity(EntityType<? extends SoulOrbEntity> type, Level level) {
        super(type, level);
        this.noPhysics = false;
    }

    /** Convenience spawn ctor. */
    public SoulOrbEntity(Level level, Vec3 pos, Tier tier, int lifetimeTicks) {
        this(ModEntityTypes.SOUL_ORB.get(), level);
        this.moveTo(pos.x, pos.y, pos.z, level.random.nextFloat() * 360.0f, 0.0f);
        this.setTier(tier);
        this.lifetime = Math.max(1, lifetimeTicks);

        // start with gentle, mostly-horizontal drift; no upward impulse
        RandomSource r = level.random;
        this.setDeltaMovement(r.triangle(0.0, 0.01), 0.0, r.triangle(0.0, 0.01));
    }

    // --------------------------------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_TIER, 0);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Tier")) {
            setTierOrdinal(Mth.clamp(tag.getInt("Tier"), 0, Tier.values().length - 1));
        }
        if (tag.contains("Life")) {
            this.lifetime = Math.max(1, tag.getInt("Life"));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Tier", getTierOrdinal());
        tag.putInt("Life", this.lifetime);
    }

    // NOTE: no getAddEntityPacket() override needed; vanilla packet is fine (no extra spawn data)

    // --------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();

        if (this.tickCount > this.lifetime) {
            this.discard();
            return;
        }

        // physics: slow gravity, light drag, tiny bounce on ground
        Vec3 vel = this.getDeltaMovement();

        vel = vel.add(0.0, -0.03, 0.0);      // gravity
        this.move(MoverType.SELF, vel);      // move
        vel = vel.scale(0.98);               // air drag

        if (this.onGround()) {
            vel = new Vec3(vel.x * 0.7, -vel.y * 0.5, vel.z * 0.7); // bounce+friction
        }

        this.setDeltaMovement(vel);
    }

    // --------------------------------------------------------------------------------------------
    // Render helpers

    public float baseQuadSize() {
        return switch (getTier()) {
            case SMALL       -> 0.28f;
            case MEDIUM      -> 0.32f;
            case LARGE       -> 0.36f;
            case EXTRA_LARGE -> 0.40f;
        };
    }

    // --------------------------------------------------------------------------------------------
    // Tier sync

    public Tier getTier() {
        int ord = getTierOrdinal();
        return Tier.values()[Mth.clamp(ord, 0, Tier.values().length - 1)];
    }

    public void setTier(Tier tier) {
        setTierOrdinal(tier.ordinal());
    }

    private int getTierOrdinal() {
        return this.entityData.get(DATA_TIER);
    }

    private void setTierOrdinal(int ord) {
        this.entityData.set(DATA_TIER, ord);
    }
}
