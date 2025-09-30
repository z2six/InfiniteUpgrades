// File: src/main/java/org/z2six/infiniteupgrades/world/menu/AngelDemonMenu.java
package org.z2six.infiniteupgrades.world.menu;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemAttributeModifiers.Entry;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.Infiniteupgrades;
import org.z2six.infiniteupgrades.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.logic.Reputation;
import org.z2six.infiniteupgrades.logic.RitualType;
import org.z2six.infiniteupgrades.logic.UpgradeService;
import org.z2six.infiniteupgrades.network.ModNet;
import org.z2six.infiniteupgrades.registry.ModMenus;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Angel/Demon menu (unified). Context-aware:
 *  - RitualType.ANGEL -> upgrade all eligible attributes each success
 *  - RitualType.DEMON -> upgrade one random eligible attribute each success
 *
 * Reputation:
 *  - Single unified scalar on the server; negative favors demon, positive favors angel.
 *  - Bonus for an attempt is computed from the scalar in the ritual's perspective.
 *  - We send a rep snapshot S2C after each actual attempt (resource consumed),
 *    so the client’s pointer updates immediately.
 *
 * Slot anchors are based on the **main.png** coordinates you provided.
 */
public class AngelDemonMenu extends AbstractContainerMenu {
    private static final Logger LOG = LogUtils.getLogger();

    // ---------------- Slot coordinates (main.png-relative anchors) ----------------
    // Two inputs + one output on main.png
    public static final int INPUT1_X = 63;
    public static final int INPUT1_Y = 37;

    public static final int INPUT2_X = 99;
    public static final int INPUT2_Y = 37;

    public static final int OUTPUT_X = 81;
    public static final int OUTPUT_Y = 73;

    // Player inventory grid (3x9), top-left anchor and spacing (16px slot + 2px gap = 18 step)
    private static final int PLAYER_INV_X = 9;
    private static final int PLAYER_INV_Y = 141;
    private static final int SLOT_STEP = 18;

    // Hotbar (1x9), top-left anchor
    private static final int HOTBAR_X = 9;
    private static final int HOTBAR_Y = 199;

    // Button id
    public static final int BUTTON_INFUSE = 0;

    // Consider these as "combat attributes" for filtering & scaling (preview)
    private static final Set<Holder<Attribute>> COMBAT_ATTRS = Set.of(
            Attributes.ATTACK_DAMAGE,
            Attributes.ATTACK_SPEED,
            Attributes.ARMOR,
            Attributes.ARMOR_TOUGHNESS,
            Attributes.KNOCKBACK_RESISTANCE
    );

    // Detect trailing " +N" in a name (space-plus-number at end)
    private static final Pattern PLUS_SUFFIX = Pattern.compile("\\s+\\+(\\d+)$");

    // Prevent recursive preview recomputation while we programmatically write slot 2
    private boolean suppressPreviewUpdate = false;

    // Backing inventory (3 slots) – invokes slotsChanged on edits
    private final Container baseInv = new SimpleContainer(3) {
        @Override
        public void setChanged() {
            super.setChanged();
            if (suppressPreviewUpdate) return;
            try {
                AngelDemonMenu.this.slotsChanged(this);
            } catch (Throwable t) {
                LOG.error("[AngelDemonMenu] slotsChanged dispatch failed: {}", t.toString());
            }
        }
    };

    // Cached preview chance (permille). Cosmetic; server is authoritative.
    private int previewChancePermille = 0;

    // ---- Ritual context ----
    private RitualType ritual = RitualType.ANGEL; // default
    private BlockPos anchorPos = BlockPos.ZERO;

    // ---- Server-authoritative pending infusion state (timer) ----
    private boolean pendingActive = false;
    private long pendingEndGameTime = -1L;           // server: level.getGameTime() tick to finalize
    private boolean pendingSuccess = false;          // precomputed outcome at attempt start
    private ItemStack pendingUpgraded = ItemStack.EMPTY; // precomputed upgraded result (if success)
    private ItemStack pendingOriginal = ItemStack.EMPTY; // snapshot of the original input (for restore/downgrade)

    // ---- Client-side convenience (for step 2 UI lock/anim) ----
    private long clientLockEndGameTime = -1L; // received via S2C; UI reads this for animation/lock
    private int clientLockDurationTicks = 0;

    public AngelDemonMenu(int id, Inventory inv) {
        super(ModMenus.ANGEL_MENU.get(), id);
        try {
            // Inputs
            this.addSlot(new Slot(baseInv, 0, INPUT1_X, INPUT1_Y) {
                @Override public boolean mayPlace(ItemStack stack) {
                    // Disallow placing while an infusion is pending
                    return !AngelDemonMenu.this.pendingActive && isCombatItem(stack);
                }
                @Override public boolean mayPickup(Player player) {
                    // Disallow removing while an infusion is pending
                    if (AngelDemonMenu.this.pendingActive) return false;
                    return super.mayPickup(player);
                }
            });
            this.addSlot(new Slot(baseInv, 1, INPUT2_X, INPUT2_Y) {
                @Override public boolean mayPlace(ItemStack stack) {
                    return !AngelDemonMenu.this.pendingActive && stack.is(Items.IRON_INGOT);
                }
                @Override public boolean mayPickup(Player player) {
                    if (AngelDemonMenu.this.pendingActive) return false;
                    return super.mayPickup(player);
                }
            });

            // Output (preview/result)
            this.addSlot(new Slot(baseInv, 2, OUTPUT_X, OUTPUT_Y) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean mayPickup(Player player) {
                    boolean real = hasRealResult();
                    LOG.debug("[AngelDemonMenu] mayPickup slot2? realResult={}", real);
                    return real;
                }
                @Override public void onTake(Player player, ItemStack stack) {
                    super.onTake(player, stack);
                    LOG.info("[AngelDemonMenu] Player took infused result from output slot");
                    withPreviewSuppressed(() -> baseInv.setItem(2, ItemStack.EMPTY));
                    // After taking, recompute ghost preview if inputs still valid
                    syncToClient("onTake result");
                    updatePreview();
                }
            });

            // Player inventory (3 rows x 9)
            for (int row = 0; row < 3; ++row) {
                for (int col = 0; col < 9; ++col) {
                    this.addSlot(new Slot(inv, col + row * 9 + 9,
                            PLAYER_INV_X + col * SLOT_STEP,
                            PLAYER_INV_Y + row * SLOT_STEP));
                }
            }
            // Hotbar (1 x 9)
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(inv, col,
                        HOTBAR_X + col * SLOT_STEP,
                        HOTBAR_Y));
            }

            // Initial preview
            updatePreview();
        } catch (Throwable t) {
            LOG.error("[AngelDemonMenu] ctor failed: {}", t.toString());
        }
    }

    public AngelDemonMenu(int id, Inventory inv, FriendlyByteBuf buf) {
        this(id, inv);
        try {
            // Buf from SigilBlockEntity contains BlockPos
            BlockPos bp = buf.readBlockPos();
            this.anchorPos = bp;

            // Derive ritual from block at that position
            var lvl = inv.player.level();
            if (lvl != null) {
                var st = lvl.getBlockState(bp);
                if (st != null) {
                    var b = st.getBlock();
                    if (b == Infiniteupgrades.UNHOLY_SIGIL.get()) {
                        this.ritual = RitualType.DEMON;
                    } else {
                        this.ritual = RitualType.ANGEL;
                    }
                }
            }
            LOG.debug("[AngelDemonMenu] Context: pos={} ritual={}", this.anchorPos, this.ritual);
        } catch (Throwable t) {
            LOG.error("[AngelDemonMenu] Failed reading ritual context from buf: {}", t.toString());
        }
    }

    public RitualType ritual() { return ritual; }

    @Override
    public boolean stillValid(Player player) {
        return true; // stateless for now
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        LOG.debug("[AngelDemonMenu] slotsChanged: in={}, res={}, out={}, realOut={} (ritual={})",
                baseInv.getItem(0), baseInv.getItem(1), baseInv.getItem(2), hasRealResult(), ritual);
        updatePreview();
    }

    /** Expose preview chance for HUD text if needed later. */
    public int getPreviewChancePermille() { return previewChancePermille; }

    /** Client-only nudge to recompute the ghost preview. */
    public void clientRecomputePreview() {
        updatePreview();
    }

    // ---- Buttons (server) ----------------------------------------------------------------------

    @Override
    public boolean clickMenuButton(Player player, int id) {
        LOG.info("[AngelDemonMenu] clickMenuButton id={} (serverSide={})", id, player != null && !player.level().isClientSide);
        if (id == BUTTON_INFUSE) {
            onInfuseButtonPressed(player);
            return true;
        }
        return super.clickMenuButton(player, id);
    }

    /** Called from server via clickMenuButton. */
    public void onInfuseButtonPressed(Player player) {
        try {
            if (player == null || player.level().isClientSide) {
                LOG.warn("[AngelDemonMenu] onInfuseButtonPressed ignored (player null or client-side)");
                return;
            }

            if (this.pendingActive) {
                LOG.debug("[AngelDemonMenu] Infuse ignored: attempt already pending until tick {}", pendingEndGameTime);
                return;
            }

            ItemStack in  = baseInv.getItem(0);
            ItemStack res = baseInv.getItem(1);

            // Validate inputs
            if (in.isEmpty() || !isCombatItem(in)) {
                LOG.debug("[AngelDemonMenu] Infuse pressed with invalid/missing combat item");
                return;
            }
            if (res.isEmpty() || !res.is(Items.IRON_INGOT)) {
                LOG.debug("[AngelDemonMenu] Infuse pressed without required resource");
                return;
            }

            // Snapshot the original input for restoration/downgrade on failure, and to remove from player access
            ItemStack originalCopy = in.copy();

            int cur = parseBaseNameAndLevel(in.getHoverName()).getSecond();

            // Base chance from SERVER model (+ reputation bonus)
            double baseChance = UpgradeService.getSuccessChance(cur);
            double bonus = Reputation.computeBonusFor(player, ritual);
            double finalChance = Mth.clamp(baseChance + bonus, 0.0, 1.0);
            boolean success = player.getRandom().nextDouble() < finalChance;

            // ---- Attempt starts: consume the resource immediately (authoritative) ----
            res.shrink(1);
            baseInv.setItem(1, res);

            // Remove the original input from access immediately to prevent duplication exploits.
            pendingOriginal = originalCopy;
            withPreviewSuppressed(() -> baseInv.setItem(0, ItemStack.EMPTY));

            // Reputation delta on attempt
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                Reputation.applyAttemptDelta(sp, ritual, success);
                // Send a fresh snapshot to the client (so the pointer moves immediately)
                ModNet.sendRepSnapshotTo(sp);
            } else {
                LOG.warn("[AngelDemonMenu] applyAttemptDelta skipped: not a ServerPlayer");
            }

            // Precompute upgraded result if success (use original snapshot)
            ItemStack upgradedIfSuccess = ItemStack.EMPTY;
            if (success) {
                UpgradeService.Result r = UpgradeService.tryUpgradeWithRitual(originalCopy, player.getRandom(), ritual);
                upgradedIfSuccess = r.upgraded();
                clearPreviewTags(upgradedIfSuccess); // ensure not marked as preview
            }

            // Server-authoritative delay
            UpgradeServerConfig.Snapshot snap = UpgradeServerConfig.snapshot();
            double sec = Math.max(0.0, snap.infuseDelaySeconds);
            int delayTicks = Math.max(0, Mth.ceil(sec * 20.0));
            long now = player.level().getGameTime();

            if (delayTicks <= 0) {
                // Finalize immediately (no timer)
                finalizePendingInternal(player, success, upgradedIfSuccess);
                // Clear any pending bookkeeping since we finalized synchronously
                this.pendingActive = false;
                this.pendingEndGameTime = -1L;
                this.pendingSuccess = false;
                this.pendingUpgraded = ItemStack.EMPTY;
                this.pendingOriginal = ItemStack.EMPTY;
            } else {
                // Arm pending
                this.pendingActive = true;
                this.pendingSuccess = success;
                this.pendingUpgraded = upgradedIfSuccess;
                this.pendingEndGameTime = now + delayTicks;

                // Notify client (UI lock/anim will respond)
                if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                    ModNet.sendInfuseStartedTo(sp, this.containerId, this.pendingEndGameTime, delayTicks);
                }

                // Resource & slot changes sync
                syncToClient("infuse attempt armed");
            }
        } catch (Throwable t) {
            LOG.error("[AngelDemonMenu] onInfuseButtonPressed failed: {}", t.toString());
        }
    }

    /**
     * Server tick hook, called from InfuseTimers. No-op unless a pending attempt exists.
     * @param nowGameTime current server level game time
     * @param sp          player owning this menu (same level)
     */
    public void serverTickPending(long nowGameTime, net.minecraft.server.level.ServerPlayer sp) {
        if (!pendingActive) return;
        if (nowGameTime < pendingEndGameTime) return;

        // Timer reached; finalize
        finalizePendingInternal(sp, pendingSuccess, pendingUpgraded);
        // Clear pending flags
        this.pendingActive = false;
        this.pendingEndGameTime = -1L;
        this.pendingSuccess = false;
        this.pendingUpgraded = ItemStack.EMPTY;
        this.pendingOriginal = ItemStack.EMPTY;
    }

    private void finalizePendingInternal(Player player, boolean success, ItemStack upgradedIfSuccess) {
        try {
            if (success) {
                LOG.info("[AngelDemonMenu] Infuse SUCCESS finalize (ritual={})", ritual);
                withPreviewSuppressed(() -> {
                    // Input was already removed at attempt start
                    baseInv.setItem(2, upgradedIfSuccess);        // real result into output
                });
            } else {
                LOG.info("[AngelDemonMenu] Infuse FAIL finalize (ritual={}) -> downgrade last level", ritual);
                if (!pendingOriginal.isEmpty()) {
                    // Downgrade +N -> +N-1 and place the downgraded item in OUTPUT
                    ItemStack downgraded = UpgradeService.downgradeLastLevel(pendingOriginal);
                    pendingOriginal = ItemStack.EMPTY;
                    withPreviewSuppressed(() -> baseInv.setItem(2, downgraded));
                }
                // No preview while a real result is present
            }

            syncToClient("infuse finalize");

            // Notify client: result ready (unlock UI etc.)
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                ModNet.sendInfuseResultTo(sp, this.containerId, success);
            }
        } catch (Throwable t) {
            LOG.error("[AngelDemonMenu] finalizePendingInternal failed: {}", t.toString());
        }
    }

    // ---- Shift-click rules ---------------------------------------------------------------------

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        try {
            ItemStack empty = ItemStack.EMPTY;
            Slot slot = this.slots.get(index);
            if (slot == null || !slot.hasItem()) return empty;

            // From OUTPUT (real result) -> player inventory
            if (index == 2) {
                if (!hasRealResult()) return ItemStack.EMPTY; // don't quick-move ghosts
                ItemStack stack = slot.getItem();
                ItemStack copy = stack.copy();
                if (!this.moveItemStackTo(stack, 3, 39, false)) return ItemStack.EMPTY;
                slot.onTake(player, stack);
                return copy;
            }

            // Disallow any mass-moving while a server attempt is pending (defensive)
            if (pendingActive) return ItemStack.EMPTY;

            ItemStack stack = slot.getItem();
            ItemStack copy = stack.copy();

            // From player inventory -> inputs
            if (index >= 3) {
                if (isCombatItem(stack)) {
                    if (!this.moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
                } else if (stack.is(Items.IRON_INGOT)) {
                    if (!this.moveItemStackTo(stack, 1, 2, false)) return ItemStack.EMPTY;
                } else {
                    return ItemStack.EMPTY;
                }
            } else {
                // From inputs -> back to player inventory
                if (!this.moveItemStackTo(stack, 3, 39, false)) return ItemStack.EMPTY;
            }

            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();

            if (stack.getCount() == copy.getCount()) return ItemStack.EMPTY;
            slot.onTake(player, stack);
            return copy;
        } catch (Throwable t) {
            LOG.error("[AngelDemonMenu] quickMoveStack failed: {}", t.toString());
            return ItemStack.EMPTY;
        }
    }

    // ---- Lifecycle: return inputs when the menu closes -----------------------------------------

    @Override
    public void removed(Player player) {
        try {
            super.removed(player);
        } catch (Throwable t) {
            LOG.error("[AngelDemonMenu] super.removed threw: {}", t.toString());
        }

        try {
            // If a pending infusion exists, finalize it NOW to avoid dupes/cancellation exploits.
            if (player != null && !player.level().isClientSide && pendingActive) {
                LOG.debug("[AngelDemonMenu] removed() with pending infusion -> finalize immediately");
                finalizePendingInternal(player, pendingSuccess, pendingUpgraded);
                // Clear pending flags after finalize
                this.pendingActive = false;
                this.pendingEndGameTime = -1L;
                this.pendingSuccess = false;
                this.pendingUpgraded = ItemStack.EMPTY;
                this.pendingOriginal = ItemStack.EMPTY;
            }

            // If slot 2 holds a GHOST preview, ensure it isn't returned.
            ItemStack out = baseInv.getItem(2);
            if (isPreview(out)) {
                withPreviewSuppressed(() -> baseInv.setItem(2, ItemStack.EMPTY));
            }

            // Return everything else (including a REAL result if present)
            this.clearContainer(player, baseInv);
            LOG.debug("[AngelDemonMenu] Menu closed; returned items (ritual={})", ritual);
        } catch (Throwable t) {
            LOG.error("[AngelDemonMenu] removed() failed to clear/return items: {}", t.toString());
        }
    }

    // ---- Preview logic (client-safe, no server mutation) ---------------------------------------

    /** Update preview item (slot 2) and cached chance text. */
    private void updatePreview() {
        try {
            // If we’re currently showing a REAL result, do not clobber it with a ghost
            if (hasRealResult()) {
                LOG.debug("[AngelDemonMenu] updatePreview skipped (real result present)");
                return;
            }

            // While an infusion is pending, do not show a ghost preview (inputs are locked/empty)
            if (pendingActive) {
                withPreviewSuppressed(() -> {
                    baseInv.setItem(2, ItemStack.EMPTY);
                    previewChancePermille = 0;
                });
                return;
            }

            ItemStack in = baseInv.getItem(0);
            ItemStack res = baseInv.getItem(1);

            // Requirements: valid combat item + at least 1 iron ingot
            if (in.isEmpty() || !isCombatItem(in) || res.isEmpty() || !res.is(Items.IRON_INGOT)) {
                withPreviewSuppressed(() -> {
                    baseInv.setItem(2, ItemStack.EMPTY);
                    previewChancePermille = 0;
                });
                return;
            }

            // Determine current level from name (… +N). If none, treat as 0.
            Pair<Component, Integer> baseAndLevel = parseBaseNameAndLevel(in.getHoverName());
            int currentLevel = baseAndLevel.getSecond();
            int nextLevel = currentLevel + 1;

            // Respect max level from SERVER config
            UpgradeServerConfig.Snapshot snap = UpgradeServerConfig.snapshot();
            int maxLevel = snap.maxLevel;
            if (nextLevel > maxLevel) {
                withPreviewSuppressed(() -> {
                    baseInv.setItem(2, ItemStack.EMPTY);
                    previewChancePermille = 0;
                });
                return;
            }

            // Chance for current -> next (preview uses base model; real attempt applies rep bonus)
            double chance = UpgradeService.getSuccessChance(currentLevel);
            previewChancePermille = (int)Math.round(chance * 1000.0);

            // Scale factor for THIS increment (server-authoritative preview tuning)
            double step = snap.percentBonusForLevelUp(currentLevel);
            double factor = 1.0 + step;

            // Build GHOST preview by scaling all combat modifiers (as before)
            ItemStack preview = in.copy();

            // Name: Base +N (strip any existing suffix first), with SERVER color thresholds
            ChatFormatting lvlColor = UpgradeServerConfig.nameColorForLevel(nextLevel);
            Component pretty = Component.literal(stripPlusSuffix(in.getHoverName().getString()))
                    .append(Component.literal(" +" + nextLevel).withStyle(lvlColor));
            preview.set(DataComponents.CUSTOM_NAME, pretty);

            // Attributes: read current modifiers then multiply combat amounts by factor
            ItemAttributeModifiers cur = preview.getAttributeModifiers();
            ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();

            for (Entry e : cur.modifiers()) {
                Holder<Attribute> attr = e.attribute();
                AttributeModifier mod = e.modifier();
                // Keep non-combat entries unchanged
                if (!isCombatAttr(attr) || mod == null) {
                    builder.add(attr, mod, e.slot());
                    continue;
                }
                // Multiply magnitude; preserve id/op/slot
                double scaled = mod.amount() * factor;
                AttributeModifier scaledMod = new AttributeModifier(mod.id(), scaled, mod.operation());
                builder.add(attr, scaledMod, e.slot());
            }

            // If no attribute component existed, try item defaults for this stack
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

            // Mark as a GHOST preview
            CustomData cd = preview.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CustomData updated = cd.update(tag -> {
                tag.putBoolean("iu_preview", true);
                tag.putDouble("iu_step", step); // informational; screen ignores the number
            });
            preview.set(DataComponents.CUSTOM_DATA, updated);

            // Put the ghost into the output slot
            withPreviewSuppressed(() -> baseInv.setItem(2, preview));
        } catch (Throwable t) {
            LOG.error("[AngelDemonMenu] updatePreview failed: {}", t.toString());
            withPreviewSuppressed(() -> {
                baseInv.setItem(2, ItemStack.EMPTY);
                previewChancePermille = 0;
            });
        }
    }

    /** Run an action while suppressing setChanged->slotsChanged feedback from our ghost writes. */
    private void withPreviewSuppressed(Runnable r) {
        boolean prev = suppressPreviewUpdate;
        suppressPreviewUpdate = true;
        try { r.run(); } finally { suppressPreviewUpdate = prev; }
    }

    private void syncToClient(String reason) {
        try {
            LOG.debug("[AngelDemonMenu] syncToClient: {}", reason);
            this.broadcastChanges();
            try {
                this.sendAllDataToRemote();
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            LOG.error("[AngelDemonMenu] syncToClient failed: {}", t.toString());
        }
    }

    // ---- Helpers ------------------------------------------------------------------------------

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
        } catch (Throwable ignored) {
            return false;
        }
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
            LOG.error("[AngelDemonMenu] clearPreviewTags failed: {}", t.toString());
        }
    }

    private static boolean isCombatAttr(Holder<Attribute> attr) {
        return attr != null && COMBAT_ATTRS.contains(attr);
    }

    private static boolean hasCombatAttributes(ItemAttributeModifiers mods) {
        if (mods == null) return false;
        for (Entry e : mods.modifiers()) {
            Holder<Attribute> a = e.attribute();
            AttributeModifier m = e.modifier();
            if (isCombatAttr(a) && m != null && Math.abs(m.amount()) > 1.0E-4) return true;
        }
        return false;
    }

    private boolean isCombatItem(ItemStack stack) {
        if (stack.isEmpty()) return false;

        try {
            if (hasCombatAttributes(stack.getAttributeModifiers())) return true;
            ItemAttributeModifiers defs = stack.getItem().getDefaultAttributeModifiers(stack);
            if (hasCombatAttributes(defs)) return true;

            // Class fallback to catch simple tools/armor that might not carry components
            Item it = stack.getItem();
            if (it instanceof ArmorItem) return true;
            if (it instanceof SwordItem) return true;
            if (it instanceof DiggerItem) return true;
            if (it instanceof TridentItem) return true;
            if (it instanceof BowItem) return true;
            if (it instanceof CrossbowItem) return true;
            if (it instanceof ShieldItem) return true;
        } catch (Throwable t) {
            LOG.error("[AngelDemonMenu] isCombatItem failed: {}", t.toString());
        }

        return false;
    }

    /** Returns (baseNameComponent, level). If no "+N", level = 0. */
    private static Pair<Component, Integer> parseBaseNameAndLevel(Component name) {
        try {
            String s = name.getString();
            Matcher m = PLUS_SUFFIX.matcher(s);
            if (m.find()) {
                int lvl = Mth.clamp(Integer.parseInt(m.group(1)), 0, 10000);
                String base = s.substring(0, m.start());
                return Pair.of(Component.literal(base), lvl);
            }
        } catch (Throwable ignored) {}
        return Pair.of(name.copy(), 0);
    }

    /** Strip trailing " +N" (if any). */
    private static String stripPlusSuffix(String s) {
        Matcher m = PLUS_SUFFIX.matcher(s);
        if (m.find()) return s.substring(0, m.start());
        return s;
    }

    // ---------- Client helper hooks (used by S2C; UI reads these) ----------

    public void clientOnInfuseStarted(long endGameTime, int durationTicks) {
        this.clientLockEndGameTime = endGameTime;
        this.clientLockDurationTicks = durationTicks;
    }

    public void clientOnInfuseResult(boolean success) {
        this.clientLockEndGameTime = -1L;
        this.clientLockDurationTicks = 0;
        // no further action here (server already synced slots)
    }

    public long getClientLockEndGameTime() { return clientLockEndGameTime; }
    public int getClientLockDurationTicks() { return clientLockDurationTicks; }
}
