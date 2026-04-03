// File: src/main/java/org/z2six/infiniteupgrades/core/config/sections/RitualsConfigSpec.java
package org.z2six.infiniteupgrades.core.config.sections;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * File: src/main/java/org/z2six/infiniteupgrades/core/config/sections/RitualsConfigSpec.java
 *
 * Angel / Demon ritual multipliers and infusion delay (server-side authority).
 *
 * The multiplier here scales the attribute step computed by the attribute rule:
 *   final step = (rule step for this level) × (ritual multiplier).
 *
 * NOTE: Client SFX volumes have been moved to the CLIENT config
 * (see org.z2six.infiniteupgrades.core.config.UpgradeClientConfig).
 */
public final class RitualsConfigSpec {

    public final ForgeConfigSpec.DoubleValue angelStepMultiplier;
    public final ForgeConfigSpec.DoubleValue demonStepMultiplier;

    /** Server-authoritative delay (seconds) between clicking Infuse and getting the result. */
    public final ForgeConfigSpec.DoubleValue infuseDelaySeconds;

    private RitualsConfigSpec(ForgeConfigSpec.Builder B) {
        B.push("rituals");

        angelStepMultiplier = B.comment(
                "Multiplier applied to the per-level step when using ANGEL.",
                "1.0 = no change, 2.0 = double the step, 0.5 = half the step."
        ).defineInRange("angelStepMultiplier", 1.0, 0.0, 100.0);

        demonStepMultiplier = B.comment(
                "Multiplier applied to the per-level step when using DEMON.",
                "Used to make Demon upgrades stronger or weaker than Angel on a per-step basis."
        ).defineInRange("demonStepMultiplier", 1.5, 0.0, 100.0);

        infuseDelaySeconds = B.comment(
                "Delay in seconds after pressing Infuse before the result is applied (server authoritative).",
                "Set to 0.0 for instant results."
        ).defineInRange("infuseDelaySeconds", 3.0, 0.0, 3600.0);

        B.pop();
    }

    public static RitualsConfigSpec define(ForgeConfigSpec.Builder B) {
        return new RitualsConfigSpec(B);
    }

    public static final class Snapshot {
        public final double angelStepMult;
        public final double demonStepMult;
        public final double infuseDelaySeconds;

        public Snapshot(double a, double d, double delaySec) {
            this.angelStepMult = Math.max(0.0, a);
            this.demonStepMult = Math.max(0.0, d);
            this.infuseDelaySeconds = Math.max(0.0, delaySec);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                Math.max(0.0, angelStepMultiplier.get()),
                Math.max(0.0, demonStepMultiplier.get()),
                Math.max(0.0, infuseDelaySeconds.get())
        );
    }
}
