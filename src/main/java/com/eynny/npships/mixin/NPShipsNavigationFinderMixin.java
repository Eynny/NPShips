package com.eynny.npships.mixin;

import com.eynny.npships.integration.sable.SableNavigationFix;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;

import org.spongepowered.asm.mixin.Mixin;

import java.util.Set;

/** Retain a healthy native result; only the explicit cross-owner option can add a bounded route. */
@Mixin(PathFinder.class)
public abstract class NPShipsNavigationFinderMixin {
    @WrapMethod(
            method =
                    "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;")
    private Path npships$search(
            PathNavigationRegion region,
            Mob mob,
            Set<BlockPos> targets,
            float range,
            int accuracy,
            float multiplier,
            Operation<Path> original) {
        var planned = SableNavigationFix.plannedPath(mob, targets, range, accuracy);
        if (planned != null) return planned;
        var result = original.call(region, mob, targets, range, accuracy, multiplier);
        return SableNavigationFix.rememberQuery(
                SableNavigationFix.retry(mob, range, accuracy, result), targets);
    }
}
