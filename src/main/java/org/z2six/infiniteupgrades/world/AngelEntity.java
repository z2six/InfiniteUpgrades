// MainFile: src/main/java/org/z2six/infiniteupgrades/world/AngelEntity.java
package org.z2six.infiniteupgrades.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.world.menu.AngelMenu;

public class AngelEntity extends Mob {
    private static final Logger LOG = LogUtils.getLogger();

    private static final EntityDataAccessor<BlockPos> ANCHOR_POS =
            SynchedEntityData.defineId(AngelEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<String> ANIM =
            SynchedEntityData.defineId(AngelEntity.class, EntityDataSerializers.STRING);

    public AngelEntity(EntityType<? extends Mob> type, Level level) {
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
            LOG.error("[AngelEntity] Attribute init failed: {}", t.toString());
        }

        this.xxa = this.yya = this.zza = 0f;
        LOG.debug("[AngelEntity] Constructed; side={}, id={}", level.isClientSide ? "CLIENT" : "SERVER", getId());
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
        catch (Throwable t) { LOG.error("[AngelEntity] getAnimation failed: {}", t.toString()); return "idle"; }
    }
    public void setAnimation(String key) {
        try { this.entityData.set(ANIM, (key == null || key.isEmpty()) ? "idle" : key); }
        catch (Throwable t) { LOG.error("[AngelEntity] setAnimation failed: {}", t.toString()); }
    }

    public void setAnchor(BlockPos pos) {
        try {
            this.entityData.set(ANCHOR_POS, pos.immutable());
            this.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            LOG.debug("[AngelEntity] Anchor set {}; snapped to ground", pos);
        } catch (Throwable t) {
            LOG.error("[AngelEntity] setAnchor failed: {}", t.toString());
        }
    }
    public BlockPos getAnchor() {
        try { return this.entityData.get(ANCHOR_POS); }
        catch (Throwable t) { LOG.error("[AngelEntity] getAnchor failed: {}", t.toString()); return BlockPos.ZERO; }
    }

    // --- Interaction/collision flags ---
    @Override public boolean isPushable() { return false; }
    @Override protected void pushEntities() {}

    // IMPORTANT: allow picking so right-click can target the entity
    @Override public boolean isPickable() { return true; }

    // Keep it invulnerable / non-attackable
    @Override public boolean isAttackable() { return false; }
    @Override public boolean canBeAffected(MobEffectInstance effect) { return false; }
    @Override public boolean hurt(DamageSource source, float amount) { return false; }

    @Override
    public void tick() {
        super.baseTick();
        this.setDeltaMovement(0, 0, 0);

        if (level().isClientSide) {
            try {
                Player p = this.level().getNearestPlayer(this.getX(), this.getY(), this.getZ(), 128.0, false);
                if (p != null) {
                    double dx = p.getX() - this.getX();
                    double dz = p.getZ() - this.getZ();
                    float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
                    this.setYRot(yaw);
                    this.setYHeadRot(yaw);
                    this.setYBodyRot(yaw);
                }
            } catch (Throwable t) {
                LOG.error("[AngelEntity] Client yaw update failed: {}", t.toString());
            }
        } else {
            try {
                if (this.tickCount % 20 == 0) {
                    BlockPos anchor = getAnchor();
                    if (anchor == null || anchor == BlockPos.ZERO) return;
                    BlockState bs = ((ServerLevel) level()).getBlockState(anchor);
                    Block block = bs.getBlock();
                    if (!(block instanceof SigilBlock)) {
                        LOG.debug("[AngelEntity] Anchor missing at {}; discarding", anchor);
                        this.discard();
                        return;
                    }
                    this.setPos(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
                }
            } catch (Throwable t) {
                LOG.error("[AngelEntity] Server tick error: {}", t.toString());
            }
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        try {
            ItemStack held = player.getItemInHand(hand);
            if (held.is(Items.NAME_TAG)) {
                return super.mobInteract(player, hand);
            }
            if (!level().isClientSide) {
                // Open our vanilla-style menu
                player.openMenu(new net.minecraft.world.MenuProvider() {
                    @Override public Component getDisplayName() {
                        return Component.translatable("gui.infiniteupgrades.angel");
                    }
                    @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                        return new org.z2six.infiniteupgrades.world.menu.AngelMenu(id, inv);
                    }
                });
                return InteractionResult.CONSUME; // server handled it
            }
            return InteractionResult.SUCCESS; // client: show hand swing, etc.
        } catch (Throwable t) {
            LOG.error("[AngelEntity] mobInteract failed: {}", t.toString());
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
            LOG.error("[AngelEntity] addAdditionalSaveData failed: {}", t.toString());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        try {
            setAnchor(new BlockPos(tag.getInt("ax"), tag.getInt("ay"), tag.getInt("az")));
            if (tag.contains("anim")) setAnimation(tag.getString("anim"));
        } catch (Throwable t) {
            LOG.error("[AngelEntity] readAdditionalSaveData failed: {}", t.toString());
        }
    }
}
