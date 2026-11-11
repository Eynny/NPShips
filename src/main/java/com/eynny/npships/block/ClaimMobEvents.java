package com.eynny.npships.block;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/** Invalidate after entity lifecycle events; resolve after the event has actually committed. */
@EventBusSubscriber(modid = "npships")
public final class ClaimMobEvents {
    private ClaimMobEvents() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void died(LivingDeathEvent event) {
        ClaimBlockEntity.hostileChanged(event.getEntity());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void joined(EntityJoinLevelEvent event) {
        ClaimBlockEntity.hostileChanged(event.getEntity());
    }

    @SubscribeEvent
    public static void left(EntityLeaveLevelEvent event) {
        ClaimBlockEntity.hostileChanged(event.getEntity());
    }
}
