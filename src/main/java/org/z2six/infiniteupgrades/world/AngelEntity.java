// MainFile: src/main/java/org/z2six/infiniteupgrades/world/AngelEntity.java
package org.z2six.infiniteupgrades.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

public class AngelEntity extends Mob {
    private static final Logger LOG = LogUtils.getLogger();

    private static final EntityDataAccessor<BlockPos> ANCHOR_POS =
            SynchedEntityData.defineId(AngelEntity.class, EntityDataSerializers.BLOCK_POS);

    // Render-only bob amplitude (server stores base Y; bob applied client-side in renderer)
    public static final float BOB_AMPLITUDE = 0.15f;
    public static final float BOB_SPEED = 0.05f;

    public AngelEntity(EntityType<? extends Mob> type, Level level) {
        super(type, level);
        setNoAi(true);
        setNoGravity(true);
        setSilent(true);
        setPersistenceRequired();
        setInvulnerable(true);

        // Attributes might not be attached yet during construction; guard against nulls.
        try {
            AttributeInstance kb = getAttribute(Attributes.KNOCKBACK_RESISTANCE);
            if (kb != null) kb.setBaseValue(1.0);
            AttributeInstance hp = getAttribute(Attributes.MAX_HEALTH);
            if (hp != null) {
                hp.setBaseValue(40.0);
                setHealth(40.0f);
            }
        } catch (Throwable t) {
            LOG.error("[AngelEntity] Attribute init failed: {}", t.toString());
        }

        this.xxa = this.yya = this.zza = 0f;
        LOG.debug("[AngelEntity] Constructed; side={}, id={}", level.isClientSide ? "CLIENT" : "SERVER", getId());
    }

    // Minimal attributes so the game is happy
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
    }

    // Anchor API
    public void setAnchor(BlockPos pos) {
        try {
            this.entityData.set(ANCHOR_POS, pos.immutable());
            // Snap position once; renderer handles bobbing visually
            this.setPos(pos.getX() + 0.5, pos.getY() + 1.6, pos.getZ() + 0.5);
            LOG.debug("[AngelEntity] Anchor set to {} ; snapped position", pos);
        } catch (Throwable t) {
            LOG.error("[AngelEntity] setAnchor failed: {}", t.toString());
        }
    }

    public BlockPos getAnchor() {
        return this.entityData.get(ANCHOR_POS);
    }

    // Make this a statue: no pushes/collisions/attacks/effects
    @Override public boolean isPushable() { return false; }
    @Override protected void pushEntities() {}
    @Override public boolean canBeCollidedWith() { return false; }
    @Override public boolean isPickable() { return false; } // can't be targeted by projectiles

    @Override
    public boolean canBeAffected(MobEffectInstance effect) {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Ignore all damage
        return false;
    }

    @Override
    public void tick() {
        // Minimal ticking: no AI/movement, just base tick + occasional anchor check.
        super.baseTick();

        // Keep frozen: zero motion
        this.setDeltaMovement(0, 0, 0);

        if (!level().isClientSide) {
            try {
                // Ensure we stay glued to the anchor; cheap check every ~1s
                if (this.tickCount % 20 == 0) {
                    BlockPos anchor = getAnchor();
                    if (anchor == null || anchor == BlockPos.ZERO) return;
                    BlockState bs = ((ServerLevel) level()).getBlockState(anchor);
                    Block block = bs.getBlock();
                    if (!(block instanceof SigilBlock)) {
                        LOG.debug("[AngelEntity] Anchor block missing at {}; discarding", anchor);
                        this.discard();
                        return;
                    }
                    // Snap precisely to anchor (no drift)
                    this.setPos(anchor.getX() + 0.5, anchor.getY() + 1.6, anchor.getZ() + 0.5);
                }
            } catch (Throwable t) {
                LOG.error("[AngelEntity] Server tick error: {}", t.toString());
            }
        }
    }

    // Interactions: Name Tag works; future GUI goes here.
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        try {
            ItemStack held = player.getItemInHand(hand);
            if (held.is(Items.NAME_TAG)) {
                // Vanilla name tag behavior
                return super.mobInteract(player, hand);
            }
            // Future: open GUI here (server-side)
            // if (!level().isClientSide) openAngelMenu(player);
            return InteractionResult.sidedSuccess(level().isClientSide);
        } catch (Throwable t) {
            LOG.error("[AngelEntity] mobInteract failed: {}", t.toString());
            return InteractionResult.PASS;
        }
    }

    // Save/load anchor so entity persists across restarts
    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        BlockPos p = getAnchor();
        tag.putInt("ax", p.getX());
        tag.putInt("ay", p.getY());
        tag.putInt("az", p.getZ());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        try {
            BlockPos p = new BlockPos(tag.getInt("ax"), tag.getInt("ay"), tag.getInt("az"));
            setAnchor(p);
        } catch (Throwable t) {
            LOG.error("[AngelEntity] readAdditionalSaveData failed: {}", t.toString());
        }
    }

    // Prevent random rotations / body yaw changes from the engine
    @Override public void setYRot(float yRot) { /* ignore engine-driven rotation */ }
    @Override public void setYBodyRot(float yBodyRot) { /* ignore */ }
    @Override public void setYHeadRot(float yHeadRot) { /* ignore */ }
}
