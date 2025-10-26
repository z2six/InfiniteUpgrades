// File: src/main/java/org/z2six/infiniteupgrades/feature/demon/entity/DemonEntity.java
package org.z2six.infiniteupgrades.feature.demon.entity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.feature.sigil.block.SigilBlock;
import org.z2six.infiniteupgrades.feature.sigil.blockentity.SigilBlockEntity;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager.ControllerRegistrar;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

public class DemonEntity extends Mob implements GeoEntity {
    private static final Logger LOG = LogUtils.getLogger();

    // GeckoLib per-entity cache
    private final AnimatableInstanceCache geckoCache = GeckoLibUtil.createInstanceCache(this);

    // Name of the animation in your demon.animation.json → "idle"
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("Idle");

    private static final EntityDataAccessor<BlockPos> ANCHOR_POS =
            SynchedEntityData.defineId(DemonEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<String> ANIM =
            SynchedEntityData.defineId(DemonEntity.class, EntityDataSerializers.STRING);

    public DemonEntity(EntityType<? extends Mob> type, Level level) {
        super(type, level);
        setNoAi(true);
        setNoGravity(true);
        setSilent(true);
        setPersistenceRequired();
        setInvulnerable(true);

        try {
            AttributeInstance kb = getAttribute(Attributes.KNOCKBACK_RESISTANCE);
            if (kb != null) kb.setBaseValue(1.0);
            AttributeInstance hp = getAttribute(Attributes.MAX_HEALTH);
            if (hp != null) {
                hp.setBaseValue(40.0);
                setHealth(40.0f);
            }
        } catch (Throwable t) {
            LOG.error("[DemonEntity] Attribute init failed: {}", t.toString());
        }

        this.xxa = this.yya = this.zza = 0f;
        LOG.debug("[DemonEntity] Constructed; side={}, id={}", level.isClientSide ? "CLIENT" : "SERVER", getId());
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ANCHOR_POS, BlockPos.ZERO);
        builder.define(ANIM, "idle");
    }

    public String getAnimation() {
        try { return this.entityData.get(ANIM); }
        catch (Throwable t) { LOG.error("[DemonEntity] getAnimation failed: {}", t.toString()); return "idle"; }
    }
    public void setAnimation(String key) {
        try { this.entityData.set(ANIM, (key == null || key.isEmpty()) ? "idle" : key); }
        catch (Throwable t) { LOG.error("[DemonEntity] setAnimation failed: {}", t.toString()); }
    }

    public void setAnchor(BlockPos pos) {
        try {
            this.entityData.set(ANCHOR_POS, pos.immutable());
            this.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            LOG.debug("[DemonEntity] Anchor set {}; snapped to ground", pos);
        } catch (Throwable t) {
            LOG.error("[DemonEntity] setAnchor failed: {}", t.toString());
        }
    }
    public BlockPos getAnchor() {
        try { return this.entityData.get(ANCHOR_POS); }
        catch (Throwable t) { LOG.error("[DemonEntity] getAnchor failed: {}", t.toString()); return BlockPos.ZERO; }
    }

    // --- Interaction/collision flags ---
    @Override public boolean isPushable() { return false; }
    @Override protected void pushEntities() {}
    @Override public boolean isPickable() { return true; } // allow clicking entity
    @Override public boolean isAttackable() { return false; }
    @Override public boolean canBeAffected(MobEffectInstance effect) { return false; }
    @Override public boolean hurt(DamageSource source, float amount) { return false; }

    @Override
    public void tick() {
        super.baseTick();
        this.setDeltaMovement(0, 0, 0);

        if (level().isClientSide) {
            try {
                Player p = this.level().getNearestPlayer(
                        this.getX(), this.getY(), this.getZ(),
                        128.0,
                        player -> !player.isSpectator()
                );
                if (p != null) {
                    double dx = p.getX() - this.getX();
                    double dz = p.getZ() - this.getZ();
                    float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
                    this.setYRot(yaw);
                    this.setYHeadRot(yaw);
                    this.setYBodyRot(yaw);
                }
            } catch (Throwable t) {
                LOG.error("[DemonEntity] Client yaw update failed: {}", t.toString());
            }
        } else {
            try {
                if (this.tickCount % 20 == 0) {
                    BlockPos anchor = getAnchor();
                    if (anchor == null || anchor == BlockPos.ZERO) return;
                    BlockState bs = ((ServerLevel) level()).getBlockState(anchor);
                    Block block = bs.getBlock();
                    if (!(block instanceof SigilBlock)) {
                        LOG.debug("[DemonEntity] Anchor missing at {}; discarding", anchor);
                        this.discard();
                        return;
                    }
                    this.setPos(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
                }
            } catch (Throwable t) {
                LOG.error("[DemonEntity] Server tick error: {}", t.toString());
            }
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        try {
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;

            // Allow vanilla name tag behavior
            if (player.getItemInHand(hand).is(net.minecraft.world.item.Items.NAME_TAG)) {
                return super.mobInteract(player, hand);
            }

            BlockPos anchor = getAnchor();
            BlockEntity be = level().getBlockEntity(anchor);
            if (be instanceof SigilBlockEntity sigil) {
                sp.openMenu(sigil, anchor);
                return InteractionResult.CONSUME;
            } else {
                LOG.warn("[DemonEntity] No SigilBlockEntity at {}", anchor);
            }

            return InteractionResult.SUCCESS;
        } catch (Throwable t) {
            LOG.error("[DemonEntity] mobInteract failed: {}", t.toString());
            return InteractionResult.PASS;
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        try {
            BlockPos p = getAnchor();
            tag.putInt("ax", p.getX());
            tag.putInt("ay", p.getY());
            tag.putInt("az", p.getZ());
            tag.putString("anim", getAnimation());
        } catch (Throwable t) {
            LOG.error("[DemonEntity] addAdditionalSaveData failed: {}", t.toString());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        try {
            setAnchor(new BlockPos(tag.getInt("ax"), tag.getInt("ay"), tag.getInt("az")));
            if (tag.contains("anim")) setAnimation(tag.getString("anim"));
        } catch (Throwable t) {
            LOG.error("[DemonEntity] readAdditionalSaveData failed: {}", t.toString());
        }
    }

    // ------------- GeckoLib: required methods + controller -------------
    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geckoCache;
    }

    @Override
    public void registerControllers(ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "demon_idle", 0, state -> {
            try {
                state.setAndContinue(IDLE);
                return PlayState.CONTINUE;
            } catch (Throwable t) {
                LOG.error("[DemonEntity] Animation controller error: {}", t.toString());
                return PlayState.STOP;
            }
        }));
    }
}
