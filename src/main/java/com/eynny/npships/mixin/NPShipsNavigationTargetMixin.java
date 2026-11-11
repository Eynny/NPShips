package com.eynny.npships.mixin;

import com.eynny.npships.integration.sable.SableNavigationFix;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;

import org.spongepowered.asm.mixin.Mixin;

/** Capture the actual entity target before native coordinate normalization. */
@Mixin(value = PathNavigation.class, priority = 500)
public abstract class NPShipsNavigationTargetMixin {
    @WrapMethod(
            method =
                    "createPath(Lnet/minecraft/world/entity/Entity;I)Lnet/minecraft/world/level/pathfinder/Path;")
    private Path npships$target(Entity target, int accuracy, Operation<Path> original) {
        return SableNavigationFix.requested(
                (PathNavigation) (Object) this, target, () -> original.call(target, accuracy));
    }

    @WrapMethod(method = "moveTo(Lnet/minecraft/world/entity/Entity;D)Z")
    private boolean npships$move(Entity target, double speed, Operation<Boolean> original) {
        return SableNavigationFix.requested(
                (PathNavigation) (Object) this, target, () -> original.call(target, speed));
    }
}
