// MainFile: src/main/java/org/z2six/infiniteupgrades/rules/chance/ChanceModel.java
package org.z2six.infiniteupgrades.rules.chance;

/** Computes chance for L -> L+1. */
public interface ChanceModel {
    /** @param currentLevel current +N (e.g. 0 means +0->+1) */
    double chanceForNextLevel(int currentLevel);
}
