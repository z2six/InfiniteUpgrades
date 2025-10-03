// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/logic/UpgradeService.java
package org.z2six.infiniteupgrades.feature.infusion.logic;

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
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig.ChanceModelType;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig.Snapshot;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-authoritative upgrade engine used by the menu.
 *
 * Changes in this version:
 *  - READ LEVEL FROM NBT (iu_upgrade.level) with name fallback.
 *  - Audit entries get a batchId per successful upgrade; downgrade reverts only the latest batch.
 *  - No randomness here; menu decides success via server-only AttemptRng and passes 'success' path by calling tryUpgradeWithRitual.
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
                c = Math.max(s.minChance, c); // respect server-configured floor
                return clamp01(c);
            } else {
                double c = s.startChance * Math.pow(s.exponentialBase, Math.max(0, currentLevel));
                return clamp01(c);
            }
        } catch (Throwable t) {
            LOG.error("[UpgradeService] getSuccessChance failed: {}", t.toString());
            return 0.0;
        }
    }

    // -------------------- Public ritual API --------------------

    /**
     * Perform an upgrade (already decided to be a success by the server).
     * Returns a new ItemStack if changed; success=false path returns original copy and current level.
     */
    public static Result tryUpgradeWithRitual(ItemStack original, RandomSource rand, RitualType ritual) {
        if (original == null || original.isEmpty()) {
            return new Result(ItemStack.EMPTY, false, 0);
        }

        ItemStack copy = original.copy();
        int currentLevel = readLevelFromTagOrZero(copy);
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

        // Bump level & persist audit (with batchId to group this single upgrade step)
        int newLevel = Math.min(currentLevel + 1, snap.maxLevel);
        long batchId = System.nanoTime(); // server-side unique-ish id for this batch

        try {
            writeAudit(copy, currentLevel, newLevel, allDeltas, batchId);
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

    // -------------------- Exact rollback of the last level (batch-aware) --------------------

    /**
     * Downgrade the item exactly one level (+N -> +N-1) by reverting only
     * the MOST RECENT batch of changes (same batchId) recorded for levelAfter==N.
     * If no proper batch is found, fall back to proportional rollback.
     */
    public static ItemStack downgradeLastLevel(ItemStack original) {
        try {
            if (original == null || original.isEmpty()) return ItemStack.EMPTY;

            ItemStack copy = original.copy();
            int currentLevel = readLevelFromTagOrZero(copy);
            if (currentLevel <= 0) {
                return copy;
            }
            int newLevel = currentLevel - 1;

            // Read audit
            CustomData cd = copy.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CompoundTag root = cd.copyTag().getCompound("iu_upgrade");
            ListTag hist = root.getList("history", Tag.TAG_COMPOUND);

            // Gather all events for target level
            List<CompoundTag> byLevel = new ArrayList<>();
            for (int i = 0; i < hist.size(); i++) {
                CompoundTag ev = hist.getCompound(i);
                if (ev.getInt("levelAfter") == currentLevel) {
                    byLevel.add(ev);
                }
            }

            if (!byLevel.isEmpty()) {
                // Find the latest batchId among those
                long maxBatch = Long.MIN_VALUE;
                for (CompoundTag ev : byLevel) {
                    long b = ev.getLong("batchId");
                    if (b > maxBatch) maxBatch = b;
                }
                // Collect only that batch
                List<CompoundTag> lastEvents = new ArrayList<>();
                for (CompoundTag ev : byLevel) {
                    if (ev.getLong("batchId") == maxBatch) {
                        lastEvents.add(ev);
                    }
                }

                // Optional: de-dup within the batch
                Set<String> seen = new HashSet<>();
                List<CompoundTag> dedup = new ArrayList<>();
                for (CompoundTag ev : lastEvents) {
                    String key = ev.getString("attribute") + "|" + ev.getString("op") + "|" + ev.getDouble("old") + "|" + ev.getDouble("new");
                    if (seen.add(key)) dedup.add(ev);
                }
                lastEvents = dedup;

                if (!lastEvents.isEmpty()) {
                    // Build a mutable working list of attribute entries
                    List<Entry> entries = new ArrayList<>(safeModifiers(copy).modifiers());

                    // Match and revert
                    for (CompoundTag ev : lastEvents) {
                        String attrKey = ev.getString("attribute");
                        String opName  = ev.getString("op");
                        double oldVal  = ev.getDouble("old");
                        double newVal  = ev.getDouble("new");

                        int bestIdx = -1;
                        double bestDiff = Double.POSITIVE_INFINITY;

                        for (int idx = 0; idx < entries.size(); idx++) {
                            Entry e = entries.get(idx);
                            ResourceLocation id = idOf(e.attribute());
                            if (id == null || !id.toString().equals(attrKey)) continue;
                            if (!e.modifier().operation().name().equals(opName)) continue;
                            double diff = Math.abs(e.modifier().amount() - newVal);
                            if (diff < bestDiff) { bestDiff = diff; bestIdx = idx; }
                        }

                        if (bestIdx >= 0) {
                            Entry e = entries.get(bestIdx);
                            AttributeModifier m = e.modifier();
                            AttributeModifier reverted = new AttributeModifier(m.id(), oldVal, m.operation());
                            entries.set(bestIdx, new Entry(e.attribute(), reverted, e.slot()));
                        } else {
                            LOG.debug("[UpgradeService] downgradeLastLevel: no entry matched attr={} op={}, one delta skipped", attrKey, opName);
                        }
                    }

                    // Commit modifiers
                    copy.set(DataComponents.ATTRIBUTE_MODIFIERS, fromEntries(entries));

                    // >>> FIX: make captured list effectively final inside lambda
                    final int fCurrentLevel = currentLevel;
                    final int fNewLevel = newLevel;
                    final List<CompoundTag> lastEventsFinal = List.copyOf(lastEvents);

                    // Update audit: set level, adjust totals, append downgrade events (one per reverted delta)
                    CustomData updated = cd.update(tag -> {
                        CompoundTag r = tag.getCompound("iu_upgrade");
                        r.putInt("level", fNewLevel);

                        CompoundTag totals = r.getCompound("totals");
                        ListTag history = r.getList("history", Tag.TAG_COMPOUND);

                        for (CompoundTag ev : lastEventsFinal) {
                            String attrKey = ev.getString("attribute");
                            double delta = ev.getDouble("delta");
                            double stepPct = ev.getDouble("stepPercent");
                            double oldVal = ev.getDouble("old");
                            double newVal = ev.getDouble("new");
                            String opName = ev.getString("op");
                            long batchId = ev.getLong("batchId");

                            // Subtract from totals (including count)  <<< FIX HERE
                            CompoundTag a = totals.getCompound(attrKey);
                            a.putDouble("sumDelta", a.getDouble("sumDelta") - delta);
                            a.putDouble("sumPercent", a.getDouble("sumPercent") - stepPct);
                            a.putDouble("lastDelta", -delta);
                            a.putDouble("lastPercent", -stepPct);
                            int c = a.getInt("count");
                            if (c > 0) a.putInt("count", c - 1);
                            totals.put(attrKey, a);

                            // Append explicit downgrade event, link back to the same batchId for traceability
                            CompoundTag down = new CompoundTag();
                            down.putLong("time", System.currentTimeMillis());
                            down.putInt("levelBefore", fCurrentLevel);
                            down.putInt("levelAfter", fNewLevel);
                            down.putString("attribute", attrKey);
                            down.putString("op", opName);
                            down.putDouble("old", newVal);
                            down.putDouble("delta", -delta);
                            down.putDouble("new", oldVal);
                            down.putDouble("stepPercent", -stepPct);
                            down.putString("ruleId", "downgrade");
                            down.putLong("batchId", batchId);
                            history.add(down);
                        }

                        r.put("totals", totals);
                        r.put("history", history);
                        tag.put("iu_upgrade", r);
                    });
                    copy.set(DataComponents.CUSTOM_DATA, updated);

                    // Update the display name suffix
                    applyColoredSuffix(copy, newLevel);
                    return copy;
                }
            }

            // Fallback (no batch for current level): angel-style proportional rollback
            LOG.debug("[UpgradeService] downgradeLastLevel: no batch for level {}, proportional fallback", currentLevel);
            return fallbackProportionalRollback(copy, newLevel);
        } catch (Throwable t) {
            LOG.error("[UpgradeService] downgradeLastLevel failed: {}", t.toString());
            return original.copy();
        }
    }

    /** Fallback path if we lack history for the current level: scale back by 1/(1+step) across configured attributes. */
    private static ItemStack fallbackProportionalRollback(ItemStack stack, int newLevel) {
        ItemStack copy = stack.copy();
        Snapshot snap = UpgradeServerConfig.snapshot();
        double step = Math.max(0.0, snap.percentBonusForLevelUp(Math.max(0, newLevel)));
        double factor = (step <= 0.0) ? 1.0 : (1.0 / (1.0 + step));

        // Restrict to attributes we manage (present in rules)
        Set<ResourceLocation> managed = new HashSet<>();
        for (var r : snap.attributes) {
            if (r.enabled) managed.add(r.id);
        }

        ItemAttributeModifiers cur = copy.getAttributeModifiers();
        ItemAttributeModifiers.Builder b = ItemAttributeModifiers.builder();
        for (Entry e : cur.modifiers()) {
            Holder<Attribute> a = e.attribute();
            AttributeModifier m = e.modifier();
            ResourceLocation id = idOf(a);
            if (m == null || id == null || !managed.contains(id)) {
                b.add(a, m, e.slot());
                continue;
            }
            double scaled = m.amount() * factor;
            b.add(a, new AttributeModifier(m.id(), scaled, m.operation()), e.slot());
        }
        copy.set(DataComponents.ATTRIBUTE_MODIFIERS, b.build());

        // Write a compact failure audit entry and update level
        try {
            final int fNewLevel = Math.max(0, newLevel);
            CustomData cd = copy.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CustomData updated = cd.update(tag -> {
                CompoundTag r = tag.getCompound("iu_upgrade");
                r.putInt("level", fNewLevel);

                // <<< FIX: conservatively decrement counts by 1 (if >0) for all tracked attributes
                CompoundTag totals = r.getCompound("totals");
                for (String key : totals.getAllKeys()) {
                    CompoundTag a = totals.getCompound(key);
                    int c = a.getInt("count");
                    if (c > 0) a.putInt("count", c - 1);
                    totals.put(key, a);
                }

                ListTag history = r.getList("history", Tag.TAG_COMPOUND);
                CompoundTag ev = new CompoundTag();
                ev.putLong("time", System.currentTimeMillis());
                ev.putInt("levelBefore", Math.max(0, fNewLevel + 1));
                ev.putInt("levelAfter", Math.max(0, fNewLevel));
                ev.putString("op", "NONE");
                ev.putDouble("old", 0.0);
                ev.putDouble("delta", 0.0);
                ev.putDouble("new", 0.0);
                ev.putDouble("stepPercent", 0.0);
                ev.putString("ruleId", "downgrade_fallback");
                ev.putLong("batchId", 0L);
                history.add(ev);

                r.put("totals", totals);
                r.put("history", history);
                tag.put("iu_upgrade", r);
            });
            copy.set(DataComponents.CUSTOM_DATA, updated);
        } catch (Throwable t) {
            LOG.error("[UpgradeService] fallbackProportionalRollback: audit write failed: {}", t.toString());
        }

        applyColoredSuffix(copy, Math.max(0, newLevel));
        return copy;
    }

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

    private static void writeAudit(ItemStack stack,
                                   int levelBefore,
                                   int levelAfter,
                                   List<AttributeDelta> deltas,
                                   long batchId) {
        if (stack == null || stack.isEmpty() || deltas == null || deltas.isEmpty()) return;

        final int fLevelBefore = levelBefore;
        final int fLevelAfter = levelAfter;
        final List<AttributeDelta> fDeltas = List.copyOf(deltas);
        CustomData cd = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CustomData updated = cd.update(tag -> {
            CompoundTag root = tag.getCompound("iu_upgrade");

            root.putInt("level", fLevelAfter);

            CompoundTag totals = root.getCompound("totals");
            ListTag hist = root.getList("history", Tag.TAG_COMPOUND);

            for (AttributeDelta d : fDeltas) {
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
                ev.putInt("levelBefore", fLevelBefore);
                ev.putInt("levelAfter", fLevelAfter);
                ev.putString("attribute", d.attrKey);
                ev.putString("op", d.op);
                ev.putDouble("old", d.oldValue);
                ev.putDouble("delta", d.deltaValue);
                ev.putDouble("new", d.newValue);
                ev.putDouble("stepPercent", d.appliedPercent);
                ev.putString("ruleId", d.ruleId);
                ev.putLong("batchId", batchId); // NEW
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

    /** Authoritative level: read iu_upgrade.level; fallback to parsing suffix. */
    public static int readLevelFromTagOrZero(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        try {
            CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
            if (cd != null) {
                CompoundTag root = cd.copyTag();
                if (root.contains("iu_upgrade", Tag.TAG_COMPOUND)) {
                    int lvl = root.getCompound("iu_upgrade").getInt("level");
                    if (lvl > 0) return Mth.clamp(lvl, 0, 100000);
                }
            }
        } catch (Throwable ignored) {}
        // fallback to name
        try {
            Component name = stack.getHoverName();
            String s = name.getString();
            Matcher m = PLUS_SUFFIX.matcher(s);
            if (m.find()) {
                return Mth.clamp(Integer.parseInt(m.group(1)), 0, 100000);
            }
        } catch (Throwable ignored) {}
        return 0;
    }

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
