// File: src/main/java/org/z2six/infiniteupgrades/core/config/sections/RitualsConfigSpec.java
package org.z2six.infiniteupgrades.core.config.sections;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * File: src/main/java/org/z2six/infiniteupgrades/core/config/sections/RitualsConfigSpec.java
 *
 * Angel / Demon ritual multipliers and infusion delay (server-side authority),
 * plus client SFX volume knobs for infusion success/fail.
 *
 * The multiplier here scales the attribute step computed by the attribute rule:
 *   final step = (rule step for this level) × (ritual multiplier).
 */
public final class RitualsConfigSpec {

    public final ModConfigSpec.DoubleValue angelStepMultiplier;
    public final ModConfigSpec.DoubleValue demonStepMultiplier;

    /** Server-authoritative delay (seconds) between clicking Infuse and getting the result. */
    public final ModConfigSpec.DoubleValue infuseDelaySeconds;

    /** Client SFX volume knobs (0.0–1.0) for infusion sound effects. */
    public final ModConfigSpec.DoubleValue successSfxVolume;
    public final ModConfigSpec.DoubleValue failSfxVolume;

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

        successSfxVolume = B.comment(
                "Client volume for infusion SUCCESS SFX. 1.0 = full volume, 0.0 = muted."
        ).defineInRange("successSfxVolume", 0.65, 0.0, 1.0);

        failSfxVolume = B.comment(
                "Client volume for infusion FAIL SFX. 1.0 = full volume, 0.0 = muted."
        ).defineInRange("failSfxVolume", 0.8, 0.0, 1.0);

        B.pop();
    }

    public static RitualsConfigSpec define(ModConfigSpec.Builder B) {
        return new RitualsConfigSpec(B);
    }

    public static final class Snapshot {
        public final double angelStepMult;
        public final double demonStepMult;
        public final double infuseDelaySeconds;

        public final double successSfxVolume; // 0..1
        public final double failSfxVolume;    // 0..1

        public Snapshot(double a, double d, double delaySec, double successVol, double failVol) {
            this.angelStepMult = Math.max(0.0, a);
            this.demonStepMult = Math.max(0.0, d);
            this.infuseDelaySeconds = Math.max(0.0, delaySec);
            this.successSfxVolume = Math.max(0.0, Math.min(1.0, successVol));
            this.failSfxVolume    = Math.max(0.0, Math.min(1.0, failVol));
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                Math.max(0.0, angelStepMultiplier.get()),
                Math.max(0.0, demonStepMultiplier.get()),
                Math.max(0.0, infuseDelaySeconds.get()),
                Math.max(0.0, Math.min(1.0, successSfxVolume.get())),
                Math.max(0.0, Math.min(1.0, failSfxVolume.get()))
        );
    }
}
