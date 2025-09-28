// File: src/main/java/org/z2six/infiniteupgrades/logic/UpgradeService.java
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemAttributeModifiers.Entry;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.config.UpgradeServerConfig.ChanceModelType;
import org.z2six.infiniteupgrades.config.UpgradeServerConfig.Snapshot;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-authoritative upgrade engine used by the menu.
 *
 * Dynamic rules:
 *  - Rules come from UpgradeServerConfig.snapshot().attributes (defaults + attributesDynamic.rules).
 *  - Angel ritual: apply ALL matching rules to attributes present on the item.
 *  - Demon ritual: choose ONE matching rule weighted by 'weight' and apply it.
 *
 * Chance remains in this service; reputation bonus is handled by the menu when clicking.
 */
public final class UpgradeService {
    private static final Logger LOG = LogUtils.getLogger();

    private static final Pattern PLUS_SUFFIX = Pattern.compile("\\s+\\+(\\d+)$");

    private UpgradeService() {}

    // -------------------- Chance (base model only; rep handled in menu) --------------------

    public static double getSuccessChance(int currentLevel) {
        try {
            Snapshot s = UpgradeServerConfig.snapshot();

            Double ov = s.chanceOverrides.get(currentLevel);
            if (ov != null) return clamp01(ov);

            if (s.chanceModel == ChanceModelType.FLAT_DECREMENT) {
                double c = s.startChance - currentLevel * s.decrementPerLevel;
                c = Math.max(s.minChance, c); // NEW: respect server-configured floor
                return Math.max(0.0, Math.min(1.0, c));
            } else {
                double c = s.startChance * Math.pow(s.exponentialBase, Math.max(0, currentLevel));
                return Math.max(0.0, Math.min(1.0, c));
            }
        } catch (Throwable t) {
            LOG.error("[UpgradeService] getSuccessChance failed: {}", t.toString());
            // Defensive: if server config borked, return 0
            return 0.0;
        }
    }

    // -------------------- Public ritual API --------------------

    public static Result tryUpgradeWithRitual(ItemStack original, RandomSource rand, RitualType ritual) {
        if (original == null || original.isEmpty()) {
            return new Result(ItemStack.EMPTY, false, 0);
        }

        ItemStack copy = original.copy();
        int currentLevel = parseLevel(copy);
        Snapshot snap = UpgradeServerConfig.snapshot();

        // Collect enabled rules into a map for quick lookup
        Map<ResourceLocation, UpgradeServerConfig.AttributeRuleConfig> ruleById = new LinkedHashMap<>();
        for (var r : snap.attributes) {
            if (r.enabled) ruleById.put(r.id, r);
        }
        if (ruleById.isEmpty()) return new Result(copy, false, currentLevel);

        // Merge current+default entries once
        List<Entry> current = new ArrayList<>(safeModifiers(copy).modifiers());
        List<Entry> defaults = new ArrayList<>(copy.getItem().getDefaultAttributeModifiers(copy).modifiers());
        List<Entry> working = mergeListsUnique(current, defaults);
        if (working.isEmpty()) return new Result(copy, false, currentLevel);

        // Identify which attribute ids are present AND have rules
        List<ResourceLocation> presentIds = new ArrayList<>();
        for (Entry e : working) {
            ResourceLocation id = idOf(e.attribute());
            if (id != null && ruleById.containsKey(id) && !presentIds.contains(id)) {
                presentIds.add(id);
            }
        }
        if (presentIds.isEmpty()) return new Result(copy, false, currentLevel);

        double ritualMult = (ritual == RitualType.ANGEL) ? snap.angelStepMult : snap.demonStepMult;

        List<AttributeDelta> allDeltas = new ArrayList<>();
        boolean changed = false;

        if (ritual == RitualType.ANGEL) {
            // Apply ALL rules sequentially, carrying the result forward
            for (ResourceLocation id : presentIds) {
                RuleResult rr = applyRule(working, id, ruleById.get(id), currentLevel, ritualMult);
                working = rr.updated;
                if (!rr.deltas.isEmpty()) {
                    allDeltas.addAll(rr.deltas);
                    changed = true;
                }
            }
        } else {
            // DEMON: choose ONE present id weighted by rule.weight
            int totalW = 0;
            for (ResourceLocation id : presentIds) totalW += Math.max(0, ruleById.get(id).weight);
            if (totalW <= 0) totalW = presentIds.size();

            int r = rand.nextInt(totalW);
            ResourceLocation chosen = presentIds.get(0);
            int acc = 0;
            for (ResourceLocation id : presentIds) {
                acc += Math.max(0, ruleById.get(id).weight);
                if (r < acc) { chosen = id; break; }
            }

            RuleResult rr = applyRule(working, chosen, ruleById.get(chosen), currentLevel, ritualMult);
            working = rr.updated;
            if (!rr.deltas.isEmpty()) {
                allDeltas.addAll(rr.deltas);
                changed = true;
            }
        }

        if (!changed) return new Result(copy, false, currentLevel);

        // Commit modifiers
        copy.set(DataComponents.ATTRIBUTE_MODIFIERS, fromEntries(working));

        // Bump level & persist audit
        int newLevel = Math.min(currentLevel + 1, snap.maxLevel);

        try {
            writeAudit(copy, currentLevel, newLevel, allDeltas);
        } catch (Throwable t) {
            LOG.error("[UpgradeService] writeAudit failed: {}", t.toString());
        }

        try {
            applyColoredSuffix(copy, newLevel);
        } catch (Throwable t) {
            LOG.error("[UpgradeService] applyColoredSuffix failed: {}", t.toString());
        }

        return new Result(copy, true, newLevel);
    }

    /** Legacy single-attr upgrader kept for API compatibility (menu uses tryUpgradeWithRitual). */
    public static Result tryUpgrade(ItemStack original, RandomSource rand) {
        return tryUpgradeWithRitual(original, rand, RitualType.DEMON);
    }

    public static record Result(ItemStack upgraded, boolean success, int newLevel) {}

    // -------------------- Rule application --------------------

    private static class AttributeDelta {
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

    private static class RuleResult {
        final List<Entry> updated;
        final List<AttributeDelta> deltas;
        RuleResult(List<Entry> updated, List<AttributeDelta> deltas) {
            this.updated = updated;
            this.deltas = deltas;
        }
    }

    private static RuleResult applyRule(List<Entry> working,
                                        ResourceLocation targetId,
                                        UpgradeServerConfig.AttributeRuleConfig rule,
                                        int currentLevel,
                                        double ritualMult) {
        if (working == null || working.isEmpty() || rule == null) {
            return new RuleResult(working, List.of());
        }

        // Resolve step for this level (overrides win)
        double base = rule.perLevelOverrides.getOrDefault(currentLevel, rule.defaultStep);
        double step = Math.max(0.0, base) * Math.max(0.0, ritualMult);

        // Count how many entries match this attribute id
        int targets = 0;
        for (Entry e : working) {
            ResourceLocation id = idOf(e.attribute());
            if (id != null && id.equals(targetId)) targets++;
        }
        if (targets == 0 || step <= 0.0) {
            return new RuleResult(working, List.of());
        }

        double perEntryAdd = 0.0;
        if (rule.stepType == UpgradeServerConfig.StepType.ADDITIVE) {
            double signed = (rule.direction == UpgradeServerConfig.Direction.INCREASE) ? step : -step;
            perEntryAdd = signed / (double)targets;
        }

        ItemAttributeModifiers.Builder b = ItemAttributeModifiers.builder();
        List<AttributeDelta> deltas = new ArrayList<>();

        for (Entry e : working) {
            Holder<Attribute> a = e.attribute();
            AttributeModifier m = e.modifier();
            if (a == null || m == null) {
                b.add(a, m, e.slot());
                continue;
            }

            ResourceLocation id = idOf(a);
            if (id == null || !id.equals(targetId)) {
                b.add(a, m, e.slot());
                continue;
            }

            // Apply the step
            double oldVal = m.amount();
            double newVal;

            if (rule.stepType == UpgradeServerConfig.StepType.PERCENT) {
                double factor = (rule.direction == UpgradeServerConfig.Direction.INCREASE)
                        ? (1.0 + step) : (1.0 - step);

                if (rule.applyToMagnitude) {
                    double sign = Math.signum(oldVal == 0.0 ? 1.0 : oldVal);
                    newVal = sign * (Math.abs(oldVal) * factor);
                } else {
                    newVal = oldVal * factor;
                }
            } else {
                // ADDITIVE
                newVal = oldVal + perEntryAdd;
            }

            // caps & rounding
            newVal = Mth.clamp(newVal, rule.capMin, rule.capMax);
            newVal = roundTo(newVal, rule.rounding);

            AttributeModifier nm = new AttributeModifier(m.id(), newVal, m.operation());
            b.add(a, nm, e.slot());

            double appliedPercent = (rule.stepType == UpgradeServerConfig.StepType.PERCENT)
                    ? ((rule.direction == UpgradeServerConfig.Direction.INCREASE) ? step : -step)
                    : 0.0;

            deltas.add(new AttributeDelta(
                    id.toString(), m.operation().name(), oldVal, newVal, newVal - oldVal, appliedPercent,
                    (rule.stepType == UpgradeServerConfig.StepType.PERCENT ? "pct_step" : "add_step")
            ));
        }

        List<Entry> updated = b.build().modifiers();
        return new RuleResult(updated, deltas);
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

    // -------------------- Small helpers --------------------

    private static ItemAttributeModifiers safeModifiers(ItemStack s) {
        try { return s.getAttributeModifiers(); }
        catch (Throwable t) { return ItemAttributeModifiers.EMPTY; }
    }

    private static @Nullable ResourceLocation idOf(Holder<Attribute> holder) {
        try { return holder != null ? holder.unwrapKey().map(k -> k.location()).orElse(null) : null; }
        catch (Throwable t) { return null; }
    }

    private static ItemAttributeModifiers fromEntries(List<Entry> entries) {
        ItemAttributeModifiers.Builder b = ItemAttributeModifiers.builder();
        for (Entry e : entries) {
            b.add(e.attribute(), e.modifier(), e.slot());
        }
        return b.build();
    }

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

    private static double roundTo(double v, double quantum) {
        if (quantum <= 0.0) return v;
        return Math.rint(v / quantum) * quantum;
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
