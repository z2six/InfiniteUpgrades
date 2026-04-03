// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/event/PlayerAttachmentEvents.java
package org.z2six.infiniteupgrades.feature.infusion.event;

import com.mojang.logging.LogUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.z2six.infiniteupgrades.feature.infusion.attachment.ModAttachments;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * Ensures our ritual slot attachments persist through player clone (death/respawn).
 * We copy both ANGEL and DEMON attachments from the old player to the new one.
 */
@EventBusSubscriber(modid = Infiniteupgrades.MODID)
public final class PlayerAttachmentEvents {
    private static final Logger LOG = LogUtils.getLogger();

    private PlayerAttachmentEvents() {}

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        try {
            var oldP = event.getOriginal();
            var newP = event.getEntity();

            ModAttachments.copy(oldP, newP);

            var angelNew = ModAttachments.get(newP, org.z2six.infiniteupgrades.feature.infusion.logic.RitualType.ANGEL);
            var demonNew = ModAttachments.get(newP, org.z2six.infiniteupgrades.feature.infusion.logic.RitualType.DEMON);

            LOG.debug("[PlayerAttachmentEvents] Copied ritual attachments to clone (wasDeath={}): angel={}, demon={}",
                    event.isWasDeath(), notEmpty(angelNew), notEmpty(demonNew));
        } catch (Throwable t) {
            LOG.error("[PlayerAttachmentEvents] onPlayerClone failed: {}", t.toString());
        }
    }

    private static boolean notEmpty(ModAttachments.RitualSlots s) {
        return !(s.s0().isEmpty() && s.s1().isEmpty() && s.s2().isEmpty());
    }
}
