// MainFile: src/main/java/org/z2six/infiniteupgrades/logic/LevelMath.java
package org.z2six.infiniteupgrades.core.math;

import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LevelMath {
    private static final Pattern PLUS_SUFFIX = Pattern.compile("\\s+\\+(\\d+)$");

    private LevelMath() {}

    /** Parse "+N" suffix from a display name; returns 0 if none. */
    public static int parseLevel(Component displayName) {
        try {
            String s = displayName.getString();
            Matcher m = PLUS_SUFFIX.matcher(s);
            if (m.find()) return Mth.clamp(Integer.parseInt(m.group(1)), 0, 100000);
        } catch (Throwable ignored) {}
        return 0;
    }

    /** Strip the trailing "+N" from a string (if present). */
    public static String stripPlusSuffix(String s) {
        Matcher m = PLUS_SUFFIX.matcher(s);
        if (m.find()) return s.substring(0, m.start());
        return s;
    }
}
