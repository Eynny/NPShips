package com.eynny.npships.mixin;

import com.eynny.npships.integration.sable.SableNavigationFix;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Preserve native arrival tolerances while comparing in the controller's precise world frame. */
@Mixin(PathNavigation.class)
public abstract class NPShipsNavigationWaypointMixin {
    @Shadow @Final protected Mob mob;
    @Shadow protected Path path;

    @Unique
    private Vec3 npships$waypoint(LocalRef<Vec3> point, LocalBooleanRef read) {
        if (!read.get()) {
            read.set(true);
            point.set(SableNavigationFix.preciseWaypoint(mob, path));
        }
        return point.get();
    }

    @ModifyExpressionValue(
            method = "followThePath",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;abs(D)D", ordinal = 0),
            require = 0)
    private double npships$exactX(
            double original,
            @Share("npshipsWaypoint") LocalRef<Vec3> point,
            @Share("npshipsWaypointRead") LocalBooleanRef read) {
        var target = npships$waypoint(point, read);
        return SableNavigationFix.arrivalDistance(mob, path, target, 0, original);
    }

    @ModifyExpressionValue(
            method = "followThePath",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;abs(D)D", ordinal = 1),
            require = 0)
    private double npships$exactY(
            double original,
            @Share("npshipsWaypoint") LocalRef<Vec3> point,
            @Share("npshipsWaypointRead") LocalBooleanRef read) {
        var target = npships$waypoint(point, read);
        return SableNavigationFix.arrivalDistance(mob, path, target, 1, original);
    }

    @ModifyExpressionValue(
            method = "followThePath",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;abs(D)D", ordinal = 2),
            require = 0)
    private double npships$exactZ(
            double original,
            @Share("npshipsWaypoint") LocalRef<Vec3> point,
            @Share("npshipsWaypointRead") LocalBooleanRef read) {
        var target = npships$waypoint(point, read);
        return SableNavigationFix.arrivalDistance(mob, path, target, 2, original);
    }
}
