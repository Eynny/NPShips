package com.eynny.npships.integration.aeronautics;

import com.eynny.npships.authoring.patches.DetachedAssemblyGuard;
import com.eynny.npships.integration.OptionalCalls;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;

import net.minecraft.server.level.ServerLevel;

/** Existing native balloon assembly side effects require the ordinary transfer path. */
public final class AeronauticsDetachedAssemblyGuard implements DetachedAssemblyGuard {
    @Override
    public boolean permits(ServerLevel level, BoundingBox3ic bounds) {
        try {
            var type =
                    Class.forName(
                            "dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.map.BalloonMap");
            var map = OptionalCalls.call(type.getField("MAP").get(null), "get", level);
            for (var balloon : (Iterable<?>) OptionalCalls.call(map, "getBalloons")) {
                var occupied = (BoundingBox3ic) OptionalCalls.call(balloon, "getBounds");
                if (occupied.intersects(bounds)) return false;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }
}
