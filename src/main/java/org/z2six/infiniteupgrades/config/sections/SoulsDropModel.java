// File: src/main/java/org/z2six/infiniteupgrades/config/sections/SoulsDropModel.java
package org.z2six.infiniteupgrades.config.sections;

/** Selection models for choosing which soul tier drops. */
public enum SoulsDropModel {
    /** Legacy: units = floor(max_hp * ratio); pick largest tier with unitValue <= units. */
    RATIO,
    /** New: choose highest tier whose minHearts <= victimHearts (hearts = HP/2). */
    HP_THRESHOLDS
}
