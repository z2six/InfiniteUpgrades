// File: src/main/java/org/z2six/infiniteupgrades/logic/SoulDrops.java
package org.z2six.infiniteupgrades.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.Infiniteupgrades;
import org.z2six.infiniteupgrades.world.SoulOrbEntity;

@EventBusSubscriber(modid = Infiniteupgrades.MODID)
public final class SoulDrops {
    private static final Logger LOG = LogUtils.getLogger();

    private static final int ORB_LIFETIME_TICKS = 20 * 30; // 30s

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent evt) {
        try {
            LivingEntity le = evt.getEntity();
            Level lvl = le.level();
            if (lvl.isClientSide) return;

            double maxHp = Math.max(1.0, le.getMaxHealth());
            int souls = (int) Math.floor(maxHp * 0.75); // baseline

            RandomSource r = le.getRandom();
            Vec3 base = le.position().add(0, le.getBbHeight() * 0.5, 0);

            int xl = souls / 16; souls -= xl * 16;
            int lg = souls / 8;  souls -= lg * 8;
            int md = souls / 4;  souls -= md * 4;
            int sm = souls;

            spawnOrbs(lvl, base, SoulOrbEntity.Tier.EXTRA_LARGE, xl, r);
            spawnOrbs(lvl, base, SoulOrbEntity.Tier.LARGE,      lg, r);
            spawnOrbs(lvl, base, SoulOrbEntity.Tier.MEDIUM,     md, r);
            spawnOrbs(lvl, base, SoulOrbEntity.Tier.SMALL,      sm, r);
        } catch (Throwable t) {
            LOG.error("[SoulDrops] onLivingDrops failed: {}", t.toString());
        }
    }

    private static void spawnOrbs(Level lvl, Vec3 base, SoulOrbEntity.Tier tier, int count, RandomSource r) {
        for (int i = 0; i < count; i++) {
            double dx = (r.nextDouble() - 0.5) * 0.6;
            double dy = (r.nextDouble()) * 0.4;
            double dz = (r.nextDouble() - 0.5) * 0.6;
            Vec3 at = base.add(dx, dy, dz);
            var orb = new SoulOrbEntity(lvl, at, tier, ORB_LIFETIME_TICKS);
            lvl.addFreshEntity(orb);
        }
    }

    private SoulDrops() {}
}
