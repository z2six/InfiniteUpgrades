// File: src/main/java/org/z2six/infiniteupgrades/config/sections/reputation/ReputationConfigSpec.java
package org.z2six.infiniteupgrades.config.sections;

import net.neoforged.neoforge.common.ModConfigSpec;

import static org.z2six.infiniteupgrades.config.parsing.ConfigParsing.clamp01;

/** Reputation section: caps and deltas. */
public final class ReputationConfigSpec {

    public final ModConfigSpec.IntValue    repMax;
    public final ModConfigSpec.DoubleValue repDeltaSuccess;
    public final ModConfigSpec.DoubleValue repDeltaFail;
    public final ModConfigSpec.DoubleValue repCrossCoupling;
    public final ModConfigSpec.DoubleValue repBonusPerPoint;
    public final ModConfigSpec.DoubleValue repBonusClamp;

    private ReputationConfigSpec(ModConfigSpec.Builder B) {
        B.push("reputation");
        repMax           = B.comment("Absolute max magnitude of reputation for either side (symmetric).")
                .defineInRange("max", 100, 1, 100000);
        repDeltaSuccess  = B.comment("Reputation delta applied to the chosen side on SUCCESS.")
                .defineInRange("deltaOnSuccess", 0.1, -1000.0, 1000.0);
        repDeltaFail     = B.comment("Reputation delta applied to the chosen side on FAIL.")
                .defineInRange("deltaOnFail", 0.0, -1000.0, 1000.0);
        repCrossCoupling = B.comment("Opposite side receives -delta*crossCoupling.")
                .defineInRange("crossCoupling", 1.0, 0.0, 1000.0);
        repBonusPerPoint = B.comment("Bonus success chance per reputation point (e.g., 0.001 = +0.1% per point).")
                .defineInRange("bonusPerPoint", 0.001, -1.0, 1.0);
        repBonusClamp    = B.comment("Clamp on absolute bonus from reputation (e.g., 0.20 = ±20%).")
                .defineInRange("bonusClamp", 0.20, 0.0, 1.0);
        B.pop();
    }

    public static ReputationConfigSpec define(ModConfigSpec.Builder B) {
        return new ReputationConfigSpec(B);
    }

    public static final class Snapshot {
        public final int repMax;
        public final double repDeltaSuccess;
        public final double repDeltaFail;
        public final double repCross;
        public final double repBonusPerPoint;
        public final double repBonusClamp;

        public Snapshot(int repMax, double repSucc, double repFail, double repCross, double bonusPerPoint, double bonusClamp) {
            this.repMax = repMax;
            this.repDeltaSuccess = repSucc;
            this.repDeltaFail = repFail;
            this.repCross = repCross;
            this.repBonusPerPoint = bonusPerPoint;
            this.repBonusClamp = bonusClamp;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                Math.max(1, repMax.get()),
                repDeltaSuccess.get(),
                repDeltaFail.get(),
                Math.max(0.0, repCrossCoupling.get()),
                repBonusPerPoint.get(),
                clamp01(repBonusClamp.get())
        );
    }
}
