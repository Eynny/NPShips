package com.eynny.npships.block;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

/** Query only the body's world bounds; Sable tracking distinguishes nearby ships. */
final class ClaimHostiles {
    private ClaimHostiles() {}

    private static AABB bounds(ServerSubLevel body) {
        var bounds = com.eynny.npships.lifecycle.NPShipsLifecycleQuery.worldBounds(body);
        return bounds == null ? null : bounds.inflate(3);
    }

    static boolean present(ServerSubLevel body) {
        var bounds = bounds(body);
        if (bounds == null) return false;
        for (var mob :
                body.getLevel()
                        .getEntitiesOfClass(
                                Mob.class, bounds, e -> e.isAlive() && e instanceof Enemy)) {
            if (belongs(body, mob)) return true;
        }
        return false;
    }

    static boolean belongs(ServerSubLevel body, Mob mob) {
        return belongsAt(body, mob.position(), Sable.HELPER.getTrackingOrVehicleSubLevel(mob));
    }

    static boolean belongsAt(
            ServerSubLevel body,
            net.minecraft.world.phys.Vec3 position,
            dev.ryanhcode.sable.sublevel.SubLevel tracked) {
        if (Sable.HELPER.getContaining(
                        body.getLevel(), net.minecraft.core.BlockPos.containing(position))
                == body) return true;
        var bounds = bounds(body);
        if (bounds == null || !bounds.contains(position)) return false;
        if (tracked == body) return true;
        if (tracked != null) return false;
        // A newly spawned/jumping mob need not have established Sable ground tracking yet.
        var local =
                net.minecraft.core.BlockPos.containing(
                        body.logicalPose().transformPositionInverse(position));
        for (int drop = 0; drop <= 3; drop++) {
            var at = local.below(drop);
            if (Sable.HELPER.getContaining(body.getLevel(), at) == body
                    && !body.getLevel()
                            .getBlockState(at)
                            .getCollisionShape(body.getLevel(), at)
                            .isEmpty()) return true;
        }
        return false;
    }
}
