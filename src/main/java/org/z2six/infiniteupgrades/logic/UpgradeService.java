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
 * Server-authoritative upgrade engine used by the menu.
 *
 * Now enhanced to:
 *  - Support ritual-specific behavior (Angel=ALL, Demon=ONE).
 *  - Apply ritual step multipliers from SERVER config.
 *  - Persist full audit per upgraded attribute.
 *  - Keep legacy preview math intact (preview still uses COMMON Config.TUNING).
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

    // -------------------- Chance (base model only; rep handled in menu) --------------------

    public static double getSuccessChance(int currentLevel) {
        try {
            Snapshot s = UpgradeServerConfig.snapshot();

            Double ov = s.chanceOverrides.get(currentLevel);
            if (ov != null) return clamp01(ov);

            if (s.chanceModel == ChanceModelType.FLAT_DECREMENT) {
                double c = s.startChance - currentLevel * s.decrementPerLevel;
                return Math.max(0.0, Math.min(1.0, c));
            } else {
                double c = s.startChance * Math.pow(s.exponentialBase, Math.max(0, currentLevel));
                return Math.max(0.0, Math.min(1.0, c));
            }
        } catch (Throwable t) {
            LOG.error("[UpgradeService] getSuccessChance failed: {}", t.toString());
            try {
                return Config.TUNING.chanceForNextLevel(currentLevel);
            } catch (Throwable ignore) {
                return 0.0;
            }
        }
    }

    // -------------------- Public ritual API --------------------

    public static Result tryUpgradeWithRitual(ItemStack original, RandomSource rand, RitualType ritual) {
        if (original == null || original.isEmpty()) {
            return new Result(ItemStack.EMPTY, false, 0);
        }

        ItemStack copy = original.copy();
        int currentLevel = parseLevel(copy);

        // Ritual step multiplier from SERVER config
        Snapshot snap = UpgradeServerConfig.snapshot();
        double baseStep = Math.max(0.0, Config.TUNING.percentBonusForLevelUp(currentLevel));
        double mult = ritual == RitualType.ANGEL ? snap.angelStepMult : snap.demonStepMult;
        double step = baseStep * Math.max(0.0, mult);

        boolean ok;
        List<AttributeDelta> deltas;

        try {
            if (ritual == RitualType.ANGEL) {
                var res = upgradeAllSupportedAttributes(copy, step);
                ok = res.modified;
                deltas = res.deltas;
            } else {
                var res = upgradeOneRandomSupportedAttribute(copy, rand, step);
                ok = res.modified;
                deltas = ok ? List.of(res.delta) : List.of();
            }
        } catch (Throwable t) {
            LOG.error("[UpgradeService] tryUpgradeWithRitual failed: {}", t.toString());
            ok = false;
            deltas = List.of();
        }

        if (!ok) {
            return new Result(copy, false, currentLevel);
        }

        int newLevel = Math.min(currentLevel + 1, snap.maxLevel);

        try {
            writeAudit(copy, currentLevel, newLevel, deltas);
        } catch (Throwable t) {
            LOG.error("[UpgradeService] writeAudit (multi) failed: {}", t.toString());
        }

        try {
            applyColoredSuffix(copy, newLevel);
        } catch (Throwable t) {
            LOG.error("[UpgradeService] applyColoredSuffix failed: {}", t.toString());
        }

        return new Result(copy, true, newLevel);
    }

    /** Legacy single-attr upgrader kept for API compatibility (used nowhere now). */
    public static Result tryUpgrade(ItemStack original, RandomSource rand) {
        return tryUpgradeWithRitual(original, rand, RitualType.DEMON);
    }

    public static record Result(ItemStack upgraded, boolean success, int newLevel) {}

    // -------------------- Core logic --------------------

    private static final class AttributeDelta {
        final String attrKey;
        final String op;
        final double oldValue;
        final double newValue;
        final double deltaValue;
        final double appliedPercent;
        final String ruleId;
        AttributeDelta(String k, String op, double oldV, double newV, double dV, double pct, String rule) {
            this.attrKey = k; this.op = op; this.oldValue = oldV; this.newValue = newV;
            this.deltaValue = dV; this.appliedPercent = pct; this.ruleId = rule;
        }
    }

    private static final class OneDelta {
        final boolean modified;
        final AttributeDelta delta;
        OneDelta(boolean m, AttributeDelta d) { this.modified = m; this.delta = d; }
    }

    private static final class ManyDeltas {
        final boolean modified;
        final List<AttributeDelta> deltas;
        ManyDeltas(boolean m, List<AttributeDelta> dl) { this.modified = m; this.deltas = dl; }
    }

    private static OneDelta upgradeOneRandomSupportedAttribute(ItemStack stack, RandomSource rand, double step) {
        if (stack == null || stack.isEmpty()) return new OneDelta(false, null);

        // Collect
        List<Entry> current = new ArrayList<>(stack.getAttributeModifiers().modifiers());
        List<Entry> defaults = new ArrayList<>(stack.getItem().getDefaultAttributeModifiers(stack).modifiers());

        List<Entry> eligible = new ArrayList<>();
        for (Entry e : mergeLists(current, defaults)) {
            if (e == null) continue;
            Holder<Attribute> a = e.attribute();
            AttributeModifier m = e.modifier();
            if (a == null || m == null) continue;
            if (!SUPPORTED.contains(a)) continue;
            eligible.add(e);
        }
        if (eligible.isEmpty()) return new OneDelta(false, null);

        Entry chosen = eligible.get(rand.nextInt(eligible.size()));
        Holder<Attribute> chosenAttr = chosen.attribute();
        AttributeModifier chosenMod = chosen.modifier();

        double oldVal = chosenMod.amount();
        String opName = chosenMod.operation().name();

        AttributeModifier scaledChosen = scaleModifier(chosenAttr, chosenMod, step);
        double newVal = scaledChosen.amount();

        double appliedPercent = chosenAttr.is(Attributes.ATTACK_SPEED) ? -step : step;

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

        if (!modified) return new OneDelta(false, null);

        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());

        String attrKey = tryAttributeKey(chosenAttr);
        AttributeDelta delta = new AttributeDelta(
                attrKey, opName, oldVal, newVal, newVal - oldVal, appliedPercent, "percent_step:" + appliedPercent
        );
        return new OneDelta(true, delta);
    }

    private static ManyDeltas upgradeAllSupportedAttributes(ItemStack stack, double step) {
        if (stack == null || stack.isEmpty()) return new ManyDeltas(false, List.of());

        List<Entry> current = new ArrayList<>(stack.getAttributeModifiers().modifiers());
        List<Entry> defaults = new ArrayList<>(stack.getItem().getDefaultAttributeModifiers(stack).modifiers());

        List<Entry> merged = mergeListsUnique(current, defaults);
        if (merged.isEmpty()) return new ManyDeltas(false, List.of());

        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
        List<AttributeDelta> results = new ArrayList<>();
        boolean changed = false;

        for (Entry e : merged) {
            Holder<Attribute> a = e.attribute();
            AttributeModifier m = e.modifier();
            if (a == null || m == null) {
                builder.add(a, m, e.slot());
                continue;
            }
            if (!SUPPORTED.contains(a)) {
                builder.add(a, m, e.slot());
                continue;
            }

            double oldVal = m.amount();
            AttributeModifier scaled = scaleModifier(a, m, step);
            double newVal = scaled.amount();

            double appliedPercent = a.is(Attributes.ATTACK_SPEED) ? -step : step;

            builder.add(a, scaled, e.slot());
            changed = true;

            String attrKey = tryAttributeKey(a);
            results.add(new AttributeDelta(
                    attrKey, m.operation().name(), oldVal, newVal, newVal - oldVal, appliedPercent, "percent_step:" + appliedPercent
            ));
        }

        if (!changed) return new ManyDeltas(false, List.of());

        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
        return new ManyDeltas(true, results);
    }

    /** scale by +(step) normally, but attack speed uses (1 - step) */
    private static AttributeModifier scaleModifier(Holder<Attribute> attr, AttributeModifier mod, double step) {
        double amount = mod.amount();
        double scaled;

        if (attr.is(Attributes.ATTACK_SPEED)) {
            scaled = amount * (1.0 - step);
            if (mod.operation() != AttributeModifier.Operation.ADD_VALUE) {
                double min = 0.05;
                scaled = Math.max(min, scaled);
            }
        } else {
            scaled = amount * (1.0 + step);
        }

        return new AttributeModifier(mod.id(), scaled, mod.operation());
    }

    // -------------------- Audit + Name helpers --------------------

    private static void writeAudit(ItemStack stack, int levelBefore, int levelAfter, List<AttributeDelta> deltas) {
        if (stack == null || stack.isEmpty() || deltas == null || deltas.isEmpty()) return;

        CustomData cd = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CustomData updated = cd.update(tag -> {
            CompoundTag root = tag.getCompound("iu_upgrade");

            root.putInt("level", levelAfter);

            CompoundTag totals = root.getCompound("totals");

            ListTag hist = root.getList("history", Tag.TAG_COMPOUND);

            for (AttributeDelta d : deltas) {
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
            }

            root.put("totals", totals);
            root.put("history", hist);
            tag.put("iu_upgrade", root);
        });
        stack.set(DataComponents.CUSTOM_DATA, updated);
    }

    private static void applyColoredSuffix(ItemStack stack, int level) {
        String base = stripPlusSuffix(stack.getHoverName().getString());

        int rgb = UpgradeServerConfig.resolveSuffixColor(level);
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
            return holder.unwrapKey().map(k -> k.location().toString()).orElseGet(() -> {
                Attribute a = holder.value();
                return a.getDescriptionId();
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
}
