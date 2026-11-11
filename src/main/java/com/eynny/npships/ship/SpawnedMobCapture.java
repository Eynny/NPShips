package com.eynny.npships.ship;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

import java.util.*;

/**
 * Collect and preserve mobs created synchronously by this placement, never nearby pre-existing
 * mobs.
 */
@EventBusSubscriber(modid = "npships")
public final class SpawnedMobCapture implements AutoCloseable {
    private static final String CREW = "npships_spawned_mob";
    private static final ThreadLocal<SpawnedMobCapture> ACTIVE = new ThreadLocal<>();
    private final SpawnedMobCapture previous;
    private final ServerLevel level;
    private final AABB bounds;
    private final List<UUID> ids = new ArrayList<>();

    public SpawnedMobCapture(ServerLevel level, AABB bounds) {
        this.level = level;
        this.bounds = bounds;
        previous = ACTIVE.get();
        ACTIVE.set(this);
    }

    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel) || !(event.getEntity() instanceof Mob mob))
            return;
        var current = ACTIVE.get();
        if (current != null
                && event.getLevel() == current.level
                && current.bounds.contains(mob.position())) {
            mob.getPersistentData().putBoolean(CREW, true);
            current.ids.add(mob.getUUID());
            // Aggregate below: large crews should not enqueue one info log per entity.
        } else if (mob.getPersistentData().getBoolean(CREW)) {
            log(mob, "JOIN", "entity loaded or transferred");
        }
    }

    @SubscribeEvent
    public static void left(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel
                && event.getEntity() instanceof Mob mob
                && mob.getPersistentData().getBoolean(CREW))
            log(mob, "LEAVE", String.valueOf(mob.getRemovalReason()));
    }

    private static void log(Mob mob, String event, String reason) {
        if (!com.eynny.npships.config.NPShipsRuntimeConfig.developerModeEnabled()) return;
        com.mojang.logging.LogUtils.getLogger()
                .info(
                        "[NPShips Crew] event={} uuid={} type={} dimension={} tick={} pos={} health={} persistent={} reason={}",
                        event,
                        mob.getUUID(),
                        net.minecraft.world.entity.EntityType.getKey(mob.getType()),
                        mob.level().dimension().location(),
                        mob.level().getGameTime(),
                        mob.position(),
                        mob.getHealth(),
                        mob.isPersistenceRequired(),
                        reason);
    }

    public List<UUID> ids() {
        return List.copyOf(ids);
    }

    @Override
    public void close() {
        if (!ids.isEmpty())
            if (com.eynny.npships.config.NPShipsRuntimeConfig.developerModeEnabled())
                com.mojang.logging.LogUtils.getLogger()
                        .debug("[NPShips Crew] placed {} mobs", ids.size());
        if (previous == null) ACTIVE.remove();
        else ACTIVE.set(previous);
    }
}
