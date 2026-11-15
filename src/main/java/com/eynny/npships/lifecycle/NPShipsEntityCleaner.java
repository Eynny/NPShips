package com.eynny.npships.lifecycle;

import com.eynny.npships.config.NPShipsRuntimeConfig;

import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.Collection;

/** Area-based structure cleanup; does not prove membership of every entity in that world AABB. */
public final class NPShipsEntityCleaner {

    private NPShipsEntityCleaner() {}

    /** Applies the same area policy and type exclusions to a loaded Sable sub-level. */
    public static void deleteEntitiesInSubLevel(
            final ServerLevel level, final ServerSubLevel subLevel) {
        var current = NPShipsLifecycleQuery.worldBounds(subLevel);
        if (current == null) deleteEntitiesInBounds(level, subLevel.boundingBox());
        else deleteEntitiesInBounds(level, current);
    }

    /**
     * Deletes non-player entities in world-space bounds except configured whitelisted types.
     *
     * <p>This catches: - mobs; - dropped items; - arrows/projectiles; - minecarts; - boats; -
     * modded entities; - any other Entity.
     *
     * <p>Players are always preserved. This retained area policy also covers unassociated entities
     * intersecting the bounds; the whitelist is a type exemption, not an ownership inference.
     */
    public static void deleteEntitiesInBounds(
            final ServerLevel level, final BoundingBox3dc bounds) {
        final AABB aabb =
                new AABB(
                        bounds.minX(),
                        bounds.minY(),
                        bounds.minZ(),
                        bounds.maxX(),
                        bounds.maxY(),
                        bounds.maxZ());

        deleteEntitiesInBounds(level, aabb);
    }

    private static void deleteEntitiesInBounds(final ServerLevel level, final AABB aabb) {
        final Collection<Entity> entities =
                level.getEntitiesOfClass(Entity.class, aabb, Entity::isAlive);
        var whitelist = NPShipsRuntimeConfig.entityCleanupWhitelist();

        for (final Entity entity : entities) {
            if (entity instanceof ServerPlayer) {
                continue;
            }
            var type =
                    whitelist.isEmpty()
                            ? null
                            : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (type != null && whitelist.contains(type.toString())) continue;

            entity.remove(Entity.RemovalReason.KILLED);
        }
    }
}
