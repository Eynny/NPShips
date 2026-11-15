package com.eynny.npships.ship;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** Authored head yaw is relative to the body; normal AI remains free to look around. */
@EventBusSubscriber(modid = "npships")
public final class EntityHeadPose {
    public static final String KEY = "npships_head_yaw";

    private EntityHeadPose() {}

    public static void apply(Entity entity) {
        if (entity instanceof LivingEntity living) {
            float offset = entity.getPersistentData().getFloat(KEY);
            if (!Float.isFinite(offset)) offset = 0;
            living.yBodyRot = living.yBodyRotO = entity.getYRot();
            living.yHeadRot = living.yHeadRotO = entity.getYRot() + offset;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void joined(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity().getPersistentData().contains(KEY))
            apply(event.getEntity());
    }
}
