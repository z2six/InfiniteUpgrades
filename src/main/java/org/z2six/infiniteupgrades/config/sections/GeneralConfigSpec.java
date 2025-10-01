package org.z2six.infiniteupgrades.config.sections;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.config.UpgradeServerConfig.UpgradeMode;

import java.util.List;

/**
 * General knobs that affect the whole system.
 */
public final class GeneralConfigSpec {
    private static final Logger LOG = LogUtils.getLogger();

    public final ModConfigSpec.IntValue maxLevel;
    public final ModConfigSpec.EnumValue<UpgradeMode> upgradeMode;

    public final ModConfigSpec.ConfigValue<List<? extends String>> nameColorRules;

    private GeneralConfigSpec(ModConfigSpec.Builder B) {
        B.push("general");

        maxLevel = B.comment(
                        "Maximum enhancement level an item can reach. Set to 0 to effectively disable upgrades.",
                        "Example: 20 means +20 is the cap.")
                .defineInRange("maxLevel", 20, 0, 1000);

        upgradeMode = B.comment(
                "How to apply attribute upgrades when an attempt succeeds:",
                " - RANDOM: pick ONE attribute (uses 'weight' values from attribute rules).",
                " - ALL:    apply the step to ALL eligible attributes.",
                "",
                "Note: Some ritual-specific logic may override this choice."
        ).defineEnum("upgradeMode", UpgradeMode.RANDOM);

        nameColorRules = B.comment(
                "Color the item's name based on its +level. Format examples:",
                " - \"A-B=color\"  (range),  e.g. \"1-4=blue\"",
                " - \"A+=color\"   (A or higher), e.g. \"10+=gold\"",
                " - \"N=color\"    (exact level), e.g. \"7=green\"",
                "Colors: https://minecraft.fandom.com/wiki/Formatting_codes",
                "Default: [\"1-4=blue\",\"5-9=light_purple\",\"10+=gold\"]"
        ).defineListAllowEmpty("nameColorRules",
                List.of("1-4=blue", "5-9=light_purple", "10+=gold"),
                o -> o instanceof String);

        B.pop();
    }

    public static GeneralConfigSpec define(ModConfigSpec.Builder B) {
        return new GeneralConfigSpec(B);
    }

    public static final class Snapshot {
        public final UpgradeMode upgradeMode;
        public final int maxLevel;

        public Snapshot(UpgradeMode mode, int maxLevel) {
            this.upgradeMode = mode;
            this.maxLevel = maxLevel;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(upgradeMode.get(), maxLevel.get());
    }

    // ---------- Name color helpers (logic unchanged) ----------

    public ChatFormatting nameColorForLevel(int level) {
        try {
            List<? extends String> rules = nameColorRules.get();
            if (rules != null) {
                for (Object o : rules) {
                    if (!(o instanceof String s)) continue;
                    ChatFormatting col = matchColorRule(level, s.trim());
                    if (col != null) return col;
                }
            }
        } catch (Throwable t) {
            LOG.error("[GeneralConfigSpec] nameColorForLevel failed: {}", t.toString());
        }
        return ChatFormatting.WHITE;
    }

    public int resolveSuffixColor(int level) {
        ChatFormatting f = nameColorForLevel(level);
        return switch (f) {
            case BLUE -> 0x5555FF;
            case LIGHT_PURPLE -> 0xFF55FF;
            case GOLD -> 0xFFAA00;
            case AQUA -> 0x55FFFF;
            case GREEN -> 0x55FF55;
            case DARK_GREEN -> 0x00AA00;
            case RED -> 0xFF5555;
            case WHITE -> 0xFFFFFF;
            default -> 0;
        };
    }

    private static @Nullable ChatFormatting matchColorRule(int level, String rule) {
        if (rule.isEmpty() || !rule.contains("=")) return null;
        String[] parts = rule.split("=", 2);
        String left = parts[0].trim();
        String colorName = parts[1].trim();

        ChatFormatting color = ChatFormatting.getByName(colorName);
        if (color == null) color = ChatFormatting.WHITE;

        int dash = left.indexOf('-');
        if (dash > 0) {
            String aStr = left.substring(0, dash).trim();
            String bStr = left.substring(dash + 1).trim();
            try {
                int a = Integer.parseInt(aStr);
                int b = Integer.parseInt(bStr);
                if (level >= a && level <= b) return color;
            } catch (NumberFormatException ignored) {}
            return null;
        }

        if (left.endsWith("+")) {
            String aStr = left.substring(0, left.length() - 1).trim();
            try {
                int a = Integer.parseInt(aStr);
                if (level >= a) return color;
            } catch (NumberFormatException ignored) {}
            return null;
        }

        try {
            int n = Integer.parseInt(left);
            if (level == n) return color;
        } catch (NumberFormatException ignored) {}

        return null;
    }
}
