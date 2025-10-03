// File: src/main/java/org/z2six/infiniteupgrades/config/parsing/ConfigParsing.java
package org.z2six.infiniteupgrades.core.config;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Shared parsing and small utility helpers used across config sections.
 *
 * NOTE: Logic mirrors what previously lived inside UpgradeServerConfig, only moved here.
 */
public final class ConfigParsing {
    private ConfigParsing() {}

    /** Clamp to [0,1]. */
    public static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }

    /** Parse level->double map from ["1=1.0","2=0.95", ...] preserving insertion order and returning an unmodifiable map. */
    public static Map<Integer, Double> parseLevelDoubleMap(@Nullable List<? extends String> lines, String labelForLogsIgnored) {
        Map<Integer, Double> out = new LinkedHashMap<>();
        if (lines == null) return out;
        for (Object o : lines) {
            if (!(o instanceof String s)) continue;
            String trimmed = s.trim();
            if (trimmed.isEmpty()) continue;
            int idx = trimmed.indexOf('=');
            if (idx <= 0 || idx >= trimmed.length() - 1) {
                // Keep silent here; original code logged via UpgradeServerConfig, but avoiding cross-dependency.
                continue;
            }
            try {
                int lvl = Integer.parseInt(trimmed.substring(0, idx).trim());
                double val = Double.parseDouble(trimmed.substring(idx + 1).trim());
                out.put(lvl, val);
            } catch (NumberFormatException ignored) {}
        }
        return Collections.unmodifiableMap(out);
    }

    /** Parse tier units list like ["SMALL=1","MEDIUM=4", ...] into a LinkedHashMap preserving order. */
    public static Map<String,Integer> parseTierUnits(@Nullable List<? extends String> list) {
        Map<String,Integer> out = new LinkedHashMap<>();
        if (list == null) return out;
        for (Object o : list) {
            if (!(o instanceof String s)) continue;
            String t = s.trim();
            int eq = t.indexOf('=');
            if (eq <= 0 || eq >= t.length()-1) continue;
            String key = t.substring(0, eq).trim().toUpperCase(Locale.ROOT);
            String val = t.substring(eq+1).trim();
            try {
                int n = Integer.parseInt(val);
                if (n > 0) out.put(key, n);
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    /** Parse tier minimum hearts list like ["SMALL=0","MEDIUM=10", ...] into a LinkedHashMap preserving order. */
    public static Map<String,Double> parseTierMinHearts(@Nullable List<? extends String> list) {
        Map<String,Double> out = new LinkedHashMap<>();
        if (list == null) return out;
        for (Object o : list) {
            if (!(o instanceof String s)) continue;
            String t = s.trim();
            int eq = t.indexOf('=');
            if (eq <= 0 || eq >= t.length()-1) continue;
            String key = t.substring(0, eq).trim().toUpperCase(Locale.ROOT);
            String val = t.substring(eq+1).trim();
            try {
                double hearts = Double.parseDouble(val);
                if (hearts >= 0.0) out.put(key, hearts);
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    /** Parse a list of resource location strings into an immutable LinkedHashSet preserving order. */
    public static Set<ResourceLocation> parseIdSet(@Nullable List<? extends String> list) {
        if (list == null || list.isEmpty()) return Set.of();
        Set<ResourceLocation> out = new LinkedHashSet<>();
        for (Object o : list) {
            if (!(o instanceof String s)) continue;
            String t = s.trim();
            if (t.isEmpty()) continue;
            try { out.add(ResourceLocation.parse(t)); } catch (Throwable ignored) {}
        }
        return Collections.unmodifiableSet(out);
    }
}
