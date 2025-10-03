// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/event/PlayerAttachmentEvents.java
package org.z2six.infiniteupgrades.feature.infusion.event;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.Infiniteupgrades;
import org.z2six.infiniteupgrades.feature.infusion.attachment.ModAttachments;
import net.neoforged.fml.common.EventBusSubscriber;

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

            // ANGEL
            var angelType = ModAttachments.ANGEL_RITUAL_SLOTS.get();
            var angelOld  = oldP.getData(angelType);
            var angelNew  = (angelOld == null)
                    ? new ModAttachments.RitualSlots(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY)
                    : new ModAttachments.RitualSlots(
                    angelOld.s0().copy(),
                    angelOld.s1().copy(),
                    angelOld.s2().copy()
            );
            newP.setData(angelType, angelNew);

            // DEMON
            var demonType = ModAttachments.DEMON_RITUAL_SLOTS.get();
            var demonOld  = oldP.getData(demonType);
            var demonNew  = (demonOld == null)
                    ? new ModAttachments.RitualSlots(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY)
                    : new ModAttachments.RitualSlots(
                    demonOld.s0().copy(),
                    demonOld.s1().copy(),
                    demonOld.s2().copy()
            );
            newP.setData(demonType, demonNew);

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
