package com.eynny.npships.mixin;

import com.eynny.npships.integration.sable.SableNavigationFix;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.level.pathfinder.PathfindingContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Virtual moving-body cells never populate the ordinary world's persistent path-type cache. */
@Mixin(PathfindingContext.class)
public abstract class NPShipsNavigationContextMixin {
    @Unique private boolean npships$isolatedTypes;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void npships$region(CollisionGetter region, Mob mob, CallbackInfo info) {
        npships$isolatedTypes = SableNavigationFix.isolatedPathTypes(mob);
    }

    @WrapOperation(
            method = "getPathTypeFromState",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/pathfinder/PathTypeCache;getOrCompute(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/pathfinder/PathType;"))
    private PathType npships$type(
            PathTypeCache cache, BlockGetter world, BlockPos pos, Operation<PathType> original) {
        if (npships$isolatedTypes) {
            // The installed native search can cache out-of-region AIR under a real world key.
            // Evict only this queried key; never write temporary geometry into the shared cache.
            cache.invalidate(pos);
            return SableNavigationFix.worldPathType(world, pos);
        }
        return original.call(cache, world, pos);
    }
}
