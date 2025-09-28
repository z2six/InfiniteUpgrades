// File: src/main/java/org/z2six/infiniteupgrades/config/sections/rituals/RitualsConfigSpec.java
package org.z2six.infiniteupgrades.config.sections;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Rituals section: angel/demon step multipliers. */
public final class RitualsConfigSpec {

    public final ModConfigSpec.DoubleValue angelStepMultiplier;
    public final ModConfigSpec.DoubleValue demonStepMultiplier;

    private RitualsConfigSpec(ModConfigSpec.Builder B) {
        B.push("rituals");
        angelStepMultiplier = B.comment("Multiplier for Angel step size (applied to per-level rule step).")
                .defineInRange("angelStepMultiplier", 1.0, 0.0, 100.0);
        demonStepMultiplier = B.comment("Multiplier for Demon step size (applied to per-level rule step).")
                .defineInRange("demonStepMultiplier", 1.5, 0.0, 100.0);
        B.pop();
    }

    public static RitualsConfigSpec define(ModConfigSpec.Builder B) {
        return new RitualsConfigSpec(B);
    }

    public static final class Snapshot {
        public final double angelStepMult;
        public final double demonStepMult;

        public Snapshot(double a, double d) {
            this.angelStepMult = a;
            this.demonStepMult = d;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                Math.max(0.0, angelStepMultiplier.get()),
                Math.max(0.0, demonStepMultiplier.get())
        );
    }
}
