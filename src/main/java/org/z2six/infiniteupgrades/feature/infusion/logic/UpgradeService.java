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

/**
 * Server-authoritative upgrade engine used by the menu.
 *
 * Implements the "frozen base + signed percent steps" model:
 * - Freeze per-attribute base amounts once in iu_upgrade.bases{ attrId: double }.
 * - Every success appends a step with a signed percent to iu_upgrade.history (one per attribute for Angel, one for Demon).
 * - Every downgrade appends an inverse step (-percent) matching the last batch.
 * - Live modifier amounts are recomputed as base * (1 + sumPercent[attr]) from iu_upgrade.totals.
 *
 * Backwards-compat:
 * - We keep using "iu_upgrade" and its "history" tag name, but the entries are canonicalized.
 * - "totals" has the same shape this UI already reads: { sumPercent (fraction), count (net), lastPercent (fraction) }.
 */
public final class UpgradeService {
    private static final Logger LOG = LogUtils.getLogger();

    private UpgradeService() {}

    // -------------------- Keys / schema --------------------
    private static final String ROOT = "iu_upgrade";
    private static final String KEY_LEVEL = "level";
    private static final String KEY_BASES = "bases";     // Compound: attrId -> double
    private static final String KEY_TOTALS = "totals";   // Compound: attrId -> { sumPercent, count, lastPercent }
    private static final String KEY_HISTORY = "history"; // List of Compound steps

    // history entry keys (canonical)
    private static final String H_TIME   = "time";
    private static final String H_BATCH  = "batchId";
    private static final String H_ATTR   = "attribute";
    private static final String H_OP     = "op";           // kept for readability (e.g., "ADD_VALUE")
    private static final String H_OLD    = "old";          // debug
    private static final String H_NEW    = "new";          // debug
    private static final String H_DELTA  = "delta";        // debug
    private static final String H_STEP_P = "stepPercent";  // signed fraction, canonical
    private static final String H_RULE   = "ruleId";       // "pct_step" | "downgrade"
    private static final String H_LBEF   = "levelBefore";
    private static final String H_LAFT   = "levelAfter";
    private static final String H_CHANGE = "change";       // +1 success, -1 downgrade

    // -------------------- Chance (base model only; rep handled in menu) --------------------

    public static double getSuccessChance(int currentLevel) {
        try {
            Snapshot s = UpgradeServerConfig.snapshot();

            Double ov = s.chanceOverrides.get(currentLevel);
            if (ov != null) return clamp01(ov);

            if (s.chanceModel == ChanceModelType.FLAT_DECREMENT) {
                double c = s.startChance - currentLevel * s.decrementPerLevel;
                c = Math.max(s.minChance, c);
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

    /**
     * Compute how many soul units are required to go from the current level to the next level.
     *
     * Model:
     *   Let nextLevel = currentLevel + 1.
     *
     *   1) If a manual override exists for nextLevel, return that.
     *   2) Otherwise, compute:
     *
     *        cost(L -> L+1) = baseCost * (expBase ^ L) * scale
     *
     *      where all three (baseCost, expBase, scale) come from the Souls config.
     *
     *   - baseCost >= 0
     *   - expBase >= 1.0 (clamped)
     *   - scale   >= 0
     *
     * The return value is an int >= 0, clamped to Integer.MAX_VALUE.
     */
    public static int getSoulCostForNextLevel(int currentLevel) {
        try {
            Snapshot s = UpgradeServerConfig.snapshot();
            UpgradeServerConfig.SoulsConfig sc = s.souls;

            int levelIndex = Math.max(0, currentLevel);
            int nextLevel  = levelIndex + 1;

            // 1) Manual override wins, if present
            Integer override = sc.upgradeCostOverrides.get(nextLevel);
            if (override != null) {
                int val = Math.max(0, override);
                LOG.debug("[UpgradeService] Soul cost override used for level {} -> {}: {}", levelIndex, nextLevel, val);
                return val;
            }

            // 2) Exponential model
            double base   = Math.max(0.0, sc.upgradeBaseCost);
            double expBase = sc.upgradeExponentialBase;
            if (expBase < 1.0) expBase = 1.0;
            double scale  = Math.max(0.0, sc.upgradeExponentialScale);

            // cost(L -> L+1) = base * (expBase^L) * scale
            double d = base * Math.pow(expBase, levelIndex) * scale;

            if (d <= 0.0) {
                LOG.debug("[UpgradeService] Soul cost for level {} -> {} computed as <= 0 (value={}); returning 0",
                        levelIndex, nextLevel, d);
                return 0;
            }
            if (d > Integer.MAX_VALUE) {
                LOG.warn("[UpgradeService] Soul cost for level {} -> {} overflow (value={}); clamping to {}",
                        levelIndex, nextLevel, d, Integer.MAX_VALUE);
                return Integer.MAX_VALUE;
            }

            int result = (int) Math.round(d);
            LOG.debug("[UpgradeService] Soul cost for level {} -> {} is {} (base={}, expBase={}, scale={})",
                    levelIndex, nextLevel, result, base, expBase, scale);
            return result;
        } catch (Throwable t) {
            LOG.error("[UpgradeService] getSoulCostForNextLevel failed: {}", t.toString());
            // On error we return 0 so we *do not* accidentally eat souls.
            return 0;
        }
    }

    // -------------------- Public ritual API --------------------

    public static Result tryUpgradeWithRitual(ItemStack original, RandomSource rand, RitualType ritual) {
        if (original == null || original.isEmpty()) {
            return new Result(ItemStack.EMPTY, false, 0);
        }

        ItemStack copy = original.copy();
        int currentLevel = readLevel(copy);
        Snapshot snap = UpgradeServerConfig.snapshot();

        // Resolve rules by id (enabled only)
        Map<ResourceLocation, UpgradeServerConfig.AttributeRuleConfig> rules = new LinkedHashMap<>();
        for (var r : snap.attributes) if (r.enabled) rules.put(r.id, r);
        if (rules.isEmpty()) return new Result(copy, false, currentLevel);

        // Merge current + defaults once (working list)
        List<Entry> working = mergeListsUnique(
                new ArrayList<>(safeModifiers(copy).modifiers()),
                new ArrayList<>(copy.getItem().getDefaultAttributeModifiers(copy).modifiers())
        );
        if (working.isEmpty()) return new Result(copy, false, currentLevel);

        // Present & rule-backed attributes
        List<ResourceLocation> present = presentRuleBackedIds(working, rules.keySet());
        if (present.isEmpty()) return new Result(copy, false, currentLevel);

        // Ensure bases / totals structures exist
        ensureBases(copy, working, present);
        // We'll update totals/history atomically (single batch)
        long batchId = System.nanoTime();

        // Resolve ritual step multiplier
        double ritualMult = (ritual == RitualType.ANGEL) ? snap.angelStepMult : snap.demonStepMult;

        // Determine which attributes are touched this attempt
        List<ResourceLocation> touched;
        if (ritual == RitualType.ANGEL) {
            touched = present;
        } else {
            touched = List.of(weightedPick(rand, present, rules));
        }

        // For each touched attribute, compute the signed step and apply it to totals; we will recompute all modifier amounts afterwards.
        List<AttrStep> steps = new ArrayList<>();
        for (ResourceLocation id : touched) {
            var rule = rules.get(id);
            if (rule == null) continue;

            double baseStep = rule.perLevelOverrides.getOrDefault(currentLevel, rule.defaultStep);
            double step = Math.max(0.0, baseStep) * Math.max(0.0, ritualMult);
            if (step <= 0.0) continue;

            // Signed step: INCREASE => +step, DECREASE => -step
            double signed = (rule.direction == UpgradeServerConfig.Direction.INCREASE) ? step : -step;
            steps.add(new AttrStep(id, signed, "pct_step", "ADD_VALUE"));
        }

        if (steps.isEmpty()) {
            return new Result(copy, false, currentLevel);
        }

        // Persist steps (+1 change), update totals (sumPercent/count/lastPercent), bump level
        int newLevel = Math.min(currentLevel + 1, snap.maxLevel);
        appendStepsAndUpdateTotals(copy, steps, /*change*/+1, batchId, currentLevel, newLevel);

        // Recompute ALL managed attributes deterministically from bases × (1 + sumPercent)
        List<Entry> recomputed = recomputeAllFromTotals(copy, working, rules.keySet());

        // Commit modifiers + name
        copy.set(DataComponents.ATTRIBUTE_MODIFIERS, fromEntries(recomputed));
        applyColoredSuffix(copy, newLevel);

        return new Result(copy, true, newLevel);
    }

    public static Result tryUpgrade(ItemStack original, RandomSource rand) {
        return tryUpgradeWithRitual(original, rand, RitualType.DEMON);
    }

    public static record Result(ItemStack upgraded, boolean success, int newLevel) {}

    // -------------------- Exact rollback of last level (batch-aware) --------------------

    public static ItemStack downgradeLastLevel(ItemStack original) {
        try {
            if (original == null || original.isEmpty()) return ItemStack.EMPTY;

            ItemStack copy = original.copy();
            int currentLevel = readLevel(copy);
            if (currentLevel <= 0) return copy;
            int newLevel = currentLevel - 1;

            CustomData cd = copy.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CompoundTag root = cd.copyTag().getCompound(ROOT);
            ListTag hist = root.getList(KEY_HISTORY, Tag.TAG_COMPOUND);

            // Find last batch that resulted in levelAfter == currentLevel and had change == +1
            long targetBatch = findLastSuccessfulBatch(hist, currentLevel);
            if (targetBatch != Long.MIN_VALUE) {
                // invert all steps in that batch
                List<AttrStep> inverse = new ArrayList<>();
                for (int i = 0; i < hist.size(); i++) {
                    CompoundTag ev = hist.getCompound(i);
                    if (ev.getLong(H_BATCH) != targetBatch) continue;
                    if (ev.getInt(H_CHANGE) != +1) continue;
                    String attr = ev.getString(H_ATTR);
                    double p = ev.getDouble(H_STEP_P);
                    inverse.add(new AttrStep(ResourceLocation.tryParse(attr), -p, "downgrade", ev.getString(H_OP)));
                }

                if (!inverse.isEmpty()) {
                    appendStepsAndUpdateTotals(copy, inverse, /*change*/-1, System.nanoTime(), currentLevel, newLevel);
                    // Recompute all amounts from totals
                    Snapshot snap = UpgradeServerConfig.snapshot();
                    Map<ResourceLocation, UpgradeServerConfig.AttributeRuleConfig> rules = new LinkedHashMap<>();
                    for (var r : snap.attributes) if (r.enabled) rules.put(r.id, r);

                    List<Entry> working = mergeListsUnique(
                            new ArrayList<>(safeModifiers(copy).modifiers()),
                            new ArrayList<>(copy.getItem().getDefaultAttributeModifiers(copy).modifiers())
                    );
                    List<Entry> recomputed = recomputeAllFromTotals(copy, working, rules.keySet());
                    copy.set(DataComponents.ATTRIBUTE_MODIFIERS, fromEntries(recomputed));

                    applyColoredSuffix(copy, newLevel);
                    return copy;
                }
            }

            // Fallback: invert the most recent +1 steps (last batch guess by max batchId among change==+1)
            long guessBatch = guessLastPositiveBatch(hist);
            if (guessBatch != Long.MIN_VALUE) {
                List<AttrStep> inverse = new ArrayList<>();
                for (int i = 0; i < hist.size(); i++) {
                    CompoundTag ev = hist.getCompound(i);
                    if (ev.getLong(H_BATCH) != guessBatch) continue;
                    if (ev.getInt(H_CHANGE) != +1) continue;
                    String attr = ev.getString(H_ATTR);
                    double p = ev.getDouble(H_STEP_P);
                    inverse.add(new AttrStep(ResourceLocation.tryParse(attr), -p, "downgrade", ev.getString(H_OP)));
                }
                if (!inverse.isEmpty()) {
                    appendStepsAndUpdateTotals(copy, inverse, /*change*/-1, System.nanoTime(), currentLevel, newLevel);

                    Snapshot snap = UpgradeServerConfig.snapshot();
                    Map<ResourceLocation, UpgradeServerConfig.AttributeRuleConfig> rules = new LinkedHashMap<>();
                    for (var r : snap.attributes) if (r.enabled) rules.put(r.id, r);

                    List<Entry> working = mergeListsUnique(
                            new ArrayList<>(safeModifiers(copy).modifiers()),
                            new ArrayList<>(copy.getItem().getDefaultAttributeModifiers(copy).modifiers())
                    );
                    List<Entry> recomputed = recomputeAllFromTotals(copy, working, rules.keySet());
                    copy.set(DataComponents.ATTRIBUTE_MODIFIERS, fromEntries(recomputed));

                    applyColoredSuffix(copy, newLevel);
                    return copy;
                }
            }

            // No steps to invert -> leave modifiers as-is, only clamp level
            applyColoredSuffix(copy, newLevel);
            setLevel(copy, newLevel);
            return copy;
        } catch (Throwable t) {
            LOG.error("[UpgradeService] downgradeLastLevel failed: {}", t.toString());
            return original.copy();
        }
    }

    // -------------------- Core helpers: steps/totals/bases + recompute --------------------

    /** Append steps (all part of one batch), update totals & history & level. change = +1 for success, -1 for downgrade. */
    private static void appendStepsAndUpdateTotals(ItemStack stack,
                                                   List<AttrStep> steps,
                                                   int change,
                                                   long batchId,
                                                   int levelBefore,
                                                   int levelAfter) {
        CustomData cd = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CustomData updated = cd.update(tag -> {
            CompoundTag up = tag.getCompound(ROOT);

            // level
            up.putInt(KEY_LEVEL, Math.max(0, levelAfter));

            // totals compound
            CompoundTag totals = up.getCompound(KEY_TOTALS);

            // history list
            ListTag history = up.getList(KEY_HISTORY, Tag.TAG_COMPOUND);

            long now = System.currentTimeMillis();
            for (AttrStep s : steps) {
                String attrKey = s.id.toString();

                // Update totals (sumPercent is canonical; count is NET successes minus downgrades)
                CompoundTag a = totals.getCompound(attrKey);
                double sumPct = a.getDouble("sumPercent") + s.signedPercent;
                int count = a.getInt("count") + (change > 0 ? 1 : -1);

                // If we crossed back to 0 net, zero the percent to avoid residue
                if (count == 0) sumPct = 0.0;

                a.putDouble("sumPercent", sumPct);
                a.putInt("count", Math.max(0, count));
                a.putDouble("lastPercent", s.signedPercent);
                totals.put(attrKey, a);

                // Append canonical step to history
                CompoundTag ev = new CompoundTag();
                ev.putLong(H_TIME, now);
                ev.putLong(H_BATCH, batchId);
                ev.putString(H_ATTR, attrKey);
                ev.putString(H_OP, s.op); // informational
                ev.putDouble(H_OLD, 0.0); // optional (not needed anymore)
                ev.putDouble(H_DELTA, 0.0);
                ev.putDouble(H_NEW, 0.0);
                ev.putDouble(H_STEP_P, s.signedPercent); // canonical signed fraction
                ev.putString(H_RULE, s.ruleId);
                ev.putInt(H_LBEF, levelBefore);
                ev.putInt(H_LAFT, levelAfter);
                ev.putInt(H_CHANGE, change);
                history.add(ev);
            }

            up.put(KEY_TOTALS, totals);
            up.put(KEY_HISTORY, history);
            tag.put(ROOT, up);
        });
        stack.set(DataComponents.CUSTOM_DATA, updated);
    }

    /** Ensure iu_upgrade.bases exists; capture first-seen amounts for rule-backed attributes. */
    private static void ensureBases(ItemStack stack, List<Entry> working, List<ResourceLocation> present) {
        CustomData cd = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CustomData updated = cd.update(tag -> {
            CompoundTag up = tag.getCompound(ROOT);
            CompoundTag bases = up.getCompound(KEY_BASES);

            // Already captured? then exit
            boolean missingAny = false;
            for (ResourceLocation id : present) {
                if (!bases.contains(id.toString(), Tag.TAG_ANY_NUMERIC)) { missingAny = true; break; }
            }
            if (!missingAny) {
                tag.put(ROOT, up);
                return;
            }

            // Capture base amounts from current/default merged list
            Map<String, Double> firstSeen = new LinkedHashMap<>();
            for (Entry e : working) {
                ResourceLocation id = idOf(e.attribute());
                if (id == null) continue;
                if (!present.contains(id)) continue;
                String key = id.toString();
                if (!firstSeen.containsKey(key) && e.modifier() != null) {
                    firstSeen.put(key, e.modifier().amount());
                }
            }

            for (var kv : firstSeen.entrySet()) {
                // Only write if missing (never overwrite)
                if (!bases.contains(kv.getKey(), Tag.TAG_ANY_NUMERIC)) {
                    bases.putDouble(kv.getKey(), kv.getValue());
                }
            }

            up.put(KEY_BASES, bases);
            tag.put(ROOT, up);
        });
        stack.set(DataComponents.CUSTOM_DATA, updated);
    }

    /** Recompute ALL rule-managed entries as base * (1 + sumPercent[attr]) using totals. */
    private static List<Entry> recomputeAllFromTotals(ItemStack stack, List<Entry> working, Set<ResourceLocation> managed) {
        // Read bases & totals once
        Map<String, Double> bases = readBases(stack);
        Map<String, Double> sumPct = readTotalsPercents(stack);

        ItemAttributeModifiers.Builder b = ItemAttributeModifiers.builder();
        for (Entry e : working) {
            Holder<Attribute> a = e.attribute();
            AttributeModifier m = e.modifier();
            if (a == null || m == null) {
                b.add(a, m, e.slot());
                continue;
            }

            ResourceLocation id = idOf(a);
            if (id == null || !managed.contains(id)) {
                b.add(a, m, e.slot());
                continue;
            }

            String key = id.toString();
            if (!bases.containsKey(key)) {
                // If base missing for a managed attr, keep current amount (defensive) and continue
                b.add(a, m, e.slot());
                continue;
            }

            double base = bases.getOrDefault(key, m.amount());
            double pct = sumPct.getOrDefault(key, 0.0);
            double newAmount = base * (1.0 + pct);

            AttributeModifier nm = new AttributeModifier(m.id(), newAmount, m.operation());
            b.add(a, nm, e.slot());
        }
        return b.build().modifiers();
    }

    private static Map<String, Double> readBases(ItemStack stack) {
        Map<String, Double> out = new LinkedHashMap<>();
        try {
            CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
            if (cd == null) return out;
            CompoundTag up = cd.copyTag().getCompound(ROOT);
            CompoundTag bases = up.getCompound(KEY_BASES);
            for (String k : bases.getAllKeys()) out.put(k, bases.getDouble(k));
        } catch (Throwable ignored) {}
        return out;
    }

    private static Map<String, Double> readTotalsPercents(ItemStack stack) {
        Map<String, Double> out = new LinkedHashMap<>();
        try {
            CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
            if (cd == null) return out;
            CompoundTag up = cd.copyTag().getCompound(ROOT);
            CompoundTag totals = up.getCompound(KEY_TOTALS);
            for (String k : totals.getAllKeys()) {
                CompoundTag a = totals.getCompound(k);
                out.put(k, a.getDouble("sumPercent"));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    // -------------------- Small structs --------------------

    private record AttrStep(ResourceLocation id, double signedPercent, String ruleId, String op) {}

    // -------------------- Utility bits (mostly kept from your original) --------------------

    /** Authoritative level: read iu_upgrade.level; fallback to suffix. */
    public static int readLevelFromTagOrZero(ItemStack stack) { return readLevel(stack); }

    private static int readLevel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        try {
            CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
            if (cd != null) {
                CompoundTag root = cd.copyTag();
                if (root.contains(ROOT, Tag.TAG_COMPOUND)) {
                    int lvl = root.getCompound(ROOT).getInt(KEY_LEVEL);
                    if (lvl > 0) return Mth.clamp(lvl, 0, 100000);
                }
            }
        } catch (Throwable ignored) {}
        // fallback to name
        try {
            Component name = stack.getHoverName();
            String s = name.getString();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\s+\\+(\\d+)$").matcher(s);
            if (m.find()) {
                return Mth.clamp(Integer.parseInt(m.group(1)), 0, 100000);
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static void setLevel(ItemStack stack, int newLevel) {
        CustomData cd = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CustomData updated = cd.update(tag -> {
            CompoundTag up = tag.getCompound(ROOT);
            up.putInt(KEY_LEVEL, Math.max(0, newLevel));
            tag.put(ROOT, up);
        });
        stack.set(DataComponents.CUSTOM_DATA, updated);
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
        for (Entry e : entries) b.add(e.attribute(), e.modifier(), e.slot());
        return b.build();
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

    private static void applyColoredSuffix(ItemStack stack, int level) {
        String base = stripPlusSuffix(stack.getHoverName().getString());

        int rgb = UpgradeServerConfig.resolveSuffixColor(level);
        ChatFormatting fallback = ChatFormatting.AQUA;

        MutableComponent pretty = Component.literal(base);
        MutableComponent suffix = Component.literal(" +" + level);

        if (rgb != 0) suffix = suffix.withStyle(s -> s.withColor(rgb));
        else suffix = suffix.withStyle(fallback);

        pretty = pretty.append(suffix);
        stack.set(DataComponents.CUSTOM_NAME, pretty);
    }

    private static String stripPlusSuffix(String s) {
        var m = java.util.regex.Pattern.compile("\\s+\\+(\\d+)$").matcher(s);
        if (m.find()) return s.substring(0, m.start());
        return s;
    }

    private static String toId(Holder<Attribute> h) {
        try {
            ResourceLocation rl = idOf(h);
            return rl == null ? "" : rl.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }

    private static List<ResourceLocation> presentRuleBackedIds(List<Entry> working, Set<ResourceLocation> ruleIds) {
        List<ResourceLocation> out = new ArrayList<>();
        for (Entry e : working) {
            ResourceLocation id = idOf(e.attribute());
            if (id != null && ruleIds.contains(id) && !out.contains(id)) out.add(id);
        }
        return out;
    }

    private static ResourceLocation weightedPick(RandomSource rand, List<ResourceLocation> present, Map<ResourceLocation, UpgradeServerConfig.AttributeRuleConfig> rules) {
        int totalW = 0;
        for (ResourceLocation id : present) totalW += Math.max(0, rules.get(id).weight);
        if (totalW <= 0) totalW = present.size();

        int r = rand.nextInt(totalW);
        ResourceLocation chosen = present.get(0);
        int acc = 0;
        for (ResourceLocation id : present) {
            acc += Math.max(0, rules.get(id).weight);
            if (r < acc) { chosen = id; break; }
        }
        return chosen;
    }

    private static long findLastSuccessfulBatch(ListTag hist, int currentLevel) {
        long best = Long.MIN_VALUE;
        for (int i = 0; i < hist.size(); i++) {
            CompoundTag ev = hist.getCompound(i);
            if (ev.getInt(H_LAFT) != currentLevel) continue;
            if (ev.getInt(H_CHANGE) != +1) continue;
            long b = ev.getLong(H_BATCH);
            if (b > best) best = b;
        }
        return best;
    }

    private static long guessLastPositiveBatch(ListTag hist) {
        long best = Long.MIN_VALUE;
        for (int i = 0; i < hist.size(); i++) {
            CompoundTag ev = hist.getCompound(i);
            if (ev.getInt(H_CHANGE) != +1) continue;
            long b = ev.getLong(H_BATCH);
            if (b > best) best = b;
        }
        return best;
    }
}
