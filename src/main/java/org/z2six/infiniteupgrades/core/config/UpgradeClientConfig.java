// File: src/main/java/org/z2six/infiniteupgrades/core/config/UpgradeClientConfig.java
package org.z2six.infiniteupgrades.core.config;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;

import static org.z2six.infiniteupgrades.core.config.ConfigParsing.clamp01;

/**
 * File: src/main/java/org/z2six/infiniteupgrades/core/config/UpgradeClientConfig.java
 *
 * CLIENT-ONLY config for InfiniteUpgrades.
 * Holds local-only knobs that should not be server-authoritative.
 *
 * Currently includes:
 *  - SFX volume for infusion success/fail (0.0..1.0).
 */
public final class UpgradeClientConfig {
    private static final Logger LOG = LogUtils.getLogger();

    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    // ---- Sections ----
    static {
        B.push("client");
        B.push("infusion");

        SUCCESS_SFX_VOLUME = B.comment("Client volume for infusion SUCCESS SFX. 1.0 = full, 0.0 = muted.")
                .defineInRange("successSfxVolume", 0.65, 0.0, 1.0);

        FAIL_SFX_VOLUME = B.comment("Client volume for infusion FAIL SFX. 1.0 = full, 0.0 = muted.")
                .defineInRange("failSfxVolume", 0.80, 0.0, 1.0);

        B.pop(); // infusion
        B.pop(); // client
    }

    public static final ModConfigSpec.DoubleValue SUCCESS_SFX_VOLUME;
    public static final ModConfigSpec.DoubleValue FAIL_SFX_VOLUME;

    public static final ModConfigSpec SPEC = B.build();

    private UpgradeClientConfig() {}

    public static final class Snapshot {
        public final double sfxSuccessVolume; // 0..1
        public final double sfxFailVolume;    // 0..1

        public Snapshot(double success, double fail) {
            this.sfxSuccessVolume = clamp01(success);
            this.sfxFailVolume    = clamp01(fail);
        }
    }

    /** Build a live snapshot; safe and defensive. */
    public static Snapshot snapshot() {
        try {
            return new Snapshot(
                    SUCCESS_SFX_VOLUME.get(),
                    FAIL_SFX_VOLUME.get()
            );
        } catch (Throwable t) {
            LOG.error("[UpgradeClientConfig] snapshot() failed; using defaults. {}", t.toString());
            return new Snapshot(0.65, 0.80);
        }
    }

    /** Optional: log reloads. Must be registered on the MOD event bus. */
    public static void onClientConfigReload(ModConfigEvent event) {
        LOG.debug("[UpgradeClientConfig] onClientConfigReload: {}", event.getConfig().getFileName());
    }
}
