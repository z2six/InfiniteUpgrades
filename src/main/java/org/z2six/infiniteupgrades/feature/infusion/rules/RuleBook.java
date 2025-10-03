// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/rules/RuleBook.java
package org.z2six.infiniteupgrades.feature.infusion.rules;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.feature.infusion.rules.chance.ChanceModel;
import org.z2six.infiniteupgrades.feature.infusion.rules.chance.ExponentialChanceModel;
import org.z2six.infiniteupgrades.feature.infusion.rules.chance.FlatDecrementChanceModel;

import java.util.*;
import java.util.stream.Collectors;

/** In-memory view of the server config. */
public final class RuleBook {
    private static final Logger LOG = LogUtils.getLogger();

    public final UpgradeServerConfig.UpgradeMode mode;
    public final int maxLevel;
    public final ChanceModel chanceModel;
    public final Map<ResourceLocation, AttributeRule> rules; // id->rule

    public RuleBook(UpgradeServerConfig.Snapshot snap) {
        this.mode = snap.upgradeMode;
        this.maxLevel = snap.maxLevel;
        this.chanceModel = switch (snap.chanceModel) {
            case FLAT_DECREMENT -> new FlatDecrementChanceModel(snap.startChance, snap.decrementPerLevel, snap.chanceOverrides);
            case EXPONENTIAL -> new ExponentialChanceModel(snap.startChance, snap.exponentialBase, snap.chanceOverrides);
        };

        Map<ResourceLocation, AttributeRule> map = new LinkedHashMap<>();
        for (UpgradeServerConfig.AttributeRuleConfig arc : snap.attributes) {
            if (!arc.enabled) continue;
            try {
                AttributeRule r = new AttributeRule(
                        arc.id, arc.enabled, arc.weight, arc.direction, arc.stepType,
                        arc.defaultStep, arc.perLevelOverrides, arc.capMin, arc.capMax,
                        arc.applyToMagnitude, arc.rounding
                );
                map.put(arc.id, r);
            } catch (Throwable t) {
                LOG.error("[RuleBook] Failed to build rule {}: {}", arc.id, t.toString());
            }
        }
        this.rules = Collections.unmodifiableMap(map);
    }

    /** Returns upgradable rules, filtered by the provided attribute set of an item. */
    public List<AttributeRule> rulesForItemAttributes(Set<ResourceLocation> itemAttrs) {
        List<AttributeRule> list = new ArrayList<>();
        for (ResourceLocation id : itemAttrs) {
            AttributeRule r = rules.get(id);
            if (r != null && r.enabled) list.add(r);
        }
        return list;
    }

    public List<AttributeRule> weightedRandomSelection(List<AttributeRule> candidates, Random random) {
        if (candidates.isEmpty()) return List.of();
        if (mode == UpgradeServerConfig.UpgradeMode.ALL) return candidates;

        int total = candidates.stream().mapToInt(r -> Math.max(0, r.weight)).sum();
        if (total <= 0) return List.of(candidates.get(0)); // stable fallback

        int roll = random.nextInt(total);
        int acc = 0;
        for (AttributeRule r : candidates) {
            acc += Math.max(0, r.weight);
            if (roll < acc) return List.of(r);
        }
        return List.of(candidates.get(candidates.size() - 1)); // fallback
    }

    @Override
    public String toString() {
        return "RuleBook{" +
                "mode=" + mode +
                ", maxLevel=" + maxLevel +
                ", rules=" + rules.keySet().stream().map(ResourceLocation::toString).collect(Collectors.joining(",")) +
                '}';
    }
}
