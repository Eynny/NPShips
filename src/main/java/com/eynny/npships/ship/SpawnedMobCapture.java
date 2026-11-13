package com.eynny.npships.ship;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
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

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void restoreLoadedPassenger(EntityJoinLevelEvent event) {
        if (!event.loadedFromDisk()
                || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof Mob mob)
                || mob.isRemoved()
                || mob.level() != level
                || !mob.getPersistentData().getBoolean(CREW)) return;
        var vehicle = mob.getVehicle();
        if (vehicle == null || vehicle.isRemoved() || vehicle.level() != level) return;
        var body = Sable.HELPER.getContaining(vehicle);
        if (!(body instanceof ServerSubLevel serverBody)
                || serverBody.isRemoved()
                || serverBody.getLevel() != level) return;
        // Restore before section registration through normal riding callbacks. The vehicle and
        // Sable retain offsets/projection, including loads that already have the correct position.
        vehicle.positionRider(mob);
    }

    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel) || !(event.getEntity() instanceof Mob mob))
            return;
        var current = ACTIVE.get();
        if (current != null
                && !event.loadedFromDisk()
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
