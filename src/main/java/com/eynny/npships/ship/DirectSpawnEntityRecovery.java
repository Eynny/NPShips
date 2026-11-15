package com.eynny.npships.ship;

import com.eynny.npships.NPShips;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** Exact cancelled identities only. No area query, pending-body guess, queue or ticker. */
@EventBusSubscriber(modid = NPShips.MOD_ID)
public final class DirectSpawnEntityRecovery {
    private DirectSpawnEntityRecovery() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void joining(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level
                && DirectSpawnJournal.cancelledEntity(level, event.getEntity().getUUID())) {
            // The event precedes native entity-manager insertion, for both file loads and adds.
            event.setCanceled(true);
            event.getEntity().discard();
        }
    }
}
