// MainFile: src/main/java/org/z2six/infiniteupgrades/feature/infusion/menu/AngelDemonMenu.java
package org.z2six.infiniteupgrades.feature.infusion.menu;

import org.z2six.infiniteupgrades.feature.souls.item.SoulCageItem;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemAttributeModifiers.Entry;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.core.net.ModNet;
import org.z2six.infiniteupgrades.core.registry.ModMenus;
import org.z2six.infiniteupgrades.feature.infusion.attachment.ModAttachments;
import org.z2six.infiniteupgrades.feature.infusion.client.InfuseClientEffects;
import org.z2six.infiniteupgrades.feature.infusion.logic.ToolSpeedUtil;
import org.z2six.infiniteupgrades.feature.infusion.logic.AttemptRng;
import org.z2six.infiniteupgrades.feature.infusion.logic.PendingStore;
import org.z2six.infiniteupgrades.feature.infusion.logic.RitualType;
import org.z2six.infiniteupgrades.feature.infusion.logic.UpgradeService;
import org.z2six.infiniteupgrades.feature.infusion.net.PendingStateS2C;
import org.z2six.infiniteupgrades.feature.reputation.logic.Reputation;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AngelDemonMenu extends AbstractContainerMenu {
    private static final Logger LOG = LogUtils.getLogger();

    public static final int INPUT1_X = 63;
    public static final int INPUT1_Y = 37;
    public static final int INPUT2_X = 99;
    public static final int INPUT2_Y = 37;
    public static final int OUTPUT_X = 81;
    public static final int OUTPUT_Y = 73;

    private static final int PLAYER_INV_X = 9;
    private static final int PLAYER_INV_Y = 141;
    private static final int SLOT_STEP = 18;
    private static final int HOTBAR_X = 9;
    private static final int HOTBAR_Y = 199;

    public static final int BUTTON_INFUSE = 0;

    private static final Set<Holder<Attribute>> COMBAT_ATTRS = Set.of(
            Attributes.ATTACK_DAMAGE,
            Attributes.ATTACK_SPEED,
            Attributes.ARMOR,
            Attributes.ARMOR_TOUGHNESS,
            Attributes.KNOCKBACK_RESISTANCE
    );
    private static final Pattern PLUS_SUFFIX = Pattern.compile("\\s+\\+(\\d+)$");

    // ====== Server-authoritative soul cost sync (via vanilla ContainerData) ======
    // data[0] = known (0/1)
    // data[1] = cost (>=0)
    // data[2] = level that the server computed cost against (>=0)
    private static final int DATA_SOUL_KNOWN = 0;
    private static final int DATA_SOUL_COST  = 1;
    private static final int DATA_SOUL_LEVEL = 2;

    private final ContainerData synced = new SimpleContainerData(3);

    private final Container baseInv = new SimpleContainer(3) {
        @Override public void setChanged() {
            super.setChanged();
            if (suppressPreviewUpdate) return;
            try { AngelDemonMenu.this.slotsChanged(this); }
            catch (Throwable t) { LOG.error("[AngelDemonMenu] slotsChanged dispatch failed: {}", t.toString()); }
        }
    };

    private boolean suppressPreviewUpdate = false;
    private int previewChancePermille = 0;

    private RitualType ritual = RitualType.ANGEL; // default; set explicitly by server ctor/buf
    private BlockPos anchorPos = BlockPos.ZERO;
    private final Player owner;

    private long clientLockEndGameTime   = 0L; // 0 => no lock
    private int  clientLockDurationTicks = 0;

    private boolean clientOutcomeKnown = false;
    private boolean clientWillSucceed  = false;

    private long menuAttemptCounter = 0L;

    public AngelDemonMenu(int id, Inventory inv) {
        super(ModMenus.ANGEL_MENU.get(), id);
        this.owner = inv.player;
        this.ritual = RitualType.ANGEL;
        this.anchorPos = BlockPos.ZERO;

        buildSlots(inv);
        this.addDataSlots(synced);

        // Start unknown until server computes it.
        clientSetSoulCostUnknown("ctor(base)");

        if (this.owner != null && !this.owner.level().isClientSide) {
            try {
                loadSlotsFromAttachmentServer();
                LOG.debug("[AngelDemonMenu] (server base-ctor) loaded persisted slots; ritual={} anchor={}", this.ritual, this.anchorPos);
            } catch (Throwable t) {
                LOG.error("[AngelDemonMenu] (server base-ctor) failed to load persisted slots: {}", t.toString());
            }
        }

        updatePreview();
    }

    public AngelDemonMenu(int id, Inventory inv, RitualType ritual, BlockPos anchorPos) {
        super(ModMenus.ANGEL_MENU.get(), id);
        this.owner = inv.player;
        this.ritual = (ritual == null ? RitualType.ANGEL : ritual);
        this.anchorPos = (anchorPos == null ? BlockPos.ZERO : anchorPos);

        buildSlots(inv);
        this.addDataSlots(synced);

        clientSetSoulCostUnknown("ctor(ritual)");

        if (this.owner != null && !this.owner.level().isClientSide) {
            try {
                loadSlotsFromAttachmentServer();
                LOG.debug("[AngelDemonMenu] (server ritual-ctor) loaded persisted slots; ritual={} anchor={}", this.ritual, this.anchorPos);
            } catch (Throwable t) {
                LOG.error("[AngelDemonMenu] (server ritual-ctor) failed to load persisted slots: {}", t.toString());
            }
        }

        updatePreview();
    }

    public AngelDemonMenu(int id, Inventory inv, FriendlyByteBuf buf) {
        super(ModMenus.ANGEL_MENU.get(), id);
        this.owner = inv.player;

        buildSlots(inv);
        this.addDataSlots(synced);

        clientSetSoulCostUnknown("ctor(buf)");

        try {
            this.anchorPos = buf.readBlockPos();
            int ord = 0;
            try { ord = buf.readVarInt(); } catch (Throwable ignored) {}
            RitualType[] vals = RitualType.values();
            if (ord < 0 || ord >= vals.length) ord = 0;
            this.ritual = vals[ord];

            LOG.debug("[AngelDemonMenu] (buf-ctor) Context from buf: pos={} ritual={}", this.anchorPos, this.ritual);

            clientClearServerLock();
        } catch (Throwable t) {
            LOG.error("[AngelDemonMenu] Failed reading ritual context from buf: {}", t.toString());
            this.ritual = RitualType.ANGEL; // safe fallback
        }

        updatePreview();
    }

    private void buildSlots(Inventory inv) {
        this.addSlot(new Slot(baseInv, 0, INPUT1_X, INPUT1_Y) {
            @Override public boolean mayPlace(ItemStack stack) {
                if (owner != null && !owner.level().isClientSide) {
                    if (PendingStore.read((net.minecraft.server.level.ServerPlayer)owner).active()) return false;
                }
                boolean ok = isUpgradeableItem(stack);
                if (ok) {
                    if (isMiningTool(stack)) {
                        LOG.debug("[AngelDemonMenu] Accepted mining tool in input: {} ({})",
                                stack.getItem().toString(), describeToolClass(stack));
                    }
                }
                return ok;
            }
        });
        this.addSlot(new Slot(baseInv, 1, INPUT2_X, INPUT2_Y) {
            @Override public boolean mayPlace(ItemStack stack) {
                if (owner != null && !owner.level().isClientSide) {
                    if (PendingStore.read((net.minecraft.server.level.ServerPlayer)owner).active()) return false;
                }
                return SoulCageItem.isCage(stack);
            }
        });

        this.addSlot(new Slot(baseInv, 2, OUTPUT_X, OUTPUT_Y) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player player) {
                boolean real = hasRealResult();
                LOG.debug("[AngelDemonMenu] mayPickup slot2? realResult={}", real);
                return real;
            }
            @Override public void onTake(Player player, ItemStack stack) {
                super.onTake(player, stack);
                LOG.debug("[AngelDemonMenu] Player took infused result from output slot");
                withPreviewSuppressed(() -> baseInv.setItem(2, ItemStack.EMPTY));
                syncToClient("onTake result");
                updatePreview();
            }
        });

        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(inv, col + row * 9 + 9,
                        PLAYER_INV_X + col * SLOT_STEP,
                        PLAYER_INV_Y + row * SLOT_STEP));
            }
        }
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(inv, col,
                    HOTBAR_X + col * SLOT_STEP,
                    HOTBAR_Y));
        }
    }

    public void serverSetContext(RitualType ritual, BlockPos anchorPos) {
        if (this.owner != null && !this.owner.level().isClientSide) {
            this.ritual = (ritual == null ? RitualType.ANGEL : ritual);
            this.anchorPos = (anchorPos == null ? BlockPos.ZERO : anchorPos);
            loadSlotsFromAttachmentServer();
            LOG.debug("[AngelDemonMenu] serverSetContext -> ritual={} anchor={}", this.ritual, this.anchorPos);
        }
    }

    public RitualType ritual() { return ritual; }

    @Override public boolean stillValid(Player player) { return true; }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        LOG.debug("[AngelDemonMenu] slotsChanged: in={} {}, res={} {}, out={} {}, realOut={} (ritual={})",
                baseInv.getItem(0).getCount(), baseInv.getItem(0).getItem(),
                baseInv.getItem(1).getCount(), baseInv.getItem(1).getItem(),
                baseInv.getItem(2).getCount(), baseInv.getItem(2).getItem(),
                hasRealResult(), ritual);

        if (owner != null && !owner.level().isClientSide) {
            persistSlotsToAttachmentServer();
        }
        updatePreview();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id != BUTTON_INFUSE) {
            return super.clickMenuButton(player, id);
        }
        if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) {
            return true; // client should never process; swallow
        }
        final long now = sp.level().getGameTime();
        final PendingStore.Snapshot snap = PendingStore.read(sp);
        if (snap.active() && now < snap.end()) return true;

        onInfuseButtonPressed(sp);
        return true;
    }

    public long getClientLockEndGameTime()   { return clientLockEndGameTime; }
    public int  getClientLockDurationTicks() { return clientLockDurationTicks; }

    public void clientOnInfuseStarted(long endGameTime, int durationTicks) {
        this.clientLockEndGameTime   = Math.max(0L, endGameTime);
        this.clientLockDurationTicks = Math.max(0,  durationTicks);
        this.clientOutcomeKnown = false;
        this.clientWillSucceed  = false;
    }
    public void clientOnInfuseResult(boolean success) {
        this.clientLockEndGameTime   = 0L;
        this.clientLockDurationTicks = 0;
        this.clientOutcomeKnown = false;
        this.clientWillSucceed  = false;
    }
    public void clientOnEarlyOutcome(boolean willSucceed) {
        this.clientOutcomeKnown = true;
        this.clientWillSucceed  = willSucceed;
    }
    public void clientOnPendingState(PendingStateS2C payload) {
        if (!payload.active()) { clientOnInfuseResult(false); return; }
        clientOnInfuseStarted(payload.endGameTime(), payload.durationTicks());
        if (payload.outcomeKnown()) clientOnEarlyOutcome(payload.willSucceed());
    }

    public void clientApplyServerLock(long endGameTime, int durationTicks) { clientOnInfuseStarted(endGameTime, durationTicks); }
    public void clientClearServerLock() { clientOnInfuseResult(false); }

    public boolean isClientOutcomeKnown() { return clientOutcomeKnown; }
    public boolean getClientWillSucceed() { return clientWillSucceed; }

    // ====== Soul cost getters (client reads these; server owns them) ======
    public boolean isClientSoulCostKnown() {
        try { return synced.get(DATA_SOUL_KNOWN) != 0; }
        catch (Throwable t) { LOG.debug("[AngelDemonMenu] isClientSoulCostKnown read failed: {}", t.toString()); return false; }
    }
    public int getClientSoulCost() {
        try { return Math.max(0, synced.get(DATA_SOUL_COST)); }
        catch (Throwable t) { LOG.debug("[AngelDemonMenu] getClientSoulCost read failed: {}", t.toString()); return 0; }
    }
    public int getClientSoulCostLevel() {
        try { return Math.max(0, synced.get(DATA_SOUL_LEVEL)); }
        catch (Throwable t) { LOG.debug("[AngelDemonMenu] getClientSoulCostLevel read failed: {}", t.toString()); return 0; }
    }

    private void serverSetSoulCostKnown(int cost, int level, String reason) {
        if (owner == null || owner.level().isClientSide) return;
        int c = Math.max(0, cost);
        int l = Math.max(0, level);
        try {
            synced.set(DATA_SOUL_KNOWN, 1);
            synced.set(DATA_SOUL_COST, c);
            synced.set(DATA_SOUL_LEVEL, l);
            LOG.debug("[AngelDemonMenu] SoulCost SYNC set: known=1 cost={} level={} reason={}", c, l, reason);
        } catch (Throwable t) {
            LOG.error("[AngelDemonMenu] serverSetSoulCostKnown failed: {}", t.toString());
        }
    }

    private void serverSetSoulCostUnknown(String reason) {
        if (owner == null || owner.level().isClientSide) return;
        try {
            synced.set(DATA_SOUL_KNOWN, 0);
            synced.set(DATA_SOUL_COST, 0);
            synced.set(DATA_SOUL_LEVEL, 0);
            LOG.debug("[AngelDemonMenu] SoulCost SYNC set: known=0 reason={}", reason);
        } catch (Throwable t) {
            LOG.error("[AngelDemonMenu] serverSetSoulCostUnknown failed: {}", t.toString());
        }
    }

    private void clientSetSoulCostUnknown(String reason) {
        // Client-side init only; server will overwrite via sync.
        if (owner == null || !owner.level().isClientSide) return;
        try {
            synced.set(DATA_SOUL_KNOWN, 0);
            synced.set(DATA_SOUL_COST, 0);
            synced.set(DATA_SOUL_LEVEL, 0);
            LOG.debug("[AngelDemonMenu] (client init) SoulCost cleared: reason={}", reason);
        } catch (Throwable t) {
            LOG.debug("[AngelDemonMenu] (client init) SoulCost clear failed: {}", t.toString());
        }
    }

    public void onInfuseButtonPressed(Player player) {
        try {
            if (player == null || player.level().isClientSide) return;

            ItemStack in0  = baseInv.getItem(0);
            ItemStack out2 = baseInv.getItem(2);

            boolean outputIsReal = !out2.isEmpty() && !isPreview(out2);
            boolean useOutputAsInput = in0.isEmpty() && outputIsReal && isUpgradeableItem(out2);

            ItemStack effectiveIn = useOutputAsInput ? out2 : in0;
            ItemStack res         = baseInv.getItem(1);

            if (effectiveIn.isEmpty() || !isUpgradeableItem(effectiveIn)) {
                LOG.debug("[AngelDemonMenu] Infuse ignored: no valid upgradable item in input");
                return;
            }
            if (res.isEmpty() || !SoulCageItem.isCage(res)) {
                LOG.debug("[AngelDemonMenu] Infuse ignored: resource slot does not contain a Soul Cage");
                return;
            }

            PendingStore.Snapshot snap = PendingStore.read((net.minecraft.server.level.ServerPlayer)player);
            if (snap.active()) {
                LOG.debug("[AngelDemonMenu] Infuse ignored: server pending already active until {}", snap.end());
                return;
            }

            ItemStack originalCopy = effectiveIn.copy();
            int cur = readLevelFromTagOrName(effectiveIn);

            int soulCost = 0;
            try { soulCost = Math.max(0, UpgradeService.getSoulCostForNextLevel(cur)); }
            catch (Throwable t) { LOG.error("[AngelDemonMenu] getSoulCostForNextLevel failed: {}", t.toString()); soulCost = 0; }

            if (!SoulCageItem.hasAtLeast(res, soulCost)) {
                int available = SoulCageItem.getTotal(res);
                LOG.debug("[AngelDemonMenu] Infuse ignored: not enough souls in cage (have={}, need={})", available, soulCost);
                return;
            }

            double baseChance  = UpgradeService.getSuccessChance(cur);
            double bonus       = Reputation.computeBonusFor(player, ritual);
            double finalChance = Mth.clamp(baseChance + bonus, 0.0, 1.0);

            if (soulCost > 0) {
                boolean consumed = SoulCageItem.consumeUnits(res, soulCost);
                if (!consumed) {
                    LOG.warn("[AngelDemonMenu] Infuse aborted: consumeUnits failed after hasAtLeast check.");
                    return;
                }
                baseInv.setItem(1, res);
            }

            withPreviewSuppressed(() -> {
                if (useOutputAsInput) baseInv.setItem(2, ItemStack.EMPTY);
                else baseInv.setItem(0, ItemStack.EMPTY);
            });

            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                long now = player.level().getGameTime();
                long attemptId = ++this.menuAttemptCounter;
                double roll = AttemptRng.roll01(sp, this.anchorPos, this.ritual, now, attemptId);
                boolean success = (roll < finalChance);

                Reputation.applyAttemptDelta(sp, ritual, success);
                ModNet.sendRepSnapshotTo(sp);

                ItemStack upgradedIfSuccess = ItemStack.EMPTY;
                if (success) {
                    var r = UpgradeService.tryUpgradeWithRitual(originalCopy, player.getRandom(), ritual);
                    upgradedIfSuccess = r.upgraded();
                    clearPreviewTags(upgradedIfSuccess);
                }

                UpgradeServerConfig.Snapshot cfg = UpgradeServerConfig.snapshot();
                int delayTicks = Math.max(0, Mth.ceil(Math.max(0.0, cfg.infuseDelaySeconds) * 20.0));
                long end = now + delayTicks;

                PendingStore.arm(sp, end, delayTicks, success, ritual, anchorPos, originalCopy, upgradedIfSuccess);

                if (delayTicks > 0) {
                    ModNet.sendInfuseStartedTo(sp, this.containerId, end, delayTicks);
                    ModNet.sendEarlyOutcomeTo(sp, success);
                } else {
                    PendingStore.finalizeIfReady(sp, now);
                    ModNet.sendInfuseResultTo(sp, this.containerId, success);
                }

                persistSlotsToAttachmentServer();

                LOG.debug("[Infuse] armed: lvl={} ritual={} base={} bonus={} final={} roll={} success={} delayTicks={} (anchor={})",
                        cur, ritual,
                        String.format("%.3f", baseChance), String.format("%.3f", bonus),
                        String.format("%.3f", finalChance), String.format("%.5f", roll),
                        success, delayTicks, anchorPos);
            }
        } catch (Throwable t) {
            LOG.error("[AngelDemonMenu] onInfuseButtonPressed failed: {}", t.toString());
        }
    }

    @Override
    public void removed(Player player) {
        try { super.removed(player); } catch (Throwable t) { LOG.error("[AngelDemonMenu] super.removed threw: {}", t.toString()); }

        try {
            ItemStack out = baseInv.getItem(2);
            if (isPreview(out)) {
                withPreviewSuppressed(() -> baseInv.setItem(2, ItemStack.EMPTY));
            }
            if (player != null && !player.level().isClientSide) {
                persistSlotsToAttachmentServer();
            }
            withPreviewSuppressed(() -> {
                baseInv.setItem(0, ItemStack.EMPTY);
                baseInv.setItem(1, ItemStack.EMPTY);
                baseInv.setItem(2, ItemStack.EMPTY);
            });
            LOG.debug("[AngelDemonMenu] Menu closed; items persisted (ritual={})", ritual);
        } catch (Throwable t) {
            LOG.error("[AngelDemonMenu] removed() failed: {}", t.toString());
        }
    }

    private void updatePreview() {
        try {
            // Server-authoritative soul cost: default to unknown unless we compute a valid cost.
            if (owner != null && !owner.level().isClientSide) {
                serverSetSoulCostUnknown("updatePreview(start)");
            }

            // Keep existing semantics: do not overwrite real results with previews.
            if (owner != null && !owner.level().isClientSide) {
                if (hasRealResult()) return;
            }
            if (hasRealResult()) return;

            if (owner instanceof net.minecraft.server.level.ServerPlayer sp) {
                if (PendingStore.read(sp).active()) {
                    withPreviewSuppressed(() -> {
                        baseInv.setItem(2, ItemStack.EMPTY);
                        previewChancePermille = 0;
                    });
                    serverSetSoulCostUnknown("updatePreview(pending)");
                    return;
                }
            }

            ItemStack in = baseInv.getItem(0);
            ItemStack res = baseInv.getItem(1);

            if (in.isEmpty() || !isUpgradeableItem(in) || res.isEmpty() || !SoulCageItem.isCage(res)) {
                withPreviewSuppressed(() -> {
                    baseInv.setItem(2, ItemStack.EMPTY);
                    previewChancePermille = 0;
                });
                if (owner != null && !owner.level().isClientSide) {
                    serverSetSoulCostUnknown("updatePreview(missing inputs)");
                }
                return;
            }

            int currentLevel = readLevelFromTagOrName(in);
            int nextLevel = currentLevel + 1;
            UpgradeServerConfig.Snapshot snap = UpgradeServerConfig.snapshot();
            int maxLevel = snap.maxLevel;
            if (nextLevel > maxLevel) {
                withPreviewSuppressed(() -> {
                    baseInv.setItem(2, ItemStack.EMPTY);
                    previewChancePermille = 0;
                });
                if (owner != null && !owner.level().isClientSide) {
                    serverSetSoulCostUnknown("updatePreview(max level)");
                }
                return;
            }

            int soulCost = 0;
            try { soulCost = Math.max(0, UpgradeService.getSoulCostForNextLevel(currentLevel)); }
            catch (Throwable t) {
                LOG.error("[AngelDemonMenu] updatePreview: getSoulCostForNextLevel failed: {}", t.toString());
                soulCost = 0;
            }

            // ✅ Server authoritative: publish the computed cost + level to client.
            if (owner != null && !owner.level().isClientSide) {
                serverSetSoulCostKnown(soulCost, currentLevel, "updatePreview(valid)");
            }

            int availableSouls = SoulCageItem.getTotal(res);
            if (availableSouls < soulCost) {
                withPreviewSuppressed(() -> {
                    baseInv.setItem(2, ItemStack.EMPTY);
                    previewChancePermille = 0;
                });
                // cost is still known; UI can show "need X" vs "have Y"
                return;
            }

            double chance = UpgradeService.getSuccessChance(currentLevel);
            previewChancePermille = (int)Math.round(chance * 1000.0);

            double step = snap.percentBonusForLevelUp(currentLevel);
            double factor = 1.0 + step;

            ItemStack preview = in.copy();

            ItemAttributeModifiers cur = preview.getAttributeModifiers();
            ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();

            for (Entry e : cur.modifiers()) {
                Holder<Attribute> attr = e.attribute();
                AttributeModifier mod = e.modifier();
                if (!isCombatAttr(attr) || mod == null) {
                    builder.add(attr, mod, e.slot());
                    continue;
                }
                double scaled = mod.amount() * factor;
                AttributeModifier scaledMod = new AttributeModifier(mod.id(), scaled, mod.operation());
                builder.add(attr, scaledMod, e.slot());
            }

            if (cur.modifiers().isEmpty()) {
                ItemAttributeModifiers def = preview.getItem().getDefaultAttributeModifiers(preview);
                for (Entry e : def.modifiers()) {
                    Holder<Attribute> attr = e.attribute();
                    AttributeModifier mod = e.modifier();
                    if (!isCombatAttr(attr) || mod == null) {
                        builder.add(attr, mod, e.slot());
                    } else {
                        double scaled = mod.amount() * factor;
                        builder.add(attr, new AttributeModifier(mod.id(), scaled, mod.operation()), e.slot());
                    }
                }
            }

            preview.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());

            try {
                if (isMiningTool(in)) {
                    double curBonus = ToolSpeedUtil.getBonus(in);       // fraction
                    double nextBonus = Math.max(0.0, curBonus + step);  // same per-level step you show elsewhere
                    ToolSpeedUtil.setBonus(preview, nextBonus);
                    LOG.debug("[AngelDemonMenu] Preview tool_speed_bonus next={} (cur={} step={}) for {}",
                            String.format(java.util.Locale.ROOT, "%.5f", nextBonus),
                            String.format(java.util.Locale.ROOT, "%.5f", curBonus),
                            String.format(java.util.Locale.ROOT, "%.5f", step),
                            in.getItem());
                }
            } catch (Throwable t) {
                LOG.error("[AngelDemonMenu] updatePreview: tool_speed_bonus preview write failed: {}", t.toString());
            }

            CustomData cd = preview.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CustomData updated = cd.update(tag -> {
                tag.putBoolean("iu_preview", true);
                tag.putDouble("iu_step", step);
            });
            preview.set(DataComponents.CUSTOM_DATA, updated);

            withPreviewSuppressed(() -> baseInv.setItem(2, preview));
        } catch (Throwable t) {
            LOG.error("[AngelDemonMenu] updatePreview failed: {}", t.toString());
            withPreviewSuppressed(() -> {
                baseInv.setItem(2, ItemStack.EMPTY);
                previewChancePermille = 0;
            });
            if (owner != null && !owner.level().isClientSide) {
                serverSetSoulCostUnknown("updatePreview(exception)");
            }
        }
    }

    private void withPreviewSuppressed(Runnable r) {
        boolean prev = suppressPreviewUpdate;
        suppressPreviewUpdate = true;
        try { r.run(); } finally { suppressPreviewUpdate = prev; }
    }

    private void syncToClient(String reason) {
        try {
            LOG.debug("[AngelDemonMenu] syncToClient: {}", reason);
            this.broadcastChanges();
            try { this.sendAllDataToRemote(); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            LOG.error("[AngelDemonMenu] syncToClient failed: {}", t.toString());
        }
    }

    public void serverPullResultFromAttachment() {
        if (owner == null || owner.level().isClientSide) return;

        var type = (ritual == RitualType.ANGEL)
                ? ModAttachments.ANGEL_RITUAL_SLOTS.get()
                : ModAttachments.DEMON_RITUAL_SLOTS.get();

        var saved = owner.getData(type);
        if (saved == null) return;

        withPreviewSuppressed(() -> {
            baseInv.setItem(2, saved.s2().copy());
        });
        syncToClient("finalize pull result");
    }

    private boolean hasRealResult() {
        ItemStack out = baseInv.getItem(2);
        return !out.isEmpty() && !isPreview(out);
    }

    private static boolean isPreview(ItemStack stack) {
        try {
            if (stack.isEmpty()) return false;
            CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
            if (cd == null) return false;
            return cd.copyTag().getBoolean("iu_preview");
        } catch (Throwable ignored) { return false; }
    }

    private static void clearPreviewTags(ItemStack stack) {
        try {
            if (stack.isEmpty()) return;
            CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
            if (cd == null) return;
            CustomData cleaned = cd.update(tag -> {
                tag.remove("iu_preview");
                tag.remove("iu_step");
            });
            stack.set(DataComponents.CUSTOM_DATA, cleaned);
        } catch (Throwable t) {
            LOG.error("[AngelDemonMenu] clearPreviewTags failed", t);
        }
    }

    private static boolean isCombatAttr(Holder<Attribute> attr) {
        return attr != null && COMBAT_ATTRS.contains(attr);
    }

    private static boolean hasCombatAttributes(@Nullable ItemAttributeModifiers mods) {
        if (mods == null) return false;
        for (Entry e : mods.modifiers()) {
            Holder<Attribute> a = e.attribute();
            AttributeModifier m = e.modifier();
            if (isCombatAttr(a) && m != null && Math.abs(m.amount()) > 1.0E-4) return true;
        }
        return false;
    }

    private static boolean isUpgradeableItem(ItemStack stack) {
        return isMiningTool(stack) || isCombatItemLegacy(stack);
    }

    private static boolean isMiningTool(ItemStack stack) {
        return ToolSpeedUtil.isMiningTool(stack);
    }

    private static boolean isCombatItemLegacy(ItemStack stack) {
        if (stack.isEmpty()) return false;
        try {
            if (hasCombatAttributes(stack.getAttributeModifiers())) return true;
            ItemAttributeModifiers defs = stack.getItem().getDefaultAttributeModifiers(stack);
            if (hasCombatAttributes(defs)) return true;
            Item it = stack.getItem();
            if (it instanceof ArmorItem) return true;
            if (it instanceof SwordItem) return true;
            if (it instanceof DiggerItem) return true; // note: legacy path also allowed tools
            if (it instanceof TridentItem) return true;
            if (it instanceof BowItem) return true;
            if (it instanceof CrossbowItem) return true;
            if (it instanceof ShieldItem) return true;
        } catch (Throwable t) { LogUtils.getLogger().error("[AngelDemonMenu] isCombatItemLegacy failed: {}", t.toString()); }
        return false;
    }

    private static String describeToolClass(ItemStack s) {
        try {
            if (s.is(ItemTags.PICKAXES)) return "pickaxe";
            if (s.is(ItemTags.SHOVELS))  return "shovel";
            if (s.is(ItemTags.AXES))     return "axe";
            if (s.is(ItemTags.HOES))     return "hoe";
            if (s.getItem() instanceof DiggerItem) return "digger";
        } catch (Throwable ignored) {}
        return "tool";
    }

    private static String stripPlusSuffix(String s) {
        Matcher m = PLUS_SUFFIX.matcher(s);
        if (m.find()) return s.substring(0, m.start());
        return s;
    }

    private static int readLevelFromTagOrName(ItemStack stack) {
        try {
            CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
            if (cd != null) {
                var root = cd.copyTag().getCompound("iu_upgrade");
                if (root.contains("level", net.minecraft.nbt.Tag.TAG_INT)) {
                    int lvl = root.getInt("level");
                    return Mth.clamp(lvl, 0, 10000);
                }
            }
        } catch (Throwable ignored) {}
        try {
            String s = stack.getHoverName().getString();
            Matcher m = PLUS_SUFFIX.matcher(s);
            if (m.find()) return Mth.clamp(Integer.parseInt(m.group(1)), 0, 10000);
        } catch (Throwable ignored) {}
        return 0;
    }

    private void persistSlotsToAttachmentServer() {
        if (owner == null || owner.level().isClientSide) return;
        var type = (ritual == RitualType.ANGEL)
                ? ModAttachments.ANGEL_RITUAL_SLOTS.get()
                : ModAttachments.DEMON_RITUAL_SLOTS.get();

        ItemStack s0 = baseInv.getItem(0).copy();
        ItemStack s1 = baseInv.getItem(1).copy();
        ItemStack s2 = baseInv.getItem(2).copy();
        if (isPreview(s2)) s2 = ItemStack.EMPTY;
        owner.setData(type, new ModAttachments.RitualSlots(s0, s1, s2));
        LOG.debug("[AngelDemonMenu] Persisted ritual slots to attachment ({})", ritual);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        try {
            ItemStack empty = ItemStack.EMPTY;
            Slot slot = this.slots.get(index);
            if (slot == null || !slot.hasItem()) return empty;

            if (index == 2) {
                if (!hasRealResult()) return ItemStack.EMPTY;
                ItemStack stack = slot.getItem();
                ItemStack copy = stack.copy();
                if (!this.moveItemStackTo(stack, 3, 39, false)) return ItemStack.EMPTY;
                slot.onTake(player, stack);
                return copy;
            }

            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                if (PendingStore.read(sp).active()) return ItemStack.EMPTY;
            }

            ItemStack stack = slot.getItem();
            ItemStack copy = stack.copy();

            if (index >= 3) {
                if (isUpgradeableItem(stack)) {
                    if (!this.moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
                } else if (SoulCageItem.isCage(stack)) {
                    if (!this.moveItemStackTo(stack, 1, 2, false)) return ItemStack.EMPTY;
                } else {
                    return ItemStack.EMPTY;
                }
            } else {
                if (!this.moveItemStackTo(stack, 3, 39, false)) return ItemStack.EMPTY;
            }

            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();

            if (stack.getCount() == copy.getCount()) return ItemStack.EMPTY;
            slot.onTake(player, stack);
            return copy;
        } catch (Throwable t) {
            LogUtils.getLogger().error("[AngelDemonMenu] quickMoveStack failed: {}", t.toString());
            return ItemStack.EMPTY;
        }
    }

    private void loadSlotsFromAttachmentServer() {
        if (owner == null || owner.level().isClientSide) return;
        var type = (ritual == RitualType.ANGEL)
                ? ModAttachments.ANGEL_RITUAL_SLOTS.get()
                : ModAttachments.DEMON_RITUAL_SLOTS.get();
        ModAttachments.RitualSlots saved = owner.getData(type);
        if (saved == null) {
            LOG.debug("[AngelDemonMenu] No saved slots in attachment for ritual={}", ritual);
            return;
        }

        withPreviewSuppressed(() -> {
            baseInv.setItem(0, saved.s0().copy());
            baseInv.setItem(1, saved.s1().copy());
            baseInv.setItem(2, saved.s2().copy());
        });
        LOG.debug("[AngelDemonMenu] Loaded ritual slots from attachment ({})", ritual);
    }
}
