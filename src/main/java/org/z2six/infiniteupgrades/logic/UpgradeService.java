// MainFile: src/main/java/org/z2six/infiniteupgrades/logic/UpgradeService.java
package org.z2six.infiniteupgrades.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemAttributeModifiers.Entry;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal, self-contained upgrade engine used by AngelMenu.
 *
 * Policy:
 *  - Success chance comes from Config.TUNING.chanceForNextLevel(currentLevel)
 *    (exposed via getSuccessChance()).
 *  - When upgrading, either scale ONE random supported attribute or ALL supported
 *    attributes (toggle later if you add a behavior flag; for now RANDOM).
 *  - Supported attributes:
 *      ATTACK_DAMAGE (+)
 *      ATTACK_SPEED  (invert: multiply by (1 - step))
 *      ARMOR         (+)
 *      ARMOR_TOUGHNESS (+)
 *      KNOCKBACK_RESISTANCE (+)
 *  - Per-upgrade step comes from Config.TUNING.percentBonusForLevelUp(currentLevel).
 *
 * This file intentionally avoids dependencies on any other “rules/registry” layer,
 * so you can build & test the Infuse flow immediately.
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

    // ---- Public API used by AngelMenu ----------------------------------------------------------

    /** Expose chance function from your tuning. */
    public static double getSuccessChance(int currentLevel) {
        try {
            return Config.TUNING.chanceForNextLevel(currentLevel);
        } catch (Throwable t) {
            LOG.error("[UpgradeService] getSuccessChance failed: {}", t.toString());
            return 0.0;
        }
    }

    /** Result wrapper for upgrades. */
    public static record Result(ItemStack upgraded, boolean success, int newLevel) {}

    /**
     * Perform the actual upgrade.
     * - Works on a copy of the provided stack (original remains untouched).
     * - Returns a Result with the upgraded stack, success flag, and new level.
     */
    public static Result tryUpgrade(ItemStack original, RandomSource rand) {
        if (original == null || original.isEmpty()) {
            return new Result(ItemStack.EMPTY, false, 0);
        }

        ItemStack copy = original.copy();
        int currentLevel = parseLevel(copy);
        boolean ok = false;

        try {
            ok = upgradeOneRandomSupportedAttribute(copy, rand);
        } catch (Throwable t) {
            LOG.error("[UpgradeService] tryUpgrade failed: {}", t.toString());
        }

        int newLevel = ok ? Math.min(currentLevel + 1, Config.TUNING.maxLevel) : currentLevel;
        return new Result(copy, ok, newLevel);
    }

    // ---- Core logic ---------------------------------------------------------------------------

    private static boolean upgradeOneRandomSupportedAttribute(ItemStack stack, RandomSource rand) {
        if (stack == null || stack.isEmpty()) return false;

        int level = parseLevel(stack);
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
        if (eligible.isEmpty()) return false;

        // RANDOM pick: choose one entry to modify
        Entry chosen = eligible.get(rand.nextInt(eligible.size()));

        // Rebuild all modifiers, replacing ONLY the chosen entry with a scaled version
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
                AttributeModifier scaled = scaleModifier(a, m, step);
                builder.add(a, scaled, e.slot());
                modified = true;
            } else {
                builder.add(a, m, e.slot());
            }
        }

        if (!modified) return false;

        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
        return true;
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

    // ---- Small helpers ------------------------------------------------------------------------

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
