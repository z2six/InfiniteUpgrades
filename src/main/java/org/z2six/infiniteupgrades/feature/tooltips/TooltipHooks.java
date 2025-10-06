// File: src/main/java/org/z2six/infiniteupgrades/feature/tooltips/TooltipHooks.java
package org.z2six.infiniteupgrades.feature.tooltips;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TooltipHooks – client-side tooltip augmentation for InfiniteUpgrades.
 *
 * Now prefers the server-authored totals at iu_upgrade.totals{ attr -> sumPercent (fraction) }.
 * If missing, falls back to scanning history for stepPercent and compounding factors (legacy items).
 */
public final class TooltipHooks {
    private static final Logger LOG = LogUtils.getLogger();

    // NBT layout
    private static final String ROOT_UPGRADE_TAG = "iu_upgrade";
    private static final String TOTALS_TAG       = "totals";
    private static final String HISTORY_TAG      = "history";
    private static final String ATTR_KEY         = "attribute";
    private static final String STEP_PCT_KEY     = "stepPercent";

    // Formatting
    private static final DecimalFormat PCT_FMT;
    static {
        DecimalFormat f = new DecimalFormat("#.#"); // 0 or 1 decimal
        f.setMaximumFractionDigits(1);
        PCT_FMT = f;
    }

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
            if (pctByAttrId.isEmpty()) return;

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
            int appended = 0;
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
                appended++;
            }
        } catch (Throwable t) {
            LOG.error("[InfiniteUpgrades] Tooltip augmentation failed (defensive skip).", t);
        }
    }

    private static Map<String, Double> readTotalsPercents(ItemStack stack) {
        Map<String, Double> out = new LinkedHashMap<>();
        try {
            if (!stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) return out;
            final CustomData cd = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            if (cd == CustomData.EMPTY) return out;
            final var up = cd.copyTag().getCompound(ROOT_UPGRADE_TAG);
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
            if (!stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) return out;
            final CustomData cd = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            if (cd == CustomData.EMPTY) return out;
            final var up = cd.copyTag().getCompound(ROOT_UPGRADE_TAG);
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

    private static void debug(String msg, Object... args) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("[InfiniteUpgrades][Tooltip] " + msg, args);
        }
    }
}
