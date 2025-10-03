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
 * Dynamically appends an accumulated "(+X%)" (or "(-X%)") after any attribute line shown in the tooltip,
 * vanilla or modded. It composes per-attribute upgrade factors from server-authored NBT:
 *
 *    components.minecraft:custom_data.iu_upgrade.history[*]
 *      - attribute: string id, e.g. "minecraft:generic.attack_damage" (modded supported)
 *      - stepPercent: double (e.g., +0.05 for +5%)  [preferred]
 *      - old/new: doubles; if stepPercent missing, use new/old factor (fallback)
 *      - op: "ADD_VALUE" (informational here)
 *
 * The code resolves each attribute id via the registry to obtain a localized display name,
 * then appends the computed "(+X%)" to that attribute's line in the tooltip.
 *
 * Defensive: if anything is missing or invalid, we skip and log at DEBUG. Never crashes.
 *
 * NOTE: This class is registered programmatically in ClientSetup (NeoForge.EVENT_BUS.addListener),
 * so there are no subscriber annotations here.
 */
public final class TooltipHooks {
    private static final Logger LOG = LogUtils.getLogger();

    // NBT layout
    private static final String ROOT_UPGRADE_TAG = "iu_upgrade";
    private static final String HISTORY_TAG      = "history";
    private static final String ATTR_KEY         = "attribute";
    private static final String STEP_PCT_KEY     = "stepPercent";
    private static final String OLD_KEY          = "old";
    private static final String NEW_KEY          = "new";

    // Attribute ids we want custom semantics for (display-side)
    private static final String ATTACK_SPEED_ID  = "minecraft:generic.attack_speed";

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
            final TooltipFlag flag = event.getFlags();

            if (stack == null || stack.isEmpty() || tooltip == null || tooltip.isEmpty()) {
                debug("Skip tooltip: empty context");
                return;
            }

            // 1) Build map: attribute-id -> compounded percent (Double, in % units)
            final Map<String, Double> pctByAttrId = computeAllAttributePercents(stack);
            if (pctByAttrId.isEmpty()) {
                return;
            }

            // 2) Resolve registry & localized names for attributes we have data for
            final RegistryAccess access = resolveRegistryAccess(event);
            final Registry<Attribute> attrReg = (access != null) ? access.registryOrThrow(Registries.ATTRIBUTE) : null;

            // Map: lowercase localized name -> percent, plus fallbacks by id/path words
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
                                    nameToPct.put(display.toLowerCase(Locale.ROOT), pct);
                                    added = true;
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    debug("Registry resolution failed for {}: {}", idStr, t.toString());
                }

                // Fallback: humanize id path (e.g., "generic.attack_speed" -> "attack speed")
                if (!added) {
                    String human = humanizeId(idStr);
                    if (!human.isBlank()) {
                        nameToPct.putIfAbsent(human, pct);
                    }
                }
            }

            if (nameToPct.isEmpty()) {
                debug("No resolvable attribute display names; leaving tooltip unchanged.");
                return;
            }

            // 3) Append "(+X%)" to any tooltip line whose text contains a known attribute display name
            int appendedCount = 0;
            for (int i = 0; i < tooltip.size(); i++) {
                final Component line = tooltip.get(i);
                final String plain = line.getString().toLowerCase(Locale.ROOT);

                // Find the best matching attribute name contained in this line
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

                // Skip near-zero to reduce clutter
                if (Math.abs(pct) < 0.0001) continue;

                final String pctText = (pct >= 0 ? "(+" : "(") + trimZeros(pct) + "%)";
                final ChatFormatting color = pct > 0.0001 ? ChatFormatting.AQUA
                        : pct < -0.0001 ? ChatFormatting.RED
                        : ChatFormatting.GRAY;

                tooltip.set(i, line.copy().append(Component.literal(" " + pctText).withStyle(color)));
                appendedCount++;
            }

            // debug("Appended % to {} tooltip line(s).", appendedCount);
        } catch (Throwable t) {
            // Never crash tooltips – log and continue.
            LOG.error("[InfiniteUpgrades] Tooltip augmentation failed (defensive skip).", t);
        }
    }

    /**
     * Builds a map of attribute-id -> compounded percent (in % units) from iu_upgrade.history.
     * Uses stepPercent when present; else falls back to new/old.
     *
     * For attack speed, we flip the sign so improvements show positive:
     * - The item modifier is a negative penalty to the player's base; reducing that penalty is an improvement.
     */
    private static Map<String, Double> computeAllAttributePercents(ItemStack stack) {
        final Map<String, Double> result = new LinkedHashMap<>();
        try {
            if (!stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) return result;

            final CustomData cd = stack.getOrDefault(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    CustomData.EMPTY
            );
            if (cd == CustomData.EMPTY) return result;

            final net.minecraft.nbt.CompoundTag root = cd.copyTag();
            if (root == null || !root.contains(ROOT_UPGRADE_TAG, net.minecraft.nbt.Tag.TAG_COMPOUND)) return result;

            final net.minecraft.nbt.CompoundTag iu = root.getCompound(ROOT_UPGRADE_TAG);
            if (!iu.contains(HISTORY_TAG, net.minecraft.nbt.Tag.TAG_LIST)) return result;

            final net.minecraft.nbt.ListTag history = iu.getList(HISTORY_TAG, net.minecraft.nbt.Tag.TAG_COMPOUND);
            if (history.isEmpty()) return result;

            // Accumulate per-attribute multiplicative factor
            final Map<String, Double> factorByAttr = new LinkedHashMap<>();
            final Map<String, Integer> countedByAttr = new LinkedHashMap<>();

            for (int i = 0; i < history.size(); i++) {
                final net.minecraft.nbt.CompoundTag step = history.getCompound(i);
                final String attrId = step.getString(ATTR_KEY);
                if (attrId == null || attrId.isBlank()) continue;

                double stepFactor = 1.0;
                boolean used = false;

                if (step.contains(STEP_PCT_KEY, net.minecraft.nbt.Tag.TAG_DOUBLE)) {
                    final double p = step.getDouble(STEP_PCT_KEY); // e.g., +0.05
                    stepFactor = 1.0 + p;
                    used = true;
                } else if (step.contains(OLD_KEY, net.minecraft.nbt.Tag.TAG_DOUBLE) &&
                        step.contains(NEW_KEY, net.minecraft.nbt.Tag.TAG_DOUBLE)) {
                    final double oldV = safeDouble(step, OLD_KEY);
                    final double newV = safeDouble(step, NEW_KEY);
                    if (oldV != 0.0) {
                        stepFactor = newV / oldV;
                        used = true;
                    }
                }

                if (!used) continue;

                factorByAttr.merge(attrId, stepFactor, (a, b) -> a * b);
                countedByAttr.merge(attrId, 1, Integer::sum);
            }

            // Convert factors to percents (compounded since base)
            for (Map.Entry<String, Double> e : factorByAttr.entrySet()) {
                final String id = e.getKey();
                final double factor = e.getValue();
                final int count = countedByAttr.getOrDefault(id, 0);
                if (count == 0) continue;

                double pct = (factor - 1.0) * 100.0;

                // Display semantics: attack speed improvements should be positive
                if (ATTACK_SPEED_ID.equals(id)) {
                    pct = -pct; // invert for display
                }

                result.put(id, pct);
            }
        } catch (Throwable t) {
            debug("computeAllAttributePercents failed: {}", t.toString());
        }
        return result;
    }

    private static double safeDouble(net.minecraft.nbt.CompoundTag tag, String key) {
        try { return tag.getDouble(key); } catch (Throwable ignored) { return 0.0; }
    }

    /** Resolve a usable RegistryAccess for attribute name translation. */
    @Nullable
    private static RegistryAccess resolveRegistryAccess(ItemTooltipEvent event) {
        try {
            if (event.getEntity() != null) {
                return event.getEntity().level().registryAccess();
            }
        } catch (Throwable ignored) {}
        try {
            if (Minecraft.getInstance() != null && Minecraft.getInstance().level != null) {
                return Minecraft.getInstance().level.registryAccess();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Fallback: turn an id like "namespace:generic.attack_speed" into "attack speed". */
    private static String humanizeId(String id) {
        try {
            String s = id;
            int colon = s.indexOf(':');
            if (colon >= 0) s = s.substring(colon + 1); // drop namespace
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
