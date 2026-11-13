package com.eynny.npships.authoring.patches;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;

import net.minecraft.server.level.ServerLevel;

/**
 * Mandatory native-environment admission in the existing registry, independent of restore policy.
 */
@FunctionalInterface
public interface DetachedAssemblyGuard {
    boolean permits(ServerLevel level, BoundingBox3ic bounds);
}
