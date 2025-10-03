// File: src/main/java/org/z2six/infiniteupgrades/core/config/sections/RitualsConfigSpec.java
package org.z2six.infiniteupgrades.core.config.sections;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Angel / Demon ritual multipliers and infusion delay (server-side authority).
 *
 * The multiplier here scales the attribute step computed by the attribute rule:
 *   final step = (rule step for this level) × (ritual multiplier).
 *
 * Example:
 *   - attributes.defaultStep = 0.05 (i.e., +5%)
 *   - ANGEL multiplier = 1.0  → +5%
 *   - DEMON multiplier = 1.5  → +7.5%
 *
 * This lets you make Demon upgrades “stronger per step” (or weaker), independently of the
 * base per-level step (tuning.stepPercent or per-attribute defaultStep/overrides).
 */
public final class RitualsConfigSpec {

    public final ModConfigSpec.DoubleValue angelStepMultiplier;
    public final ModConfigSpec.DoubleValue demonStepMultiplier;

    /** Server-authoritative delay (seconds) between clicking Infuse and getting the result. */
    public final ModConfigSpec.DoubleValue infuseDelaySeconds;

    private RitualsConfigSpec(ModConfigSpec.Builder B) {
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

    public static RitualsConfigSpec define(ModConfigSpec.Builder B) {
        return new RitualsConfigSpec(B);
    }

    public static final class Snapshot {
        public final double angelStepMult;
        public final double demonStepMult;
        public final double infuseDelaySeconds;

        public Snapshot(double a, double d, double delaySec) {
            this.angelStepMult = a;
            this.demonStepMult = d;
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
