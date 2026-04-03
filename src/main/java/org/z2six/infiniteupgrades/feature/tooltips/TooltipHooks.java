// MainFile: src/main/java/org/z2six/infiniteupgrades/feature/tooltips/TooltipHooks.java
package org.z2six.infiniteupgrades.feature.tooltips;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.config.UpgradeServerConfig;
import org.z2six.infiniteupgrades.core.util.StackTagUtil;
import org.z2six.infiniteupgrades.feature.infusion.logic.ToolSpeedUtil;

import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * TooltipHooks – client-side tooltip augmentation for InfiniteUpgrades.
 *
 * Now prefers the server-authored totals at iu_upgrade.totals{ attr -> sumPercent (fraction) }.
 * If missing, falls back to scanning history for stepPercent and compounding factors (legacy items).
 *
 * Also emits a custom "Block Speed (+X%)" line for mining tools when the tool_speed_bonus stat is present.
 * The label uses a custom green (#00A800) and the numeric part in parentheses is aqua,
 * so preview obfuscation can target the number.
 *
 * Additionally, this class decorates the first tooltip line with a colored " +N"
 * suffix based on iu_upgrade.level, without writing any CUSTOM_NAME to the item.
 */
public final class TooltipHooks {
    private static final Logger LOG = LogUtils.getLogger();

    /** Toggle for chatty debug logs from this class. Set true only when needed. */
    private static final boolean DEBUG_VERBOSE = false;

    // NBT layout
    private static final String ROOT_UPGRADE_TAG = "iu_upgrade";
    private static final String TOTALS_TAG       = "totals";
    private static final String HISTORY_TAG      = "history";
    private static final String ATTR_KEY         = "attribute";
    private static final String STEP_PCT_KEY     = "stepPercent";
    private static final String LEVEL_KEY        = "level";

    // Formatting
    private static final DecimalFormat PCT_FMT;
    static {
        DecimalFormat f = new DecimalFormat("#.#"); // 0 or 1 decimal
        f.setMaximumFractionDigits(1);
        PCT_FMT = f;
    }

    // Custom colors
    private static final int BLOCK_SPEED_LABEL_RGB = 0x00A800; // #00a800

    // Name suffix pattern: trailing " +<digits>"
    private static final Pattern PLUS_SUFFIX = Pattern.compile("\\s+\\+(\\d+)$");

    private TooltipHooks() {}

    // Registered via NeoForge.EVENT_BUS.addListener(TooltipHooks::onTooltip)
    public static void onTooltip(ItemTooltipEvent event) {
        try {
            final ItemStack stack = event.getItemStack();
            final List<Component> tooltip = event.getToolTip();

            if (stack == null || stack.isEmpty() || tooltip == null || tooltip.isEmpty()) {
                return;
            }

            // 1) Prefer totals (server-canonical)
            final Map<String, Double> pctByAttrId = readTotalsPercents(stack);
            if (pctByAttrId.isEmpty()) {
                // 2) Legacy fallback: scan history and aggregate signed stepPercent
                pctByAttrId.putAll(aggregatePercentsFromHistory(stack));
            }
            if (pctByAttrId.isEmpty()) {
                // keep going—custom "Block Speed" and "+N" name suffix may still need to be appended
            } else {
                // 3) Resolve registry to map display names -> percents
                final RegistryAccess access = resolveRegistryAccess(event);
                final Registry<Attribute> attrReg = (access != null) ? access.registryOrThrow(Registries.ATTRIBUTE) : null;

                final Map<String, Double> nameToPct = new LinkedHashMap<>();
                for (Map.Entry<String, Double> e : pctByAttrId.entrySet()) {
                    final String idStr = e.getKey();
                    final Double pct = e.getValue();
                    if (pct == null) continue;

                    boolean added = false;
                    try {
                        if (attrReg != null) {
                            ResourceLocation rl = ResourceLocation.tryParse(idStr);
                            if (rl != null && attrReg.containsKey(rl)) {
                                Attribute attr = attrReg.get(rl);
                                if (attr != null) {
                                    String display = Component.translatable(attr.getDescriptionId()).getString();
                                    if (display != null && !display.isBlank()) {
                                        nameToPct.put(display.toLowerCase(Locale.ROOT), pct * 100.0);
                                        added = true;
                                    }
                                }
                            }
                        }
                    } catch (Throwable t) {
                        debug("Registry resolution failed for {}: {}", idStr, t.toString());
                    }

                    if (!added) {
                        String human = humanizeId(idStr);
                        if (!human.isBlank()) {
                            nameToPct.putIfAbsent(human, pct * 100.0);
                        }
                    }
                }

                // 4) Append "(±X%)" after matching attribute lines
                for (int i = 0; i < tooltip.size(); i++) {
                    final Component line = tooltip.get(i);
                    final String plain = line.getString().toLowerCase(Locale.ROOT);

                    String matched = null;
                    Double pct = null;
                    for (Map.Entry<String, Double> e : nameToPct.entrySet()) {
                        final String needle = e.getKey();
                        if (!needle.isEmpty() && plain.contains(needle)) {
                            matched = needle;
                            pct = e.getValue();
                            break;
                        }
                    }
                    if (matched == null || pct == null) continue;

                    if (Math.abs(pct) < 0.0001) continue;

                    final String pctText = (pct >= 0 ? "(+" : "(") + trimZeros(pct) + "%)";
                    final ChatFormatting color = pct > 0.0001 ? ChatFormatting.AQUA
                            : pct < -0.0001 ? ChatFormatting.RED
                            : ChatFormatting.GRAY;

                    tooltip.set(i, line.copy().append(Component.literal(" " + pctText).withStyle(color)));
                }
            }

            // 5) Append "+N" suffix to the *first* tooltip line based on iu_upgrade.level.
            //    - Does NOT touch NBT name; purely visual.
            //    - If the first line already ends with " +<digits>", we leave it alone (no double suffix).
            try {
                final int level = readLevelFromTagOrZero(stack);
                if (level > 0 && tooltip != null && !tooltip.isEmpty()) {
                    Component first = tooltip.get(0);
                    String plainName = first.getString();

                    // If it already ends with " +digits", don't append another.
                    if (!PLUS_SUFFIX.matcher(plainName).find()) {
                        int rgb = UpgradeServerConfig.resolveSuffixColor(level);
                        ChatFormatting fallback = ChatFormatting.AQUA;

                        MutableComponent suffix = Component.literal(" +" + level);
                        if (rgb != 0) {
                            suffix = suffix.withStyle(style -> style.withColor(rgb));
                        } else {
                            suffix = suffix.withStyle(fallback);
                        }

                        tooltip.set(0, first.copy().append(suffix));
                        debug("Tooltip: appended level suffix +{} to first line", level);
                    } else {
                        debug("Tooltip: first line already has +N suffix, skipping IU suffix");
                    }
                }
            } catch (Throwable t) {
                debug("Tooltip: level suffix append failed: {}", t.toString());
            }

        } catch (Throwable t) {
            LOG.error("[InfiniteUpgrades] Tooltip augmentation failed (defensive skip).", t);
        }

        // Append custom tool stat line if present:
        // "Block Speed " in #00A800, then "(+X%)" in aqua so the preview obfuscator hits the numbers in parens.
        try {
            final ItemStack stack = event.getItemStack();
            if (ToolSpeedUtil.isMiningTool(stack)) {
                double frac = ToolSpeedUtil.getBonus(stack);
                if (Math.abs(frac) > 1.0e-6) {
                    String pct = ToolSpeedUtil.formatPercentNoSign(frac); // "15%" etc.
                    MutableComponent line =
                            Component.literal(" Block Speed ")
                                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(BLOCK_SPEED_LABEL_RGB)))
                                    .append(Component.literal("(+" + pct + ")").withStyle(ChatFormatting.AQUA));
                    event.getToolTip().add(line);
                    debug("Tooltip: appended Block Speed (+{}) with custom green label", pct);
                }
            }
        } catch (Throwable t) {
            debug("Tooltip: tool_speed_bonus append failed: {}", t.toString());
        }
    }

    private static Map<String, Double> readTotalsPercents(ItemStack stack) {
        Map<String, Double> out = new LinkedHashMap<>();
        try {
            final var up = StackTagUtil.getTagCopy(stack).getCompound(ROOT_UPGRADE_TAG);
            final var totals = up.getCompound(TOTALS_TAG);
            for (String key : totals.getAllKeys()) {
                out.put(key, totals.getCompound(key).getDouble("sumPercent")); // fraction
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** Legacy fallback: just sum signed stepPercent from history. */
    private static Map<String, Double> aggregatePercentsFromHistory(ItemStack stack) {
        Map<String, Double> out = new LinkedHashMap<>();
        try {
            final var up = StackTagUtil.getTagCopy(stack).getCompound(ROOT_UPGRADE_TAG);
            if (!up.contains(HISTORY_TAG, net.minecraft.nbt.Tag.TAG_LIST)) return out;
            final var history = up.getList(HISTORY_TAG, net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < history.size(); i++) {
                final var ev = history.getCompound(i);
                final String id = ev.getString(ATTR_KEY);
                if (id == null || id.isBlank()) continue;
                double p = ev.getDouble(STEP_PCT_KEY);
                out.merge(id, p, Double::sum);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /**
     * Read iu_upgrade.level if present; else fallback to parsing " +N" from the hover name.
     * This mirrors the server-side helpers but stays client-only and read-only.
     */
    private static int readLevelFromTagOrZero(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        try {
            final var root = StackTagUtil.getTagCopy(stack);
            if (root.contains(ROOT_UPGRADE_TAG, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                final var up = root.getCompound(ROOT_UPGRADE_TAG);
                if (up.contains(LEVEL_KEY, net.minecraft.nbt.Tag.TAG_INT)) {
                    int lvl = up.getInt(LEVEL_KEY);
                    if (lvl > 0) {
                        return Mth.clamp(lvl, 0, 100_000);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // Fallback: parse "+N" at end of hover name (for legacy items that still have it baked in).
        try {
            String s = stack.getHoverName().getString();
            var m = PLUS_SUFFIX.matcher(s);
            if (m.find()) {
                int lvl = Integer.parseInt(m.group(1));
                return Mth.clamp(lvl, 0, 100_000);
            }
        } catch (Throwable ignored) {
        }

        return 0;
    }

    @Nullable
    private static RegistryAccess resolveRegistryAccess(ItemTooltipEvent event) {
        try { if (event.getEntity() != null) return event.getEntity().level().registryAccess(); }
        catch (Throwable ignored) {}
        try { if (Minecraft.getInstance() != null && Minecraft.getInstance().level != null) return Minecraft.getInstance().level.registryAccess(); }
        catch (Throwable ignored) {}
        return null;
    }

    private static String humanizeId(String id) {
        try {
            String s = id;
            int colon = s.indexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            s = s.replace('_', ' ').replace('.', ' ').trim();
            return s.toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return id.toLowerCase(Locale.ROOT);
        }
    }

    private static String trimZeros(double pct) {
        String s = PCT_FMT.format(pct);
        s = s.replace(',', '.');
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s;
    }

    /** Centralized debug gate. Flip {@link #DEBUG_VERBOSE} to enable these logs. */
    private static void debug(String msg, Object... args) {
        if (!DEBUG_VERBOSE) return;            // hard switch for spam control
        if (!LOG.isDebugEnabled()) return;     // respect logger level too
        LOG.debug("[InfiniteUpgrades][Tooltip] " + msg, args);
    }
}
