// infiniteupgrades/logic/UpgradeData

package org.z2six.infiniteupgrades.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Stores and retrieves per-item upgrade information inside CUSTOM_DATA under key "iu_upgrade".
 *
 * Schema (NBT under CUSTOM_DATA root):
 *
 * iu_upgrade: {
 *   level: int,                      // current +N level
 *   history: List[                   // list of per-attribute entries (1 per unique attribute id)
 *     {
 *       id: "minecraft:generic.attack_damage",
 *       count: int,                  // how many times we directly targeted this attribute
 *       sumPercent: double,          // cumulative percent deltas (sum of +/- fractions, e.g. +0.05 + 0.1 => 0.15)
 *       sumAdd: double,              // cumulative additive deltas (if any rules used ADDITIVE)
 *       original: double,            // first-seen magnitude for this attribute (approximation)
 *       current: double              // last-computed magnitude after upgrades (approximation)
 *     },
 *     ...
 *   ]
 * }
 *
 * Notes:
 * - We purposefully store a *summary* per attribute that’s stable to render in panels or logs.
 * - The actual authoritative modifiers live on ATTRIBUTE_MODIFIERS and are applied by UpgradeService.
 * - All methods here are defensive; they’ll never throw and will log warnings if something’s off.
 */
public final class UpgradeData {
    private static final Logger LOG = LogUtils.getLogger();

    public static final String ROOT = "iu_upgrade";
    private static final String KEY_LEVEL     = "level";
    private static final String KEY_HISTORY   = "history";
    private static final String KEY_ID        = "id";
    private static final String KEY_COUNT     = "count";
    private static final String KEY_SUM_PCT   = "sumPercent";
    private static final String KEY_SUM_ADD   = "sumAdd";
    private static final String KEY_ORIGINAL  = "original";
    private static final String KEY_CURRENT   = "current";

    // In-memory representation
    private int level;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public static final class Entry {
        public final String id;    // attribute RL, e.g., minecraft:generic.attack_damage
        public int count;
        public double sumPercent;
        public double sumAdd;
        public double original;
        public double current;

        public Entry(String id) {
            this.id = id;
        }

        public CompoundTag toTag() {
            CompoundTag t = new CompoundTag();
            t.putString(KEY_ID, id);
            t.putInt(KEY_COUNT, count);
            t.putDouble(KEY_SUM_PCT, sumPercent);
            t.putDouble(KEY_SUM_ADD, sumAdd);
            t.putDouble(KEY_ORIGINAL, original);
            t.putDouble(KEY_CURRENT, current);
            return t;
        }

        public static Entry fromTag(CompoundTag t) {
            if (t == null) return null;
            String id = t.getString(KEY_ID);
            if (id == null || id.isEmpty()) return null;
            Entry e = new Entry(id);
            e.count = safeGetInt(t, KEY_COUNT);
            e.sumPercent = safeGetDouble(t, KEY_SUM_PCT);
            e.sumAdd = safeGetDouble(t, KEY_SUM_ADD);
            e.original = safeGetDouble(t, KEY_ORIGINAL);
            e.current = safeGetDouble(t, KEY_CURRENT);
            return e;
        }
    }

    private UpgradeData() {}

    // ---------- Load / Save ----------

    /** Read a snapshot from the given stack. Never throws. */
    public static UpgradeData read(ItemStack stack) {
        UpgradeData data = new UpgradeData();
        try {
            if (stack == null || stack.isEmpty()) return data;
            CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
            if (cd == null) return data;
            CompoundTag root = cd.copyTag();
            if (!root.contains(ROOT, Tag.TAG_COMPOUND)) return data;
            CompoundTag up = root.getCompound(ROOT);
            data.level = safeGetInt(up, KEY_LEVEL);

            if (up.contains(KEY_HISTORY, Tag.TAG_LIST)) {
                ListTag list = up.getList(KEY_HISTORY, Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag et = list.getCompound(i);
                    Entry e = Entry.fromTag(et);
                    if (e != null) data.entries.put(e.id, e);
                }
            }
        } catch (Throwable t) {
            LOG.error("[UpgradeData] read failed: {}", t.toString());
        }
        return data;
    }

    /** Write snapshot back into the stack. Returns true if it changed the stack. */
    public boolean writeTo(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return false;
            CustomData cd = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CustomData updated = cd.update(root -> {
                CompoundTag up = root.getCompound(ROOT);
                up.putInt(KEY_LEVEL, level);

                ListTag list = new ListTag();
                for (Entry e : entries.values()) {
                    list.add(e.toTag());
                }
                up.put(KEY_HISTORY, list);

                root.put(ROOT, up);
            });
            stack.set(DataComponents.CUSTOM_DATA, updated);
            return true;
        } catch (Throwable t) {
            LOG.error("[UpgradeData] writeTo failed: {}", t.toString());
            return false;
        }
    }

    // ---------- Mutations / Queries ----------

    public int level() { return level; }
    public void setLevel(int lvl) { this.level = Math.max(0, lvl); }

    public Map<String, Entry> getEntriesView() { return Map.copyOf(entries); }

    /** Ensure there is an entry for attribute id and return it. */
    public Entry ensure(String attrId) {
        return entries.computeIfAbsent(Objects.requireNonNull(attrId), Entry::new);
    }

    /**
     * Append an upgrade change to an attribute.
     * @param attrId attribute RL (e.g., "minecraft:generic.attack_damage")
     * @param stepPercent fraction applied this step (+0.05 for +5%, or -0.05)
     * @param stepAdd additive delta this step (0 if none)
     * @param fromValue magnitude before this step (for bookkeeping)
     * @param toValue magnitude after this step
     */
    public void appendChange(String attrId, double stepPercent, double stepAdd, double fromValue, double toValue) {
        Entry e = ensure(attrId);
        e.count += 1;
        e.sumPercent += stepPercent;
        e.sumAdd += stepAdd;
        if (e.count == 1) {
            e.original = fromValue;
        }
        e.current = toValue;
    }

    /** Convenience: a short summary like "(+15%)" or "(+10%, +2)". Empty string if no data. */
    public String summaryFor(String attrId) {
        Entry e = entries.get(attrId);
        if (e == null) return "";
        StringBuilder sb = new StringBuilder();
        if (Math.abs(e.sumPercent) > 1.0e-9) {
            double pct = e.sumPercent * 100.0;
            sb.append(String.format("%s%.0f%%", (pct >= 0 ? "+" : ""), pct));
        }
        if (Math.abs(e.sumAdd) > 1.0e-9) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(String.format("%s%.2f", (e.sumAdd >= 0 ? "+" : ""), e.sumAdd));
        }
        if (sb.length() == 0) return "";
        return "(" + sb + ")";
    }

    /** Debug helper for logs. */
    public Component debugComponent() {
        StringBuilder sb = new StringBuilder();
        sb.append("L").append(level).append(" ");
        for (Entry e : entries.values()) {
            sb.append("[").append(e.id).append(" c=").append(e.count)
                    .append(" pct=").append(String.format("%.3f", e.sumPercent))
                    .append(" add=").append(String.format("%.3f", e.sumAdd))
                    .append(" cur=").append(String.format("%.3f", e.current))
                    .append("] ");
        }
        return Component.literal(sb.toString().trim());
    }

    // ---------- helpers ----------

    private static int safeGetInt(CompoundTag t, String k) {
        try { return t.getInt(k); } catch (Throwable ignored) { return 0; }
    }
    private static double safeGetDouble(CompoundTag t, String k) {
        try { return t.getDouble(k); } catch (Throwable ignored) { return 0.0; }
    }
}
