package org.z2six.infiniteupgrades.feature.infusion.logic;

import java.util.ArrayList;
import java.util.List;

final class UpgradeCandidateSelector {
    private UpgradeCandidateSelector() {}

    static <T> List<T> candidatesForItem(List<T> presentAttributeIds,
                                         boolean miningTool,
                                         T miningSpeedId) {
        if (miningTool) {
            return miningSpeedId == null ? List.of() : List.of(miningSpeedId);
        }
        return new ArrayList<>(presentAttributeIds);
    }
}
