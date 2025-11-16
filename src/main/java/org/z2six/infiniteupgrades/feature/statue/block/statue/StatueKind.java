// MainFile: src/main/java/org/z2six/infiniteupgrades/feature/statue/block/statue/StatueKind.java
package org.z2six.infiniteupgrades.feature.statue.block.statue;

/**
 * Distinguishes which statue it is so RMB can open the correct screen.
 */
public enum StatueKind {
    ANGEL,
    DEMON;

    public boolean isAngel() { return this == ANGEL; }
    public boolean isDemon() { return this == DEMON; }
}
