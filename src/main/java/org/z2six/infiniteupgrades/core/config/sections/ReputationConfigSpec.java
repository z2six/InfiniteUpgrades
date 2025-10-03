// File: src/main/java/org/z2six/infiniteupgrades/config/sections/reputation/ReputationConfigSpec.java
package org.z2six.infiniteupgrades.core.config.sections;

import net.neoforged.neoforge.common.ModConfigSpec;

import static org.z2six.infiniteupgrades.core.config.ConfigParsing.clamp01;

/**
 * User-friendly Reputation settings (UNIFIED value).
 *
 * Your reputation is ONE number that goes from negative to positive:
 * - Negative numbers mean DEMON-aligned.
 * - Positive numbers mean ANGEL-aligned.
 *
 * Gameplay summary:
 * - When you use an ANGEL ritual, your reputation moves toward POSITIVE.
 * - When you use a DEMON ritual, your reputation moves toward NEGATIVE.
 * - Reputation also affects infusion success chance: the more aligned you are
 *   with the ritual you’re using, the more your success chance gets a bonus
 *   (and a penalty if you’re aligned with the opposite side).
 *
 * Helpful mental model:
 *    Demon <----- negative ... 0 ... positive -----> Angel
 */
public final class ReputationConfigSpec {

    public final ModConfigSpec.IntValue    repMax;
    public final ModConfigSpec.DoubleValue repDeltaSuccess;
    public final ModConfigSpec.DoubleValue repDeltaFail;
    public final ModConfigSpec.DoubleValue repBonusPerPoint;
    public final ModConfigSpec.DoubleValue repBonusClamp;

    /** NEW: Scale factor applied to the *opposite-side* penalty (0..1). Example: 0.5 ⇒ half malus. */
    public final ModConfigSpec.DoubleValue oppositePenaltyFactor;

    private ReputationConfigSpec(ModConfigSpec.Builder B) {
        B.push("reputation");

        repMax = B.comment(
                "MAXIMUM absolute reputation you can reach. Your real range is [-max .. +max].",
                "",
                "What this means:",
                " - If max = 100, your reputation goes from -100 (very Demon) to +100 (very Angel).",
                " - If max = 1000, your reputation goes from -1000 to +1000, and so on.",
                "",
                "Tip: If you raise this number a lot, also consider lowering 'bonusPerPoint'",
                "so the total bonus stays reasonable (see example under 'bonusPerPoint')."
        ).defineInRange("max", 100, 1, 100000);

        repDeltaSuccess = B.comment(
                "How much your reputation moves TOWARD the ritual side when an infusion SUCCESSFULLY completes.",
                "",
                "Plain English:",
                " - Using ANGEL: adds this value to your reputation (moves it more POSITIVE).",
                " - Using DEMON: subtracts this value (moves it more NEGATIVE).",
                "",
                "Example:",
                " - If deltaOnSuccess = 0.5 and you succeed at an Angel infusion, your reputation increases by +0.5.",
                " - If you succeed at a Demon infusion, your reputation decreases by -0.5."
        ).defineInRange("deltaOnSuccess", 0.1, -1000.0, 1000.0);

        repDeltaFail = B.comment(
                "How much your reputation moves TOWARD the ritual side when an infusion FAILS.",
                "",
                "Why would a failure still move reputation? It reflects your attempt/intent.",
                "Set this to 0.0 if you only want successful infusions to matter.",
                "",
                "Example:",
                " - If deltaOnFail = 0.1 and you fail an Angel infusion, your reputation increases by +0.1.",
                " - If you fail a Demon infusion, your reputation decreases by -0.1."
        ).defineInRange("deltaOnFail", 0.0, -1000.0, 1000.0);

        repBonusPerPoint = B.comment(
                "How much each single reputation POINT affects infusion success chance when using that side.",
                "",
                "Important details:",
                " - Positive reputation HELPS Angel infusions but HURTS Demon infusions.",
                " - Negative reputation HELPS Demon infusions but HURTS Angel infusions.",
                " - The total bonus (or penalty) is clamped by 'bonusClamp' below.",
                "",
                "Units:",
                " - This number is a FRACTION added to the success chance. For example:",
                "   0.001 = +0.1% per reputation point (because 0.001 * 100% = 0.1%).",
                "",
                "Recommended pairings (so max alignment roughly hits the clamp):",
                " - max = 100   -> bonusPerPoint ≈ 0.002  (100 * 0.002 = 0.20  => 20%)",
                " - max = 500   -> bonusPerPoint ≈ 0.0004 (500 * 0.0004 = 0.20 => 20%)",
                " - max = 1000  -> bonusPerPoint ≈ 0.0002 (1000 * 0.0002 = 0.20 => 20%)",
                "",
                "Example with defaults:",
                " - max = 100, bonusPerPoint = 0.001, bonusClamp = 0.20 (±20%)",
                "   If your reputation is +60 and you use ANGEL: bonus = +0.060 = +6%.",
                "   If you use DEMON at +60: bonus = -0.060 = -6% (a penalty)."
        ).defineInRange("bonusPerPoint", 0.001, -1.0, 1.0);

        repBonusClamp = B.comment(
                "The MAXIMUM absolute bonus (or penalty) reputation can apply to infusion chance.",
                "",
                "This protects the game from becoming too easy or too hard at extreme reputation.",
                "",
                "Example:",
                " - If bonusClamp = 0.20, your total bonus cannot go above +20% or below -20%,",
                "   even if 'max' and 'bonusPerPoint' would otherwise produce a larger value."
        ).defineInRange("bonusClamp", 0.20, 0.0, 1.0);

        oppositePenaltyFactor = B.comment(
                "Scale applied to the *penalty* when you infuse with the OPPOSITE faction.",
                "Example: 0.5 means if Demon side would be +20%, Angel will be -10%."
        ).defineInRange("oppositePenaltyFactor", 0.5, 0.0, 1.0);

        B.pop();
    }

    public static ReputationConfigSpec define(ModConfigSpec.Builder B) {
        return new ReputationConfigSpec(B);
    }

    public static final class Snapshot {
        public final int repMax;
        public final double repDeltaSuccess;
        public final double repDeltaFail;
        public final double repBonusPerPoint;
        public final double repBonusClamp;
        /** NEW: 0..1 multiplier for opposite-side malus. */
        public final double oppositePenaltyFactor;

        public Snapshot(int repMax, double repSucc, double repFail, double bonusPerPoint, double bonusClamp,
                        double oppositePenaltyFactor) {
            this.repMax = repMax;
            this.repDeltaSuccess = repSucc;
            this.repDeltaFail = repFail;
            this.repBonusPerPoint = bonusPerPoint;
            this.repBonusClamp = bonusClamp;
            this.oppositePenaltyFactor = Math.max(0.0, Math.min(1.0, oppositePenaltyFactor));
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                Math.max(1, repMax.get()),
                repDeltaSuccess.get(),
                repDeltaFail.get(),
                repBonusPerPoint.get(),
                clamp01(repBonusClamp.get()),
                clamp01(oppositePenaltyFactor.get())
        );
    }
}
