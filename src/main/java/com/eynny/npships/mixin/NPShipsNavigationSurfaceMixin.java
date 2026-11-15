package com.eynny.npships.mixin;

import com.eynny.npships.integration.sable.NativeLandNavigation;
import com.eynny.npships.integration.sable.SableNavigationFix;
import com.eynny.npships.integration.sable.SableSurfaceNavigation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;

import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.*;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PathNavigation.class)
public abstract class NPShipsNavigationSurfaceMixin implements NativeLandNavigation.Navigator {
    @Shadow @Final protected net.minecraft.world.entity.Mob mob;

    public net.minecraft.world.entity.Mob npships$navigationMob() {
        return mob;
    }

    @Shadow protected Path path;

    @Shadow private net.minecraft.core.BlockPos targetPos;

    @WrapMethod(
            method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;")
    private Path npships$nativeGoalCacheKey(
            java.util.Set<net.minecraft.core.BlockPos> targets,
            int padding,
            boolean above,
            int accuracy,
            float range,
            Operation<Path> original) {
        SableNavigationFix.prepareRequest((PathNavigation) (Object) this, accuracy, range);
        var cached = path;
        var result = original.call(targets, padding, above, accuracy, range);
        // Native createPath may return before PathFinder sees an already cached route. Recheck
        // only that ordinary cached value through the same cross-owner admission boundary.
        if (result != null && result == cached && !SableSurfaceNavigation.owned(result))
            result =
                    SableNavigationFix.rememberQuery(
                            SableNavigationFix.retry(mob, range, accuracy, result), targets);
        // Keep the caller's cache key in its own frame. Owned route contacts remain world-space.
        // Native routing and its canUpdatePath/stop/reachRange behavior still own this request.
        if (SableNavigationFix.ownsRequest((PathNavigation) (Object) this, result, accuracy, range)
                && targets.size() == 1
                && !targets.contains(targetPos)) targetPos = targets.iterator().next().immutable();
        return result;
    }

    @Shadow
    private void resetStuckTimeout() {
        throw new AssertionError();
    }

    @Unique private java.lang.ref.WeakReference<Path> npships$timedPath;
    @Unique private int npships$timedIndex = -1;
    @Unique private net.minecraft.world.phys.Vec3 npships$progressPosition;
    @Shadow protected int tick;
    @Shadow private int lastStuckCheck;
    @Shadow private net.minecraft.world.phys.Vec3 lastStuckCheckPos;

    @Inject(method = "doStuckDetection", at = @At("HEAD"), require = 0)
    private void npships$preciseProgress(
            net.minecraft.world.phys.Vec3 position, CallbackInfo info) {
        if (!SableSurfaceNavigation.waterRoute(path)) {
            npships$timedPath = null;
            npships$timedIndex = -1;
            npships$progressPosition = null;
            return;
        }
        // Rounded BlockPos cannot distinguish successive fine contacts. Reset the native
        // per-contact timer only on real route progress; its no-movement check still runs.
        boolean newPath = npships$timedPath == null || npships$timedPath.get() != path;
        int index = path.getNextNodeIndex();
        if (!newPath
                && index > npships$timedIndex
                && npships$progressPosition != null
                && mob.position().distanceToSqr(npships$progressPosition) > 1e-8) {
            // Native nominal walking speed is not actual swimming speed. A checked contact
            // reached through real movement is progress; an unchanged actor gets no extension.
            lastStuckCheck = tick;
            lastStuckCheckPos = position;
            npships$progressPosition = mob.position();
        }
        if (newPath || npships$timedIndex != index) {
            resetStuckTimeout();
            if (newPath) {
                npships$timedPath = new java.lang.ref.WeakReference<>(path);
                npships$progressPosition = mob.position();
            }
            npships$timedIndex = index;
        }
    }

    /**
     * Owned points already include the exact native support height; do not resnap them against
     * ordinary-world water/air underneath the physical body.
     */
    @Inject(method = "getGroundY", at = @At("HEAD"), cancellable = true, require = 0)
    private void npships$ownedHeight(
            net.minecraft.world.phys.Vec3 point,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Double> info) {
        if (SableSurfaceNavigation.owned(path)) info.setReturnValue(point.y);
    }

    @WrapOperation(
            method = "getGroundY",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/pathfinder/WalkNodeEvaluator;getFloorLevel(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)D"),
            require = 0)
    private double npships$projectedGroundHeight(
            net.minecraft.world.level.BlockGetter world,
            net.minecraft.core.BlockPos position,
            Operation<Double> original,
            @com.llamalad7.mixinextras.sugar.Local(argsOnly = true)
                    net.minecraft.world.phys.Vec3 point) {
        // Leave any future upstream physical-frame getter or changed query position alone.
        if (world == mob.level()
                && position.equals(net.minecraft.core.BlockPos.containing(point))
                && SableNavigationFix.preciseGroundHeight(mob, path, point)) return point.y;
        return original.call(world, position);
    }

    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(
            method = "followThePath",
            at =
                    @At(
                            value = "FIELD",
                            target =
                                    "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;maxDistanceToWaypoint:F",
                            opcode = org.objectweb.asm.Opcodes.GETFIELD),
            require = 0)
    private float npships$shoreArrival(float tolerance) {
        return SableSurfaceNavigation.arrivalHorizontalTolerance(path, tolerance);
    }

    @org.spongepowered.asm.mixin.injection.ModifyConstant(
            method = "followThePath",
            constant = @org.spongepowered.asm.mixin.injection.Constant(doubleValue = 1.0),
            require = 0)
    private double npships$swimArrival(double tolerance) {
        return SableSurfaceNavigation.arrivalHeightTolerance(path, tolerance);
    }

    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;canUpdatePath()Z"),
            require = 0)
    private boolean npships$shoreAirArrival(boolean nativeResult) {
        return SableSurfaceNavigation.updateShore(path, nativeResult);
    }

    @WrapOperation(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/ai/control/MoveControl;setWantedPosition(DDDD)V"),
            require = 0)
    private void npships$shoreIntent(
            net.minecraft.world.entity.ai.control.MoveControl control,
            double x,
            double y,
            double z,
            double speed,
            Operation<Void> original) {
        original.call(control, x, y, z, SableSurfaceNavigation.shoreSpeed(path, speed));
    }

    @Inject(
            method = "moveTo(Lnet/minecraft/world/level/pathfinder/Path;D)Z",
            at = @At("RETURN"),
            require = 0)
    private void npships$installedRequest(
            Path candidate,
            double speed,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> info) {
        if (info.getReturnValueZ())
            SableNavigationFix.installed((PathNavigation) (Object) this, path);
    }

    @Inject(method = "stop", at = @At("HEAD"), require = 0)
    private void npships$cancelRequest(CallbackInfo info) {
        npships$timedPath = null;
        npships$timedIndex = -1;
        npships$progressPosition = null;
        SableNavigationFix.cancelPlanning((PathNavigation) (Object) this);
        SableSurfaceNavigation.cancel(path);
    }

    @WrapMethod(method = "tick")
    private void npships$requestOwner(Operation<Void> original) {
        var target = SableSurfaceNavigation.target(path);
        if (target == null) original.call();
        else
            SableNavigationFix.requested(
                    (PathNavigation) (Object) this, target, () -> original.call());
    }

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void npships$jump(CallbackInfo info) {
        SableSurfaceNavigation.jump(path);
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private void npships$validatedRoute(CallbackInfo info) {
        SableNavigationFix.preparePlanning((PathNavigation) (Object) this);
        path = SableSurfaceNavigation.prepare(path);
        if (!SableSurfaceNavigation.ready(path)) info.cancel();
    }

    @WrapOperation(
            method = "followThePath",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;canCutCorner(Lnet/minecraft/world/level/pathfinder/PathType;)Z"),
            require = 0)
    private boolean npships$keepEdges(
            PathNavigation nav, PathType type, Operation<Boolean> original) {
        return !SableSurfaceNavigation.owned(path) && original.call(nav, type);
    }
}
