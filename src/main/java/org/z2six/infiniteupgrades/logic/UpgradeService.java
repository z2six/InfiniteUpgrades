// MainFile: src/main/java/org/z2six/infiniteupgrades/logic/UpgradeService.java
package org.z2six.infiniteupgrades.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemAttributeModifiers.Entry;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.Config;
import org.z2six.infiniteupgrades.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.config.UpgradeServerConfig.ChanceModelType;
import org.z2six.infiniteupgrades.config.UpgradeServerConfig.Snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal, self-contained upgrade engine used by AngelMenu.
 *
 * Now enhanced to:
 *  - Use SERVER config (UpgradeServerConfig) for chance logic.
 *  - Persist a full audit trail into the item (CUSTOM_DATA key "iu_upgrade"):
 *      level, history[], and per-attribute totals.
 *  - Apply the colored " +N" suffix to the item's name on successful infusion
 *    using server-configured color tiers.
 *
 * Existing preview math in the GUI still reads from Config.TUNING (your older COMMON config)
 * for now, so nothing that worked before is broken. Server authority applies at the moment of
 * actual infusion (this class).
 */
public final class UpgradeService {
    private static final Logger LOG = LogUtils.getLogger();

    private static final Pattern PLUS_SUFFIX = Pattern.compile("\\s+\\+(\\d+)$");

    private static final Set<Holder<Attribute>> SUPPORTED = Set.of(
            Attributes.ATTACK_DAMAGE,
            Attributes.ATTACK_SPEED,
            Attributes.ARMOR,
            Attributes.ARMOR_TOUGHNESS,
            Attributes.KNOCKBACK_RESISTANCE
    );

    private UpgradeService() {}

    // -------------------- Public API used by AngelMenu --------------------

    /**
     * Success chance for current level, using SERVER config (authoritative).
     */
    public static double getSuccessChance(int currentLevel) {
        try {
            Snapshot s = UpgradeServerConfig.snapshot();

            // Per-level override wins
            Double ov = s.chanceOverrides.get(currentLevel);
            if (ov != null) return clamp01(ov);

            // Model-based fallback
            if (s.chanceModel == ChanceModelType.FLAT_DECREMENT) {
                double c = s.startChance - currentLevel * s.decrementPerLevel;
                return Math.max(0.0, Math.min(1.0, c));
            } else {
                // EXPONENTIAL: start * base^(currentLevel)
                double c = s.startChance * Math.pow(s.exponentialBase, Math.max(0, currentLevel));
                return Math.max(0.0, Math.min(1.0, c));
            }
        } catch (Throwable t) {
            LOG.error("[UpgradeService] getSuccessChance failed: {}", t.toString());
            // Defensive fallback to previous COMMON tuning if server config borked
            try {
                return Config.TUNING.chanceForNextLevel(currentLevel);
            } catch (Throwable ignore) {
                return 0.0;
            }
        }
    }

    /** Result wrapper for upgrades. */
    public static record Result(ItemStack upgraded, boolean success, int newLevel) {}

    /**
     * Perform the actual upgrade.
     * - Works on a copy of the provided stack (original remains untouched).
     * - Returns a Result with the upgraded stack, success flag, and new level.
     * - On success: persists audit data, bumps +N level, and applies colored suffix.
     */
    public static Result tryUpgrade(ItemStack original, RandomSource rand) {
        if (original == null || original.isEmpty()) {
            return new Result(ItemStack.EMPTY, false, 0);
        }

        ItemStack copy = original.copy();
        int currentLevel = parseLevel(copy);
        boolean ok = false;
        AttributeDelta delta = null;

        try {
            DeltaAndFlag res = upgradeOneRandomSupportedAttribute(copy, rand);
            ok = res.modified;
            delta = res.delta;
        } catch (Throwable t) {
            LOG.error("[UpgradeService] tryUpgrade failed: {}", t.toString());
        }

        if (!ok) {
            return new Result(copy, false, currentLevel);
        }

        // Enforce max level from SERVER config
        int newLevel = Math.min(currentLevel + 1, UpgradeServerConfig.snapshot().maxLevel);

        // Persist audit trail & bump level field under iu_upgrade
        try {
            writeAudit(copy, currentLevel, newLevel, delta);
        } catch (Throwable t) {
            LOG.error("[UpgradeService] writeAudit failed: {}", t.toString());
        }

        // Apply colored name suffix " +N" (server-configured tiers)
        try {
            applyColoredSuffix(copy, newLevel);
        } catch (Throwable t) {
            LOG.error("[UpgradeService] applyColoredSuffix failed: {}", t.toString());
        }

        return new Result(copy, true, newLevel);
    }

    // -------------------- Core logic --------------------

    /**
     * Holder for a single attribute change performed during the upgrade.
     */
    private static final class AttributeDelta {
        final String attrKey;              // e.g. "minecraft:generic.attack_damage" or best-effort
        final String op;                   // AttributeModifier.Operation name
        final double oldValue;
        final double newValue;
        final double deltaValue;           // new - old (raw)
        final double appliedPercent;       // fraction e.g. +0.05 or -0.05 (best-effort)
        final String ruleId;               // simple description
        AttributeDelta(String k, String op, double oldV, double newV, double dV, double pct, String rule) {
            this.attrKey = k; this.op = op; this.oldValue = oldV; this.newValue = newV;
            this.deltaValue = dV; this.appliedPercent = pct; this.ruleId = rule;
        }
    }

    private static final class DeltaAndFlag {
        final boolean modified;
        final AttributeDelta delta;
        DeltaAndFlag(boolean m, AttributeDelta d) { this.modified = m; this.delta = d; }
    }

    /**
     * Upgrades ONE random supported attribute. Returns whether any change was made
     * and the exact delta facts for audit/tooltip purposes.
     */
    private static DeltaAndFlag upgradeOneRandomSupportedAttribute(ItemStack stack, RandomSource rand) {
        if (stack == null || stack.isEmpty()) return new DeltaAndFlag(false, null);

        int level = parseLevel(stack);

        // We keep using your original step math for now (COMMON config), because
        // preview and earlier logic depend on it. Server rules for which attribute
        // to change will come later when we expand rule system; here we preserve behavior.
        double step = Math.max(0.0, Config.TUNING.percentBonusForLevelUp(level));

        // Collect all supported modifier entries (current + defaults)
        List<Entry> current = new ArrayList<>(stack.getAttributeModifiers().modifiers());
        List<Entry> defaults = new ArrayList<>(stack.getItem().getDefaultAttributeModifiers(stack).modifiers());

        // Decide which entries are eligible (by attribute id)
        List<Entry> eligible = new ArrayList<>();
        for (Entry e : mergeLists(current, defaults)) {
            if (e == null) continue;
            Holder<Attribute> a = e.attribute();
            AttributeModifier m = e.modifier();
            if (a == null || m == null) continue;
            if (!SUPPORTED.contains(a)) continue;
            eligible.add(e);
        }
        if (eligible.isEmpty()) return new DeltaAndFlag(false, null);

        // RANDOM pick: choose one entry to modify
        Entry chosen = eligible.get(rand.nextInt(eligible.size()));
        Holder<Attribute> chosenAttr = chosen.attribute();
        AttributeModifier chosenMod = chosen.modifier();

        double oldVal = chosenMod.amount();
        String opName = chosenMod.operation().name();

        // Compute new value for the chosen entry
        AttributeModifier scaledChosen = scaleModifier(chosenAttr, chosenMod, step);
        double newVal = scaledChosen.amount();

        // Best-effort applied percent (sign-aware):
        double appliedPercent = chosenAttr.is(Attributes.ATTACK_SPEED) ? -step : step;

        // Rebuild all modifiers, replacing ONLY the chosen entry with the scaled version
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
        boolean modified = false;

        for (Entry e : mergeListsUnique(current, defaults)) {
            Holder<Attribute> a = e.attribute();
            AttributeModifier m = e.modifier();
            if (a == null || m == null) {
                builder.add(a, m, e.slot());
                continue;
            }

            if (!modified && sameEntry(e, chosen)) {
                builder.add(a, scaledChosen, e.slot());
                modified = true;
            } else {
                builder.add(a, m, e.slot());
            }
        }

        if (!modified) return new DeltaAndFlag(false, null);

        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());

        // Build delta facts for audit
        String attrKey = tryAttributeKey(chosenAttr);
        AttributeDelta delta = new AttributeDelta(
                attrKey,
                opName,
                oldVal,
                newVal,
                newVal - oldVal,
                appliedPercent,
                "percent_step:" + appliedPercent
        );
        return new DeltaAndFlag(true, delta);
    }

    /** scale by +(step) normally, but attack speed uses (1 - step) */
    private static AttributeModifier scaleModifier(Holder<Attribute> attr, AttributeModifier mod, double step) {
        double amount = mod.amount();
        double scaled;

        if (attr.is(Attributes.ATTACK_SPEED)) {
            // smaller number => faster attacks; scale down by (1 - step)
            scaled = amount * (1.0 - step);

            // Only apply a safety floor for multiplicative ops; keep ADD_VALUE as-is (many tools are negative by design)
            if (mod.operation() != AttributeModifier.Operation.ADD_VALUE) {
                double min = 0.05; // guardrail against zero/negative in multiplicative contexts
                scaled = Math.max(min, scaled);
            }
        } else {
            // “Normal” attributes scale up
            scaled = amount * (1.0 + step);
        }

        return new AttributeModifier(mod.id(), scaled, mod.operation());
    }

    // -------------------- Audit + Name helpers --------------------

    private static void writeAudit(ItemStack stack, int levelBefore, int levelAfter, AttributeDelta d) {
        if (stack == null || stack.isEmpty() || d == null) return;

        CustomData cd = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CustomData updated = cd.update(tag -> {
            CompoundTag root = tag.getCompound("iu_upgrade");

            // Level
            root.putInt("level", levelAfter);

            // Totals (per attribute)
            CompoundTag totals = root.getCompound("totals");
            CompoundTag a = totals.getCompound(d.attrKey);

            int count = a.getInt("count") + 1;
            double sumDelta = a.getDouble("sumDelta") + d.deltaValue;
            double sumPercent = a.getDouble("sumPercent") + d.appliedPercent;

            a.putInt("count", count);
            a.putDouble("sumDelta", sumDelta);
            a.putDouble("sumPercent", sumPercent);
            a.putDouble("lastDelta", d.deltaValue);
            a.putDouble("lastPercent", d.appliedPercent);
            totals.put(d.attrKey, a);

            // History
            ListTag hist = root.getList("history", Tag.TAG_COMPOUND);
            CompoundTag ev = new CompoundTag();
            ev.putLong("time", System.currentTimeMillis());
            ev.putInt("levelBefore", levelBefore);
            ev.putInt("levelAfter", levelAfter);
            ev.putString("attribute", d.attrKey);
            ev.putString("op", d.op);
            ev.putDouble("old", d.oldValue);
            ev.putDouble("delta", d.deltaValue);
            ev.putDouble("new", d.newValue);
            ev.putDouble("stepPercent", d.appliedPercent);
            ev.putString("ruleId", d.ruleId);
            hist.add(ev);

            root.put("history", hist);
            tag.put("iu_upgrade", root);
        });
        stack.set(DataComponents.CUSTOM_DATA, updated);
    }

    private static void applyColoredSuffix(ItemStack stack, int level) {
        // Base name (strip prior +N)
        String base = stripPlusSuffix(stack.getHoverName().getString());

        // Determine color from SERVER config tiers
        int rgb = UpgradeServerConfig.resolveSuffixColor(level);
        // If config returned 0 (unset), fall back to AQUA (vanilla-like)
        ChatFormatting fallback = ChatFormatting.AQUA;

        MutableComponent pretty = Component.literal(base);
        MutableComponent suffix = Component.literal(" +" + level);

        if (rgb != 0) {
            suffix = suffix.withStyle(s -> s.withColor(rgb));
        } else {
            suffix = suffix.withStyle(fallback);
        }

        pretty = pretty.append(suffix);
        stack.set(DataComponents.CUSTOM_NAME, pretty);
    }

    private static String tryAttributeKey(Holder<Attribute> holder) {
        try {
            // Attempt to get a stable registry location, e.g., minecraft:generic.attack_damage
            return holder.unwrapKey().map(k -> k.location().toString()).orElseGet(() -> {
                Attribute a = holder.value();
                return a.getDescriptionId(); // fallback (translatable id)
            });
        } catch (Throwable t) {
            return String.valueOf(holder);
        }
    }

    // -------------------- Small helpers --------------------

    private static int parseLevel(ItemStack stack) {
        try {
            Component name = stack.getHoverName();
            String s = name.getString();
            Matcher m = PLUS_SUFFIX.matcher(s);
            if (m.find()) {
                return Mth.clamp(Integer.parseInt(m.group(1)), 0, 10000);
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static String stripPlusSuffix(String s) {
        Matcher m = PLUS_SUFFIX.matcher(s);
        if (m.find()) return s.substring(0, m.start());
        return s;
    }

    private static boolean sameEntry(Entry a, Entry b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        // Consider same attribute + same id + same op + same slot as "same"
        AttributeModifier am = a.modifier();
        AttributeModifier bm = b.modifier();
        if (am == null || bm == null) return false;
        return a.attribute().equals(b.attribute())
                && am.id().equals(bm.id())
                && am.operation() == bm.operation()
                && a.slot() == b.slot();
    }

    private static List<Entry> mergeLists(List<Entry> a, List<Entry> b) {
        List<Entry> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    /** Merge current+defaults but avoid exact duplicate (same attribute/id/op/slot). */
    private static List<Entry> mergeListsUnique(List<Entry> current, List<Entry> defaults) {
        List<Entry> out = new ArrayList<>(current);
        for (Entry e : defaults) {
            boolean dup = false;
            for (Entry c : current) {
                if (sameEntry(e, c)) { dup = true; break; }
            }
            if (!dup) out.add(e);
        }
        return out;
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }

    // Optional: small debug pretty-printer
    @SuppressWarnings("unused")
    private static Component prettyAttrName(Holder<Attribute> a) {
        try {
            return Component.translatable(a.value().getDescriptionId()).withStyle(ChatFormatting.GRAY);
        } catch (Throwable ignored) {
            return Component.literal(String.valueOf(a)).withStyle(ChatFormatting.GRAY);
        }
    }
}
