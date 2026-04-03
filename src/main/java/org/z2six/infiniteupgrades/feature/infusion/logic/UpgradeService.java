// MainFile: src/main/java/org/z2six/infiniteupgrades/feature/infusion/logic/UpgradeService.java
package org.z2six.infiniteupgrades.feature.infusion.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig.ChanceModelType;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig.Snapshot;
import org.z2six.infiniteupgrades.core.util.ItemAttributeHelper;
import org.z2six.infiniteupgrades.core.util.StackTagUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
 *
 * NEW:
 * - Per-stat FINAL MULTIPLIERS (from TuningConfigSpec.finalMultipliers) are applied at the very end of step math,
 *   both for vanilla attributes and for the custom Block Speed stat. History stores the applied (post-multiplier) step.
 */
public final class UpgradeService {
    private static final Logger LOG = LogUtils.getLogger();

    private UpgradeService() {}

    private static final String ROOT = "iu_upgrade";
    private static final String KEY_LEVEL = "level";
    private static final String KEY_BASES = "bases";
    private static final String KEY_TOTALS = "totals";
    private static final String KEY_HISTORY = "history";

    private static final String H_TIME = "time";
    private static final String H_BATCH = "batchId";
    private static final String H_ATTR = "attribute";
    private static final String H_OP = "op";
    private static final String H_OLD = "old";
    private static final String H_NEW = "new";
    private static final String H_DELTA = "delta";
    private static final String H_STEP_P = "stepPercent";
    private static final String H_RULE = "ruleId";
    private static final String H_LBEF = "levelBefore";
    private static final String H_LAFT = "levelAfter";
    private static final String H_CHANGE = "change";

    private static final ResourceLocation BLOCK_SPEED_ID = ResourceLocation.fromNamespaceAndPath("infiniteupgrades", "block_speed");

    public static double getSuccessChance(int currentLevel) {
        try {
            Snapshot s = UpgradeServerConfig.snapshot();

            Double ov = s.chanceOverrides.get(currentLevel);
            if (ov != null) {
                return clamp01(ov);
            }

            if (s.chanceModel == ChanceModelType.FLAT_DECREMENT) {
                double c = s.startChance - currentLevel * s.decrementPerLevel;
                c = Math.max(s.minChance, c);
                return clamp01(c);
            }

            double c = s.startChance * Math.pow(s.exponentialBase, Math.max(0, currentLevel));
            return clamp01(c);
        } catch (Throwable t) {
            LOG.error("[UpgradeService] getSuccessChance failed: {}", t.toString());
            return 0.0;
        }
    }

    public static int getSoulCostForNextLevel(int currentLevel) {
        try {
            Snapshot s = UpgradeServerConfig.snapshot();
            UpgradeServerConfig.SoulsConfig sc = s.souls;

            int levelIndex = Math.max(0, currentLevel);
            int nextLevel = levelIndex + 1;

            Integer override = sc.upgradeCostOverrides.get(nextLevel);
            if (override != null) {
                int val = Math.max(0, override);
                LOG.debug("[UpgradeService] Soul cost override used for level {} -> {}: {}", levelIndex, nextLevel, val);
                return val;
            }

            double base = Math.max(0.0, sc.upgradeBaseCost);
            double expBase = Math.max(1.0, sc.upgradeExponentialBase);
            double scale = Math.max(0.0, sc.upgradeExponentialScale);
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
            return 0;
        }
    }

    public static Result tryUpgradeWithRitual(ItemStack original, RandomSource rand, RitualType ritual) {
        if (original == null || original.isEmpty()) {
            return new Result(ItemStack.EMPTY, false, 0);
        }

        ItemStack copy = original.copy();
        int currentLevel = readLevel(copy);
        Snapshot snap = UpgradeServerConfig.snapshot();

        Map<ResourceLocation, UpgradeServerConfig.AttributeRuleConfig> rules = new LinkedHashMap<>();
        for (var r : snap.attributes) {
            if (r.enabled) {
                rules.put(r.id, r);
            }
        }
        if (rules.isEmpty()) {
            return new Result(copy, false, currentLevel);
        }

        List<ItemAttributeHelper.Entry> working = mergeListsUnique(
                new ArrayList<>(ItemAttributeHelper.getCurrentEntries(copy)),
                new ArrayList<>(ItemAttributeHelper.getDefaultEntries(copy))
        );
        if (working.isEmpty()) {
            return new Result(copy, false, currentLevel);
        }

        List<ResourceLocation> present = presentRuleBackedIds(working, rules.keySet());

        boolean isMiningTool = false;
        try {
            isMiningTool = ToolSpeedUtil.isMiningTool(copy);
        } catch (Throwable ignored) {
        }

        List<ResourceLocation> candidates = new ArrayList<>(present);
        if (isMiningTool) {
            candidates.add(BLOCK_SPEED_ID);
        }
        if (candidates.isEmpty()) {
            return new Result(copy, false, currentLevel);
        }

        ensureBases(copy, working, present);

        long batchId = System.nanoTime();
        double ritualMult = ritual == RitualType.ANGEL ? snap.angelStepMult : snap.demonStepMult;

        List<ResourceLocation> touched = ritual == RitualType.ANGEL
                ? candidates
                : List.of(weightedPickAllowingSynthetic(rand, candidates, rules));

        List<AttrStep> steps = new ArrayList<>();
        for (ResourceLocation id : touched) {
            if (BLOCK_SPEED_ID.equals(id)) {
                double baseStep = snap.percentBonusForLevelUp(currentLevel);
                double step = Math.max(0.0, baseStep) * Math.max(0.0, ritualMult);
                step *= Math.max(0.0, snap.finalMultiplier(BLOCK_SPEED_ID));
                if (step <= 0.0) {
                    continue;
                }

                try {
                    double cur = ToolSpeedUtil.getBonus(copy);
                    double next = cur + step;
                    ToolSpeedUtil.setBonus(copy, next);
                    LOG.debug("[UpgradeService] BlockSpeed touched by {}: old={} new={} step={} (finalMult={})",
                            ritual, fmt(cur), fmt(next), fmt(step), fmt(snap.finalMultiplier(BLOCK_SPEED_ID)));
                } catch (Throwable t) {
                    LOG.error("[UpgradeService] tool_speed_bonus apply failed: {}", t.toString());
                }

                steps.add(new AttrStep(BLOCK_SPEED_ID, step, "pct_step", "ADD_VALUE"));
                continue;
            }

            var rule = rules.get(id);
            if (rule == null) {
                continue;
            }

            double baseStep = rule.perLevelOverrides.getOrDefault(currentLevel, rule.defaultStep);
            double step = Math.max(0.0, baseStep) * Math.max(0.0, ritualMult);
            step *= Math.max(0.0, snap.finalMultiplier(id));
            if (step <= 0.0) {
                continue;
            }

            double signed = rule.direction == UpgradeServerConfig.Direction.INCREASE ? step : -step;
            steps.add(new AttrStep(id, signed, "pct_step", "ADD_VALUE"));
        }

        if (steps.isEmpty()) {
            return new Result(copy, false, currentLevel);
        }

        int newLevel = Math.min(currentLevel + 1, snap.maxLevel);
        appendStepsAndUpdateTotals(copy, steps, 1, batchId, currentLevel, newLevel);

        List<ItemAttributeHelper.Entry> recomputed = recomputeAllFromTotals(copy, working, rules.keySet());
        ItemAttributeHelper.writeEntries(copy, recomputed);

        return new Result(copy, true, newLevel);
    }

    public static Result tryUpgrade(ItemStack original, RandomSource rand) {
        return tryUpgradeWithRitual(original, rand, RitualType.DEMON);
    }

    public record Result(ItemStack upgraded, boolean success, int newLevel) {}

    public static ItemStack downgradeLastLevel(ItemStack original) {
        try {
            if (original == null || original.isEmpty()) {
                return ItemStack.EMPTY;
            }

            ItemStack copy = original.copy();
            int currentLevel = readLevel(copy);
            if (currentLevel <= 0) {
                return copy;
            }
            int newLevel = currentLevel - 1;

            CompoundTag root = getUpgradeRoot(copy);
            ListTag hist = root.getList(KEY_HISTORY, Tag.TAG_COMPOUND);

            long targetBatch = findLastSuccessfulBatch(hist, currentLevel);
            if (targetBatch != Long.MIN_VALUE) {
                List<AttrStep> inverse = new ArrayList<>();
                for (int i = 0; i < hist.size(); i++) {
                    CompoundTag ev = hist.getCompound(i);
                    if (ev.getLong(H_BATCH) != targetBatch || ev.getInt(H_CHANGE) != 1) {
                        continue;
                    }
                    inverse.add(new AttrStep(ResourceLocation.tryParse(ev.getString(H_ATTR)), -ev.getDouble(H_STEP_P), "downgrade", ev.getString(H_OP)));
                }

                if (!inverse.isEmpty()) {
                    appendStepsAndUpdateTotals(copy, inverse, -1, System.nanoTime(), currentLevel, newLevel);

                    Snapshot snap = UpgradeServerConfig.snapshot();
                    Map<ResourceLocation, UpgradeServerConfig.AttributeRuleConfig> rules = new LinkedHashMap<>();
                    for (var r : snap.attributes) {
                        if (r.enabled) {
                            rules.put(r.id, r);
                        }
                    }

                    List<ItemAttributeHelper.Entry> working = mergeListsUnique(
                            new ArrayList<>(ItemAttributeHelper.getCurrentEntries(copy)),
                            new ArrayList<>(ItemAttributeHelper.getDefaultEntries(copy))
                    );
                    ItemAttributeHelper.writeEntries(copy, recomputeAllFromTotals(copy, working, rules.keySet()));

                    double deltaBlock = 0.0;
                    for (AttrStep st : inverse) {
                        if (BLOCK_SPEED_ID.equals(st.id)) {
                            deltaBlock += st.signedPercent;
                        }
                    }
                    if (Math.abs(deltaBlock) > 1.0E-12) {
                        double cur = ToolSpeedUtil.getBonus(copy);
                        ToolSpeedUtil.setBonus(copy, cur + deltaBlock);
                    }
                    return copy;
                }
            }

            long guessBatch = guessLastPositiveBatch(hist);
            if (guessBatch != Long.MIN_VALUE) {
                List<AttrStep> inverse = new ArrayList<>();
                for (int i = 0; i < hist.size(); i++) {
                    CompoundTag ev = hist.getCompound(i);
                    if (ev.getLong(H_BATCH) != guessBatch || ev.getInt(H_CHANGE) != 1) {
                        continue;
                    }
                    inverse.add(new AttrStep(ResourceLocation.tryParse(ev.getString(H_ATTR)), -ev.getDouble(H_STEP_P), "downgrade", ev.getString(H_OP)));
                }

                if (!inverse.isEmpty()) {
                    appendStepsAndUpdateTotals(copy, inverse, -1, System.nanoTime(), currentLevel, newLevel);

                    Snapshot snap = UpgradeServerConfig.snapshot();
                    Map<ResourceLocation, UpgradeServerConfig.AttributeRuleConfig> rules = new LinkedHashMap<>();
                    for (var r : snap.attributes) {
                        if (r.enabled) {
                            rules.put(r.id, r);
                        }
                    }

                    List<ItemAttributeHelper.Entry> working = mergeListsUnique(
                            new ArrayList<>(ItemAttributeHelper.getCurrentEntries(copy)),
                            new ArrayList<>(ItemAttributeHelper.getDefaultEntries(copy))
                    );
                    ItemAttributeHelper.writeEntries(copy, recomputeAllFromTotals(copy, working, rules.keySet()));

                    double deltaBlock = 0.0;
                    for (AttrStep st : inverse) {
                        if (BLOCK_SPEED_ID.equals(st.id)) {
                            deltaBlock += st.signedPercent;
                        }
                    }
                    if (Math.abs(deltaBlock) > 1.0E-12) {
                        double cur = ToolSpeedUtil.getBonus(copy);
                        ToolSpeedUtil.setBonus(copy, cur + deltaBlock);
                    }
                    return copy;
                }
            }

            setLevel(copy, newLevel);
            return copy;
        } catch (Throwable t) {
            LOG.error("[UpgradeService] downgradeLastLevel failed: {}", t.toString());
            return original.copy();
        }
    }

    private static void appendStepsAndUpdateTotals(ItemStack stack, List<AttrStep> steps, int change, long batchId, int levelBefore, int levelAfter) {
        StackTagUtil.updateTag(stack, tag -> {
            CompoundTag up = tag.contains(ROOT, Tag.TAG_COMPOUND) ? tag.getCompound(ROOT) : new CompoundTag();
            up.putInt(KEY_LEVEL, Math.max(0, levelAfter));

            CompoundTag totals = up.contains(KEY_TOTALS, Tag.TAG_COMPOUND) ? up.getCompound(KEY_TOTALS) : new CompoundTag();
            ListTag history = up.contains(KEY_HISTORY, Tag.TAG_LIST) ? up.getList(KEY_HISTORY, Tag.TAG_COMPOUND) : new ListTag();

            long now = System.currentTimeMillis();
            for (AttrStep s : steps) {
                if (s.id == null) {
                    continue;
                }

                String attrKey = s.id.toString();
                CompoundTag a = totals.contains(attrKey, Tag.TAG_COMPOUND) ? totals.getCompound(attrKey) : new CompoundTag();
                double sumPct = a.getDouble("sumPercent") + s.signedPercent;
                int count = a.getInt("count") + (change > 0 ? 1 : -1);
                if (count == 0) {
                    sumPct = 0.0;
                }

                a.putDouble("sumPercent", sumPct);
                a.putInt("count", Math.max(0, count));
                a.putDouble("lastPercent", s.signedPercent);
                totals.put(attrKey, a);

                CompoundTag ev = new CompoundTag();
                ev.putLong(H_TIME, now);
                ev.putLong(H_BATCH, batchId);
                ev.putString(H_ATTR, attrKey);
                ev.putString(H_OP, s.op);
                ev.putDouble(H_OLD, 0.0);
                ev.putDouble(H_DELTA, 0.0);
                ev.putDouble(H_NEW, 0.0);
                ev.putDouble(H_STEP_P, s.signedPercent);
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
    }

    private static void ensureBases(ItemStack stack, List<ItemAttributeHelper.Entry> working, List<ResourceLocation> present) {
        StackTagUtil.updateTag(stack, tag -> {
            CompoundTag up = tag.contains(ROOT, Tag.TAG_COMPOUND) ? tag.getCompound(ROOT) : new CompoundTag();
            CompoundTag bases = up.contains(KEY_BASES, Tag.TAG_COMPOUND) ? up.getCompound(KEY_BASES) : new CompoundTag();

            boolean missingAny = false;
            for (ResourceLocation id : present) {
                if (!bases.contains(id.toString(), Tag.TAG_ANY_NUMERIC)) {
                    missingAny = true;
                    break;
                }
            }
            if (!missingAny) {
                tag.put(ROOT, up);
                return;
            }

            Map<String, Double> firstSeen = new LinkedHashMap<>();
            for (ItemAttributeHelper.Entry e : working) {
                ResourceLocation id = e.attributeId();
                if (id == null || !present.contains(id) || e.modifier() == null) {
                    continue;
                }
                firstSeen.putIfAbsent(id.toString(), e.modifier().getAmount());
            }

            for (var kv : firstSeen.entrySet()) {
                if (!bases.contains(kv.getKey(), Tag.TAG_ANY_NUMERIC)) {
                    bases.putDouble(kv.getKey(), kv.getValue());
                }
            }

            up.put(KEY_BASES, bases);
            tag.put(ROOT, up);
        });
    }

    private static List<ItemAttributeHelper.Entry> recomputeAllFromTotals(ItemStack stack, List<ItemAttributeHelper.Entry> working, Set<ResourceLocation> managed) {
        Map<String, Double> bases = readBases(stack);
        Map<String, Double> sumPct = readTotalsPercents(stack);
        List<ItemAttributeHelper.Entry> out = new ArrayList<>(working.size());

        for (ItemAttributeHelper.Entry e : working) {
            Attribute attr = e.attribute();
            AttributeModifier m = e.modifier();
            ResourceLocation id = e.attributeId();
            if (attr == null || m == null || id == null || !managed.contains(id)) {
                out.add(e);
                continue;
            }

            String key = id.toString();
            if (!bases.containsKey(key)) {
                out.add(e);
                continue;
            }

            double base = bases.getOrDefault(key, m.getAmount());
            double pct = sumPct.getOrDefault(key, 0.0);
            double newAmount = base * (1.0 + pct);
            AttributeModifier nm = new AttributeModifier(m.getId(), m.getName(), newAmount, m.getOperation());
            out.add(new ItemAttributeHelper.Entry(attr, id, nm, e.slot()));
        }

        return out;
    }

    private static Map<String, Double> readBases(ItemStack stack) {
        Map<String, Double> out = new LinkedHashMap<>();
        try {
            CompoundTag bases = getUpgradeRoot(stack).getCompound(KEY_BASES);
            for (String k : bases.getAllKeys()) {
                out.put(k, bases.getDouble(k));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static Map<String, Double> readTotalsPercents(ItemStack stack) {
        Map<String, Double> out = new LinkedHashMap<>();
        try {
            CompoundTag totals = getUpgradeRoot(stack).getCompound(KEY_TOTALS);
            for (String k : totals.getAllKeys()) {
                CompoundTag a = totals.getCompound(k);
                out.put(k, a.getDouble("sumPercent"));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private record AttrStep(ResourceLocation id, double signedPercent, String ruleId, String op) {}

    public static int readLevelFromTagOrZero(ItemStack stack) {
        return readLevel(stack);
    }

    private static int readLevel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }

        try {
            CompoundTag up = getUpgradeRoot(stack);
            if (up.contains(KEY_LEVEL, Tag.TAG_INT)) {
                int lvl = up.getInt(KEY_LEVEL);
                if (lvl > 0) {
                    return Mth.clamp(lvl, 0, 100000);
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            String s = stack.getHoverName().getString();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\s+\\+(\\d+)$").matcher(s);
            if (m.find()) {
                return Mth.clamp(Integer.parseInt(m.group(1)), 0, 100000);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static void setLevel(ItemStack stack, int newLevel) {
        StackTagUtil.updateTag(stack, tag -> {
            CompoundTag up = tag.contains(ROOT, Tag.TAG_COMPOUND) ? tag.getCompound(ROOT) : new CompoundTag();
            up.putInt(KEY_LEVEL, Math.max(0, newLevel));
            tag.put(ROOT, up);
        });
    }

    private static List<ItemAttributeHelper.Entry> mergeListsUnique(List<ItemAttributeHelper.Entry> current, List<ItemAttributeHelper.Entry> defaults) {
        return ItemAttributeHelper.mergeUnique(current, defaults);
    }

    private static boolean sameEntry(ItemAttributeHelper.Entry a, ItemAttributeHelper.Entry b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.attribute() == null || b.attribute() == null || a.modifier() == null || b.modifier() == null) {
            return false;
        }
        return a.attribute().equals(b.attribute())
                && a.modifier().getId().equals(b.modifier().getId())
                && a.modifier().getOperation() == b.modifier().getOperation()
                && a.slot() == b.slot();
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static List<ResourceLocation> presentRuleBackedIds(List<ItemAttributeHelper.Entry> working, Set<ResourceLocation> ruleIds) {
        List<ResourceLocation> out = new ArrayList<>();
        for (ItemAttributeHelper.Entry e : working) {
            ResourceLocation id = e.attributeId();
            if (id != null && ruleIds.contains(id) && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static ResourceLocation weightedPickAllowingSynthetic(RandomSource rand, List<ResourceLocation> candidates, Map<ResourceLocation, UpgradeServerConfig.AttributeRuleConfig> rules) {
        int totalW = 0;
        for (ResourceLocation id : candidates) {
            int w = rules.containsKey(id) ? Math.max(0, rules.get(id).weight) : 1;
            totalW += w <= 0 ? 1 : w;
        }
        if (totalW <= 0) {
            totalW = candidates.size();
        }

        int r = rand.nextInt(totalW);
        ResourceLocation chosen = candidates.get(0);
        int acc = 0;
        for (ResourceLocation id : candidates) {
            int w = rules.containsKey(id) ? Math.max(0, rules.get(id).weight) : 1;
            w = w <= 0 ? 1 : w;
            acc += w;
            if (r < acc) {
                chosen = id;
                break;
            }
        }
        return chosen;
    }

    private static long findLastSuccessfulBatch(ListTag hist, int currentLevel) {
        long best = Long.MIN_VALUE;
        for (int i = 0; i < hist.size(); i++) {
            CompoundTag ev = hist.getCompound(i);
            if (ev.getInt(H_LAFT) != currentLevel || ev.getInt(H_CHANGE) != 1) {
                continue;
            }
            long b = ev.getLong(H_BATCH);
            if (b > best) {
                best = b;
            }
        }
        return best;
    }

    private static long guessLastPositiveBatch(ListTag hist) {
        long best = Long.MIN_VALUE;
        for (int i = 0; i < hist.size(); i++) {
            CompoundTag ev = hist.getCompound(i);
            if (ev.getInt(H_CHANGE) != 1) {
                continue;
            }
            long b = ev.getLong(H_BATCH);
            if (b > best) {
                best = b;
            }
        }
        return best;
    }

    private static CompoundTag getUpgradeRoot(ItemStack stack) {
        CompoundTag root = StackTagUtil.getTagCopy(stack);
        return root.contains(ROOT, Tag.TAG_COMPOUND) ? root.getCompound(ROOT).copy() : new CompoundTag();
    }

    private static String fmt(double x) {
        return String.format(Locale.ROOT, "%.5f", x);
    }
}
