package org.z2six.infiniteupgrades.feature.infusion.logic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpgradeCandidateSelectorTest {
    private static final String ATTACK_DAMAGE = "minecraft:generic.attack_damage";
    private static final String ATTACK_SPEED = "minecraft:generic.attack_speed";
    private static final String MINING_SPEED = "infiniteupgrades:block_speed";

    @Test
    void miningToolsOnlyReceiveMiningSpeedCandidate() {
        List<String> candidates = UpgradeCandidateSelector.candidatesForItem(
                List.of(ATTACK_DAMAGE, ATTACK_SPEED),
                true,
                MINING_SPEED
        );

        assertEquals(List.of(MINING_SPEED), candidates);
    }

    @Test
    void nonMiningItemsKeepTheirPresentAttributeCandidates() {
        List<String> present = List.of(ATTACK_DAMAGE, ATTACK_SPEED);

        List<String> candidates = UpgradeCandidateSelector.candidatesForItem(
                present,
                false,
                MINING_SPEED
        );

        assertEquals(present, candidates);
    }
}
