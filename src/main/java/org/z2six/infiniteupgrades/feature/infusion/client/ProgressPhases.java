// File: src/main/java/org/z2six/infiniteupgrades/client/ProgressPhases.java
package org.z2six.infiniteupgrades.feature.infusion.client;

/**
 * Single source of truth for infusion animation phase splits.
 * Used by both the UI (progress draw) and SFX scheduler to avoid drift.
 */
public final class ProgressPhases {
    private ProgressPhases() {}

    // Must match ProgressFillView’s values
    public static final double BREATH_FRACTION = 0.10;   // 10%
    public static final double REVERSE_TO_FILL_RATIO = 0.25; // reverse = 0.25 * fill

    /** Fraction of total time used by the fill phase. */
    public static double fillFraction() {
        return (1.0 - BREATH_FRACTION) / (1.0 + REVERSE_TO_FILL_RATIO);
    }

    /** Fraction of total time used by the breathe phase. */
    public static double breatheFraction() {
        return BREATH_FRACTION;
    }

    /** Fraction of total time used by the reverse phase. */
    public static double reverseFraction() {
        return REVERSE_TO_FILL_RATIO * fillFraction();
    }

    /** Ticks from start until flashing begins (i.e., when fill is complete). */
    public static int ticksUntilFlashingStart(int totalTicks) {
        double t = Math.max(0, totalTicks) * fillFraction();
        // round up a hair so we don’t miss the boundary due to integer truncation
        return Math.max(0, (int)Math.floor(t));
    }

    /** Ticks from end back to flashing start (how far before end flashing begins). */
    public static int ticksBeforeEndAtFlashingStart(int totalTicks) {
        // flashing begins when fill ends; remaining time is breathe+reverse
        double remainingFrac = breatheFraction() + reverseFraction();
        double t = Math.max(0, totalTicks) * remainingFrac;
        return Math.max(1, (int)Math.round(t));
    }
}
