package org.z2six.infiniteupgrades.client;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.logic.UpgradeData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Global tooltip enhancer:
 *  - If an item has iu_upgrade data, append a concise "(+X%)" style summary to attribute lines.
 *  - If an item has iu_preview=true (our ghost preview), obfuscate only numeric parts.
 *
 * This mirrors the logic used in AngelScreen, but works anywhere the item is hovered (inventory, world, etc.).
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = "infiniteupgrades")
public final class TooltipHooks {
    private static final Logger LOG = LogUtils.getLogger();

    // Patterns to alter attribute lines
    private static final Pattern LEADING_NUM = Pattern.compile("^\\s*([+\\-]?\\d+(?:\\.\\d+)?)\\s+(.*)$");
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*\\]");

    private TooltipHooks() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent evt) {
        try {
            ItemStack stack = evt.getItemStack();
            if (stack == null || stack.isEmpty()) return;

            // Detect preview ghosts (iu_preview) — obfuscate numeric parts only.
            if (isPreview(stack)) {
                List<Component> vanilla = evt.getToolTip();
                List<Component> modified = obfuscateNumericPartsInCombatLines(vanilla);
                // Replace the list in-place (preserving header line etc.)
                vanilla.clear();
                vanilla.addAll(modified);
                return;
            }

            // For real items: show summaries from iu_upgrade (if present).
            UpgradeData data = UpgradeData.read(stack);
            if (data.level() <= 0 && data.getEntriesView().isEmpty()) {
                return; // nothing to add
            }

            List<Component> tip = evt.getToolTip();
            List<Component> out = new ArrayList<>(tip.size());

            for (Component line : tip) {
                String raw = line.getString();
                String lower = raw.toLowerCase(Locale.ROOT);

                // Attribute lines: append the summary if we recognize which attribute it is
                String attrId = guessAttrId(lower);
                if (attrId != null) {
                    String summary = data.summaryFor(attrId);
                    if (!summary.isEmpty()) {
                        MutableComponent with = Component.literal(raw)
                                .append(Component.literal(" " + summary).withStyle(ChatFormatting.DARK_GREEN));
                        out.add(with);
                        continue;
                    }
                }

                // default: keep the line
                out.add(line);
            }

            tip.clear();
            tip.addAll(out);

        } catch (Throwable t) {
            LOG.error("[TooltipHooks] onTooltip failed: {}", t.toString());
        }
    }

    // -------------- helpers --------------

    private static boolean isPreview(ItemStack stack) {
        try {
            CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
            if (cd == null) return false;
            return cd.copyTag().getBoolean("iu_preview");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Try to map a tooltip attribute line to an attribute id we track. */
    private static String guessAttrId(String lowerLine) {
        // Order matters; simple heuristics for vanilla attributes:
        if (lowerLine.contains("attack damage"))         return "minecraft:generic.attack_damage";
        if (lowerLine.contains("attack speed"))          return "minecraft:generic.attack_speed";
        if (lowerLine.contains("armor toughness"))       return "minecraft:generic.armor_toughness";
        // Guard against "armor trim"
        if (lowerLine.contains(" armor") && !lowerLine.contains("armor trim")) return "minecraft:generic.armor";
        if (lowerLine.contains("knockback resistance"))  return "minecraft:generic.knockback_resistance";
        return null;
    }

    private static List<Component> obfuscateNumericPartsInCombatLines(List<Component> vanilla) {
        List<Component> out = new ArrayList<>(vanilla.size());
        for (Component c : vanilla) {
            String raw = c.getString();
            String lower = raw.toLowerCase(Locale.ROOT);

            // Skip equipment-slot headers like "When in Main Hand"
            if (lower.startsWith("when ")) {
                out.add(c);
                continue;
            }

            boolean isCombatLine =
                    lower.contains("attack damage") ||
                            lower.contains("attack speed")  ||
                            lower.contains("armor toughness") ||
                            (lower.contains(" armor") && !lower.contains("armor trim")) ||
                            lower.contains("knockback resistance");

            if (!isCombatLine) {
                out.add(c);
                continue;
            }

            Matcher m = LEADING_NUM.matcher(raw);
            if (!m.find()) {
                out.add(obfuscateBracketsOnly(raw));
                continue;
            }

            String num = m.group(1);
            String rest = m.group(2);

            MutableComponent rebuilt = Component.literal("")
                    .append(Component.literal(num).withStyle(ChatFormatting.OBFUSCATED))
                    .append(Component.literal(" "))
                    .append(obfuscateBracketSegments(rest));
            out.add(rebuilt);
        }
        return out;
    }

    private static MutableComponent obfuscateBracketSegments(String text) {
        MutableComponent result = Component.literal("");
        int idx = 0;
        Matcher bm = BRACKETS.matcher(text);
        while (bm.find()) {
            if (bm.start() > idx) {
                result = result.append(Component.literal(text.substring(idx, bm.start())));
            }
            String seg = text.substring(bm.start(), bm.end());
            result = result.append(Component.literal(seg).withStyle(ChatFormatting.OBFUSCATED));
            idx = bm.end();
        }
        if (idx < text.length()) {
            result = result.append(Component.literal(text.substring(idx)));
        }
        return result;
    }

    private static MutableComponent obfuscateBracketsOnly(String text) {
        return obfuscateBracketSegments(text);
    }
}
